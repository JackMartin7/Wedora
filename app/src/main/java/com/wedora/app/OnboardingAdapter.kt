package com.wedora.app

import android.view.LayoutInflater
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

    inner class PageViewHolder(
        private val binding: ItemOnboardingPageBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(page: OnboardingPage) {
            val container = binding.illustrationContainer

            // Views are recycled, so clear any collage from a previous slide first.
            container.removeAllViews()
            LayoutInflater.from(container.context)
                .inflate(page.illustrationLayoutRes, container, true)

            applyCanvasScale(page)

            binding.tvPageTitle.setText(page.titleRes)
            binding.tvPageDescription.setText(page.descriptionRes)
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

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PageViewHolder {
        val binding = ItemOnboardingPageBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return PageViewHolder(binding)
    }

    override fun onBindViewHolder(holder: PageViewHolder, position: Int) {
        holder.bind(pages[position])
    }

    override fun getItemCount(): Int = pages.size
}
