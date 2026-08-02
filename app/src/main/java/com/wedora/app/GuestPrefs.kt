package com.wedora.app

import android.content.Context
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.random.Random

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
    private const val KEY_GUEST_NUMBER = "guest_number"
    private const val KEY_PROFILES_VIEWED_TODAY = "guest_profiles_viewed_today"
    private const val KEY_PROFILES_VIEWED_DATE = "guest_profiles_viewed_date"
    private const val KEY_GUEST_GENDER = "guest_gender"
    private const val KEY_GUEST_INTERESTED_IN = "guest_interested_in"

    /**
     * Range for a freshly generated guest number — wide enough that two
     * guests on two different devices picking the same one in the same
     * stretch of time is unlikely, without needing a backend counter (guests
     * have no Firebase Auth session at all, so there's no safe, rule-gated
     * way to increment a shared Firestore counter for them). Per-device
     * unique, not globally unique — see [guestDisplayName]'s doc comment.
     */
    private const val GUEST_NUMBER_MIN = 1
    private const val GUEST_NUMBER_MAX = 999_999

    /**
     * How many distinct profiles a guest can view per calendar day, across
     * Home's swipe stack and Explore's Discover grid combined — one shared
     * pool, not this many on each screen, so a guest can't dodge the cap by
     * switching tabs.
     * There's no Firestore account to track this against, so — unlike the
     * signed-in like/message limits in LikeLimit.kt/MessageLimit.kt — it
     * lives entirely in this device-local SharedPreferences file.
     */
    const val DAILY_PROFILE_VIEW_LIMIT = 25

    fun isGuest(context: Context): Boolean =
        prefs(context).getBoolean(KEY_IS_GUEST, false)

    /**
     * Assigns a guest number the first time this device goes guest, and
     * leaves an existing one untouched on every call after that — so if this
     * somehow runs again for a guest who's already mid-session (there's no
     * legitimate path back to "Continue as Guest" without clearGuest running
     * first, but this guards it regardless), their number can't appear to
     * change out from under them.
     */
    fun setGuest(context: Context) {
        val p = prefs(context)
        val editor = p.edit().putBoolean(KEY_IS_GUEST, true)
        if (!p.contains(KEY_GUEST_NUMBER)) {
            editor.putInt(KEY_GUEST_NUMBER, Random.nextInt(GUEST_NUMBER_MIN, GUEST_NUMBER_MAX + 1))
        }
        editor.apply()
    }

    /**
     * Call after a successful sign-up/sign-in so the account is no longer
     * gated. Also drops the guest number and view-count state — a real
     * account doesn't need either, and a later "Continue as Guest" on the
     * same device (after logging out again) starts a genuinely new guest
     * identity rather than resuming the old one.
     */
    fun clearGuest(context: Context) {
        prefs(context).edit()
            .putBoolean(KEY_IS_GUEST, false)
            .remove(KEY_GUEST_NUMBER)
            .remove(KEY_PROFILES_VIEWED_TODAY)
            .remove(KEY_PROFILES_VIEWED_DATE)
            .remove(KEY_GUEST_GENDER)
            .remove(KEY_GUEST_INTERESTED_IN)
            .apply()
    }

    /**
     * A guest's own gender and who they're interested in, as [Gender]'s
     * canonical firestoreValue strings — the same "male"/"female"
     * vocabulary the signed-up profile-setup flow writes to Firestore,
     * kept consistent here even though this never leaves the device, so
     * formatting either one only ever needs one lookup table (Gender.values).
     * Null until something sets them; nothing does yet — see
     * [setGuestGenderPreferences]'s own doc comment.
     */
    fun guestGender(context: Context): String? =
        prefs(context).getString(KEY_GUEST_GENDER, null)

    fun guestInterestedIn(context: Context): String? =
        prefs(context).getString(KEY_GUEST_INTERESTED_IN, null)

    /**
     * Not called anywhere yet — there's currently no guest-facing screen
     * that asks for either value, only the signed-up Sign Up flow's own
     * ProfileStep2GenderActivity, which guests never reach. Added so
     * ProfileActivity's guest gender pill has a real API to read once such
     * a screen exists, instead of that screen having to reach into
     * SharedPreferences directly.
     */
    fun setGuestGenderPreferences(context: Context, gender: String, interestedIn: String) {
        prefs(context).edit()
            .putString(KEY_GUEST_GENDER, gender)
            .putString(KEY_GUEST_INTERESTED_IN, interestedIn)
            .apply()
    }

    /**
     * "Guest 001", growing past three digits naturally rather than truncating
     * (see [GUEST_NUMBER_MAX]) — this is the one display name every guest
     * screen should use instead of a bare "Guest", so a returning guest
     * recognizes their own session across visits. Per-device unique, not
     * globally unique: there's no backend counter behind it (see
     * GUEST_NUMBER_MIN/MAX's own doc comment), so two different devices can
     * in principle land on the same number. Falls back to the pre-numbered
     * "Guest" label in the practically-impossible case this is read before
     * [setGuest] has ever run for this device.
     */
    fun guestDisplayName(context: Context): String {
        val number = prefs(context).getInt(KEY_GUEST_NUMBER, 0)
        return if (number == 0) {
            context.getString(R.string.guest_label)
        } else {
            context.getString(R.string.guest_numbered_label, number.toString().padStart(3, '0'))
        }
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
