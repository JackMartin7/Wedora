package com.wedora.app

import com.google.android.gms.tasks.Task
import com.google.firebase.Timestamp
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldPath
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.QuerySnapshot
import com.google.firebase.firestore.SetOptions

/**
 * Writes the match document between [selfUid] and [otherUid], returning the
 * task so each caller can attach its own UI handling. Shared by the Home feed
 * and the profile detail screen, which both offer a like action.
 *
 * Uses set(merge) rather than checking for an existing match first. A pre-read
 * would need a rule permitting reads of match documents the caller isn't in
 * yet — and since user documents are readable and keyed by UID, that would let
 * anyone enumerate UID pairs and reconstruct the whole match graph. Writing
 * blind keeps the read rule strict; the cost is that re-liking someone
 * refreshes createdAt.
 */
/**
 * Looks up whether a match already exists between these two users; the result
 * is empty if not.
 *
 * Deliberately a document-ID *query* rather than a get(). Membership in the
 * security rules is read off the stored document, so a get() for a match that
 * doesn't exist has no `users` array to check and is rejected outright —
 * indistinguishable from "you may not see this". A query instead returns
 * nothing for a missing document, and because the ID always contains the
 * caller's own UID, any document it does return is one they're a member of.
 *
 * The read rule stays strict on purpose: loosening it enough for the get()
 * would let anyone probe UID pairs and reconstruct the whole match graph.
 */
fun matchExistsQuery(
    firestore: FirebaseFirestore,
    selfUid: String,
    otherUid: String
): Task<QuerySnapshot> =
    firestore.collection(Match.COLLECTION)
        .whereEqualTo(FieldPath.documentId(), Match.idFor(selfUid, otherUid))
        .limit(1)
        .get()

fun createMatchDocument(
    firestore: FirebaseFirestore,
    selfUid: String,
    otherUid: String
): Task<Void> {
    val matchData = mapOf(
        Match.FIELD_USERS to listOf(selfUid, otherUid),
        Match.FIELD_CREATED_AT to FieldValue.serverTimestamp(),
        // Who initiated. Drives the other participant's notification, and the
        // security rules require it to be the caller.
        Match.FIELD_LIKED_BY to selfUid
    )
    return firestore.collection(Match.COLLECTION)
        .document(Match.idFor(selfUid, otherUid))
        .set(matchData, SetOptions.merge())
}

/**
 * A match between two users — `matches/{matchId}`.
 *
 * As with [UserProfile], the Firestore field names live here as constants
 * rather than as literals spread across call sites.
 *
 * This app uses an *instant* match model: liking someone creates the match
 * document immediately, so there is no pending/one-sided like state. A like
 * is therefore mutual by construction.
 */
data class Match(
    val id: String,
    val users: List<String>,
    /** Null while the server timestamp is still pending on a just-written doc. */
    val createdAt: Timestamp?,
    /**
     * Who initiated the like. Null on matches written before this field
     * existed — those can't be attributed, so they're excluded from
     * notifications rather than guessed at or migrated.
     */
    val likedBy: String?,
    /** Missing is treated as unseen, so old documents surface as notifications. */
    val seenByRecipient: Boolean
) {

    /** The other participant's UID, or null if [selfUid] isn't in this match. */
    fun otherUserId(selfUid: String): String? =
        if (selfUid in users) users.firstOrNull { it != selfUid } else null

    /** True when [selfUid] is the one who liked — i.e. not a notification. */
    fun isLikeBy(selfUid: String): Boolean = likedBy != null && likedBy == selfUid

    /** True when someone else liked [selfUid], regardless of seen state. */
    fun isLikeFor(selfUid: String): Boolean = likedBy != null && likedBy != selfUid

    /** A like for [selfUid] that they haven't looked at yet — drives the badge. */
    fun isUnseenLikeFor(selfUid: String): Boolean = isLikeFor(selfUid) && !seenByRecipient

    companion object {
        const val COLLECTION = "matches"
        const val SUBCOLLECTION_MESSAGES = "messages"

        const val FIELD_USERS = "users"
        const val FIELD_CREATED_AT = "createdAt"
        const val FIELD_LIKED_BY = "likedBy"
        const val FIELD_SEEN_BY_RECIPIENT = "seenByRecipient"

        /**
         * Deterministic, order-independent document ID for a pair of users.
         *
         * Sorting is what makes the pair collapse to a single document: A
         * liking B and B liking A both resolve to the same ID, so a match can
         * never be duplicated.
         */
        fun idFor(uidA: String, uidB: String): String =
            listOf(uidA, uidB).sorted().joinToString("_")

        /** Null when the document has no usable `users` array. */
        fun from(snapshot: DocumentSnapshot): Match? {
            @Suppress("UNCHECKED_CAST")
            val users = snapshot.get(FIELD_USERS) as? List<String> ?: return null
            if (users.size != 2) return null
            return Match(
                id = snapshot.id,
                users = users,
                createdAt = snapshot.getTimestamp(FIELD_CREATED_AT),
                likedBy = snapshot.getString(FIELD_LIKED_BY),
                seenByRecipient = snapshot.getBoolean(FIELD_SEEN_BY_RECIPIENT) ?: false
            )
        }
    }
}
