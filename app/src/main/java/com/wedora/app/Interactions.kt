package com.wedora.app

import android.annotation.SuppressLint
import android.view.MotionEvent
import android.view.View

/**
 * Press feedback: the view dips slightly while held and springs back on
 * release.
 *
 * Driven by ViewPropertyAnimator rather than an `<scale>` animation resource.
 * A view Animation only transforms while it plays and reverts afterwards —
 * fillAfter keeps the pixels but not the view's actual scale, so a second tap
 * arriving mid-animation starts from stale values and the button visibly
 * jumps. ViewPropertyAnimator mutates the real property and each new call
 * retargets the one in flight, which is what makes rapid tapping look right.
 *
 * The listener never consumes the event: it returns false so the view's own
 * onTouchEvent still runs, keeping click listeners, ripples and accessibility
 * behaviour exactly as they were. That's also why suppressing the
 * ClickableViewAccessibility warning is safe here — no click handling has been
 * moved into onTouch.
 */
@SuppressLint("ClickableViewAccessibility")
fun View.addPressScale(pressedScale: Float = 0.95f) {
    setOnTouchListener { view, event ->
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> view.scaleTo(pressedScale, PRESS_DOWN_MS)
            MotionEvent.ACTION_UP -> view.scaleTo(1f, PRESS_UP_MS)

            // CANCEL means a parent claimed the gesture — on a feed card that's
            // the swipe stack taking over. Reset without an animator: the stack
            // drives the same view through animate() to fling it away, and two
            // ViewPropertyAnimators on one view fight over it. Snapping back is
            // invisible anyway, since the card is already moving.
            MotionEvent.ACTION_CANCEL -> {
                view.animate().cancel()
                view.scaleX = 1f
                view.scaleY = 1f
            }
        }
        false
    }
}

private const val PRESS_DOWN_MS = 90L
private const val PRESS_UP_MS = 130L

private fun View.scaleTo(scale: Float, duration: Long) {
    animate().scaleX(scale).scaleY(scale).setDuration(duration).start()
}
