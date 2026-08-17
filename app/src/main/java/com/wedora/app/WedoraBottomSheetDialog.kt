package com.wedora.app

import android.view.View
import android.view.animation.OvershootInterpolator
import com.google.android.material.bottomsheet.BottomSheetBehavior
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
     * Opens every sheet fully expanded instead of at the library's automatic
     * peek height.
     *
     * Nothing in this app configures BottomSheetBehavior, so the defaults
     * come from Widget.Design.BottomSheet.Modal: `behavior_peekHeight=auto`,
     * `behavior_skipCollapsed=false`, and a 64dp peek floor. The auto height
     * is computed as
     *
     *     min( max(peekHeightMin, parentHeight - parentWidth * 9/16), childHeight )
     *
     * which is fine in portrait — on a 360x800 screen that's
     * `800 - 202 = 598`, capped to the content's own height, so the sheet
     * appears whole. Rotate to 800x360 and the same expression is
     * `360 - 450 = -90`; the 64dp floor wins, and every sheet in the app
     * opens showing nothing but its drag handle. That is a latent bug in all
     * of them, not just the daily-limit sheet where it was first noticed.
     *
     * skipCollapsed goes with it: without it a downward drag settles back at
     * that same 64dp sliver rather than dismissing. These are modal sheets
     * asking for a decision, so the only two useful states are "open" and
     * "gone".
     *
     * onStart, not onCreateView — the behavior isn't attached to the dialog's
     * container until the dialog is being shown.
     *
     * No-op in portrait, where the peek height already resolves to the full
     * content height.
     */
    override fun onStart() {
        super.onStart()
        val sheet = dialog?.findViewById<View>(
            com.google.android.material.R.id.design_bottom_sheet
        ) ?: return
        BottomSheetBehavior.from(sheet).apply {
            skipCollapsed = true
            state = BottomSheetBehavior.STATE_EXPANDED
        }
    }

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
