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
    val seenByRecipient: Boolean,
    /**
     * Newest message, denormalised onto the match doc so the Chats list can
     * render (and live-update) without a per-match message query. Null on
     * matches nobody has messaged yet.
     */
    val lastMessage: LastMessage?
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

        const val FIELD_LAST_MESSAGE = "lastMessage"
        const val LM_TEXT = "text"
        const val LM_SENT_AT = "sentAt"
        const val LM_SENDER_ID = "senderId"
        const val LM_UNREAD_COUNT = "unreadCount"

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
            return Match(
                id = snapshot.id,
                users = users,
                createdAt = snapshot.getTimestamp(FIELD_CREATED_AT),
                likedBy = snapshot.getString(FIELD_LIKED_BY),
                seenByRecipient = snapshot.getBoolean(FIELD_SEEN_BY_RECIPIENT) ?: false,
                lastMessage = parseLastMessage(snapshot)
            )
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
