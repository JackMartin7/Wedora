package com.wedora.app

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.annotation.ColorRes
import androidx.core.content.ContextCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.wedora.app.databinding.ActivityPaymentSubscriptionBinding
import com.wedora.app.databinding.ItemPremiumFeatureBinding

/**
 * Premium plans and the subscribe flow — see [BillingManager] for the actual
 * Play Billing integration this screen drives.
 *
 * The prices shown while a card is just being browsed are the design's
 * placeholder copy; [BillingManager.formattedPrice] overwrites them with the
 * real, per-country Play Console price once product details resolve (it may
 * not have resolved yet by the time this screen opens, hence the
 * placeholder-first approach rather than blocking on it).
 */
class PaymentSubscriptionActivity : WedoraBaseActivity(), RateAppBottomSheet.Host {

    private lateinit var binding: ActivityPaymentSubscriptionBinding

    /** Yearly is preselected, matching the design's default emphasis. */
    private var yearlySelected = true

    /** True while a purchase is in flight — guards against a double-tap launching two billing flows. */
    private var purchaseInProgress = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPaymentSubscriptionBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applyEdgeInsets(binding.root)

        binding.btnBack.setOnClickListener { finish() }

        binding.cardMonthly.setOnClickListener { selectPlan(yearly = false) }
        binding.cardYearly.setOnClickListener { selectPlan(yearly = true) }

        binding.btnSubscribe.setOnClickListener { subscribe() }
        binding.btnSubscribe.addPressScale()
        binding.tvRestorePurchase.setOnClickListener { restorePurchase() }

        buildFeatureList()
        applyPlanSelection()
        applyLivePrices()
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

    // ----- Live prices -------------------------------------------------

    /**
     * Overwrites the placeholder price copy with Play Console's real,
     * per-country price once product details have resolved — a no-op (the
     * placeholder keeps showing) if they haven't resolved yet, which is the
     * common case right after a fresh install since BillingManager only
     * starts querying once its connection finishes (see its own doc
     * comment). There's no callback for "details just arrived" to re-run
     * this against, so a user who opens this screen in that window keeps
     * the placeholder for this visit — acceptable since it's the same
     * currency/ballpark copy the design already shipped with.
     */
    private fun applyLivePrices() {
        BillingManager.formattedPrice(BillingManager.PRODUCT_ID_MONTHLY)?.let {
            binding.tvMonthlyPrice.text = getString(R.string.plan_monthly_price_format, it)
        }
        BillingManager.formattedPrice(BillingManager.PRODUCT_ID_YEARLY)?.let {
            binding.tvYearlyPrice.text = getString(R.string.plan_yearly_price_format, it)
        }
    }

    // ----- Purchase ------------------------------------------------------

    private fun subscribe() {
        if (purchaseInProgress) return
        val productId = if (yearlySelected) BillingManager.PRODUCT_ID_YEARLY else BillingManager.PRODUCT_ID_MONTHLY

        setPurchaseLoading(true)
        BillingManager.launchBillingFlow(this, productId) { result ->
            setPurchaseLoading(false)
            when (result) {
                BillingManager.PurchaseResult.Success -> {
                    // The Firestore write BillingManager just made will also
                    // reach PremiumStatus's own listener, but that's a
                    // separate async round trip — applying it here too means
                    // this screen flips to premiumMemberSection immediately
                    // rather than waiting on it.
                    applyPremiumState()
                    maybeShowRatePrompt()
                }
                BillingManager.PurchaseResult.Cancelled -> {
                    // The user backed out of Google's own payment sheet —
                    // not an error, so no dialog/toast, matching how this
                    // app treats every other user-initiated cancel (e.g.
                    // dismissing a photo picker).
                }
                is BillingManager.PurchaseResult.Failed -> showPurchaseFailed()
            }
        }
    }

    /**
     * Asks for a rating right after a successful subscription — deliberately
     * hooked to this callback rather than anywhere inside [BillingManager].
     *
     * BillingManager.handlePurchase runs for restores too (syncEntitlement
     * reconciles previously-unacknowledged purchases on startup), so hooking
     * it there would re-ask every existing subscriber on launch. This
     * callback only ever fires for a purchase the user just completed —
     * syncEntitlement's path passes a null callback and never reaches it.
     */
    private fun maybeShowRatePrompt() {
        if (isFinishing || isDestroyed) return
        if (!RatePromptPrefs.shouldPrompt(this, RatePromptPrefs.Trigger.PREMIUM_PURCHASE)) return

        RatePromptPrefs.recordPromptShown(this)
        RateAppBottomSheet.show(supportFragmentManager)
    }

    override fun onRateAppRequested() {
        RatePromptPrefs.settle(this)
        openPlayStoreListing()
    }

    private fun restorePurchase() {
        if (purchaseInProgress) return
        setPurchaseLoading(true)
        BillingManager.syncEntitlement { foundActive ->
            setPurchaseLoading(false)
            applyPremiumState()
            Toast.makeText(
                this,
                if (foundActive) R.string.plan_restore_success else R.string.plan_restore_none_found,
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun setPurchaseLoading(loading: Boolean) {
        purchaseInProgress = loading
        binding.btnSubscribe.isEnabled = !loading
        binding.tvRestorePurchase.isEnabled = !loading
        binding.progressSubscribe.visibility = if (loading) View.VISIBLE else View.GONE
        binding.btnSubscribe.text = if (loading) "" else getString(R.string.plan_subscribe)
    }

    private fun showPurchaseFailed() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.plan_purchase_failed_title)
            .setMessage(R.string.plan_purchase_failed_message)
            .setPositiveButton(R.string.action_ok, null)
            .show()
    }
}
