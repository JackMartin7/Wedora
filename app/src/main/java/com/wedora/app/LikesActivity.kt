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
import com.wedora.app.databinding.ItemProfileViewerStripLockedBinding

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

        /** Decorative only — see item_profile_viewer_strip_locked.xml. */
        const val PROFILE_VIEWERS_LOCKED_TEASER_COUNT = 3
    }

    private lateinit var binding: ActivityLikesBinding
    private val firestore: FirebaseFirestore by lazy { FirebaseFirestore.getInstance() }

    private val adapter = LikesAdapter { like ->
        startActivity(ProfileDetailActivity.intent(this, like.likerUserId))
    }

    private val matchedUserAdapter = MatchedUserAdapter { user ->
        startActivity(ProfileDetailActivity.intent(this, user.otherUserId))
    }

    private val profileViewerAdapter = ProfileViewerStripAdapter { viewer ->
        startActivity(ProfileDetailActivity.intent(this, viewer.viewerUid))
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

        binding.rvProfileViewers.layoutManager =
            LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        binding.rvProfileViewers.adapter = profileViewerAdapter

        setUpWedoraBottomNav(binding.bottomNav, R.id.nav_match)

        val openSignUp = View.OnClickListener {
            startActivity(Intent(this, SignUpActivity::class.java))
        }
        binding.guestSignUpBanner.setOnClickListener(openSignUp)
        binding.btnGuestSignUpBannerAction.setOnClickListener(openSignUp)

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
            showGuestTeaser()
            return
        }

        val selfUid = FirebaseAuth.getInstance().realUid
        if (selfUid == null) {
            showNoLikes()
            return
        }

        showLoading()
        // Own premium status first — showLikes needs it to decide whether to
        // blur at all, so it has to be known before the likes list renders,
        // not fetched alongside it. The same read also gates the "Who Viewed
        // Your Profile" strip below, rather than each running its own.
        firestore.collection(UserProfile.COLLECTION).document(selfUid).get()
            .addOnSuccessListener { snapshot ->
                val isPremium = UserProfile.from(snapshot).isPremium
                loadReceivedLikesAndShow(selfUid, isPremium)
                loadProfileViewersStrip(selfUid, isPremium)
            }
            .addOnFailureListener {
                // Can't confirm premium — fail closed to the free (blurred)
                // experience rather than risk unblurring for a status that
                // couldn't be verified. profileViewersSection stays hidden,
                // its XML default, for the same reason.
                loadReceivedLikesAndShow(selfUid, isPremium = false)
            }
    }

    /**
     * Free users never reach the query at all — same reasoning as
     * ProfileViewNotificationWatcher's own isPremium gate: this data isn't
     * access-controlled by Firestore rules (an owner can always read their
     * own viewers), so the gate here is about not fetching or showing
     * something a free account's UI never surfaces anywhere else, not about
     * enforcing a permission — a free account can still read their own real
     * profileViews subcollection (firestore.rules allows the owner
     * regardless of tier); this gate is purely about not surfacing the
     * feature outside where it's meant to be visible.
     *
     * Free: a static locked teaser, always shown (same shape as
     * ProfileViewersActivity's own free path — see showLockedProfileViewers),
     * no query needed. Premium: the real list, hidden if there happen to be
     * zero viewers.
     */
    private fun loadProfileViewersStrip(selfUid: String, isPremium: Boolean) {
        if (!isPremium) {
            showLockedProfileViewers()
            return
        }

        loadProfileViewers(
            firestore,
            selfUid,
            onResult = { viewers ->
                binding.profileViewersSection.visibility =
                    if (viewers.isEmpty()) View.GONE else View.VISIBLE
                binding.rvProfileViewers.visibility = View.VISIBLE
                binding.profileViewersLockedContainer.visibility = View.GONE
                profileViewerAdapter.submitList(viewers)
            },
            onError = { binding.profileViewersSection.visibility = View.GONE }
        )
    }

    /**
     * Free-tier teaser: a few static blurred-avatar-and-lock tiles, always
     * shown regardless of whether this account actually has any real
     * viewers — matching ProfileViewersActivity's own free path, which
     * shows the same teaser unconditionally rather than querying first to
     * decide. Tapping any tile (or the row itself) leads where the lock
     * implies: Payment & Subscription.
     */
    private fun showLockedProfileViewers() {
        binding.profileViewersSection.visibility = View.VISIBLE
        binding.rvProfileViewers.visibility = View.GONE
        binding.profileViewersLockedContainer.visibility = View.VISIBLE
        binding.profileViewersLockedContainer.removeAllViews()

        val openUpgrade = View.OnClickListener {
            startActivity(Intent(this, PaymentSubscriptionActivity::class.java))
        }
        binding.profileViewersLockedContainer.setOnClickListener(openUpgrade)

        val inflater = LayoutInflater.from(this)
        repeat(PROFILE_VIEWERS_LOCKED_TEASER_COUNT) {
            val tile = ItemProfileViewerStripLockedBinding.inflate(
                inflater, binding.profileViewersLockedContainer, false
            )
            binding.profileViewersLockedContainer.addView(tile.root)
            tile.root.setOnClickListener(openUpgrade)
            // Blurred after layout: the pre-31 path rasterises the drawable,
            // and there are no dimensions to rasterise into before a
            // measure pass.
            tile.ivLockedViewerAvatar.post { tile.ivLockedViewerAvatar.applyLockedBlur() }
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
        binding.guestSignUpBanner.visibility = View.GONE
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

        // Signed-in only now — a guest never reaches this, see showGuestTeaser.
        binding.likesScroll.visibility = View.GONE
        binding.tvLikeCount.visibility = View.GONE
        binding.featuredContainer.visibility = View.GONE
        binding.rvLikes.visibility = View.GONE
        binding.guestSignUpBanner.visibility = View.GONE
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
            binding.guestSignUpBanner.visibility = View.GONE
            return
        }

        val teasing = likes.size > FEATURED_COUNT
        if (teasing) {
            showFeatured(FEATURED_COUNT)
        } else {
            binding.featuredContainer.visibility = View.GONE
            binding.featuredContainer.removeAllViews()
        }

        val remainder = if (teasing) likes.drop(FEATURED_COUNT) else likes
        binding.rvLikes.visibility = if (remainder.isEmpty()) View.GONE else View.VISIBLE
        adapter.submitList(remainder)

        binding.guestSignUpBanner.visibility = View.GONE
        binding.premiumBanner.visibility = View.VISIBLE
        val open = View.OnClickListener {
            startActivity(Intent(this, PaymentSubscriptionActivity::class.java))
        }
        binding.premiumBanner.setOnClickListener(open)
        binding.btnBannerAction.setOnClickListener(open)
    }

    /**
     * A guest has no real likes to show — nobody can like an account that
     * doesn't exist — so instead of the old "sign up" empty-state message
     * (nothing to scroll past, banner sitting alone at the top) this reuses
     * the exact same blurred "featured" tiles a free signed-in user with
     * more than FEATURED_COUNT likes sees, purely as a preview of what the
     * feature looks like unlocked. The actual ask to sign up moves to
     * guestSignUpBanner — a quiet, persistent strip pinned above the bottom
     * nav — rather than sitting in front of the content.
     */
    private fun showGuestTeaser() {
        binding.skeletonLikes.hideSkeleton()
        binding.progressLoading.visibility = View.GONE
        binding.emptyState.hide()
        binding.likesScroll.visibility = View.VISIBLE

        binding.tvLikeCount.visibility = View.GONE
        binding.rvLikes.visibility = View.GONE
        binding.premiumBanner.visibility = View.GONE
        showFeatured(FEATURED_COUNT)

        binding.guestSignUpBanner.visibility = View.VISIBLE
    }

    /**
     * Builds the locked tiles: equal-width, square, blurred, lock centred.
     * No real data needed — every tile is the same neutral blurred
     * placeholder regardless of who's behind it (see item_like_featured.xml),
     * so [count] is all a caller has to supply, whether backed by real
     * likers (showLikes) or nothing at all (showGuestTeaser).
     */
    private fun showFeatured(count: Int) {
        binding.featuredContainer.removeAllViews()
        binding.featuredContainer.visibility = View.VISIBLE

        val inflater = LayoutInflater.from(this)
        repeat(count) { index ->
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
            // profile it's hiding — Sign Up for a guest (there's no premium
            // to upgrade to without an account first), Payment & Subscription
            // otherwise.
            tile.root.setOnClickListener {
                val destination =
                    if (GuestPrefs.isGuest(this)) SignUpActivity::class.java
                    else PaymentSubscriptionActivity::class.java
                startActivity(Intent(this, destination))
            }
        }
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()
}
