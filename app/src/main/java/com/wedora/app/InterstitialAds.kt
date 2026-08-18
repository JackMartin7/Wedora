package com.wedora.app

import android.app.Activity
import android.content.Context
import android.util.Log
import com.google.android.gms.ads.AdError
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.FullScreenContentCallback
import com.google.android.gms.ads.LoadAdError
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.google.android.gms.ads.interstitial.InterstitialAd
import com.google.android.gms.ads.interstitial.InterstitialAdLoadCallback

/**
 * The full-screen ad shown between sessions, earned from any of three
 * sources — see [Trigger] — that all draw on one shared budget.
 *
 * Every constant here exists to keep this the least intrusive full-screen
 * format it can be, because interstitials are the placement AdMob polices
 * hardest. Four rules, all enforced in [shouldShow]:
 *
 *  - **Never mid-action.** Only offered at a natural pause: after a card has
 *    already left the stack (never during a drag), or as a screen the user
 *    asked to leave is being closed.
 *  - **Never early.** Both [MIN_SESSION_MS] since the app came to the
 *    foreground AND the triggering source's own threshold must be met, so a
 *    user who opens the app and swipes twice never sees one.
 *  - **Never stacked.** [MIN_GAP_MS] between shows, and a rewarded ad
 *    (see [RewardedAds]) suppresses one for [MIN_GAP_AFTER_REWARDED_MS] —
 *    two full-screen ads back to back is the single fastest way to get a
 *    policy complaint, and the user just opted into one of them.
 *  - **Never multiplied by having more sources.** Adding trigger points
 *    adds *ways to earn* an ad, never *extra ads*: every gate above is
 *    shared, and [show] resets all counters, so swiping past the threshold
 *    and closing profiles and leaving chats in quick succession still
 *    yields exactly one ad.
 *
 * Premium users never reach any of it: [shouldShow] returns false for them
 * outright, and nothing is even preloaded.
 *
 * State is in-memory and reset on every foreground (see [onStart]), so
 * "session" means one visit to the app rather than one process — a warm
 * resume after backgrounding starts from zero, which is what keeps a
 * part-used counter from firing an ad on the first action back.
 */
object InterstitialAds : DefaultLifecycleObserver {

    private const val TAG = "WedoraAds"

    /**
     * Starts a fresh ad session whenever the app comes to the foreground.
     *
     * Attached to [ProcessLifecycleOwner] from WedoraApplication, the same
     * way PresenceTracker is, so "session" means the whole process being
     * foregrounded rather than any single Activity — navigating Home ->
     * Profile -> Home is not a new session and must not reset progress.
     */
    override fun onStart(owner: LifecycleOwner) {
        foregroundedAt = System.currentTimeMillis()
        resetCounters()
    }

    /** Between swipe sessions on Home — AdMob console Interstitial unit. */
    private const val AD_UNIT_ID = "ca-app-pub-6998303779941960/2041601713"

    /**
     * What earned the ad. Only ever decides the LAST clause in
     * [shouldShow] — every gate before it is shared, which is what stops
     * three sources firing three ads in quick succession.
     */
    enum class Trigger(val threshold: Int) {
        /**
         * Cards swiped on Home — by far the highest-volume action.
         *
         * 10, lowered from 20. This threshold is a one-time warm-up gate
         * rather than a per-ad cadence: [counts] is only ever incremented
         * (cleared per foreground session by resetCounters, never on a
         * show), so once it is crossed shouldShow stays true and the actual
         * rate limiter for the rest of the session is MIN_GAP_MS. Lowering
         * it therefore changes when the FIRST interstitial of a session
         * becomes eligible, and nothing else — density is unaffected.
         *
         * 20 was unreachable for the users who matter most here. Free tier
         * gets 10 likes a day; passes are unlimited, so a session is bounded
         * by likes, not swipes. At a 70% like rate the wall arrives at ~14
         * swipes and the interstitial never fired at all; at 50% it landed
         * at swipe 20, exactly as the user ran out of likes. 10 is reachable
         * across that whole range and puts the first ad mid-session instead
         * of at the wall.
         */
        SWIPE(10),

        /**
         * Profile detail screens closed. Deliberate, medium-volume; closing
         * back to the feed is a clean transition point.
         */
        PROFILE_CLOSE(5),

        /**
         * Chat threads exited. Lowest volume of the three, so a higher
         * threshold would mean this never fires and adds no inventory at
         * all. It is also the most user-sensitive surface — messaging is
         * the core value, and someone leaving a conversation is often on
         * their way to another one — so the shared floor below, not this
         * number, is what keeps it from being intrusive.
         */
        CHAT_EXIT(4)
    }

    /** Hard floor between two interstitials, whatever the swipe count says. */
    private const val MIN_GAP_MS = 5L * 60 * 1000

    /** A rewarded ad suppresses an interstitial for this long afterwards. */
    private const val MIN_GAP_AFTER_REWARDED_MS = 60L * 1000

    /**
     * Minimum time in the foreground before any interstitial is eligible,
     * however many swipes have happened.
     *
     * The swipe count alone is not enough to keep this away from app open:
     * swiping is fast, and 20 cards can be cleared in well under a minute
     * from a cold start. AdMob enforces hard on interstitials that appear
     * at or near launch, and "20 deliberate actions" is not a defence if
     * they all happened in the first twenty seconds.
     */
    private const val MIN_SESSION_MS = 60L * 1000

    private var loaded: InterstitialAd? = null
    private var loading = false

    /**
     * Events counted per source since the last ad. Reset together — see
     * [resetCounters] for why that has to be all of them, not just the one
     * that fired.
     */
    private val counts = mutableMapOf<Trigger, Int>()

    private var lastShownAt = 0L

    /**
     * When the app last came to the foreground. Reset — along with the
     * swipe counter — on every foreground, not just on process start.
     *
     * The distinction matters: Android keeps a process alive across
     * backgrounding, so without this a user who swiped 19 times, left for
     * an hour and came back would get an interstitial on the very first
     * swipe of what is, to them, a brand-new session. That is the
     * unexpected-launch-interstitial pattern, just reached by warm resume
     * rather than cold start.
     */
    private var foregroundedAt = 0L

    /** Set by [RewardedAds] so a rewarded view suppresses the next interstitial. */
    @Volatile
    var lastRewardedAt = 0L

    /**
     * Counts one event from [trigger] and reports whether an interstitial is
     * now due.
     *
     * Counting and eligibility are one call on purpose: separating them
     * invites a caller to count without checking, or check without
     * counting, and either drifts the cadence silently.
     */
    fun onEvent(context: Context, trigger: Trigger): Boolean {
        if (PremiumStatus.isPremium()) return false
        counts[trigger] = (counts[trigger] ?: 0) + 1
        preloadIfNeeded(trigger, context)
        return shouldShow(trigger)
    }

    /**
     * Every check here except the last is shared across all three sources,
     * and all of them are evaluated before the per-source count is even
     * consulted. That ordering is the design: a trigger can only ever
     * satisfy the final clause, never grant itself an ad the other two
     * wouldn't also have been allowed.
     */
    private fun shouldShow(trigger: Trigger): Boolean {
        if (PremiumStatus.isPremium()) return false
        if (loaded == null) return false

        val now = System.currentTimeMillis()

        // Nothing may fire near app open, regardless of swipe count.
        // foregroundedAt == 0 only if onStart never ran, which would mean
        // this object was never attached — treat that as not eligible
        // rather than as an infinitely old session.
        if (foregroundedAt == 0L || now - foregroundedAt < MIN_SESSION_MS) return false

        // lastShownAt == 0 means none shown yet this process — the swipe
        // threshold alone gates the first one, so a fresh session isn't
        // silently blocked by a gap it can't have violated.
        if (lastShownAt != 0L && now - lastShownAt < MIN_GAP_MS) return false
        if (now - lastRewardedAt < MIN_GAP_AFTER_REWARDED_MS) return false

        // The one per-source clause.
        return (counts[trigger] ?: 0) >= trigger.threshold
    }

    /**
     * Zeroes every counter, not just the one that fired.
     *
     * Resetting only the firing source would leave the others "banked" at
     * or above their thresholds, so the next event on either would fire the
     * instant the [MIN_GAP_MS] floor cleared — turning the real cadence
     * into "one ad per 5 minutes" rather than "one ad per N events". After
     * this, the next ad needs a fresh full threshold somewhere AND the
     * floor to clear.
     */
    private fun resetCounters() = counts.clear()

    /**
     * Loads one ahead so [show] never has to wait. Ads are requested only
     * once the user is within striking distance of the threshold, so a
     * session of two swipes costs no ad request at all.
     */
    private fun preloadIfNeeded(trigger: Trigger, context: Context) {
        if (loaded != null || loading) return
        if (PremiumStatus.isPremium()) return
        // Half the triggering source's own threshold: far enough ahead to be
        // ready, late enough that a short session never costs a request.
        if ((counts[trigger] ?: 0) < trigger.threshold / 2) return

        loading = true
        InterstitialAd.load(
            context.applicationContext,
            AD_UNIT_ID,
            AdRequest.Builder().build(),
            object : InterstitialAdLoadCallback() {
                override fun onAdLoaded(ad: InterstitialAd) {
                    loading = false
                    loaded = ad
                }

                override fun onAdFailedToLoad(error: LoadAdError) {
                    loading = false
                    loaded = null
                    Log.w(
                        TAG,
                        "Interstitial failed — code=${error.code} " +
                            "domain=${error.domain} message=${error.message}"
                    )
                }
            }
        )
    }

    /**
     * Shows the preloaded ad, if there still is one.
     *
     * [onClosed] always runs exactly once — immediately when there was no ad
     * to show, otherwise when the ad is dismissed or fails to display. That
     * contract is what the screen-exit triggers depend on: an Activity that
     * has already called finish() can't host an ad, so those callers show
     * the ad first and complete their navigation from here. A callback that
     * could be skipped would strand the user on a screen they asked to
     * leave.
     *
     * Counters reset whether or not anything displayed: a failed show is
     * still the moment having passed, and retrying immediately would put a
     * full-screen ad in front of someone who just got one attempt already.
     */
    fun show(activity: Activity, onClosed: () -> Unit = {}) {
        val ad = loaded
        loaded = null
        resetCounters()

        if (ad == null) {
            onClosed()
            return
        }
        lastShownAt = System.currentTimeMillis()

        ad.fullScreenContentCallback = object : FullScreenContentCallback() {
            override fun onAdDismissedFullScreenContent() = onClosed()

            override fun onAdFailedToShowFullScreenContent(error: AdError) {
                Log.w(TAG, "Interstitial failed to show: ${error.message}")
                onClosed()
            }
        }
        ad.show(activity)
    }
}
