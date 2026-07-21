package com.wedora.app

import com.google.firebase.firestore.DocumentSnapshot

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
    val users: List<String>
) {

    /** The other participant's UID, or null if [selfUid] isn't in this match. */
    fun otherUserId(selfUid: String): String? =
        if (selfUid in users) users.firstOrNull { it != selfUid } else null

    companion object {
        const val COLLECTION = "matches"
        const val SUBCOLLECTION_MESSAGES = "messages"

        const val FIELD_USERS = "users"
        const val FIELD_CREATED_AT = "createdAt"

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
            return Match(id = snapshot.id, users = users)
        }
    }
}
