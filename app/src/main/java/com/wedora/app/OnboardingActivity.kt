package com.wedora.app

import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.widget.ImageView
import android.widget.LinearLayout
import androidx.core.content.ContextCompat
import androidx.viewpager2.widget.ViewPager2
import com.wedora.app.databinding.ActivityOnboardingBinding

class OnboardingActivity : WedoraBaseActivity() {

    private lateinit var binding: ActivityOnboardingBinding

    private val pages = listOf(
        OnboardingPage(
            R.layout.view_onboarding_illustration_1,
            R.string.onboarding_title_1,
            R.string.onboarding_desc_1,
            canvasWidthDp = 280
        ),
        OnboardingPage(
            R.layout.view_onboarding_illustration_2,
            R.string.onboarding_title_2,
            R.string.onboarding_desc_2,
            canvasWidthDp = 348
        ),
        OnboardingPage(
            R.layout.view_onboarding_illustration_3,
            R.string.onboarding_title_3,
            R.string.onboarding_desc_3,
            canvasWidthDp = 348
        )
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityOnboardingBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applyEdgeInsets(binding.root)

        val adapter = OnboardingAdapter(pages)
        binding.viewPager.adapter = adapter
        // Keeps all three pages alive rather than recycling them, which is
        // what lets OnboardingAdapter hold onto its holders to drive
        // per-page entrance animations. Trivial cost for 3 static pages.
        binding.viewPager.offscreenPageLimit = pages.size

        buildIndicators()
        updateIndicators(0)

        // Pagination dots / Next button rise in once, on first entry — the
        // motion spec only lists these in screen 1's own animation table,
        // matching how they're persistent chrome here (outside the pager),
        // not per-page content that should re-animate on every swipe.
        Motion.riseUp(binding.indicatorContainer, durationMs = 500, delayMs = 1200)
        Motion.riseUp(binding.btnNext, durationMs = 550, delayMs = 1300)

        binding.viewPager.registerOnPageChangeCallback(
            object : ViewPager2.OnPageChangeCallback() {
                /**
                 * Entrance animations are driven from here, not from
                 * onPageSelected: that only fires once a swipe settles, so a
                 * page would sit visibly blank for the whole drag before
                 * animating. Firing as soon as a page is even partially
                 * scrolled into view means its choreography starts with the
                 * swipe. [OnboardingAdapter.playEntranceIfNeeded] is
                 * idempotent, so being called on every scroll frame is fine.
                 */
                override fun onPageScrolled(
                    position: Int,
                    positionOffset: Float,
                    positionOffsetPixels: Int
                ) {
                    adapter.playEntranceIfNeeded(position)
                    if (positionOffset > 0f) adapter.playEntranceIfNeeded(position + 1)
                }

                override fun onPageSelected(position: Int) {
                    updateIndicators(position)
                    binding.btnNext.setText(
                        if (position == pages.lastIndex) R.string.onboarding_get_started
                        else R.string.onboarding_next
                    )
                }
            }
        )

        // Page 0's own entrance: onPageScrolled normally fires after the
        // first layout pass, but posting it too is cheap insurance against
        // the first page ever sitting at prepareHidden()'s alpha 0.
        binding.viewPager.post { adapter.playEntranceIfNeeded(0) }

        binding.tvSkip.setOnClickListener { finishOnboarding() }

        binding.btnNext.setOnClickListener {
            val current = binding.viewPager.currentItem
            if (current == pages.lastIndex) {
                finishOnboarding()
            } else {
                binding.viewPager.currentItem = current + 1
            }
        }
        binding.btnNext.addPressScale()
    }

    private fun buildIndicators() {
        val size = resources.getDimensionPixelSize(R.dimen.indicator_dot_size)
        val spacing = resources.getDimensionPixelSize(R.dimen.indicator_dot_spacing)
        binding.indicatorContainer.removeAllViews()
        repeat(pages.size) { index ->
            val dot = ImageView(this)
            val params = LinearLayout.LayoutParams(size, size).apply {
                gravity = Gravity.CENTER_VERTICAL
                if (index > 0) marginStart = spacing
            }
            dot.layoutParams = params
            binding.indicatorContainer.addView(dot)
        }
    }

    private fun updateIndicators(activePosition: Int) {
        val activeWidth = resources.getDimensionPixelSize(R.dimen.indicator_dot_active_width)
        val size = resources.getDimensionPixelSize(R.dimen.indicator_dot_size)
        for (i in 0 until binding.indicatorContainer.childCount) {
            val dot = binding.indicatorContainer.getChildAt(i) as ImageView
            val isActive = i == activePosition
            dot.setImageDrawable(
                ContextCompat.getDrawable(
                    this,
                    if (isActive) R.drawable.indicator_dot_active
                    else R.drawable.indicator_dot_inactive
                )
            )
            dot.layoutParams = (dot.layoutParams as LinearLayout.LayoutParams).apply {
                width = if (isActive) activeWidth else size
            }
        }
    }

    private fun finishOnboarding() {
        OnboardingPrefs.setOnboardingComplete(this)
        // A first-time user has no account yet, so Sign Up is the more
        // logical landing than Login — "Already have an account? Login"
        // on that screen still reaches LoginActivity for anyone who does.
        startActivity(Intent(this, SignUpActivity::class.java))
        finish()
        applyHandoffTransition()
    }
}
