package com.wedora.app

import android.app.Activity
import android.app.Dialog
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.Window

/**
 * The spinner shown while a rewarded ad loads.
 *
 * A Dialog owned by the host Activity rather than a busy state on
 * [DailyLimitReachedBottomSheet]: the rewarded ad is full-screen, so the
 * sheet's view can be destroyed while the ad is up, and driving "stop being
 * busy" back into a fragment that may be gone is more fragile than owning
 * the spinner somewhere that outlives the ad. See [runRewardedBonusFlow] for
 * the dismissal path, including the timeout.
 */
object AdLoadingDialog {

    /**
     * Shows the spinner over [activity], or returns null if the Activity is
     * already going away — callers treat null as "nothing to dismiss later".
     *
     * Not cancelable: a back press here would leave the ad still loading
     * behind a dismissed dialog, and the ad would then open with no
     * explanation. [runRewardedBonusFlow]'s timeout is what guarantees this
     * can't strand the user.
     */
    fun show(activity: Activity): Dialog? {
        if (activity.isFinishing || activity.isDestroyed) return null

        return Dialog(activity).apply {
            requestWindowFeature(Window.FEATURE_NO_TITLE)
            setContentView(R.layout.dialog_ad_loading)
            setCancelable(false)
            // The layout draws its own card; without this the framework's
            // opaque dialog background would sit behind it as a visible slab.
            window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            show()
        }
    }

    /** Dismisses [dialog] if it's still attached, swallowing the
     *  IllegalArgumentException a dialog whose host window has already gone
     *  throws — the ad is full-screen, so that's a real possibility here. */
    fun dismiss(dialog: Dialog?) {
        dialog ?: return
        runCatching { if (dialog.isShowing) dialog.dismiss() }
    }
}
