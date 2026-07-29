package com.wedora.app

import android.animation.Animator
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.wedora.app.databinding.ItemOnboardingPageBinding

class OnboardingAdapter(
    private val pages: List<OnboardingPage>
) : RecyclerView.Adapter<OnboardingAdapter.PageViewHolder>() {

    private companion object {
        /** Breathing room so a scaled canvas never sits flush against the screen edge. */
        const val EDGE_MARGIN_DP = 8
    }

    /**
     * Holders by adapter position, so [playEntranceIfNeeded] can reach a page's
     * views from the Activity's page-change callback. Safe to hold onto only
     * because OnboardingActivity sets offscreenPageLimit to the full page
     * count — with the default limit these holders would be recycled and
     * these references would go stale.
     */
    private val holders = mutableMapOf<Int, PageViewHolder>()

    /**
     * Positions whose one-time entrance has already played. Entrance
     * animations are deliberately NOT started from [onBindViewHolder]:
     * ViewPager2 pre-binds adjacent pages, so binding-time animation would
     * play pages 2 and 3 while they're still offscreen and leave the user
     * swiping onto a fully-settled screen with no choreography at all.
     */
    private val playedPositions = mutableSetOf<Int>()

    inner class PageViewHolder(
        val binding: ItemOnboardingPageBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        /**
         * Infinite loops (float/radar) started by this page's entrance —
         * tracked so they can be cancelled if this holder is ever recycled.
         * One-time pop-in/slide/rise animators aren't tracked; they finish on
         * their own and never need cancelling.
         */
        private val runningAnimators = mutableListOf<Animator>()

        fun bind(page: OnboardingPage) {
            val container = binding.illustrationContainer

            // Views are recycled, so clear any collage from a previous slide first.
            container.removeAllViews()
            LayoutInflater.from(container.context)
                .inflate(page.illustrationLayoutRes, container, true)

            applyCanvasScale(page)

            binding.tvPageTitle.setText(page.titleRes)
            binding.tvPageDescription.setText(page.descriptionRes)

            cancelRunningAnimators()
            prepareHidden()
        }

        /**
         * Everything that animates in starts invisible, set at bind time
         * rather than at play time so a page can never flash fully-formed
         * for a frame before its entrance begins.
         */
        private fun prepareHidden() {
            val collage = binding.illustrationContainer.getChildAt(0) as? ViewGroup ?: return
            for (i in 0 until collage.childCount) collage.getChildAt(i).alpha = 0f
            binding.tvPageTitle.alpha = 0f
            binding.tvPageDescription.alpha = 0f
        }

        fun playEntrance(position: Int) {
            val collage = binding.illustrationContainer.getChildAt(0) as? ViewGroup
            if (collage != null) {
                runningAnimators += when (position) {
                    0 -> animateRadarSlide(collage)
                    2 -> animateMatchSlide(collage)
                    else -> animateChipSlide(collage)
                }
            }
            Motion.riseUp(binding.tvPageTitle, durationMs = 600, delayMs = 1050)
            Motion.riseUp(binding.tvPageDescription, durationMs = 600, delayMs = 1150)
        }

        fun cancelRunningAnimators() {
            runningAnimators.forEach { it.cancel() }
            runningAnimators.clear()
        }

        /**
         * The collages are laid out on fixed-size canvases (280dp for slide 1,
         * 348dp for slides 2-3). On screens narrower than the canvas the edges
         * would be cut off, so shrink the whole thing proportionally instead.
         *
         * Scaling is visual only — it does not affect layout — and the canvas is
         * centred, so the default centre pivot keeps it in place.
         */
        private fun applyCanvasScale(page: OnboardingPage) {
            val canvas = binding.illustrationContainer.getChildAt(0) ?: return
            val metrics = canvas.resources.displayMetrics
            val screenDp = metrics.widthPixels / metrics.density
            val availableDp = screenDp - EDGE_MARGIN_DP

            val scale = (availableDp / page.canvasWidthDp).coerceAtMost(1f)
            canvas.scaleX = scale
            canvas.scaleY = scale
        }
    }

    /**
     * Plays [position]'s entrance the first time that page becomes even
     * partially visible, and never again — called from OnboardingActivity's
     * scroll callback rather than from bind (see [playedPositions]).
     */
    fun playEntranceIfNeeded(position: Int) {
        if (position !in pages.indices || !playedPositions.add(position)) return
        holders[position]?.playEntrance(position)
    }

    /**
     * Onboarding screen 1 — concentric radar rings + scattered avatars.
     * Timings come directly from the motion spec's table. The spec lists 8
     * "avatar" slots; this collage has 6 real avatars (centre first, then the
     * other 5 in spec order) plus the 2 decorative dots, which round out the
     * set and take the same pop-in-then-float treatment the spec gives
     * avatars 4-8.
     */
    private fun animateRadarSlide(collage: ViewGroup): List<Animator> {
        val running = mutableListOf<Animator>()
        collage.findViewById<View>(R.id.ringOuter)?.let {
            Motion.pulse(it, 0.75f, 1.25f, 0.7f, 0f, durationMs = 2600, delayMs = 300, infinite = true)?.let(running::add)
        }
        collage.findViewById<View>(R.id.ringInner)?.let {
            Motion.pulse(it, 0.75f, 1.25f, 0.7f, 0f, durationMs = 2600, delayMs = 1600, infinite = true)?.let(running::add)
        }

        collage.findViewById<View>(R.id.avatarCenter)?.let { Motion.popIn(it, 500, 60) }
        collage.findViewById<View>(R.id.avatarTL)?.let { Motion.popIn(it, 500, 140) }
        collage.findViewById<View>(R.id.avatarTR)?.let { Motion.popIn(it, 550, 220) }

        collage.findViewById<View>(R.id.avatarRM)?.let {
            Motion.popIn(it, 500, 420)
            Motion.floatLoop(it, 3600, 1000)?.let(running::add)
        }
        collage.findViewById<View>(R.id.avatarBL)?.let {
            Motion.popIn(it, 500, 540)
            Motion.floatLoop(it, 4200, 1300)?.let(running::add)
        }
        collage.findViewById<View>(R.id.avatarBC)?.let {
            Motion.popIn(it, 500, 660)
            Motion.floatLoop(it, 3900, 600)?.let(running::add)
        }
        collage.findViewById<View>(R.id.dot1)?.let {
            Motion.popIn(it, 500, 780)
            Motion.floatLoop(it, 4400, 1600)?.let(running::add)
        }
        collage.findViewById<View>(R.id.dot2)?.let {
            Motion.popIn(it, 500, 900)
            Motion.floatLoop(it, 3400, 900)?.let(running::add)
        }
        collage.findViewById<View>(R.id.heartBadge)?.let { Motion.popIn(it, 500, 1000) }

        return running
    }

    /**
     * Onboarding screen 2 — interest-tag/photo collage. The spec's table
     * enumerates 7 generically-named "chip" elements mixing pop-in, slide-in
     * and float; this collage actually has 9 children (chips + photos + small
     * icons), so rather than force an inexact 7-to-9 mapping, every child
     * takes the same alternating pop/slide-in pattern the spec's chips use,
     * staggered in the collage's own back-to-front layout order, with an
     * occasional float layered on top (the spec's rough "every third chip
     * floats" rhythm).
     */
    private fun animateChipSlide(collage: ViewGroup): List<Animator> {
        val running = mutableListOf<Animator>()
        val baseDelayMs = 80L
        val stepMs = 110L
        for (i in 0 until collage.childCount) {
            val child = collage.getChildAt(i)
            val delayMs = baseDelayMs + i * stepMs
            when (i % 3) {
                0 -> Motion.popIn(child, 500, delayMs)
                1 -> Motion.slideIn(child, fromXDp = 26f, durationMs = 500, delayMs = delayMs)
                else -> Motion.slideIn(child, fromXDp = -26f, durationMs = 500, delayMs = delayMs)
            }
            if (i % 4 == 0) {
                Motion.floatLoop(child, (4000 + (i % 3) * 200).toLong(), delayMs + 500)?.let(running::add)
            }
        }
        return running
    }

    /**
     * Onboarding screen 3 — two profile photos + match-percent badge +
     * sparkles. The 8 sparkles are identical views at fixed indices (3..10,
     * right after photoLeft/photoRight/matchBadge in this collage's layout
     * order); their stagger uses the spec's own formula: delay =
     * 1.6 + i*0.15s, float duration = 3.2 + (i%4)*0.4s.
     */
    private fun animateMatchSlide(collage: ViewGroup): List<Animator> {
        val running = mutableListOf<Animator>()
        collage.findViewById<View>(R.id.photoLeft)?.let { Motion.slideIn(it, fromXDp = -26f, durationMs = 600, delayMs = 100) }
        collage.findViewById<View>(R.id.photoRight)?.let { Motion.slideIn(it, fromXDp = 26f, durationMs = 600, delayMs = 100) }
        collage.findViewById<View>(R.id.matchBadge)?.let { Motion.countPop(it, 550, 700) }

        for (i in 0 until 8) {
            val sparkle = collage.getChildAt(3 + i) ?: continue
            val delayMs = (1600 + i * 150).toLong()
            Motion.popIn(sparkle, 500, delayMs)
            Motion.floatLoop(sparkle, (3200 + (i % 4) * 400).toLong(), delayMs + 500)?.let(running::add)
        }
        return running
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PageViewHolder {
        val binding = ItemOnboardingPageBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return PageViewHolder(binding)
    }

    override fun onBindViewHolder(holder: PageViewHolder, position: Int) {
        holder.bind(pages[position])
        holders[position] = holder
        // Covers the (not expected, given offscreenPageLimit) case of a page
        // being rebound after its entrance already played: replay rather than
        // leave it stuck at prepareHidden()'s alpha 0.
        if (position in playedPositions) holder.playEntrance(position)
    }

    override fun onViewRecycled(holder: PageViewHolder) {
        holder.cancelRunningAnimators()
        holders.entries.removeAll { it.value === holder }
        super.onViewRecycled(holder)
    }

    override fun getItemCount(): Int = pages.size
}
