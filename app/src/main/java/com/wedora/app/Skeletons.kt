package com.wedora.app

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.AccelerateDecelerateInterpolator
import androidx.annotation.LayoutRes

/**
 * Skeleton placeholders, shown while a screen's first read is in flight.
 *
 * A skeleton beats a spinner here because every one of these screens has a
 * known shape: the user sees where the cards, rows or tiles will be and the
 * layout doesn't jump when they arrive.
 *
 * The shimmer runs on the skeleton *container* rather than each placeholder —
 * one animator per screen instead of a dozen, and pulsing them independently
 * would draw attention to the placeholders rather than away from them.
 */
private const val SHIMMER_MS = 700L
private const val SHIMMER_MIN_ALPHA = 0.4f
private const val CROSSFADE_MS = 220L

/** Fills a container with [count] copies of [item] and starts the shimmer. */
fun ViewGroup.showSkeleton(@LayoutRes item: Int, count: Int) {
    removeAllViews()
    val inflater = LayoutInflater.from(context)
    repeat(count) { inflater.inflate(item, this, true) }

    visibility = View.VISIBLE
    alpha = 1f
    startShimmer()
}

/**
 * Fades the skeleton out and the real content in.
 *
 * The shimmer is cancelled first: it animates the same alpha the crossfade
 * needs, and leaving it running would have the two fight over the property.
 * Children are cleared once hidden so the placeholders aren't left measuring
 * and drawing behind the content.
 */
fun ViewGroup.crossfadeToContent(content: View, clearChildren: Boolean = true) {
    stopShimmer()

    content.alpha = 0f
    content.visibility = View.VISIBLE
    content.animate().alpha(1f).setDuration(CROSSFADE_MS).start()

    animate()
        .alpha(0f)
        .setDuration(CROSSFADE_MS)
        .withEndAction {
            visibility = View.GONE
            // Skeletons built by showSkeleton own their children and clear
            // them; one declared statically in XML (the profile stats card)
            // has to keep its own, or a second load would fade in an empty
            // placeholder.
            if (clearChildren) removeAllViews()
            alpha = 1f
        }
        .start()
}

/** Hides the skeleton with no content to reveal — an empty or failed load. */
fun ViewGroup.hideSkeleton() {
    stopShimmer()
    visibility = View.GONE
    removeAllViews()
    alpha = 1f
}

fun View.startShimmer() {
    stopShimmer()
    val animator = ObjectAnimator.ofFloat(this, View.ALPHA, 1f, SHIMMER_MIN_ALPHA).apply {
        duration = SHIMMER_MS
        repeatMode = ValueAnimator.REVERSE
        repeatCount = ValueAnimator.INFINITE
        interpolator = AccelerateDecelerateInterpolator()
    }
    setTag(R.id.tag_shimmer, animator)
    animator.start()
}

/**
 * Cancels the shimmer and restores full opacity. Safe to call on a view that
 * was never shimmering, which is what lets callers stop unconditionally.
 */
fun View.stopShimmer() {
    (getTag(R.id.tag_shimmer) as? ObjectAnimator)?.cancel()
    setTag(R.id.tag_shimmer, null)
    alpha = 1f
}
