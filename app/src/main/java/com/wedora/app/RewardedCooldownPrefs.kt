package com.wedora.app

import android.content.Context
import java.util.Locale

/**
 * The local half of the rewarded-ad cooldown: one ad per
 * [COOLDOWN_MS] per user.
 *
 * **This is the UI mirror, not the enforcement.** A countdown has to tick
 * every second, and re-reading Firestore at 1Hz to drive a label would be
 * absurd, so the timer runs off the device clock. That makes it trivially
 * bypassable on its own — moving the clock forward re-enables the button.
 *
 * What makes that harmless is that the *grant* is gated server-side:
 * firestore.rules compares [UserProfile.FIELD_LAST_REWARDED_AD_AT] against
 * request.time, which the device has no say over. Someone who skips the
 * countdown gets to watch an ad that grants them nothing.
 *
 * This is a stronger position than the daily like/message limits, which
 * genuinely can't be enforced — rules have no way to format request.time
 * into "yyyy-MM-dd" to check a claimed date. A cooldown needs no formatting,
 * only timestamp arithmetic, which rules do support.
 *
 * Keyed by UID like [LocalProfilePrefs] and [FeedCache], matching the
 * server field's per-user scope: switching accounts on a shared device
 * shouldn't inherit someone else's countdown.
 *
 * Global across both quotas — one timer covers likes and messages together,
 * because the thing being rate-limited is ad views, not either quota.
 */
object RewardedCooldownPrefs {

    private const val PREFS_NAME = "wedora_prefs"
    private const val KEY_PREFIX = "rewarded_last_ad_at_"

    /** Mirrored as duration.value(10, 'm') in firestore.rules. */
    const val COOLDOWN_MS = 10L * 60 * 1000

    /** Records a granted reward. Call only on a confirmed grant — a load
     *  failure or an early dismiss costs the user nothing. */
    fun recordAdWatched(context: Context, uid: String) {
        prefs(context).edit()
            .putLong(KEY_PREFIX + uid, System.currentTimeMillis())
            .apply()
    }

    /** Milliseconds left on the cooldown, or 0 if an ad may be watched now. */
    fun remainingMs(context: Context, uid: String): Long {
        val last = prefs(context).getLong(KEY_PREFIX + uid, 0L)
        if (last == 0L) return 0L

        val elapsed = System.currentTimeMillis() - last
        // Negative means the clock moved backwards since the grant (a manual
        // change, or a network time correction). Treating that as "expired"
        // rather than as a huge remaining time keeps an honest user from
        // being locked out for hours; the server check is what stops a
        // dishonest one, so there's nothing to gain by guessing here.
        if (elapsed < 0L) return 0L

        return (COOLDOWN_MS - elapsed).coerceAtLeast(0L)
    }

    /** "8:32" — for the countdown button and the fallback toast. */
    fun formatRemaining(remainingMs: Long): String {
        // Rounded up, so a label never reads 0:00 while the button is still
        // disabled: 500ms left should show 0:01, not 0:00.
        val totalSeconds = (remainingMs + 999) / 1000
        return String.format(Locale.US, "%d:%02d", totalSeconds / 60, totalSeconds % 60)
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
