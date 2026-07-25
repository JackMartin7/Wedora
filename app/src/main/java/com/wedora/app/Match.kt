package com.wedora.app

import android.util.Log
import com.google.android.gms.tasks.Task
import com.google.firebase.Timestamp
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldPath
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
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

/**
 * The write [createMatchDocument] and the daily-limit-aware like path both
 * need — pulled out so the gated version (see LikeLimit.kt) can fold it into
 * its own batch instead of duplicating it.
 */
internal fun matchDataFor(selfUid: String, otherUid: String): Map<String, Any> = mapOf(
    // Sorted, so both participants write the byte-identical array.
    //
    // This is what made mutual likes fail. The document ID is sorted, so
    // the second person to like lands on the document the first one
    // created — a merge, i.e. an update. Written in caller order this
    // array arrived reversed ([B, A] over a stored [A, B]), and the rules'
    // "participants can never change" check is an order-sensitive list
    // comparison, so it rejected every like-back with PERMISSION_DENIED.
    Match.FIELD_USERS to listOf(selfUid, otherUid).sorted(),
    Match.FIELD_CREATED_AT to FieldValue.serverTimestamp(),
    // Adds the caller to the set of people who have liked. arrayUnion is
    // commutative and idempotent, so a like-back adds the second person
    // without disturbing the first, and re-liking is a no-op.
    //
    // This replaces the scalar likedBy, which held only one UID: the
    // second liker overwrote it, flipping the like's attribution. That
    // silently broke the like-back case even once the write was permitted
    // — the first liker's heart reverted to grey, they could no longer
    // unlike (the delete rule keyed off likedBy), and because the
    // recipient had already marked the like seen, neither side was
    // notified that it was now mutual.
    Match.FIELD_LIKED_USERS to FieldValue.arrayUnion(selfUid)
)

fun createMatchDocument(
    firestore: FirebaseFirestore,
    selfUid: String,
    otherUid: String
): Task<Void> =
    firestore.collection(Match.COLLECTION)
        .document(Match.idFor(selfUid, otherUid))
        .set(matchDataFor(selfUid, otherUid), SetOptions.merge())

/**
 * Logs a failed Firestore write with its error code, rather than leaving only
 * the generic toast the user sees.
 *
 * PERMISSION_DENIED is called out by name because it has one overwhelmingly
 * likely cause here — firestore.rules changed but was never deployed — and
 * that has cost real debugging time on this project more than once. The
 * message names the command so the log line is self-explanatory.
 */
fun logFirestoreWriteFailure(tag: String, what: String, e: Exception) {
    val code = (e as? FirebaseFirestoreException)?.code
    if (code == FirebaseFirestoreException.Code.PERMISSION_DENIED) {
        Log.w(
            tag,
            "$what: PERMISSION_DENIED. The live ruleset rejected this write — " +
                "firestore.rules is probably not deployed. " +
                "Run: firebase deploy --only firestore:rules",
            e
        )
    } else {
        Log.w(tag, "$what: ${code?.name ?: e.javaClass.simpleName}", e)
    }
}

/**
 * Deletes the match between these two users — an unlike. The rules only permit
 * this when the caller is the one who created the like (likedBy), so a user can
 * withdraw their own like but not erase one made for them.
 *
 * Deleting the document is what removes the person from the other user's Likes
 * and Notifications, since both read straight off the matches collection.
 */
fun deleteMatchDocument(
    firestore: FirebaseFirestore,
    selfUid: String,
    otherUid: String
): Task<Void> =
    firestore.collection(Match.COLLECTION)
        .document(Match.idFor(selfUid, otherUid))
        .delete()

/**
 * "Delete for me": hides one or more conversations from [selfUid]'s own Chats
 * list, one-sided — the match document and its messages are untouched, so the
 * other participant's list is unaffected. A new message from them removes
 * [selfUid] from `hiddenBy` again (see the batch in ChatThreadActivity's
 * sendMessage), so this isn't a block — it's exactly the WhatsApp-style
 * "delete chat" behavior.
 *
 * One batch for the whole selection, so a multi-delete either fully applies or
 * fully fails rather than leaving some chats hidden and others not.
 */
fun hideMatchesForUser(
    firestore: FirebaseFirestore,
    matchIds: Collection<String>,
    selfUid: String
): Task<Void> {
    val batch = firestore.batch()
    matchIds.forEach { matchId ->
        batch.update(
            firestore.collection(Match.COLLECTION).document(matchId),
            Match.FIELD_HIDDEN_BY, FieldValue.arrayUnion(selfUid)
        )
    }
    return batch.commit()
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
     * Everyone who has liked, in no particular order — one UID for a one-sided
     * like, both for a mutual one. Empty on matches written before likes were
     * attributed at all; those can't be attributed, so they're excluded from
     * notifications rather than guessed at.
     */
    val likedUsers: List<String>,
    /**
     * Who has already seen the like they received. Per-user rather than a
     * single flag, because a mutual match has a notification for each side and
     * one boolean can only clear one of them.
     */
    val seenBy: List<String>,
    /**
     * Newest message, denormalised onto the match doc so the Chats list can
     * render (and live-update) without a per-match message query. Null on
     * matches nobody has messaged yet.
     */
    val lastMessage: LastMessage?,
    /**
     * Everyone who has "deleted" this chat from their own Chats list —
     * one-sided, not a block. A UID here is removed the moment the OTHER
     * participant sends a new message, so the conversation reappears rather
     * than staying hidden forever.
     */
    val hiddenBy: List<String>
) {

    /**
     * Snapshot of the newest message, plus how many the *recipient* (whoever is
     * not [senderId]) hasn't read. A single counter, so it always refers to the
     * side that didn't send last.
     */
    data class LastMessage(
        val text: String?,
        val sentAt: Timestamp?,
        val senderId: String?,
        val unreadCount: Int
    )

    /** The other participant's UID, or null if [selfUid] isn't in this match. */
    fun otherUserId(selfUid: String): String? =
        if (selfUid in users) users.firstOrNull { it != selfUid } else null

    /**
     * True when the newest message is from the other person and [selfUid] still
     * has some unread — i.e. this row should show an unread badge. False when
     * [selfUid] sent last (that count is the other side's), or nothing's unread.
     */
    fun hasUnreadFor(selfUid: String): Boolean {
        val lm = lastMessage ?: return false
        return lm.senderId != null && lm.senderId != selfUid && lm.unreadCount > 0
    }

    /** True when [selfUid] has liked. Stays true after the other person likes back. */
    fun isLikeBy(selfUid: String): Boolean = selfUid in likedUsers

    /** True when the other participant has liked [selfUid], regardless of seen state. */
    fun isLikeFor(selfUid: String): Boolean = likedUsers.any { it != selfUid }

    /** Both participants have liked — the match is live for both of them. */
    fun isMutual(): Boolean = users.isNotEmpty() && users.all { it in likedUsers }

    /** A like for [selfUid] that they haven't looked at yet — drives the badge. */
    fun isUnseenLikeFor(selfUid: String): Boolean = isLikeFor(selfUid) && selfUid !in seenBy

    /** The other participant, when they're the one who liked [selfUid]. */
    fun likerFor(selfUid: String): String? = likedUsers.firstOrNull { it != selfUid }

    /** True when [selfUid] has "deleted" this chat from their own Chats list. */
    fun isHiddenFor(selfUid: String): Boolean = selfUid in hiddenBy

    companion object {
        const val COLLECTION = "matches"
        const val SUBCOLLECTION_MESSAGES = "messages"

        const val FIELD_USERS = "users"
        const val FIELD_CREATED_AT = "createdAt"
        const val FIELD_LIKED_USERS = "likedUsers"
        const val FIELD_SEEN_BY = "seenBy"
        const val FIELD_HIDDEN_BY = "hiddenBy"

        /**
         * Superseded by [FIELD_LIKED_USERS] and [FIELD_SEEN_BY]. Still read so
         * matches created before this change keep working; never written.
         */
        const val LEGACY_FIELD_LIKED_BY = "likedBy"
        const val LEGACY_FIELD_SEEN_BY_RECIPIENT = "seenByRecipient"

        const val FIELD_LAST_MESSAGE = "lastMessage"
        const val LM_TEXT = "text"
        const val LM_SENT_AT = "sentAt"
        const val LM_SENDER_ID = "senderId"
        const val LM_UNREAD_COUNT = "unreadCount"

        /**
         * Per-participant read receipts — `{ "{uid}": serverTimestamp() }` —
         * written by whichever participant most recently had the thread
         * open. A message is "read" by its recipient once this value for
         * them is >= the message's own sentAt; see MessageAdapter.
         */
        const val FIELD_LAST_READ_AT = "lastReadAt"

        /** Dotted path for updating a single participant's read receipt. */
        fun pathLastReadAt(uid: String) = "$FIELD_LAST_READ_AT.$uid"

        /**
         * Dotted paths for updating individual lastMessage keys. Firestore's
         * varargs update() treats a dot as a nested-field separator and creates
         * the parent map if it's absent, so the first message on a match works
         * without seeding the map first.
         */
        const val PATH_LM_TEXT = "$FIELD_LAST_MESSAGE.$LM_TEXT"
        const val PATH_LM_SENT_AT = "$FIELD_LAST_MESSAGE.$LM_SENT_AT"
        const val PATH_LM_SENDER_ID = "$FIELD_LAST_MESSAGE.$LM_SENDER_ID"
        const val PATH_LM_UNREAD_COUNT = "$FIELD_LAST_MESSAGE.$LM_UNREAD_COUNT"

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

            val likedUsers = parseLikedUsers(snapshot)
            return Match(
                id = snapshot.id,
                users = users,
                createdAt = snapshot.getTimestamp(FIELD_CREATED_AT),
                likedUsers = likedUsers,
                seenBy = parseSeenBy(snapshot, users, likedUsers),
                lastMessage = parseLastMessage(snapshot),
                hiddenBy = parseHiddenBy(snapshot)
            )
        }

        /** Empty on any match predating this field — nothing is hidden. */
        @Suppress("UNCHECKED_CAST")
        private fun parseHiddenBy(snapshot: DocumentSnapshot): List<String> =
            snapshot.get(FIELD_HIDDEN_BY) as? List<String> ?: emptyList()

        /**
         * Reads the likers as the UNION of `likedUsers` and the single-UID
         * `likedBy` it replaced. That union is what lets existing matches keep
         * working without a data migration.
         *
         * It has to be a union rather than a preference: liking back on a
         * legacy document writes likedUsers = [the second liker] while the
         * original liker still sits in likedBy alone, so reading only
         * likedUsers would drop them and un-match the pair.
         */
        @Suppress("UNCHECKED_CAST")
        private fun parseLikedUsers(snapshot: DocumentSnapshot): List<String> {
            val current = snapshot.get(FIELD_LIKED_USERS) as? List<String> ?: emptyList()
            val legacy = snapshot.getString(LEGACY_FIELD_LIKED_BY)
            return if (legacy == null) current else (current + legacy).distinct()
        }

        /**
         * Reads who has seen their like. On a legacy document the boolean says
         * only *that* the recipient looked, so it's attributed to the
         * participant who didn't send the like — the one it was a notification
         * for. Missing reads as unseen, so old likes still surface.
         */
        @Suppress("UNCHECKED_CAST")
        private fun parseSeenBy(
            snapshot: DocumentSnapshot,
            users: List<String>,
            likedUsers: List<String>
        ): List<String> {
            (snapshot.get(FIELD_SEEN_BY) as? List<String>)
                ?.let { return it }
            if (snapshot.getBoolean(LEGACY_FIELD_SEEN_BY_RECIPIENT) != true) return emptyList()
            return users.filterNot { it in likedUsers }
        }

        /** Null for matches with no messages yet, or a malformed field. */
        private fun parseLastMessage(snapshot: DocumentSnapshot): LastMessage? {
            val map = snapshot.get(FIELD_LAST_MESSAGE) as? Map<*, *> ?: return null
            return LastMessage(
                text = map[LM_TEXT] as? String,
                sentAt = map[LM_SENT_AT] as? Timestamp,
                senderId = map[LM_SENDER_ID] as? String,
                unreadCount = (map[LM_UNREAD_COUNT] as? Number)?.toInt() ?: 0
            )
        }
    }
}
