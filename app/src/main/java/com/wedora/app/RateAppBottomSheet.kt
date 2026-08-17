package com.wedora.app

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.fragment.app.FragmentManager

/**
 * Opens this app's Play Store listing.
 *
 * market:// hands off to the Play Store app directly; the https form is the
 * fallback for a device without it. Both are wrapped because an implicit
 * VIEW intent with no handler throws rather than no-oping — and neither
 * needs a <queries> manifest entry, since nothing here calls resolveActivity.
 *
 * packageName is safe as the listing id: app/build.gradle.kts sets no
 * applicationIdSuffix, so debug and release both report com.wedora.app.
 */
fun Context.openPlayStoreListing() {
    val listing = "details?id=$packageName"
    try {
        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("market://$listing")))
    } catch (e: ActivityNotFoundException) {
        Log.i("WedoraRate", "No Play Store app; falling back to the web listing", e)
        try {
            startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/$listing"))
            )
        } catch (e2: ActivityNotFoundException) {
            Log.w("WedoraRate", "Nothing on this device can open the Play listing", e2)
        }
    }
}

/**
 * Asks the user to rate the app, shown once they've sent their third message
 * ever (see [RatePromptPrefs] for the milestone and the re-ask budget).
 *
 * "Rate Now" hands off to the host, which opens the Play Store listing
 * directly. Deliberately NOT Play Core's In-App Review API: that API's
 * guidance forbids asking the user anything before showing its rating card,
 * which a "Rate Now / Maybe Later" prompt does by definition. An outbound
 * link to the listing isn't governed by that API at all, so this keeps the
 * two-option prompt without the conflict — at the cost of leaving the app,
 * which the in-app card would have avoided.
 *
 * "Maybe Later" is the base class's default dismiss. Nothing is recorded on
 * dismissal because [RatePromptPrefs.recordPromptShown] already ran when the
 * sheet was shown — so swiping it away counts the same as tapping the
 * button, which is the intent.
 */
class RateAppBottomSheet : ConfirmBottomSheet() {

    /** Implemented by the screen that shows this sheet (ChatThreadActivity). */
    interface Host {
        fun onRateAppRequested()
    }

    override val iconRes = R.drawable.ic_star
    override val titleRes = R.string.rate_app_title
    override val subtitleRes = R.string.rate_app_subtitle
    override val primaryLabelRes = R.string.rate_app_now
    override val secondaryLabelRes = R.string.rate_app_later

    override fun onPrimary() {
        (activity as? Host)?.onRateAppRequested()
    }

    companion object {
        private const val TAG = "rate_app"

        fun show(fragmentManager: FragmentManager) {
            RateAppBottomSheet().show(fragmentManager, TAG)
        }
    }
}
