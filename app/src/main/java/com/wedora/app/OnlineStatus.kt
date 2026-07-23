package com.wedora.app

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

    /** True when [lastSeen] is within the online window. False when null. */
    fun isOnline(lastSeen: Date?): Boolean =
        lastSeen != null && now() - lastSeen.time <= ONLINE_WINDOW_MS

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
