package com.wedora.app

import android.view.View
import android.view.animation.OvershootInterpolator
import com.google.android.material.bottomsheet.BottomSheetDialogFragment

/**
 * Base for the app's modal bottom sheets.
 *
 * Applies [R.style.WedoraBottomSheetTheme], which gives the rounded-top
 * surface and keeps the framework's dim overlay and bottom-to-top slide.
 * Dismiss-on-outside-tap is on by default for a modal sheet.
 *
 * On top of the slide, [springIn] adds a gentle settle so the sheet reads as
 * arriving with a little weight rather than snapping to a stop. The tension is
 * deliberately low — a strong overshoot on a whole sheet looks like a bug, not
 * a flourish.
 */
abstract class WedoraBottomSheetDialog : BottomSheetDialogFragment() {

    override fun getTheme(): Int = R.style.WedoraBottomSheetTheme

    /**
     * Nudges [content] up from a small offset and lets it overshoot slightly
     * back into place, layered over the sheet's own slide. Call from
     * onViewCreated with the sheet's root.
     */
    protected fun springIn(content: View) {
        content.translationY = SPRING_START_OFFSET_PX * resources.displayMetrics.density
        content.animate()
            .translationY(0f)
            .setInterpolator(OvershootInterpolator(SPRING_TENSION))
            .setDuration(SPRING_DURATION_MS)
            .start()
    }

    private companion object {
        const val SPRING_START_OFFSET_PX = 20f
        const val SPRING_TENSION = 0.55f
        const val SPRING_DURATION_MS = 320L
    }
}
