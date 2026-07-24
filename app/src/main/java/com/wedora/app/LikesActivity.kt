package com.wedora.app

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.LinearLayout
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.wedora.app.databinding.ActivityLikesBinding
import com.wedora.app.databinding.ItemLikeFeaturedBinding

/**
 * The Likes tab: how many people liked you, a locked teaser of the first two,
 * an upgrade banner, then the rest as a photo grid.
 *
 * Same data as [NotificationsActivity] (via [loadReceivedLikes]). Opening it
 * marks those likes seen, so reaching them from the tab clears the Home badge
 * just as opening the bell does.
 *
 * The teaser is presentation only, for a free account. Every liker is still
 * readable from Firestore by this user regardless of [UserProfile.isPremium]
 * — likes aren't access-controlled by tier, only their presentation is.
 * Blurring two tiles is a prompt to upgrade, not an access control.
 */
class LikesActivity : AppCompatActivity() {

    private companion object {
        const val GRID_COLUMNS = 2

        /** How many likers are shown locked above the banner. */
        const val FEATURED_COUNT = 2

        /** Two rows of two tiles each, i.e. four placeholder squares. */
        const val SKELETON_ROWS = 2
    }

    private lateinit var binding: ActivityLikesBinding
    private val firestore: FirebaseFirestore by lazy { FirebaseFirestore.getInstance() }

    private val adapter = LikesAdapter { like ->
        startActivity(ProfileDetailActivity.intent(this, like.likerUserId))
    }

    private val matchedUserAdapter = MatchedUserAdapter { user ->
        startActivity(ProfileDetailActivity.intent(this, user.otherUserId))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLikesBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.rvLikes.layoutManager = GridLayoutManager(this, GRID_COLUMNS)
        binding.rvLikes.adapter = adapter

        binding.rvUsersMatched.layoutManager =
            LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        binding.rvUsersMatched.adapter = matchedUserAdapter

        setUpWedoraBottomNav(binding.bottomNav, R.id.nav_match)

        loadLikes()
        loadUsersMatched()
    }

    /**
     * Independent of [loadLikes]: this strip cares about the match count, not
     * the like count, so it loads and shows/hides on its own rather than
     * threading through the likes empty-state logic below.
     */
    private fun loadUsersMatched() {
        val selfUid = FirebaseAuth.getInstance().currentUser
            ?.takeUnless { GuestPrefs.isGuest(this) }?.uid
        if (selfUid == null) {
            binding.usersMatchedSection.visibility = View.GONE
            return
        }

        loadMatchedUsers(
            firestore,
            selfUid,
            onResult = { users ->
                binding.usersMatchedSection.visibility =
                    if (users.isEmpty()) View.GONE else View.VISIBLE
                matchedUserAdapter.submitList(users)
            },
            onError = { binding.usersMatchedSection.visibility = View.GONE }
        )
    }

    private fun loadLikes() {
        if (GuestPrefs.isGuest(this)) {
            // A guest has no likes to show, but is exactly who the sign-up
            // prompt is for — so the banner shows even though the list can't.
            showEmpty(
                R.drawable.ic_sparkle_heart,
                R.string.empty_likes_guest_title,
                R.string.empty_likes_guest_subtitle
            )
            showBanner(guest = true)
            return
        }

        val selfUid = FirebaseAuth.getInstance().currentUser?.uid
        if (selfUid == null) {
            showNoLikes()
            return
        }

        showLoading()
        // Own premium status first — showLikes needs it to decide whether to
        // blur at all, so it has to be known before the likes list renders,
        // not fetched alongside it.
        firestore.collection(UserProfile.COLLECTION).document(selfUid).get()
            .addOnSuccessListener { snapshot ->
                loadReceivedLikesAndShow(selfUid, UserProfile.from(snapshot).isPremium)
            }
            .addOnFailureListener {
                // Can't confirm premium — fail closed to the free (blurred)
                // experience rather than risk unblurring for a status that
                // couldn't be verified.
                loadReceivedLikesAndShow(selfUid, isPremium = false)
            }
    }

    private fun loadReceivedLikesAndShow(selfUid: String, isPremium: Boolean) {
        loadReceivedLikes(
            firestore,
            selfUid,
            onResult = { likes, unseenMatchIds ->
                if (likes.isEmpty()) showNoLikes() else showLikes(likes, isPremium)
                // Marks seen even when the display list is empty but unseen ids
                // exist (all likers' profiles were missing), so the badge still
                // clears.
                markLikesSeen(firestore, selfUid, unseenMatchIds)
            },
            onError = { showNoLikes() }
        )
    }

    // ----- View state -----------------------------------------------------

    /** Two rows of two tiles — the 2-column grid the real list uses. */
    private fun showLoading() {
        binding.progressLoading.visibility = View.GONE
        binding.emptyState.hide()
        binding.likesScroll.visibility = View.VISIBLE

        binding.tvLikeCount.visibility = View.GONE
        binding.featuredContainer.visibility = View.GONE
        binding.premiumBanner.visibility = View.GONE
        binding.rvLikes.visibility = View.GONE
        binding.skeletonLikes.showSkeleton(R.layout.item_skeleton_like_row, SKELETON_ROWS)
    }

    /** The three paths that end with no likes share one message. */
    private fun showNoLikes() = showEmpty(
        R.drawable.ic_sparkle_heart,
        R.string.empty_likes_title,
        R.string.empty_likes_subtitle
    )

    private fun showEmpty(
        @DrawableRes icon: Int,
        @StringRes title: Int,
        @StringRes subtitle: Int
    ) {
        binding.skeletonLikes.hideSkeleton()
        binding.progressLoading.visibility = View.GONE
        binding.emptyState.show(icon, title, subtitle)

        // The scroll container still hosts the banner, so it stays visible for
        // guests; its inner sections are hidden individually.
        binding.likesScroll.visibility = View.GONE
        binding.tvLikeCount.visibility = View.GONE
        binding.featuredContainer.visibility = View.GONE
        binding.rvLikes.visibility = View.GONE
    }

    /**
     * Premium: every liker shown plainly in the grid — no teaser, no lock, no
     * upgrade banner, since there's nothing left to upgrade to.
     *
     * Free, with two or more likers: the first [FEATURED_COUNT] are shown
     * locked and the remainder fill the grid. With one, the teaser is skipped
     * entirely and that single liker is shown plainly — blurring a list of
     * one leaves the screen looking broken rather than tantalising.
     */
    private fun showLikes(likes: List<ReceivedLike>, isPremium: Boolean) {
        binding.progressLoading.visibility = View.GONE
        binding.emptyState.hide()
        binding.likesScroll.visibility = View.VISIBLE
        binding.skeletonLikes.hideSkeleton()

        binding.tvLikeCount.visibility = View.VISIBLE
        binding.tvLikeCount.text =
            resources.getQuantityString(R.plurals.likes_count, likes.size, likes.size)

        if (isPremium) {
            binding.featuredContainer.visibility = View.GONE
            binding.featuredContainer.removeAllViews()
            binding.rvLikes.visibility = View.VISIBLE
            adapter.submitList(likes)
            binding.premiumBanner.visibility = View.GONE
            return
        }

        val teasing = likes.size > FEATURED_COUNT
        if (teasing) {
            showFeatured(likes.take(FEATURED_COUNT))
        } else {
            binding.featuredContainer.visibility = View.GONE
            binding.featuredContainer.removeAllViews()
        }

        val remainder = if (teasing) likes.drop(FEATURED_COUNT) else likes
        binding.rvLikes.visibility = if (remainder.isEmpty()) View.GONE else View.VISIBLE
        adapter.submitList(remainder)

        showBanner(guest = false)
    }

    /** Builds the locked tiles: equal-width, square, blurred, lock centred. */
    private fun showFeatured(featured: List<ReceivedLike>) {
        binding.featuredContainer.removeAllViews()
        binding.featuredContainer.visibility = View.VISIBLE

        val inflater = LayoutInflater.from(this)
        featured.forEachIndexed { index, _ ->
            val tile = ItemLikeFeaturedBinding.inflate(
                inflater, binding.featuredContainer, false
            )
            tile.root.layoutParams = LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
            ).apply {
                marginStart = if (index == 0) 0 else dp(12)
                marginEnd = 0
            }
            binding.featuredContainer.addView(tile.root)

            // Blurred after layout: the pre-31 path rasterises the view's
            // drawable, and before a measure pass there are no dimensions to
            // rasterise into.
            tile.ivFeaturedPhoto.post { tile.ivFeaturedPhoto.applyLockedBlur() }

            // Tapping a locked tile leads where the lock implies, not to the
            // profile it's hiding.
            tile.root.setOnClickListener { openUpgradeDestination(guest = false) }
        }
    }

    private fun showBanner(guest: Boolean) {
        binding.premiumBanner.visibility = View.VISIBLE
        binding.tvBannerText.setText(
            if (guest) R.string.premium_banner_guest else R.string.premium_banner_likes
        )
        binding.btnBannerAction.setText(
            if (guest) R.string.premium_banner_signup_action
            else R.string.premium_banner_upgrade_action
        )

        val open = View.OnClickListener { openUpgradeDestination(guest) }
        binding.premiumBanner.setOnClickListener(open)
        binding.btnBannerAction.setOnClickListener(open)

        // For a guest the empty state hides the scroll container, so the banner
        // would go with it — show the container and hide everything else in it.
        if (guest) binding.likesScroll.visibility = View.VISIBLE
    }

    private fun openUpgradeDestination(guest: Boolean) {
        val destination =
            if (guest) SignUpActivity::class.java else PaymentSubscriptionActivity::class.java
        startActivity(Intent(this, destination))
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()
}
