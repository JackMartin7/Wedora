package com.wedora.app

import android.content.Context
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import kotlin.math.abs

/**
 * Minimal Tinder-style swipe card stack — one card on top, the next peeking
 * behind it. The top card drags, rotates, and shows LIKE / PASS overlays
 * proportional to the drag; past a horizontal threshold it flings off and the
 * peek slides up to take its place. Left/right can also be triggered
 * programmatically for the on-card action buttons.
 *
 * Written custom rather than pulling in a card-stack library: the usual one
 * (yuyakaido/card-stack-view) shipped via the now-defunct jCenter, and a
 * dependency that fails to resolve would break the whole build. This covers
 * exactly what this screen needs without that risk.
 *
 * Not a RecyclerView — it only ever holds two child views, so recycling buys
 * nothing. It's a plain container the host binds into via [Listener].
 *
 * NOTE: touch and fling behaviour is the kind of thing that wants tuning on a
 * real device; the constants below are reasonable starting points.
 */
class SwipeCardStackView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    interface Listener {
        /** Populate [cardView] with the item at [position]. */
        fun onBindCard(cardView: View, position: Int)
        fun onSwipedRight(position: Int)
        fun onSwipedLeft(position: Int)
        /** Fired once the last card has been swiped away. */
        fun onEmptied()
    }

    private companion object {
        const val MAX_ROTATION = 20f
        const val PEEK_SCALE = 0.95f
        const val PEEK_TRANSLATION_DP = 22f
        /** Fling threshold as a fraction of the view's width. */
        const val SWIPE_THRESHOLD = 0.32f
        const val FLING_OFFSCREEN_FACTOR = 1.6f
        const val ANIM_MS = 220L

        /**
         * Drag distance, as a fraction of the threshold, before the overlay
         * starts appearing. Without it the heart flickers on during the small
         * wobble that precedes a tap.
         */
        const val OVERLAY_DEAD_ZONE = 0.15f

        /** Overshoot on the promoted card, for a spring rather than a glide. */
        const val PROMOTE_TENSION = 1.6f
        const val PROMOTE_MS = 260L

        /** First-load cascade: how far below each card starts, and its stagger. */
        const val CASCADE_OFFSET_DP = 28f
        const val CASCADE_MS = 300L
        const val CASCADE_STAGGER_MS = 70L
    }

    private var itemLayoutRes = 0
    private var itemCount = 0
    private var topPosition = 0
    private var listener: Listener? = null

    private var topCard: View? = null
    private var peekCard: View? = null

    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private val peekTranslationPx = PEEK_TRANSLATION_DP * resources.displayMetrics.density

    private var downX = 0f
    private var downY = 0f
    private var dragging = false
    private var animating = false

    /** (Re)initialises the stack from position 0 with [count] items. */
    fun setup(itemLayoutRes: Int, count: Int, listener: Listener) {
        this.itemLayoutRes = itemLayoutRes
        this.listener = listener
        this.itemCount = count
        this.topPosition = 0
        dragging = false
        animating = false
        removeAllViews()
        topCard = null
        peekCard = null

        if (count == 0) {
            listener.onEmptied()
        } else {
            renderInitialStack()
        }
    }

    /** Re-runs the host's bind on the currently visible cards (e.g. after the
     *  liked-set changes), without disturbing an in-progress drag or fling. */
    fun rebindVisibleCards() {
        if (animating || dragging) return
        topCard?.let { listener?.onBindCard(it, topPosition) }
        peekCard?.let { listener?.onBindCard(it, topPosition + 1) }
    }

    fun swipeRight() {
        if (!animating && topCard != null) flingOff(right = true, notify = true)
    }

    fun swipeLeft() {
        if (!animating && topCard != null) flingOff(right = false, notify = true)
    }

    /**
     * Removes the top card without reporting a swipe — for a block, which isn't
     * a like or a pass. The card slides off left and the next takes its place.
     */
    fun dismissTop() {
        if (!animating && topCard != null) flingOff(right = false, notify = false)
    }

    // ----- Stack composition ------------------------------------------------

    private fun inflateCard(): View =
        LayoutInflater.from(context).inflate(itemLayoutRes, this, false)

    private fun renderInitialStack() {
        // Peek added first so it sits behind the top card.
        if (topPosition + 1 < itemCount) {
            peekCard = inflateCard().also { peek ->
                listener?.onBindCard(peek, topPosition + 1)
                peek.scaleX = PEEK_SCALE
                peek.scaleY = PEEK_SCALE
                peek.translationY = peekTranslationPx
                addView(peek)
            }
        }
        topCard = inflateCard().also { top ->
            listener?.onBindCard(top, topPosition)
            addView(top)
            resetCardTransforms(top)
        }

        cascadeIn()
    }

    /**
     * First-load flourish: the cards rise into place from slightly below,
     * staggered back to front, so the stack assembles rather than appearing.
     *
     * Only on a fresh [setup] — a promotion after a swipe has its own spring,
     * and running both would animate the same card twice.
     */
    private fun cascadeIn() {
        val offset = CASCADE_OFFSET_DP * resources.displayMetrics.density
        // Back to front, so the top card is the last to settle.
        listOfNotNull(peekCard, topCard).forEachIndexed { index, card ->
            val restingY = card.translationY
            card.translationY = restingY + offset
            card.alpha = 0f
            card.animate()
                .translationY(restingY)
                .alpha(1f)
                .setStartDelay(index * CASCADE_STAGGER_MS)
                .setDuration(CASCADE_MS)
                .setInterpolator(DecelerateInterpolator())
                .start()
        }
    }

    /** After the top card flings off: promote the peek, add a fresh peek behind. */
    private fun promoteAfterSwipe() {
        topCard = peekCard
        peekCard = null
        topCard?.let { promoted ->
            resetCardTransforms(promoted)
            // Springs up to full size rather than snapping. The peek already
            // grew part of the way during the drag, so this finishes the
            // movement it started instead of restating it.
            promoted.animate()
                .scaleX(1f).scaleY(1f).translationY(0f)
                .setInterpolator(OvershootInterpolator(PROMOTE_TENSION))
                .setDuration(PROMOTE_MS)
                .start()
        }

        val nextPeekPosition = topPosition + 1
        if (topCard != null && nextPeekPosition < itemCount) {
            peekCard = inflateCard().also { peek ->
                listener?.onBindCard(peek, nextPeekPosition)
                peek.scaleX = PEEK_SCALE
                peek.scaleY = PEEK_SCALE
                peek.translationY = peekTranslationPx
                addView(peek, 0) // behind the promoted top card
            }
        }

        if (topCard == null) listener?.onEmptied()
    }

    private fun resetCardTransforms(card: View) {
        card.translationX = 0f
        card.rotation = 0f
        card.findViewById<View?>(R.id.overlayLike)?.alpha = 0f
        card.findViewById<View?>(R.id.overlayPass)?.alpha = 0f
    }

    // ----- Touch ------------------------------------------------------------

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        if (animating || topCard == null) return false
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = ev.x
                downY = ev.y
                dragging = false
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = ev.x - downX
                val dy = ev.y - downY
                // Claim the gesture only once it's clearly a horizontal drag,
                // so taps still reach the card's buttons and body.
                if (!dragging && abs(dx) > touchSlop && abs(dx) > abs(dy)) {
                    dragging = true
                    return true
                }
            }
        }
        return false
    }

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        if (animating) return false
        val top = topCard ?: return false

        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = ev.x
                downY = ev.y
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = ev.x - downX
                if (!dragging && abs(dx) > touchSlop && abs(dx) > abs(ev.y - downY)) {
                    dragging = true
                }
                if (dragging) updateDrag(top, dx)
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (dragging) {
                    dragging = false
                    settleDrag(top)
                }
                return true
            }
        }
        return false
    }

    private fun updateDrag(top: View, dx: Float) {
        top.translationX = dx
        top.rotation = (dx / width) * MAX_ROTATION

        val ratio = (dx / (width * SWIPE_THRESHOLD)).coerceIn(-1f, 1f)
        top.findViewById<View?>(R.id.overlayLike)?.alpha = overlayAlpha(ratio)
        top.findViewById<View?>(R.id.overlayPass)?.alpha = overlayAlpha(-ratio)

        // The peek grows toward full size as the top card is dragged away.
        peekCard?.let { peek ->
            val progress = abs(ratio)
            peek.scaleX = PEEK_SCALE + (1f - PEEK_SCALE) * progress
            peek.scaleY = PEEK_SCALE + (1f - PEEK_SCALE) * progress
            peek.translationY = peekTranslationPx * (1f - progress)
        }
    }

    /**
     * Opacity for a directional overlay at drag [ratio] (1 = at the threshold).
     *
     * Held at zero through a short dead zone, then eased so the icon arrives
     * gently and is fully opaque exactly when the card would commit. A linear
     * ramp made it appear the instant a finger moved and reach full strength
     * well before the swipe was decided, which read as the card promising
     * something it hadn't done yet.
     */
    private fun overlayAlpha(ratio: Float): Float {
        val magnitude = ratio.coerceAtLeast(0f)
        if (magnitude <= OVERLAY_DEAD_ZONE) return 0f
        val progress = (magnitude - OVERLAY_DEAD_ZONE) / (1f - OVERLAY_DEAD_ZONE)
        return progress * progress
    }

    private fun settleDrag(top: View) {
        val dx = top.translationX
        when {
            dx > width * SWIPE_THRESHOLD -> flingOff(right = true, notify = true)
            dx < -width * SWIPE_THRESHOLD -> flingOff(right = false, notify = true)
            else -> springBack(top)
        }
    }

    private fun springBack(top: View) {
        animating = true
        top.animate()
            .translationX(0f).rotation(0f)
            .setDuration(ANIM_MS)
            .withEndAction { animating = false }
            .start()
        top.findViewById<View?>(R.id.overlayLike)?.animate()?.alpha(0f)?.setDuration(ANIM_MS)?.start()
        top.findViewById<View?>(R.id.overlayPass)?.animate()?.alpha(0f)?.setDuration(ANIM_MS)?.start()
        peekCard?.animate()
            ?.scaleX(PEEK_SCALE)?.scaleY(PEEK_SCALE)?.translationY(peekTranslationPx)
            ?.setDuration(ANIM_MS)?.start()
    }

    private fun flingOff(right: Boolean, notify: Boolean) {
        val top = topCard ?: return
        animating = true
        val swipedPosition = topPosition
        val targetX = if (right) width * FLING_OFFSCREEN_FACTOR else -width * FLING_OFFSCREEN_FACTOR

        // The peek is deliberately left where it is. Growing it here would put
        // it at full size before promoteAfterSwipe runs, and that spring would
        // then animate 1f to 1f — invisible. Letting the promotion do the whole
        // remaining growth is what makes the next card actually spring up,
        // whether the drag left it partway or a button swipe left it untouched.

        top.animate()
            .translationX(targetX)
            .rotation(if (right) MAX_ROTATION else -MAX_ROTATION)
            .setDuration(ANIM_MS)
            .withEndAction {
                animating = false
                removeView(top)
                topPosition++
                if (notify) {
                    if (right) listener?.onSwipedRight(swipedPosition)
                    else listener?.onSwipedLeft(swipedPosition)
                }
                promoteAfterSwipe()
            }
            .start()
    }
}
