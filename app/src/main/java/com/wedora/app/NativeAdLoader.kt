package com.wedora.app

import android.content.Context
import android.util.Log
import com.google.android.gms.ads.AdListener
import com.google.android.gms.ads.AdLoader
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.LoadAdError
import com.google.android.gms.ads.nativead.NativeAd

/**
 * Loads one native ad at a time for whichever placement calls it — see
 * [NativeAdPool], which owns picking the right ad unit ID per screen and
 * passes it in here.
 */
object NativeAdLoader {

    private const val TAG = "WedoraAds"

    /** Home's swipe stack — AdMob console name "Native swaping". */
    const val AD_UNIT_ID_HOME_SWIPE = "ca-app-pub-6998303779941960/6974351853"

    /** Explore's Discover grid — AdMob console name "Native explore". */
    const val AD_UNIT_ID_EXPLORE = "ca-app-pub-6998303779941960/3186436074"

    /** Likes screen's list — AdMob console name "Native likes". */
    const val AD_UNIT_ID_LIKES = "ca-app-pub-6998303779941960/1649993126"

    /** Conversation list — AdMob console name "Native chats". */
    const val AD_UNIT_ID_CHATS = "ca-app-pub-6998303779941960/5513942865"

    /**
     * Consecutive *reportable* failures per ad unit, so a persistent problem
     * reaches Crashlytics once rather than on every retry. Reset by the next
     * success. No-fill never counts toward this — see [isReportable].
     */
    private val consecutiveFailures = mutableMapOf<String, Int>()

    /**
     * After this many consecutive reportable failures on one unit, report it.
     */
    private const val FAILURE_REPORT_THRESHOLD = 5

    /** AdMob's NO_FILL — nobody bid. Not a fault. */
    private const val ERROR_CODE_NO_FILL = 3

    /**
     * Whether a failure code says something is actually broken.
     *
     * NO_FILL is excluded outright rather than merely rate-limited:
     * confirmed on this app's own Explore unit, which returns it steadily
     * while its sibling units fill normally. That is an inventory outcome,
     * not a defect, and no threshold makes a chronic one stop being noise —
     * it would just report every fifth occurrence forever and train us to
     * ignore the one alert that matters.
     *
     * What's left is worth waking up for: 1=INVALID_REQUEST (wrong unit id,
     * app-id mismatch, or a unit created with the wrong format),
     * 2=NETWORK_ERROR, 0=INTERNAL_ERROR. Every code is still logged to
     * logcat regardless, so NO_FILL stays diagnosable on demand.
     *
     * One caveat on code 1 now that [NativeAdPool] retries: the SDK itself
     * throttles a unit that has failed several times in a row and answers
     * requests made during that window with INVALID_REQUEST rather than
     * sending them to Google at all. A burst of code 1 therefore means
     * "requesting too fast" at least as readily as "this unit is wrong" —
     * which is precisely why the retry backoff starts at 60s, far outside
     * that window.
     */
    private fun isReportable(code: Int): Boolean = code != ERROR_CODE_NO_FILL

    /**
     * [onFailed] receives the LoadAdError code so the caller can tell NO_FILL
     * apart from everything else — [NativeAdPool] retries only code 3, since
     * retrying code 1 would be retrying into the SDK's own throttle.
     */
    fun loadAd(
        context: Context,
        adUnitId: String,
        onLoaded: (NativeAd) -> Unit,
        onFailed: (code: Int) -> Unit
    ) {
        val loader = AdLoader.Builder(context, adUnitId)
            .forNativeAd { nativeAd ->
                consecutiveFailures.remove(adUnitId)
                onLoaded(nativeAd)
            }
            .withAdListener(object : AdListener() {
                override fun onAdFailedToLoad(adError: LoadAdError) {
                    // code and domain, not just message: they're what separate
                    // "nobody bid" from "this unit is wrong", and message alone
                    // can't. 0=INTERNAL_ERROR, 1=INVALID_REQUEST (bad unit id /
                    // app id mismatch), 2=NETWORK_ERROR, 3=NO_FILL (normal, and
                    // by far the most common on a small app).
                    Log.w(
                        TAG,
                        "Native ad failed on $adUnitId — " +
                            "code=${adError.code} domain=${adError.domain} " +
                            "message=${adError.message}"
                    )

                    if (isReportable(adError.code)) {
                        val failures = (consecutiveFailures[adUnitId] ?: 0) + 1
                        consecutiveFailures[adUnitId] = failures
                        if (failures == FAILURE_REPORT_THRESHOLD) {
                            // Exactly ==, not >=: report once at the threshold
                            // rather than on every failure past it.
                            CrashReporting.record(
                                IllegalStateException(
                                    "Native ad unit $adUnitId failed $failures times in a row " +
                                        "(latest code=${adError.code} domain=${adError.domain})"
                                ),
                                "NativeAdLoader repeated failure"
                            )
                        }
                    }
                    onFailed(adError.code)
                }
            })
            .build()
        loader.loadAd(AdRequest.Builder().build())
    }
}
