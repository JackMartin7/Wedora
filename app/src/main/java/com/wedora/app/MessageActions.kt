package com.wedora.app

import com.google.android.gms.tasks.Task
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore

/**
 * Message-level writes for delete and react — split out from MessageLimit.kt
 * (which owns *sending*) since these operate on an existing message rather
 * than creating one, and each maps to exactly one of firestore.rules'
 * isDeleteForEveryone/isDeleteForMe/isReactionUpdate paths. Sending any of
 * these three writes with extra fields mixed in, or fields from a different
 * one of the three, is rejected server-side by design — see firestore.rules'
 * own doc comments on why each function only reads its own exact key set.
 */

private fun messageDoc(
    firestore: FirebaseFirestore,
    matchId: String,
    messageId: String
) = firestore.collection(Match.COLLECTION).document(matchId)
    .collection(Match.SUBCOLLECTION_MESSAGES).document(messageId)

/**
 * "Delete for everyone" — sender only (enforced server-side; this function
 * doesn't re-check, since a non-sender's write is simply rejected by the
 * rules and the caller sees that failure like any other).
 *
 * Also clears the match doc's lastMessage preview to a deleted placeholder,
 * but only when [lastMessageId] (the match's current lastMessage.messageId,
 * as already loaded client-side) equals this [messageId] — i.e. only when
 * this message IS the current preview. Deleting an older message that isn't
 * the newest leaves lastMessage untouched entirely.
 *
 * This reads no fresh match state itself — [lastMessageId] is whatever the
 * caller already has loaded — so there's a narrow, accepted race: if a new
 * message arrives in the moment between the caller loading that value and
 * this write landing, the placeholder could briefly overwrite a newer
 * preview until the next message event re-renders it. Accepted deliberately
 * rather than adding this codebase's first Firestore transaction for a
 * cosmetic edge case.
 */
fun deleteMessageForEveryone(
    firestore: FirebaseFirestore,
    matchId: String,
    messageId: String,
    lastMessageId: String?
): Task<Void> {
    val batch = firestore.batch()
    batch.update(
        messageDoc(firestore, matchId, messageId),
        Message.FIELD_DELETED, true,
        Message.FIELD_DELETED_AT, FieldValue.serverTimestamp(),
        Message.FIELD_TEXT, FieldValue.delete()
    )
    if (lastMessageId == messageId) {
        batch.update(
            firestore.collection(Match.COLLECTION).document(matchId),
            Match.PATH_LM_DELETED, true,
            Match.PATH_LM_TEXT, FieldValue.delete()
        )
    }
    return batch.commit()
}

/**
 * "Delete for me" — hides [messageId] from [selfUid]'s own view of the
 * thread. One-sided: the document, its text, and the other participant's
 * view are all untouched. Never touches lastMessage — unlike delete-for-
 * everyone, this doesn't change what either side's Chats list preview shows.
 */
fun deleteMessageForMe(
    firestore: FirebaseFirestore,
    matchId: String,
    messageId: String,
    selfUid: String
): Task<Void> =
    messageDoc(firestore, matchId, messageId)
        .update(Message.FIELD_DELETED_FOR, FieldValue.arrayUnion(selfUid))

/**
 * Sets [selfUid]'s reaction to [emoji], replacing whatever they'd reacted
 * with before (a plain field write, not arrayUnion — there's only ever one
 * reaction per user, so the new value simply replaces the old one).
 */
fun setMessageReaction(
    firestore: FirebaseFirestore,
    matchId: String,
    messageId: String,
    selfUid: String,
    emoji: String
): Task<Void> =
    messageDoc(firestore, matchId, messageId)
        .update(Message.pathReaction(selfUid), emoji)

/** Removes [selfUid]'s reaction entirely — tapping the same emoji again. */
fun removeMessageReaction(
    firestore: FirebaseFirestore,
    matchId: String,
    messageId: String,
    selfUid: String
): Task<Void> =
    messageDoc(firestore, matchId, messageId)
        .update(Message.pathReaction(selfUid), FieldValue.delete())
