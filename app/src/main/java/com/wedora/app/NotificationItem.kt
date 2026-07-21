package com.wedora.app

import android.content.Context
import com.google.firebase.Timestamp

/**
 * One row on the Notifications screen: someone liked you.
 *
 * [matchId] is carried so commit 2 can mark the underlying match seen.
 */
data class NotificationItem(
    val matchId: String,
    val likerUserId: String,
    val likerName: String,
    val createdAt: Timestamp?
)

/**
 * Compact relative time for notification rows — "now", "5m ago", "3h ago",
 * "2d ago", "4w ago". Deliberately terser than [formatChatTimestamp], which
 * shows clock times and weekdays for the chat list.
 *
 * Returns "" for a null timestamp so the row can hide the field rather than
 * print something misleading.
 */
fun formatRelativeShort(context: Context, timestamp: Timestamp?): String {
    if (timestamp == null) return ""

    val minute = 60_000L
    val hour = 60 * minute
    val day = 24 * hour
    val week = 7 * day

    // Clamped at zero: a server timestamp can land marginally ahead of the
    // device clock, and "-1m ago" would look broken.
    val elapsed = (System.currentTimeMillis() - timestamp.toDate().time).coerceAtLeast(0)

    return when {
        elapsed < minute -> context.getString(R.string.notification_time_now)
        elapsed < hour -> context.getString(R.string.notification_time_minutes, elapsed / minute)
        elapsed < day -> context.getString(R.string.notification_time_hours, elapsed / hour)
        elapsed < week -> context.getString(R.string.notification_time_days, elapsed / day)
        else -> context.getString(R.string.notification_time_weeks, elapsed / week)
    }
}
