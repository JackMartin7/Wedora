package com.wedora.app

import android.util.Log
import com.google.android.gms.tasks.Task
import com.google.android.gms.tasks.Tasks
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.QuerySnapshot

private const val TAG = "WedoraPasses"

/**
 * People this user has swiped past.
 *
 * `passes/{uid}/passedUsers/{passedUid}`, mirroring the block list — private
 * to its owner, keyed by UID so a repeat pass overwrites rather than
 * duplicating.
 */
object Passes {
    const val COLLECTION = "passes"
    const val PASSED_USERS_SUBCOLLECTION = "passedUsers"
    const val FIELD_PASSED_AT = "passedAt"

    /**
     * A pass is a soft signal, so the feed only excludes the most recent ones.
     * Someone who has swiped through thousands of profiles would otherwise pay
     * for a growing read on every feed load, to hide people the query is
     * unlikely to return anyway.
     */
    const val MAX_LOADED = 500L
}

private fun passedUsersRef(firestore: FirebaseFirestore, selfUid: String) =
    firestore.collection(Passes.COLLECTION)
        .document(selfUid)
        .collection(Passes.PASSED_USERS_SUBCOLLECTION)

/**
 * Records a pass. Fire-and-forget by design: the card is already gone by the
 * time this runs, and there is nothing useful to tell the user if it fails —
 * the only consequence is that the profile can come back in a later session,
 * which is a far smaller cost than interrupting a swipe streak with an error.
 */
fun passUser(firestore: FirebaseFirestore, selfUid: String, passedUid: String) {
    passedUsersRef(firestore, selfUid)
        .document(passedUid)
        .set(mapOf(Passes.FIELD_PASSED_AT to FieldValue.serverTimestamp()))
        .addOnFailureListener { e ->
            Log.w(TAG, "Couldn't record pass on $passedUid; they may reappear", e)
        }
}

/**
 * The most recent [Passes.MAX_LOADED] passes, newest first.
 *
 * Ordering on a single field inside one subcollection uses an automatic index,
 * so this needs no composite index definition.
 */
fun loadPassedUsersQuery(firestore: FirebaseFirestore, selfUid: String): Task<QuerySnapshot> =
    passedUsersRef(firestore, selfUid)
        .orderBy(Passes.FIELD_PASSED_AT, Query.Direction.DESCENDING)
        .limit(Passes.MAX_LOADED)
        .get()

/**
 * Everyone the feed should leave out, split by how absolute each exclusion
 * is — see [buildFeedCards], which walks these as progressively wider tiers
 * when the strictest set leaves too few people to show.
 *
 * [blocked] and [matched] are permanent: a block is a safety decision that
 * must never be undone by a fallback, and someone already matched has a chat
 * thread, so putting them back in the swipe deck is noise rather than
 * discovery. [passed] and [liked] are soft — reintroduced, in that order,
 * only once the feed would otherwise be empty.
 *
 * [liked] deliberately excludes mutual matches (they're in [matched]
 * instead), so the two sets never overlap.
 */
data class FeedExclusions(
    val blocked: Set<String>,
    val matched: Set<String>,
    val passed: Set<String>,
    val liked: Set<String>
) {
    companion object {
        /** A guest: no account, so nothing to exclude. */
        val EMPTY = FeedExclusions(
            blocked = emptySet(),
            matched = emptySet(),
            passed = emptySet(),
            liked = emptySet()
        )
    }
}

/**
 * Everyone the feed should leave out: blocked, already passed, and already
 * liked.
 *
 * All three are read here rather than taken from whatever state the screen
 * happens to hold. The liked set in particular is normally maintained by a
 * snapshot listener that starts in onStart, which is not guaranteed to have
 * delivered by the time the feed loads — reading it here removes that race, at
 * the cost of one query that the listener would run anyway.
 *
 * Fails *open*: a read error contributes nothing to the exclusion set rather
 * than emptying the feed. An unfiltered feed is a much better failure than a
 * blank one, and the next load re-applies it.
 */
fun loadFeedExclusions(
    firestore: FirebaseFirestore,
    selfUid: String,
    onResult: (FeedExclusions) -> Unit
) {
    val blocked = firestore.collection(Moderation.BLOCKS_COLLECTION)
        .document(selfUid)
        .collection(Moderation.BLOCKED_USERS_SUBCOLLECTION)
        .get()

    val passed = loadPassedUsersQuery(firestore, selfUid)

    val liked = firestore.collection(Match.COLLECTION)
        .whereArrayContains(Match.FIELD_USERS, selfUid)
        .get()

    // whenAllComplete, not whenAllSuccess: one failing read shouldn't discard
    // the other two, which is what a combined success-only task would do.
    Tasks.whenAllComplete(blocked, passed, liked)
        .addOnSuccessListener {
            val blockedIds = blocked.resultOrNull()?.documents?.map { it.id }?.toSet().orEmpty()
            val passedIds = passed.resultOrNull()?.documents?.map { it.id }?.toSet().orEmpty()

            // A match document exists as soon as either side likes, so only the
            // ones this user initiated are excluded — someone who liked *them*
            // should still show up, so they can like back.
            //
            // Split by mutuality from the same already-loaded documents, so
            // separating "matched" from "liked but not matched" costs no extra
            // read: a mutual match is permanently excluded (they have a chat
            // thread), a one-sided like only until the feed runs dry.
            val myLikes = liked.resultOrNull()?.documents
                ?.mapNotNull { Match.from(it) }
                ?.filter { it.isLikeBy(selfUid) }
                .orEmpty()

            val matchedIds = myLikes
                .filter { it.isMutual() }
                .mapNotNull { it.otherUserId(selfUid) }
                .toSet()

            val likedIds = myLikes
                .filterNot { it.isMutual() }
                .mapNotNull { it.otherUserId(selfUid) }
                .toSet()

            onResult(
                FeedExclusions(
                    blocked = blockedIds,
                    matched = matchedIds,
                    passed = passedIds,
                    liked = likedIds
                )
            )
        }
}

/**
 * The snapshot if the task succeeded, or null — never throws on a failure.
 *
 * Reported as a non-fatal because this path fails *open* and is otherwise
 * completely silent in production: a persistent failure here means users are
 * shown people they blocked or already passed, with nothing on screen or in
 * any dashboard to say why.
 */
private fun Task<QuerySnapshot>.resultOrNull(): QuerySnapshot? =
    if (isSuccessful) result else {
        Log.w(TAG, "A feed exclusion read failed; continuing without it", exception)
        exception?.let { CrashReporting.record(it, "loadFeedExclusions read failed") }
        null
    }
