package com.wedora.app

import android.animation.Animator
import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.animation.TimeInterpolator
import android.animation.ValueAnimator
import android.provider.Settings
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.view.animation.PathInterpolator

/**
 * Reusable animator building blocks for the splash and onboarding motion
 * spec (see SplashActivity / OnboardingActivity). Every cubic-bezier(...)
 * curve from the design spec maps 1:1 to a [PathInterpolator] with the same
 * 4 control points — the bounce/overshoot feel specifically comes from
 * those numbers, so callers should reach for [BOUNCE]/[SMOOTH_DECEL] rather
 * than a generic interpolator.
 *
 * [popIn]/[countPop] approximate the spec's multi-keyframe overshoot
 * (0%→45% overshoot→68% undershoot→85%→100%) with a single scale animation
 * under [BOUNCE] rather than literally replaying 4 keyframes — a cubic
 * spring curve already produces that same overshoot-then-settle shape on
 * its own, so the extra keyframes would be reproducing what the curve
 * already does.
 */
object Motion {

    /** cubic-bezier(.34,1.56,.64,1) — the bouncy "pop-in" overshoot curve. */
    val BOUNCE: TimeInterpolator = PathInterpolator(0.34f, 1.56f, 0.64f, 1f)

    /** cubic-bezier(.16,1,.3,1) — smooth decelerate, used for slide/rise/fade. */
    val SMOOTH_DECEL: TimeInterpolator = PathInterpolator(0.16f, 1f, 0.3f, 1f)

    /** cubic-bezier(.4,0,.2,1) — the splash progress bar's own curve. */
    val STANDARD: TimeInterpolator = PathInterpolator(0.4f, 0f, 0.2f, 1f)

    /**
     * Whether the system's "Remove animations" accessibility setting is on.
     * ObjectAnimator/ValueAnimator already respect this scale for duration,
     * but an infinite-repeat animator would otherwise spin forever re-firing
     * listeners at ~0 duration for no visible benefit — every entry point
     * below checks this and jumps straight to the end state instead.
     */
    fun reducedMotion(view: View): Boolean =
        Settings.Global.getFloat(view.context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f

    /** CSS steps(count, end): the value jumps in [count] discrete increments rather than easing continuously. */
    fun stepInterpolator(count: Int): TimeInterpolator = TimeInterpolator { input ->
        val stepCount = count.coerceAtLeast(1)
        val step = (input * stepCount).toInt().coerceAtMost(stepCount - 1)
        step.toFloat() / (stepCount - 1).coerceAtLeast(1)
    }

    private fun View.dp(value: Float) = value * resources.displayMetrics.density

    /** wd-pop-in: scale 0.2→1, fade in, bouncy overshoot. */
    fun popIn(view: View, durationMs: Long, delayMs: Long = 0) {
        if (reducedMotion(view)) {
            view.alpha = 1f; view.scaleX = 1f; view.scaleY = 1f
            return
        }
        view.alpha = 0f; view.scaleX = 0.2f; view.scaleY = 0.2f
        view.animate()
            .alpha(1f).scaleX(1f).scaleY(1f)
            .setDuration(durationMs).setStartDelay(delayMs)
            .setInterpolator(BOUNCE)
            .start()
    }

    /** wd-count: scale 0.4→1, fade in — the match-percent badge's pop. */
    fun countPop(view: View, durationMs: Long, delayMs: Long = 0) {
        if (reducedMotion(view)) {
            view.alpha = 1f; view.scaleX = 1f; view.scaleY = 1f
            return
        }
        view.alpha = 0f; view.scaleX = 0.4f; view.scaleY = 0.4f
        view.animate()
            .alpha(1f).scaleX(1f).scaleY(1f)
            .setDuration(durationMs).setStartDelay(delayMs)
            .setInterpolator(BOUNCE)
            .start()
    }

    /** wd-rise / wd-fade-up: fade in while translating up from [fromYDp]. */
    fun riseUp(
        view: View,
        durationMs: Long,
        delayMs: Long = 0,
        fromYDp: Float = 20f,
        interpolator: TimeInterpolator = SMOOTH_DECEL
    ) {
        if (reducedMotion(view)) {
            view.alpha = 1f; view.translationY = 0f
            return
        }
        view.alpha = 0f; view.translationY = view.dp(fromYDp)
        view.animate()
            .alpha(1f).translationY(0f)
            .setDuration(durationMs).setStartDelay(delayMs)
            .setInterpolator(interpolator)
            .start()
    }

    /** wd-slide-l / wd-slide-r: fade in while translating in from [fromXDp] (negative = from the left). */
    fun slideIn(view: View, fromXDp: Float, durationMs: Long, delayMs: Long = 0) {
        if (reducedMotion(view)) {
            view.alpha = 1f; view.translationX = 0f
            return
        }
        view.alpha = 0f; view.translationX = view.dp(fromXDp)
        view.animate()
            .alpha(1f).translationX(0f)
            .setDuration(durationMs).setStartDelay(delayMs)
            .setInterpolator(SMOOTH_DECEL)
            .start()
    }

    /**
     * wd-float: an infinite idle bob, meant to be started only after a
     * one-time entrance animation (pop-in/slide) has already finished.
     * Returns the running [Animator] so the caller can [Animator.cancel] it
     * when the hosting page leaves the screen (e.g. a ViewPager2 page
     * change) — an uncancelled infinite animator would otherwise keep
     * re-firing on a view that's no longer visible.
     */
    fun floatLoop(view: View, durationMs: Long, delayMs: Long = 0, amplitudeDp: Float = 7f): Animator? {
        if (reducedMotion(view)) return null
        return ObjectAnimator.ofFloat(view, View.TRANSLATION_Y, 0f, -view.dp(amplitudeDp)).apply {
            duration = durationMs / 2
            startDelay = delayMs
            interpolator = AccelerateDecelerateInterpolator()
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
            start()
        }
    }

    /**
     * wd-radar / wd-ring: an expanding-ripple pulse — scale out and fade,
     * then (if [infinite]) restart from scratch rather than reverse, since
     * a ripple is a one-directional expansion repeating from its start
     * state, not a back-and-forth like [floatLoop]. Returns the running
     * [Animator] so infinite ones can be cancelled the same way as
     * [floatLoop]'s.
     */
    fun pulse(
        view: View,
        fromScale: Float,
        toScale: Float,
        fromAlpha: Float,
        toAlpha: Float,
        durationMs: Long,
        delayMs: Long = 0,
        infinite: Boolean = false
    ): Animator? {
        if (reducedMotion(view)) {
            view.alpha = 0f
            return null
        }
        view.scaleX = fromScale; view.scaleY = fromScale; view.alpha = fromAlpha
        val pvhX = PropertyValuesHolder.ofFloat(View.SCALE_X, fromScale, toScale)
        val pvhY = PropertyValuesHolder.ofFloat(View.SCALE_Y, fromScale, toScale)
        val pvhA = PropertyValuesHolder.ofFloat(View.ALPHA, fromAlpha, toAlpha)
        return ObjectAnimator.ofPropertyValuesHolder(view, pvhX, pvhY, pvhA).apply {
            duration = durationMs
            startDelay = delayMs
            interpolator = DecelerateInterpolator()
            if (infinite) {
                repeatCount = ValueAnimator.INFINITE
                repeatMode = ValueAnimator.RESTART
            }
            start()
        }
    }

    /**
     * up-arrow-loop: a slow upward travel that fades in at the bottom and out
     * at the top, forever — the update nudge sheet's one sustained motion.
     *
     * A translate+fade loop rather than a bounce: the arrow is asking the user
     * to move something upward, and a bouncing arrow reads as decoration
     * instead of instruction. Returns the animator so the host can cancel it
     * on teardown; an uncancelled infinite animator outlives the sheet.
     */
    fun arrowLoop(view: View, durationMs: Long = 1900, delayMs: Long = 0): Animator? {
        if (reducedMotion(view)) return null
        val d = view.dp(1f)
        val pvhY = PropertyValuesHolder.ofFloat(View.TRANSLATION_Y, 9f * d, 0f, 0f, -11f * d)
        // Fades match the travel: invisible at both extremes, solid in the
        // middle 44% where the arrow is actually legible.
        val pvhA = PropertyValuesHolder.ofFloat(View.ALPHA, 0f, 1f, 1f, 0f)
        return ObjectAnimator.ofPropertyValuesHolder(view, pvhY, pvhA).apply {
            duration = durationMs
            startDelay = delayMs
            interpolator = STANDARD
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.RESTART
            start()
        }
    }

    /**
     * up-shake: one decaying horizontal shake, then done — the update failure
     * dialog's whole emotional cue. Deliberately single-shot and amplitude-
     * decaying (8 -> 7 -> 5 -> 4 -> 2 dp): a looping shake reads as an ongoing
     * error, and the spec is explicit that the failure never loops and never
     * turns red.
     *
     * Under reduced motion this does nothing and the caller is expected to
     * fire its haptic instead — see UpdateFailedDialog.
     */
    fun shake(view: View, durationMs: Long = 550) {
        if (reducedMotion(view)) return
        val d = view.dp(1f)
        val pvh = PropertyValuesHolder.ofFloat(
            View.TRANSLATION_X,
            0f, -8f * d, 7f * d, -5f * d, 4f * d, -2f * d, 0f
        )
        ObjectAnimator.ofPropertyValuesHolder(view, pvh).apply {
            duration = durationMs
            interpolator = PathInterpolator(0.36f, 0.07f, 0.19f, 0.97f)
            start()
        }
    }

    /**
     * up-shimmer: a highlight band sweeping across [view]'s width, looping.
     * Used only on the App Version row's pending state.
     *
     * [view] is the band itself (a gradient strip), swept from fully off the
     * left edge to fully off the right of [containerWidthPx]. Returns the
     * animator so the caller can stop it once the user has seen the row —
     * a settings row that shimmers forever is noise, not a cue.
     */
    fun shimmer(
        view: View,
        containerWidthPx: Int,
        durationMs: Long = 2400,
        delayMs: Long = 1000
    ): Animator? {
        if (reducedMotion(view)) return null
        val bandWidth = view.width.takeIf { it > 0 } ?: (containerWidthPx / 2)
        return ObjectAnimator.ofFloat(
            view, View.TRANSLATION_X,
            -bandWidth.toFloat(), containerWidthPx.toFloat()
        ).apply {
            duration = durationMs
            startDelay = delayMs
            interpolator = AccelerateDecelerateInterpolator()
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.RESTART
            start()
        }
    }

    /**
     * A view's width grows from 0 to [targetWidthPx] continuously under
     * [interpolator] — used for the splash progress bar. [view]'s
     * layoutParams.width is mutated directly each frame since plain
     * View properties have no animatable "width".
     */
    fun revealWidth(view: View, targetWidthPx: Int, durationMs: Long, delayMs: Long, interpolator: TimeInterpolator) {
        if (reducedMotion(view)) {
            view.layoutParams = view.layoutParams.apply { width = targetWidthPx }
            return
        }
        ValueAnimator.ofInt(0, targetWidthPx).apply {
            duration = durationMs
            startDelay = delayMs
            this.interpolator = interpolator
            addUpdateListener { view.layoutParams = view.layoutParams.apply { width = it.animatedValue as Int } }
            start()
        }
    }

    /**
     * wd-write: a typewriter reveal — [view]'s width grows from 0 to
     * [targetWidthPx] in [steps] discrete jumps (steps(N, end)), used by the
     * splash wordmark. [view] must be a clipping container (clipChildren) so
     * its content is actually hidden below the current width, not just
     * measured smaller.
     */
    fun stepRevealWidth(view: View, targetWidthPx: Int, durationMs: Long, delayMs: Long, steps: Int) =
        revealWidth(view, targetWidthPx, durationMs, delayMs, stepInterpolator(steps))
}
