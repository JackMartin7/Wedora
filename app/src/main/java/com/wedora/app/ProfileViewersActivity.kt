package com.wedora.app

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.LinearLayout
import com.google.android.gms.ads.nativead.NativeAd
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.wedora.app.databinding.ActivityProfileViewersBinding
import com.wedora.app.databinding.ItemProfileViewerFeaturedBinding

/**
 * "Who Viewed My Profile" — premium-gated the same way the Likes tab is: free
 * sees a blurred teaser and an upgrade banner, Premium sees the real list.
 *
 * Unlike Likes, the free state shows nothing beyond the teaser and banner —
 * there's no partial reveal here, since this screen has no non-premium
 * purpose at all (Likes still counts and previews; "who viewed" only exists
 * to be unlocked).
 */
class ProfileViewersActivity : WedoraBaseActivity() {

    private companion object {
        /** Decorative only — see item_profile_viewer_featured.xml. */
        const val FEATURED_TEASER_COUNT = 3
    }

    private lateinit var binding: ActivityProfileViewersBinding
    private val firestore: FirebaseFirestore by lazy { FirebaseFirestore.getInstance() }

    private val adapter = ProfileViewerAdapter { viewer ->
        startActivity(ProfileDetailActivity.intent(this, viewer.viewerUid))
    }

    /**
     * Reuses the Likes ad unit rather than minting another: both are the
     * same "free user looking at gated social proof" context, and one unit
     * with more traffic reports more usefully than two starved ones.
     */
    private val adPool = NativeAdPool(this, NativeAdLoader.AD_UNIT_ID_LIKES)

    /** The ad currently bound into the banner; destroyed when replaced or on exit. */
    private var loadedAd: NativeAd? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityProfileViewersBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applyEdgeInsets(binding.root)

        binding.rvViewers.layoutManager = LinearLayoutManager(this)
        binding.rvViewers.adapter = adapter

        binding.btnBack.setOnClickListener { finish() }

        val openUpgrade = View.OnClickListener {
            startActivity(Intent(this, PaymentSubscriptionActivity::class.java))
        }
        binding.premiumBanner.setOnClickListener(openUpgrade)
        binding.btnBannerAction.setOnClickListener(openUpgrade)

        // Kicked off before load()'s own round trip so an ad is likely ready
        // by the time showTeaser needs one. No-ops for Premium.
        adPool.refill()

        load()
    }

    /**
     * Native ads hold their own resources until told to let go — both the
     * one bound into the banner and anything still pooled. Same reasoning as
     * every other ad-carrying screen's onDestroy.
     */
    override fun onDestroy() {
        loadedAd?.destroy()
        loadedAd = null
        adPool.destroyAll()
        super.onDestroy()
    }

    private fun load() {
        val selfUid = FirebaseAuth.getInstance().currentUser
            ?.takeUnless { GuestPrefs.isGuest(this) }?.uid
        if (selfUid == null) {
            // Unreachable in practice — Profile itself gates guests before
            // this screen is ever reached — but the teaser is still the
            // correct thing to show if it somehow is.
            showTeaser()
            return
        }

        showLoading()
        firestore.collection(UserProfile.COLLECTION).document(selfUid).get()
            .addOnSuccessListener { snapshot ->
                val self = UserProfile.from(snapshot)
                if (self.isPremium) {
                    loadAndShowViewers(selfUid, self.latitude, self.longitude)
                } else {
                    showTeaser()
                }
            }
            .addOnFailureListener {
                // Can't confirm premium — fail closed to the teaser rather
                // than risk unblurring for a status that couldn't be verified.
                showTeaser()
            }
    }

    private fun loadAndShowViewers(selfUid: String, myLat: Double?, myLon: Double?) {
        loadProfileViewers(
            firestore,
            selfUid,
            myLat, myLon,
            onResult = { viewers -> if (viewers.isEmpty()) showEmpty() else showViewers(viewers) },
            onError = { showEmpty() }
        )
    }

    // ----- View state -------------------------------------------------------

    private fun showLoading() {
        binding.emptyState.hide()
        binding.teaserSection.visibility = View.GONE
        binding.rvViewers.visibility = View.GONE
        binding.progressLoading.visibility = View.VISIBLE
    }

    private fun showViewers(viewers: List<ProfileViewer>) {
        binding.progressLoading.visibility = View.GONE
        binding.emptyState.hide()
        binding.teaserSection.visibility = View.GONE
        binding.rvViewers.visibility = View.VISIBLE
        adapter.submitList(viewers)
    }

    private fun showEmpty() {
        binding.progressLoading.visibility = View.GONE
        binding.teaserSection.visibility = View.GONE
        binding.rvViewers.visibility = View.GONE
        binding.emptyState.show(
            R.drawable.ic_sparkle_heart,
            R.string.empty_profile_viewers_title,
            R.string.empty_profile_viewers_subtitle
        )
    }

    private fun showTeaser() {
        binding.progressLoading.visibility = View.GONE
        binding.emptyState.hide()
        binding.rvViewers.visibility = View.GONE
        binding.teaserSection.visibility = View.VISIBLE
        buildFeaturedTiles()
        showAdBanner()
    }

    /**
     * The free-tier ad slot under the upgrade banner.
     *
     * Only reached from [showTeaser], so it's inherently free-users-only —
     * a Premium account takes the [loadAndShowViewers] branch and never gets
     * here. [NativeAdPool.refill] re-checks isPremium anyway, which is what
     * makes that belt-and-braces rather than a second source of truth.
     *
     * Polls whatever is already loaded and gives up quietly if nothing is:
     * the slot stays hidden rather than showing an empty card, and the
     * refill below means one is usually ready by the next visit.
     */
    private fun showAdBanner() {
        val ad = adPool.poll()
        if (ad == null) {
            binding.adBanner.root.visibility = View.GONE
            adPool.refill()
            return
        }
        loadedAd?.destroy()
        loadedAd = ad
        binding.adBanner.bindNativeAd(ad)
        binding.adBanner.root.visibility = View.VISIBLE
        adPool.refill()
    }

    /**
     * A fixed row of blurred placeholder tiles, not tied to real viewer data
     * — see item_profile_viewer_featured.xml for why that's harmless here.
     * removeAllViews() first keeps this idempotent if it's ever called twice.
     */
    private fun buildFeaturedTiles() {
        binding.featuredContainer.removeAllViews()

        val inflater = LayoutInflater.from(this)
        repeat(FEATURED_TEASER_COUNT) { index ->
            val tile = ItemProfileViewerFeaturedBinding.inflate(
                inflater, binding.featuredContainer, false
            )
            tile.root.layoutParams = LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
            ).apply {
                marginStart = if (index == 0) 0 else dp(12)
                marginEnd = 0
            }
            binding.featuredContainer.addView(tile.root)

            // Blurred after layout, same reasoning as Likes' featured tiles:
            // the pre-31 path rasterises the drawable, and there are no
            // dimensions to rasterise into before a measure pass.
            tile.ivFeaturedViewerPhoto.post { tile.ivFeaturedViewerPhoto.applyLockedBlur() }
        }
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()
}
