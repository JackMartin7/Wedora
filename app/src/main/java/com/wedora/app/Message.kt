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
 *
 * [pending] mirrors the snapshot's own hasPendingWrites — true while this
 * message is still only the local echo of a write Firestore hasn't
 * acknowledged yet. MessageAdapter uses it to show a single checkmark before
 * the server confirms and a double one after, so it needs the listener that
 * builds these to run with MetadataChanges.INCLUDE — otherwise the
 * pending-to-confirmed transition never re-fires the snapshot and the
 * checkmark would silently stick on single.
 *
 * [deleted]/[deletedAt] are "delete for everyone" — firestore.rules enforces
 * that only the sender can set these, exactly once, and only together with
 * clearing [text] entirely (not to an empty string — see the rules'
 * isDeleteForEveryone doc comment). [text] is meaningless once [deleted] is
 * true and callers should render the "This message was deleted" placeholder
 * instead of reading it.
 *
 * [deletedFor] is "delete for me" — the uids that have hidden this message
 * from their own view. Filtering it out of what's shown is a client
 * responsibility (see ChatThreadActivity); the rules only guarantee that
 * writes to it are append-only and self-only, not that it's hidden anywhere.
 *
 * [reactions] maps a uid to the single emoji they've reacted with — one
 * reaction per user, enforced by the rules restricting a write to only the
 * caller's own key.
 */
data class Message(
    val id: String,
    val senderId: String,
    val text: String,
    val sentAt: Timestamp?,
    val pending: Boolean = false,
    val deleted: Boolean = false,
    val deletedAt: Timestamp? = null,
    val deletedFor: List<String> = emptyList(),
    val reactions: Map<String, String> = emptyMap()
) {
    companion object {
        const val FIELD_SENDER_ID = "senderId"
        const val FIELD_TEXT = "text"
        const val FIELD_SENT_AT = "sentAt"
        const val FIELD_DELETED = "deleted"
        const val FIELD_DELETED_AT = "deletedAt"
        const val FIELD_DELETED_FOR = "deletedFor"
        const val FIELD_REACTIONS = "reactions"

        /** Dotted path for updating a single user's reaction without
         *  reading or resending the rest of the map — same shape as
         *  Match.pathLastReadAt. */
        fun pathReaction(uid: String) = "$FIELD_REACTIONS.$uid"

        /**
         * Null only when even the base fields (senderId/text) are missing —
         * a malformed document, not just a deleted one. A deleted message
         * still parses normally; [text] will simply be empty since the rules
         * require it to be cleared, and callers key off [deleted] rather
         * than an empty [text] to decide whether to show the placeholder.
         */
        fun from(snapshot: DocumentSnapshot): Message? {
            val senderId = snapshot.getString(FIELD_SENDER_ID) ?: return null
            val text = snapshot.getString(FIELD_TEXT).orEmpty()
            @Suppress("UNCHECKED_CAST")
            val deletedFor = snapshot.get(FIELD_DELETED_FOR) as? List<String> ?: emptyList()
            @Suppress("UNCHECKED_CAST")
            val reactions = snapshot.get(FIELD_REACTIONS) as? Map<String, String> ?: emptyMap()
            return Message(
                id = snapshot.id,
                senderId = senderId,
                text = text,
                sentAt = snapshot.getTimestamp(
                    FIELD_SENT_AT,
                    DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
                ),
                pending = snapshot.metadata.hasPendingWrites(),
                deleted = snapshot.getBoolean(FIELD_DELETED) ?: false,
                deletedAt = snapshot.getTimestamp(FIELD_DELETED_AT),
                deletedFor = deletedFor,
                reactions = reactions
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
