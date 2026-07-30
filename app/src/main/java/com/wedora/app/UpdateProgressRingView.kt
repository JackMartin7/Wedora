package com.wedora.app

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import android.view.accessibility.AccessibilityEvent
import androidx.core.content.ContextCompat

/**
 * The download-progress ring: a pale track with a coral sweep, drawn from
 * 12 o'clock clockwise.
 *
 * A custom view rather than a styled ProgressBar because the design needs a
 * 9dp round-capped stroke on a 92dp-diameter circle with a specific track
 * colour, and reskinning ProgressBar's drawable to that is more code than
 * drawing two arcs.
 *
 * Deliberately has no animation of its own beyond interpolating between two
 * real progress values: the sweep must represent bytes actually downloaded, so
 * this never advances on a timer and never eases past the last reported value.
 */
class UpdateProgressRingView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = STROKE_DP * resources.displayMetrics.density
        color = ContextCompat.getColor(context, R.color.wedora_input_bg)
    }

    private val progressPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = STROKE_DP * resources.displayMetrics.density
        strokeCap = Paint.Cap.ROUND
        color = ContextCompat.getColor(context, R.color.wedora_accent)
    }

    private val arcBounds = RectF()
    private var sweepFraction = 0f
    private var animator: ValueAnimator? = null

    /**
     * Last value announced to accessibility services. Announcing every frame
     * would make a screen reader unusable, so this only speaks at 10% steps —
     * which is also the granularity the spec asks for.
     */
    private var lastAnnouncedStep = -1

    /**
     * Moves the sweep to [fraction] (0..1). [animate] interpolates from the
     * current position over [PROGRESS_STEP_MS] so consecutive byte events read
     * as continuous travel rather than a jump; pass false under reduced motion
     * so the ring steps instead.
     */
    fun setProgress(fraction: Float, animate: Boolean = true) {
        val target = fraction.coerceIn(0f, 1f)
        animator?.cancel()

        if (!animate || Motion.reducedMotion(this)) {
            // Reduced motion: quantise to 10% so the ring still visibly
            // changes without a continuous sweep.
            sweepFraction = (Math.round(target * 10f) / 10f)
            invalidate()
            announceIfStepped(target)
            return
        }

        animator = ValueAnimator.ofFloat(sweepFraction, target).apply {
            duration = PROGRESS_STEP_MS
            interpolator = Motion.STANDARD
            addUpdateListener {
                sweepFraction = it.animatedValue as Float
                invalidate()
            }
            start()
        }
        announceIfStepped(target)
    }

    private fun announceIfStepped(fraction: Float) {
        val step = (fraction * 10).toInt()
        if (step == lastAnnouncedStep) return
        lastAnnouncedStep = step
        val percent = step * 10
        contentDescription = context.getString(R.string.update_downloading_percent, percent)
        sendAccessibilityEvent(AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val inset = trackPaint.strokeWidth / 2f
        arcBounds.set(inset, inset, width - inset, height - inset)

        canvas.drawArc(arcBounds, 0f, 360f, false, trackPaint)
        if (sweepFraction > 0f) {
            // -90 starts the sweep at 12 o'clock rather than 3 o'clock.
            canvas.drawArc(arcBounds, START_ANGLE, 360f * sweepFraction, false, progressPaint)
        }
    }

    override fun onDetachedFromWindow() {
        animator?.cancel()
        animator = null
        super.onDetachedFromWindow()
    }

    private companion object {
        const val STROKE_DP = 9f
        const val START_ANGLE = -90f

        /** Matches the app's 300 ms "standard" motion token. */
        const val PROGRESS_STEP_MS = 300L
    }
}
