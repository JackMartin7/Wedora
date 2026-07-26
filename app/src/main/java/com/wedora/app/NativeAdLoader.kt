// Intentionally using test ad IDs through internal/closed testing to avoid
// invalid-traffic risk on real ad units. Swap to real IDs immediately before
// public release.

package com.wedora.app

import android.content.Context
import android.util.Log
import com.google.android.gms.ads.AdListener
import com.google.android.gms.ads.AdLoader
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.LoadAdError
import com.google.android.gms.ads.nativead.NativeAd

/**
 * Loads one native ad at a time for Home's swipe stack (see
 * HomeActivity.refillAdPool). Google's public test native ad unit ID —
 * always resolves to a real, renderable test ad, so callers can develop and
 * test the actual insertion/binding/dismiss flow without an AdMob account.
 */
object NativeAdLoader {

    private const val TAG = "WedoraAds"

    private const val TEST_NATIVE_AD_UNIT_ID = "ca-app-pub-3940256099942544/2247696110"

    fun loadAd(context: Context, onLoaded: (NativeAd) -> Unit, onFailed: () -> Unit) {
        val loader = AdLoader.Builder(context, TEST_NATIVE_AD_UNIT_ID)
            .forNativeAd { nativeAd -> onLoaded(nativeAd) }
            .withAdListener(object : AdListener() {
                override fun onAdFailedToLoad(adError: LoadAdError) {
                    Log.w(TAG, "Native ad failed to load: ${adError.message}")
                    onFailed()
                }
            })
            .build()
        loader.loadAd(AdRequest.Builder().build())
    }
}
