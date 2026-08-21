package com.wedora.app

import android.view.View
import java.util.Date
import kotlin.math.max

/**
 * Turns a `lastSeen` timestamp into a human status: "Online" when recent,
 * "Last seen 2h ago" otherwise, or nothing at all when it's unknown.
 *
 * "Recent" is a five-minute window — long enough that a brief context switch
 * (answering a message, checking a notification) still reads as online, short
 * enough that it means genuinely present rather than "opened the app today".
 */
object OnlineStatus {

    private const val ONLINE_WINDOW_MS = 5 * 60 * 1000L

    /**
     * The window the "Active today" FILTER uses - deliberately much wider than
     * [ONLINE_WINDOW_MS], and deliberately not called online.
     *
     * The two exist for different jobs. The dot answers "are they here right
     * now", so five minutes is right. A filter needs a pool worth filtering:
     * measured against production, zero users were within five minutes and 94
     * were within twenty-four hours, so a five-minute filter would have
     * returned nothing and read as broken.
     *
     * That gap is exactly why the filter is NOT labelled "Online Now". Someone
     * seen six hours ago passes it but shows no dot, and a control claiming
     * they are online while the card says otherwise is a contradiction the
     * user sees immediately.
     */
    const val ACTIVE_WINDOW_MS = 24 * 60 * 60 * 1000L

    /** True when [lastSeen] is within the online window. False when null. */
    fun isOnline(lastSeen: Date?): Boolean =
        lastSeen != null && now() - lastSeen.time <= ONLINE_WINDOW_MS

    /**
     * True when [lastSeen] is within [ACTIVE_WINDOW_MS]. False when null, which
     * is what excludes a profile with no lastSeen recorded once the filter is
     * on - the same rule status and interests use: an active-today filter that
     * keeps unknown activity is not filtering by activity.
     */
    fun isActiveRecently(lastSeen: Date?): Boolean =
        lastSeen != null && now() - lastSeen.time <= ACTIVE_WINDOW_MS

    /**
     * "Online", "Last seen 2h ago", or null when [lastSeen] is missing so the
     * caller can show nothing rather than an empty or guessed status.
     */
    fun format(lastSeen: Date?): String? {
        if (lastSeen == null) return null
        return if (isOnline(lastSeen)) "Online" else "Last seen ${timeAgo(lastSeen)}"
    }

    /** "2m ago", "1h ago", "3d ago" — coarsening as the gap grows. */
    private fun timeAgo(date: Date): String {
        val minutes = max(0L, now() - date.time) / 60_000L
        return when {
            minutes < 1 -> "just now"
            minutes < 60 -> "${minutes}m ago"
            minutes < 24 * 60 -> "${minutes / 60}h ago"
            else -> "${minutes / (24 * 60)}d ago"
        }
    }

    private fun now(): Long = System.currentTimeMillis()
}

/**
 * Shows or hides an online-status dot (see view_online_status_dot.xml) based
 * on [lastSeen]. Used on every avatar list — Home, Chats, Likes,
 * Notifications, Match History, Profile Detail.
 */
fun View.bindOnlineDot(lastSeen: Date?) {
    visibility = if (OnlineStatus.isOnline(lastSeen)) View.VISIBLE else View.GONE
}
