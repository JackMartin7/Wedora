package com.wedora.app

import android.content.Context
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

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
    private const val KEY_PROFILES_VIEWED_TODAY = "guest_profiles_viewed_today"
    private const val KEY_PROFILES_VIEWED_DATE = "guest_profiles_viewed_date"

    /**
     * How many distinct profiles a guest can view per calendar day, across
     * Home's swipe stack and Explore's Discover grid combined — one shared
     * pool, not five each, so a guest can't dodge the cap by switching tabs.
     * There's no Firestore account to track this against, so — unlike the
     * signed-in like/message limits in LikeLimit.kt/MessageLimit.kt — it
     * lives entirely in this device-local SharedPreferences file.
     */
    const val DAILY_PROFILE_VIEW_LIMIT = 5

    fun isGuest(context: Context): Boolean =
        prefs(context).getBoolean(KEY_IS_GUEST, false)

    fun setGuest(context: Context) {
        prefs(context).edit().putBoolean(KEY_IS_GUEST, true).apply()
    }

    /** Call after a successful sign-up/sign-in so the account is no longer gated. */
    fun clearGuest(context: Context) {
        prefs(context).edit().putBoolean(KEY_IS_GUEST, false).apply()
        prefs(context).edit()
            .remove(KEY_PROFILES_VIEWED_TODAY)
            .remove(KEY_PROFILES_VIEWED_DATE)
            .apply()
    }

    /**
     * Today's count, or 0 if the stored date isn't today — the reset a new
     * calendar day needs happens implicitly by comparing dates on every read
     * rather than as a separate pass, the same shape LikeLimit.kt's own daily
     * cap uses for signed-in users.
     */
    fun guestProfilesViewedToday(context: Context): Int {
        val p = prefs(context)
        return if (p.getString(KEY_PROFILES_VIEWED_DATE, null) == todayDateString()) {
            p.getInt(KEY_PROFILES_VIEWED_TODAY, 0)
        } else {
            0
        }
    }

    /**
     * Records one more profile viewed today and returns the new count. Rolls
     * the stored date forward to today first if it was stale, which is what
     * makes the very next call after midnight start counting from zero again.
     */
    fun recordGuestProfileViewed(context: Context): Int {
        val next = guestProfilesViewedToday(context) + 1
        prefs(context).edit()
            .putInt(KEY_PROFILES_VIEWED_TODAY, next)
            .putString(KEY_PROFILES_VIEWED_DATE, todayDateString())
            .apply()
        return next
    }

    private fun todayDateString(): String =
        SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
