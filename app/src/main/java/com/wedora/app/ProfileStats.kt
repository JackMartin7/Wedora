package com.wedora.app

import android.content.Context
import java.util.Locale

/**
 * Formatting and scoring for the three stats on the Profile screen.
 *
 * Kept out of the activity so the arithmetic — which is the part worth being
 * sure about — is readable on its own.
 */

/** Below this, the profile counts as incomplete and the figure is shown in accent. */
const val PROFILE_COMPLETE_THRESHOLD = 70

/**
 * Compact count: plain below 1000, then "1.2k" / "3.4M", with a whole number
 * losing its ".0" ("1k", not "1.0k").
 *
 * Formatted with [Locale.US] deliberately. The default locale would render the
 * decimal separator as a comma in much of the world, turning 1200 into "1,2k",
 * which reads as a thousands separator rather than a decimal point.
 */
fun formatStatCount(count: Int): String = when {
    count < 1_000 -> count.toString()
    count < 1_000_000 -> compact(count / 1_000.0, "k")
    else -> compact(count / 1_000_000.0, "M")
}

private fun compact(value: Double, suffix: String): String {
    // Truncated, not rounded: 1999 should read "1.9k", never "2k", which would
    // overstate the number.
    val truncated = kotlin.math.floor(value * 10) / 10
    return if (truncated % 1.0 == 0.0) {
        "${truncated.toInt()}$suffix"
    } else {
        String.format(Locale.US, "%.1f%s", truncated, suffix)
    }
}

/**
 * How complete this profile is, as a percentage.
 *
 * The weights total exactly 100. City and country score together because they
 * are collected together and one without the other isn't a usable location.
 *
 * The photo is device-local (see [LocalProfilePrefs]), so this is inherently a
 * per-device figure: the same account on a new phone reads 15% lower until a
 * photo is picked again. That follows from photos never being uploaded, and is
 * the honest number for what this device can show other users.
 */
fun calculateProfileCompletion(context: Context, uid: String, profile: UserProfile): Int {
    var score = 0
    if (!profile.displayName.isNullOrBlank()) score += 15
    if (profile.age != null) score += 15
    if (!profile.city.isNullOrBlank() && !profile.country.isNullOrBlank()) score += 15
    if (!profile.gender.isNullOrBlank()) score += 10
    if (!profile.interestedIn.isNullOrBlank()) score += 10
    if (!profile.bio.isNullOrBlank()) score += 20
    if (LocalProfilePrefs.getPhotoPath(context, uid) != null) score += 15
    return score
}
