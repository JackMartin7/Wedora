package com.wedora.app

import android.content.Context
import java.io.File

/**
 * The single SharedPreferences file every preference object in the app writes
 * to — [OnboardingPrefs], [GuestPrefs], [ThemePrefs] and [LocalProfilePrefs]
 * all share it, keyed by their own prefixes.
 */
private const val PREFS_NAME = "wedora_prefs"

/**
 * Wipes every stored preference and the on-device profile photo.
 *
 * Used by account deletion, where leaving anything behind would carry state
 * from a deleted account into the next sign-in on this device: a stale dark
 * mode choice is harmless, but a lingering guest flag or a photo keyed to a
 * UID that no longer exists is not.
 *
 * Clearing the file also resets the onboarding flag, so the next launch starts
 * from onboarding — correct here, since deleting the account returns the
 * device to a first-run state.
 *
 * The photo lives in filesDir rather than in preferences, so clearing the
 * preferences alone would orphan the file with nothing left pointing at it.
 */
fun clearAllWedoraData(context: Context, uid: String?) {
    uid?.let { LocalProfilePrefs.getPhotoPath(context, it) }
        ?.let { path -> runCatching { File(path).delete() } }

    context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        .edit()
        .clear()
        .apply()
}
