package com.wedora.app

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.TextView
import androidx.annotation.ColorRes
import androidx.core.content.ContextCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.wedora.app.databinding.ActivityPaymentSubscriptionBinding
import com.wedora.app.databinding.ItemPremiumFeatureBinding

/**
 * Premium plans and the (not yet built) subscribe flow.
 *
 * Nothing here transacts. There is no Play Billing integration, no product
 * IDs and no entitlement stored anywhere, so both Subscribe and Restore land
 * on a "Coming Soon" dialog rather than pretending to charge or unlock
 * something. The plan selection is real UI state but is not persisted — it
 * would only be read by a purchase flow that doesn't exist yet.
 *
 * The prices are the design's placeholder copy. Real prices have to come from
 * the Play Console at runtime (they're per-country and can change), so treat
 * these as layout, not as a source of truth.
 */
class PaymentSubscriptionActivity : WedoraBaseActivity() {

    private lateinit var binding: ActivityPaymentSubscriptionBinding

    /** Yearly is preselected, matching the design's default emphasis. */
    private var yearlySelected = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPaymentSubscriptionBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnBack.setOnClickListener { finish() }

        binding.cardMonthly.setOnClickListener { selectPlan(yearly = false) }
        binding.cardYearly.setOnClickListener { selectPlan(yearly = true) }

        binding.btnSubscribe.setOnClickListener { showComingSoon() }
        binding.btnSubscribe.addPressScale()
        binding.tvRestorePurchase.setOnClickListener { showComingSoon() }

        buildFeatureList()
        applyPlanSelection()
    }

    /**
     * Re-applied on every resume, not just onCreate, so flipping isPremium
     * via Firebase Console while this screen is already open (or returning
     * to it after that happens elsewhere) reflects immediately — matching
     * ProfileActivity's own "re-check on resume" convention.
     */
    override fun onResume() {
        super.onResume()
        applyPremiumState()
    }

    /**
     * Reads the session-wide cache rather than its own Firestore fetch — a
     * stale read here has no real consequence (worst case: the upgrade UI
     * lingers a moment before the next resume corrects it), unlike the
     * quota-enforcing paths that keep their own authoritative reads.
     */
    private fun applyPremiumState() {
        val isPremium = PremiumStatus.isPremium()
        binding.premiumMemberSection.visibility = if (isPremium) View.VISIBLE else View.GONE
        binding.upgradeSection.visibility = if (isPremium) View.GONE else View.VISIBLE
    }

    private fun buildFeatureList() {
        val inflater = LayoutInflater.from(this)
        resources.getStringArray(R.array.premium_features).forEach { feature ->
            val row = ItemPremiumFeatureBinding.inflate(
                inflater, binding.featureContainer, true
            )
            row.tvFeature.text = feature
        }
    }

    // ----- Plan selection -------------------------------------------------

    private fun selectPlan(yearly: Boolean) {
        if (yearly == yearlySelected) return
        yearlySelected = yearly
        applyPlanSelection()
    }

    /**
     * Both cards are styled on every change rather than only the one tapped —
     * selection is mutually exclusive, so the card being deselected needs
     * resetting just as much as the one being selected.
     */
    private fun applyPlanSelection() {
        styleCard(
            selected = !yearlySelected,
            background = binding.cardMonthly,
            title = binding.tvMonthlyTitle,
            price = binding.tvMonthlyPrice
        )
        styleCard(
            selected = yearlySelected,
            background = binding.cardYearly,
            title = binding.tvYearlyTitle,
            price = binding.tvYearlyPrice
        )

        // The Best Value badge sits on the yearly card, so it has to invert
        // with it: white-on-accent while that card is filled, accent-on-white
        // once it isn't. A single fixed treatment would vanish into one state
        // or the other.
        binding.tvBestValue.setBackgroundResource(
            if (yearlySelected) R.drawable.bg_badge_on_accent
            else R.drawable.bg_badge_on_surface
        )
        binding.tvBestValue.setTextColor(
            color(if (yearlySelected) R.color.wedora_accent else R.color.white)
        )
    }

    private fun styleCard(
        selected: Boolean,
        background: View,
        title: TextView,
        price: TextView
    ) {
        background.setBackgroundResource(
            if (selected) R.drawable.bg_plan_selected else R.drawable.bg_plan_unselected
        )
        title.setTextColor(color(if (selected) R.color.white else R.color.wedora_text))
        price.setTextColor(
            color(if (selected) R.color.white else R.color.wedora_text_secondary)
        )
    }

    private fun color(@ColorRes resId: Int) = ContextCompat.getColor(this, resId)

    // ----- Not yet available ----------------------------------------------

    private fun showComingSoon() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.plan_coming_soon_title)
            .setMessage(R.string.plan_coming_soon_message)
            .setPositiveButton(R.string.action_ok, null)
            .show()
    }
}
