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

/**
 * Clears the preferences that belong to the account signing out, leaving
 * device-scoped ones alone.
 *
 * Logging out is not account deletion, so [clearAllWedoraData]'s blunt
 * `clear()` would be wrong here — it would reset the onboarding flag and
 * send the device back to the intro carousel, and drop the dark-mode choice,
 * neither of which belongs to the account.
 *
 * What has to go is anything account-scoped stored under a flat key, since a
 * second account on the same device would otherwise inherit it:
 *
 *  - [FilterPrefs] — age range, distance, status, looking-for and interests.
 *    Signing in as someone else and silently inheriting their feed filters
 *    is the visible bug here.
 *
 * Deliberately NOT cleared:
 *
 *  - [ThemePrefs], [OnboardingPrefs] — device preferences, not account data.
 *  - [RatePromptPrefs] — device-scoped on purpose (see its own doc): a Play
 *    rating belongs to the device and its Play account. Clearing it would
 *    re-prompt someone who has already rated, just because they switched
 *    accounts — the exact thing that scoping was chosen to avoid.
 *  - [LocalProfilePrefs], [LikedProfilesCache], [FeedCache] — already
 *    UID-keyed, so another account simply never reads the previous one's
 *    entries. They're left in place so switching back doesn't refetch
 *    everything from scratch.
 *  - [GuestPrefs] — the logout path clears it explicitly already.
 */
fun clearAccountScopedData(context: Context) {
    FilterPrefs.reset(context)
}
