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

    fun loadAd(context: Context, adUnitId: String, onLoaded: (NativeAd) -> Unit, onFailed: () -> Unit) {
        val loader = AdLoader.Builder(context, adUnitId)
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
