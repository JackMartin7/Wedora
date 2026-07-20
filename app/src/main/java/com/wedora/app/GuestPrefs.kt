package com.wedora.app

import android.content.Context

/**
 * Tracks whether the user chose "Continue as Guest" and is therefore browsing
 * without a Firebase account.
 *
 * Guests can view the feed but not act on it — see HomeActivity, which routes
 * gated actions to sign-up instead. The flag is cleared once a guest completes
 * a real sign-up.
 *
 * Shares the same SharedPreferences file as [OnboardingPrefs] and [ThemePrefs].
 */
object GuestPrefs {

    private const val PREFS_NAME = "wedora_prefs"
    private const val KEY_IS_GUEST = "is_guest"

    fun isGuest(context: Context): Boolean =
        prefs(context).getBoolean(KEY_IS_GUEST, false)

    fun setGuest(context: Context) {
        prefs(context).edit().putBoolean(KEY_IS_GUEST, true).apply()
    }

    /** Call after a successful sign-up/sign-in so the account is no longer gated. */
    fun clearGuest(context: Context) {
        prefs(context).edit().putBoolean(KEY_IS_GUEST, false).apply()
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
