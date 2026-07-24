package com.wedora.app

import com.google.android.gms.tasks.Task
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import com.google.firebase.firestore.WriteBatch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Free-tier messages per calendar day. Premium is unlimited. An independent
 *  quota from the daily like limit — see [likeUserRespectingDailyLimit]. */
private const val FREE_DAILY_MESSAGE_LIMIT = 10

/** "yyyy-MM-dd" for the device's local calendar day. */
private fun todayDateString(): String =
    SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())

/** What happened when [sendMessageRespectingDailyLimit] was asked to send a message. */
sealed class MessageSendAttempt {
    /** The write is in flight; attach UI handling to [task] as usual. */
    data class Started(val task: Task<Void>) : MessageSendAttempt()

    /** Free tier, already at [FREE_DAILY_MESSAGE_LIMIT] for today — nothing was written. */
    object DailyLimitReached : MessageSendAttempt()
}

/**
 * Sends a message, enforcing the free-tier daily cap first — the same shape
 * as [likeUserRespectingDailyLimit]: reads the sender's own profile (cheap
 * and already fully readable, unlike the blind-write match document), skips
 * the check entirely for Premium, and otherwise resets the count whenever the
 * stored date isn't today. The message write and the incremented count are
 * one batch, so a message is never recorded without the count moving, or
 * vice versa.
 *
 * Client-side only, same as the like limit — see firestore.rules for how far
 * this is (and isn't) enforced server-side.
 */
fun sendMessageRespectingDailyLimit(
    firestore: FirebaseFirestore,
    selfUid: String,
    otherUid: String,
    matchId: String,
    text: String,
    onResult: (MessageSendAttempt) -> Unit
) {
    val selfDoc = firestore.collection(UserProfile.COLLECTION).document(selfUid)
    selfDoc.get()
        .addOnSuccessListener { snapshot ->
            val profile = UserProfile.from(snapshot)
            if (profile.isPremium) {
                onResult(
                    MessageSendAttempt.Started(
                        sendMessageBatch(firestore, selfUid, otherUid, matchId, text)
                    )
                )
                return@addOnSuccessListener
            }

            val today = todayDateString()
            val countSoFar =
                if (profile.messagesSentDate == today) profile.messagesSentToday else 0

            if (countSoFar >= FREE_DAILY_MESSAGE_LIMIT) {
                onResult(MessageSendAttempt.DailyLimitReached)
                return@addOnSuccessListener
            }

            val batch = firestore.batch()
            batch.set(
                selfDoc,
                mapOf(
                    UserProfile.FIELD_MESSAGES_SENT_TODAY to countSoFar + 1,
                    UserProfile.FIELD_MESSAGES_SENT_DATE to today
                ),
                SetOptions.merge()
            )
            addMessageWrites(batch, firestore, selfUid, otherUid, matchId, text)
            onResult(MessageSendAttempt.Started(batch.commit()))
        }
        .addOnFailureListener {
            // Can't confirm the limit — fail open rather than blocking a
            // message over a transient read error.
            onResult(
                MessageSendAttempt.Started(
                    sendMessageBatch(firestore, selfUid, otherUid, matchId, text)
                )
            )
        }
}

private fun sendMessageBatch(
    firestore: FirebaseFirestore,
    selfUid: String,
    otherUid: String,
    matchId: String,
    text: String
): Task<Void> {
    val batch = firestore.batch()
    addMessageWrites(batch, firestore, selfUid, otherUid, matchId, text)
    return batch.commit()
}

/** The message doc + lastMessage summary update — see ChatThreadActivity.sendMessage's
 *  original doc comment for why these are batched and what each piece does. */
private fun addMessageWrites(
    batch: WriteBatch,
    firestore: FirebaseFirestore,
    selfUid: String,
    otherUid: String,
    matchId: String,
    text: String
) {
    val matchDoc = firestore.collection(Match.COLLECTION).document(matchId)
    val message = mapOf(
        Message.FIELD_SENDER_ID to selfUid,
        Message.FIELD_TEXT to text,
        Message.FIELD_SENT_AT to FieldValue.serverTimestamp()
    )
    batch.set(matchDoc.collection(Match.SUBCOLLECTION_MESSAGES).document(), message)
    batch.update(
        matchDoc,
        Match.PATH_LM_TEXT, text,
        Match.PATH_LM_SENT_AT, FieldValue.serverTimestamp(),
        Match.PATH_LM_SENDER_ID, selfUid,
        Match.PATH_LM_UNREAD_COUNT, FieldValue.increment(1),
        Match.FIELD_HIDDEN_BY, FieldValue.arrayRemove(otherUid)
    )
}
