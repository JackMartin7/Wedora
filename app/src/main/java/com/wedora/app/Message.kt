package com.wedora.app

import android.content.Context
import android.text.format.DateFormat
import android.text.format.DateUtils
import com.google.firebase.Timestamp
import com.google.firebase.firestore.DocumentSnapshot
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * One message in `matches/{matchId}/messages`.
 *
 * [sentAt] is read with ESTIMATE rather than the default NONE behaviour. A
 * just-sent message appears in the local snapshot before the server resolves
 * its timestamp, and under NONE that field reads back null — which would sort
 * the message to the wrong end of the thread until the server confirmed it.
 * ESTIMATE fills in a local approximation so ordering stays stable.
 */
data class Message(
    val id: String,
    val senderId: String,
    val text: String,
    val sentAt: Timestamp?
) {
    companion object {
        const val FIELD_SENDER_ID = "senderId"
        const val FIELD_TEXT = "text"
        const val FIELD_SENT_AT = "sentAt"

        fun from(snapshot: DocumentSnapshot): Message? {
            val senderId = snapshot.getString(FIELD_SENDER_ID) ?: return null
            val text = snapshot.getString(FIELD_TEXT) ?: return null
            return Message(
                id = snapshot.id,
                senderId = senderId,
                text = text,
                sentAt = snapshot.getTimestamp(
                    FIELD_SENT_AT,
                    DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
                )
            )
        }
    }
}

/**
 * Chat-list style timestamp: clock time today, "Yesterday", the weekday within
 * the last week, then a plain date. Uses the device's 12/24-hour preference.
 */
fun formatChatTimestamp(context: Context, timestamp: Timestamp?): String {
    if (timestamp == null) return ""
    val millis = timestamp.toDate().time
    val now = System.currentTimeMillis()

    return when {
        DateUtils.isToday(millis) ->
            DateFormat.getTimeFormat(context).format(Date(millis))

        DateUtils.isToday(millis + DateUtils.DAY_IN_MILLIS) ->
            context.getString(R.string.timestamp_yesterday)

        now - millis < 7 * DateUtils.DAY_IN_MILLIS ->
            SimpleDateFormat("EEE", Locale.getDefault()).format(Date(millis))

        else ->
            DateFormat.getDateFormat(context).format(Date(millis))
    }
}
