package com.wedora.app

import java.util.Locale

/**
 * A count shortened for display: "999", "1.2k", "3.4m".
 *
 * Figma 132:2 shows the received-like count as "1.2k", and the swipe card uses
 * the same treatment so the two screens agree.
 *
 * TRUNCATES rather than rounds. 1,999 reads as "1.9k", not "2k": this labels a
 * real quantity on someone's profile, and rounding up would overstate it. The
 * cost is that 1,999 and 1,900 look alike, which matters far less.
 *
 * A whole value drops the decimal, so 1,000 is "1k" rather than "1.0k". The
 * separator follows the device locale, since this is display copy.
 *
 * Values below 1,000 are returned as-is, which also covers the negatives that
 * UserProfile.from already floors away.
 */
fun formatCompactCount(count: Int): String = when {
    count < 1_000 -> count.toString()
    count < 1_000_000 -> compactUnit(count / 1_000.0, "k")
    else -> compactUnit(count / 1_000_000.0, "m")
}

private fun compactUnit(value: Double, suffix: String): String {
    val truncated = (value * 10).toInt() / 10.0
    return if (truncated % 1.0 == 0.0) {
        "${truncated.toInt()}$suffix"
    } else {
        String.format(Locale.getDefault(), "%.1f%s", truncated, suffix)
    }
}
