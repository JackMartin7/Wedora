package com.wedora.app

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.LinearLayout
import androidx.appcompat.app.AppCompatActivity
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
class ProfileViewersActivity : AppCompatActivity() {

    private companion object {
        /** Decorative only — see item_profile_viewer_featured.xml. */
        const val FEATURED_TEASER_COUNT = 3
    }

    private lateinit var binding: ActivityProfileViewersBinding
    private val firestore: FirebaseFirestore by lazy { FirebaseFirestore.getInstance() }

    private val adapter = ProfileViewerAdapter { viewer ->
        startActivity(ProfileDetailActivity.intent(this, viewer.viewerUid))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityProfileViewersBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.rvViewers.layoutManager = LinearLayoutManager(this)
        binding.rvViewers.adapter = adapter

        binding.btnBack.setOnClickListener { finish() }

        val openUpgrade = View.OnClickListener {
            startActivity(Intent(this, PaymentSubscriptionActivity::class.java))
        }
        binding.premiumBanner.setOnClickListener(openUpgrade)
        binding.btnBannerAction.setOnClickListener(openUpgrade)

        load()
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
