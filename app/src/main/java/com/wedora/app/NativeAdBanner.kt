package com.wedora.app

import android.view.View
import com.google.android.gms.ads.nativead.NativeAd
import com.wedora.app.databinding.ViewNativeAdBannerBinding

/**
 * Binds [ad] into a [view_native_ad_banner] instance.
 *
 * Shared rather than repeated per screen because the SDK wiring here is
 * load-bearing and easy to get subtly wrong: impressions and clicks are only
 * tracked if headlineView/mediaView/callToActionView are assigned on the
 * NativeAdView AND setNativeAd is called last. A placement that skips any of
 * that still renders, still looks fine, and silently reports nothing —
 * which is exactly the kind of invisible inventory loss this whole piece of
 * work is about.
 */
fun ViewNativeAdBannerBinding.bindNativeAd(ad: NativeAd) {
    tvAdHeadline.text = ad.headline
    tvAdCta.text = ad.callToAction
    tvAdCta.visibility = if (ad.callToAction.isNullOrBlank()) View.GONE else View.VISIBLE

    root.headlineView = tvAdHeadline
    root.callToActionView = tvAdCta
    root.mediaView = adMedia
    // Must be last: the SDK reads the view assignments above at this call.
    root.setNativeAd(ad)
}
