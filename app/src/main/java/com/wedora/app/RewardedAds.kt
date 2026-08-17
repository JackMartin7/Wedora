package com.wedora.app

import android.app.Activity
import android.util.Log
import com.google.android.gms.ads.AdError
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.FullScreenContentCallback
import com.google.android.gms.ads.LoadAdError
import com.google.android.gms.ads.rewarded.RewardedAd
import com.google.android.gms.ads.rewarded.RewardedAdLoadCallback
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The opt-in "watch an ad for one more" flow, offered when a free user hits
 * a daily cap — the middle option between the wall and a subscription.
 *
 * Rewarded is the one format AdMob requires to be genuinely opt-in with the
 * value stated up front, which is why this is only ever reached from a
 * button the user taps on [DailyLimitReachedBottomSheet], never
 * automatically. The reward is granted on the SDK's own
 * `onUserEarnedReward` callback, not on the ad closing — a user who
 * dismisses early gets nothing, which is the behaviour the format requires.
 *
 * Loading is on demand rather than pooled: this is a rare, deliberate
 * action, so an ad request that only happens when someone actually asks is
 * both cheaper and a truer signal than keeping one warm forever.
 */
object RewardedAds {

    private const val TAG = "WedoraAds"

    /** The "watch an ad for one more" flow — AdMob console Rewarded unit. */
    private const val AD_UNIT_ID = "ca-app-pub-6998303779941960/2514430349"

    /** What the user is spending an ad view on. */
    enum class Reward { LIKE, MESSAGE }

    /** How [show] finished, for the caller's UI. */
    sealed class Result {
        /** Reward earned and written — the caller may retry the blocked action. */
        object Granted : Result()

        /** No ad available, or the user dismissed before earning it. */
        object NotEarned : Result()
    }

    private var loading = false

    /**
     * Loads and shows a rewarded ad, granting [reward] only if the SDK
     * reports the user actually earned it.
     *
     * [onResult] always fires exactly once, on the main thread — including
     * on a load failure, so a caller can re-enable its button without
     * needing a timeout of its own.
     *
     * [onAdOpening] fires just before the ad takes over the screen, and only
     * on the path where that actually happens. It exists so the caller can
     * take its loading spinner down and stop counting against its load
     * timeout at the right moment: without it, a caller can't tell a slow
     * load apart from a long ad, and would time out mid-view.
     */
    fun show(
        activity: Activity,
        reward: Reward,
        onAdOpening: () -> Unit = {},
        onResult: (Result) -> Unit
    ) {
        if (loading) {
            onResult(Result.NotEarned)
            return
        }
        loading = true

        RewardedAd.load(
            activity,
            AD_UNIT_ID,
            AdRequest.Builder().build(),
            object : RewardedAdLoadCallback() {
                override fun onAdFailedToLoad(error: LoadAdError) {
                    loading = false
                    Log.w(
                        TAG,
                        "Rewarded failed — code=${error.code} " +
                            "domain=${error.domain} message=${error.message}"
                    )
                    onResult(Result.NotEarned)
                }

                override fun onAdLoaded(ad: RewardedAd) {
                    loading = false
                    onAdOpening()
                    presentAd(activity, ad, reward, onResult)
                }
            }
        )
    }

    private fun presentAd(
        activity: Activity,
        ad: RewardedAd,
        reward: Reward,
        onResult: (Result) -> Unit
    ) {
        var earned = false

        ad.fullScreenContentCallback = object : FullScreenContentCallback() {
            override fun onAdDismissedFullScreenContent() {
                if (!earned) {
                    onResult(Result.NotEarned)
                    return
                }
                // Suppresses the next between-swipes interstitial: two
                // full-screen ads back to back is the fastest route to a
                // policy complaint, and this user just opted into one.
                InterstitialAds.lastRewardedAt = System.currentTimeMillis()
                grantBonus(activity, reward, onResult)
            }

            override fun onAdFailedToShowFullScreenContent(error: AdError) {
                Log.w(TAG, "Rewarded failed to show: ${error.message}")
                onResult(Result.NotEarned)
            }
        }

        // Fires before onAdDismissed — record it and act once the ad closes,
        // so the user isn't interrupted by a Firestore write mid-view.
        ad.show(activity) { earned = true }
    }

    /**
     * Writes the earned bonus. Uses increment-by-read rather than
     * FieldValue.increment because the daily reset depends on comparing the
     * stored date to today, which a blind increment can't do — the same
     * read-then-write shape LikeLimit/MessageLimit already use.
     *
     * Caps at [UserProfile.MAX_DAILY_BONUS_LIKES] /
     * [UserProfile.MAX_DAILY_BONUS_MESSAGES] — one per quota, each set to its
     * own base allowance — matching firestore.rules: a write past either
     * would be rejected server-side anyway, so refusing here turns a
     * confusing PERMISSION_DENIED into an honest "not earned".
     *
     * Also stamps [UserProfile.FIELD_LAST_REWARDED_AD_AT] with a SERVER
     * timestamp, which is what the rules' 10-minute cooldown compares
     * against. It goes in the same write as the bonus on purpose: the rules
     * require both to move together, so a client can't bank a bonus without
     * also starting its own cooldown.
     */
    private fun grantBonus(activity: Activity, reward: Reward, onResult: (Result) -> Unit) {
        val selfUid = com.google.firebase.auth.FirebaseAuth.getInstance().realUid
        if (selfUid == null) {
            onResult(Result.NotEarned)
            return
        }

        val firestore = FirebaseFirestore.getInstance()
        val selfDoc = firestore.collection(UserProfile.COLLECTION).document(selfUid)
        val today = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())

        selfDoc.get()
            .addOnSuccessListener { snapshot ->
                val profile = UserProfile.from(snapshot)
                val storedDate =
                    if (reward == Reward.LIKE) profile.bonusLikesDate else profile.bonusMessagesDate
                val storedCount =
                    if (reward == Reward.LIKE) profile.bonusLikesToday else profile.bonusMessagesToday

                val cap = if (reward == Reward.LIKE) {
                    UserProfile.MAX_DAILY_BONUS_LIKES
                } else {
                    UserProfile.MAX_DAILY_BONUS_MESSAGES
                }

                val current = if (storedDate == today) storedCount else 0
                if (current >= cap) {
                    onResult(Result.NotEarned)
                    return@addOnSuccessListener
                }

                val quotaFields = if (reward == Reward.LIKE) {
                    mapOf(
                        UserProfile.FIELD_BONUS_LIKES_TODAY to current + 1,
                        UserProfile.FIELD_BONUS_LIKES_DATE to today
                    )
                } else {
                    mapOf(
                        UserProfile.FIELD_BONUS_MESSAGES_TODAY to current + 1,
                        UserProfile.FIELD_BONUS_MESSAGES_DATE to today
                    )
                }
                val fields = quotaFields +
                    (UserProfile.FIELD_LAST_REWARDED_AD_AT to FieldValue.serverTimestamp())

                selfDoc.set(fields, SetOptions.merge())
                    .addOnSuccessListener {
                        // Starts the local countdown only once the server has
                        // accepted the write. Doing it optimistically would
                        // lock the button for 10 minutes over a rejected
                        // grant the user never received.
                        RewardedCooldownPrefs.recordAdWatched(activity, selfUid)
                        onResult(Result.Granted)
                    }
                    .addOnFailureListener { e ->
                        logFirestoreWriteFailure(TAG, "Failed to grant rewarded bonus", e)
                        onResult(Result.NotEarned)
                    }
            }
            .addOnFailureListener { e ->
                // Fails closed, unlike the limit checks: granting a bonus we
                // couldn't confirm the current count for could push past the
                // cap and be rejected by the rules anyway.
                Log.w(TAG, "Couldn't read bonus state; not granting", e)
                onResult(Result.NotEarned)
            }
    }
}
