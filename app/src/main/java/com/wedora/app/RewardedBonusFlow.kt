package com.wedora.app

import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.firebase.auth.FirebaseAuth

/**
 * The whole "watch an ad for one more" interaction, shared by every screen
 * that can surface [DailyLimitReachedBottomSheet] — Home, the chat thread,
 * and the profile detail screen all offer it, and all three should behave
 * identically. Being the single funnel is what let the loading spinner, the
 * success sheet and the cooldown land in one place rather than three.
 *
 * Deliberately does NOT retry the action that was blocked. The user is told
 * the allowance is theirs and taps again themselves: auto-retrying would
 * mean an ad view silently spending itself on a like the user might no
 * longer want by the time the ad finishes, and on a screen they may have
 * navigated away from.
 */

/**
 * How long to wait for an ad to open before giving up.
 *
 * Belt-and-braces, not the primary path: [RewardedAds.show] already
 * guarantees exactly one callback including on load failure. This exists
 * only for an SDK that never calls back at all, which would otherwise leave
 * a non-cancelable spinner on screen forever.
 */
private const val LOAD_TIMEOUT_MS = 15_000L

fun AppCompatActivity.runRewardedBonusFlow(kind: DailyLimitReachedBottomSheet.Kind) {
    val uid = FirebaseAuth.getInstance().realUid
    if (uid == null) {
        Toast.makeText(this, R.string.rewarded_not_earned, Toast.LENGTH_LONG).show()
        return
    }

    // Normally unreachable — the sheet disables its own button and shows a
    // countdown for the whole cooldown. Kept as a guard for the paths that
    // don't come through the sheet's tick (a stale sheet left on screen
    // across a config change, say), so a tap can't spend an ad request the
    // rules would refuse to pay out on anyway.
    val remaining = RewardedCooldownPrefs.remainingMs(this, uid)
    if (remaining > 0L) {
        Toast.makeText(
            this,
            getString(
                R.string.rewarded_cooldown_toast,
                RewardedCooldownPrefs.formatRemaining(remaining)
            ),
            Toast.LENGTH_LONG
        ).show()
        return
    }

    val reward = when (kind) {
        DailyLimitReachedBottomSheet.Kind.LIKES -> RewardedAds.Reward.LIKE
        DailyLimitReachedBottomSheet.Kind.MESSAGES -> RewardedAds.Reward.MESSAGE
    }

    val loadingDialog = AdLoadingDialog.show(this)
    val handler = Handler(Looper.getMainLooper())

    // One latch for three racing paths — timeout, ad-opened, and result.
    // Whichever lands first owns taking the spinner down; the others become
    // no-ops. Without it a timeout firing just as the ad opens would toast
    // "no ad available" over an ad that is playing.
    var spinnerHandled = false
    fun takeSpinnerDown(): Boolean {
        if (spinnerHandled) return false
        spinnerHandled = true
        handler.removeCallbacksAndMessages(null)
        AdLoadingDialog.dismiss(loadingDialog)
        return true
    }

    handler.postDelayed({
        if (takeSpinnerDown() && !isFinishing && !isDestroyed) {
            Toast.makeText(this, R.string.rewarded_not_earned, Toast.LENGTH_LONG).show()
        }
    }, LOAD_TIMEOUT_MS)

    RewardedAds.show(
        activity = this,
        reward = reward,
        // The ad is about to take the screen: the wait is over, and the
        // timeout must stop counting or a long ad would trip it mid-view.
        onAdOpening = { takeSpinnerDown() }
    ) { result ->
        val ownsFeedback = takeSpinnerDown()

        // The ad is full-screen, so this Activity was stopped while it ran
        // and can legitimately be gone by the time the callback lands.
        if (isFinishing || isDestroyed) return@show

        when (result) {
            // The sheet is worth showing even if the timeout already spoke:
            // a reward that was genuinely granted should be acknowledged
            // properly, and the write is what the user actually cares about.
            RewardedAds.Result.Granted ->
                RewardGrantedBottomSheet.show(supportFragmentManager, kind)

            // Failure only speaks if it wasn't already spoken for, so the
            // user never gets two "no ad available" toasts for one tap.
            RewardedAds.Result.NotEarned ->
                if (ownsFeedback) {
                    Toast.makeText(this, R.string.rewarded_not_earned, Toast.LENGTH_LONG).show()
                }
        }
    }
}
