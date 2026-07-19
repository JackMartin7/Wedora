package com.wedora.app

import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.widget.ImageView
import android.widget.LinearLayout
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.viewpager2.widget.ViewPager2
import com.wedora.app.databinding.ActivityOnboardingBinding

class OnboardingActivity : AppCompatActivity() {

    private lateinit var binding: ActivityOnboardingBinding

    private val pages = listOf(
        OnboardingPage(
            R.drawable.ic_onboarding_venue,
            R.string.onboarding_title_1,
            R.string.onboarding_desc_1
        ),
        OnboardingPage(
            R.drawable.ic_onboarding_plan,
            R.string.onboarding_title_2,
            R.string.onboarding_desc_2
        ),
        OnboardingPage(
            R.drawable.ic_onboarding_celebrate,
            R.string.onboarding_title_3,
            R.string.onboarding_desc_3
        )
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityOnboardingBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.viewPager.adapter = OnboardingAdapter(pages)

        buildIndicators()
        updateIndicators(0)

        binding.viewPager.registerOnPageChangeCallback(
            object : ViewPager2.OnPageChangeCallback() {
                override fun onPageSelected(position: Int) {
                    updateIndicators(position)
                    binding.btnNext.setText(
                        if (position == pages.lastIndex) R.string.onboarding_get_started
                        else R.string.onboarding_next
                    )
                }
            }
        )

        binding.tvSkip.setOnClickListener { finishOnboarding() }

        binding.btnNext.setOnClickListener {
            val current = binding.viewPager.currentItem
            if (current == pages.lastIndex) {
                finishOnboarding()
            } else {
                binding.viewPager.currentItem = current + 1
            }
        }
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
        startActivity(Intent(this, LoginActivity::class.java))
        finish()
    }
}
