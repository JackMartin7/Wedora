package com.wedora.app

import android.app.Activity
import android.graphics.Color
import android.graphics.drawable.Animatable
import android.view.View
import android.view.ViewGroup
import com.google.android.material.snackbar.Snackbar
import com.wedora.app.databinding.ViewUpdateReadyBinding

/**
 * 03 · "Update downloaded — restart to finish installing".
 *
 * Persistent by design: a downloaded-but-uninstalled update is a state the
 * user has to resolve, so this never auto-dismisses. It is hosted on the
 * Activity's own content view rather than any fragment, so it survives
 * navigation within the app.
 *
 * If the user swipes it away it is re-shown on the next resume (see
 * HomeActivity) and mirrored by the App Version row, so dismissing it can
 * never strand a downloaded update with no route to install.
 */
object UpdateReadySnackbar {

    private var current: Snackbar? = null

    /** When the snackbar first appeared, to measure how long users sit on it. */
    private var shownAt = 0L

    fun show(activity: Activity, versionCode: Int) {
        // Already up for this Activity — re-showing would restart the tick
        // animation and re-log the impression.
        if (current?.isShown == true) return

        val root = activity.findViewById<ViewGroup>(android.R.id.content) ?: return
        val snackbar = Snackbar.make(root, "", Snackbar.LENGTH_INDEFINITE)

        val layout = snackbar.view as Snackbar.SnackbarLayout
        // Strip Material's own surface and padding: the custom view draws the
        // whole card, and leaving them in would show a second background
        // behind it plus doubled insets.
        layout.setBackgroundColor(Color.TRANSPARENT)
        layout.setPadding(0, 0, 0, 0)
        layout.findViewById<View>(com.google.android.material.R.id.snackbar_text)
            ?.visibility = View.INVISIBLE

        val b = ViewUpdateReadyBinding.inflate(activity.layoutInflater, layout, false)
        layout.addView(b.root)

        b.btnRestart.setOnClickListener {
            UpdateAnalytics.installStart(
                versionCode,
                if (shownAt == 0L) 0L else System.currentTimeMillis() - shownAt
            )
            // Play takes over from here with its own install + relaunch UI.
            // Anything unsaved must already be persisted — see the call site.
            UpdateRepository.completeUpdate()
        }

        snackbar.show()
        current = snackbar
        shownAt = System.currentTimeMillis()

        UpdateAnalytics.readyView(versionCode)
        // Spoken immediately rather than left to focus order: the snackbar
        // arrives unprompted, so a screen-reader user is told about it the same
        // moment a sighted user sees it.
        b.root.announceForAccessibility(
            activity.getString(R.string.update_ready_announcement)
        )

        playTimeline(b)
    }

    /** Timeline from the spec, relative to the snackbar's own rise. */
    private fun playTimeline(b: ViewUpdateReadyBinding) {
        if (Motion.reducedMotion(b.root)) {
            b.disc.alpha = 1f
            b.disc.scaleX = 1f
            b.disc.scaleY = 1f
            b.btnRestart.alpha = 1f
            // Show the tick at its finished state without stroking it on.
            b.ivTick.setImageResource(R.drawable.ic_check_thin)
            return
        }

        Motion.popIn(b.disc, durationMs = 450, delayMs = 280)

        // Exactly two ripples, then stop — a repeating ripple on a persistent
        // snackbar becomes a permanent distraction.
        Motion.pulse(
            b.ripple,
            fromScale = 1f, toScale = 2.1f,
            fromAlpha = 0.5f, toAlpha = 0f,
            durationMs = 1400, delayMs = 500
        )
        b.ripple.postDelayed({
            Motion.pulse(
                b.ripple,
                fromScale = 1f, toScale = 2.1f,
                fromAlpha = 0.5f, toAlpha = 0f,
                durationMs = 1400, delayMs = 0
            )
        }, 1900)

        b.ivTick.postDelayed({
            (b.ivTick.drawable as? Animatable)?.start()
        }, 520)

        // Lands last so the eye finishes on the thing to tap.
        Motion.popIn(b.btnRestart, durationMs = 400, delayMs = 420)
    }

    /** Called when the hosting Activity goes away, so no stale reference is held. */
    fun dismiss() {
        current?.dismiss()
        current = null
        shownAt = 0L
    }
}
