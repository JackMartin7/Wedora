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
import com.google.android.gms.ads.nativead.NativeAd
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

    /**
     * Loaded-and-ready native ads for the likes grid — same shared
     * implementation Home's swipe stack and Explore's Discover grid use (see
     * [NativeAdPool]), a separate instance since each screen manages its own
     * display list.
     */
    private val adPool = NativeAdPool(this)

    /**
     * Positions in the grid where [buildLikesGridItems] wanted to insert an
     * ad but [adPool] was empty at that moment — a real like ended up there
     * instead, the same race Home/Explore guard against (see either one's
     * own doc comment on the equivalent field). Backfilled by
     * [backfillPendingAdSlot] once an ad finishes loading. No "already
     * bound, don't touch it" restriction on which index is eligible — like
     * DiscoverAdapter, LikesAdapter is a real ListAdapter/RecyclerView, so
     * re-submitting the list safely re-binds whichever position changed
     * type, on screen or not.
     */
    private val pendingAdSlots = mutableListOf<Int>()

    /**
     * Whether it's still safe to touch views or start a Glide/native-ad load
     * on this Activity instance. Toggling dark/light mode recreates the
     * Activity, and every async Firestore read below has no way to be
     * cancelled once in flight — same class of bug Home/Explore's feed loads
     * had (see either one's own isUsable doc comment), applied here
     * proactively rather than waiting to hit the identical crash.
     */
    private fun isUsable(): Boolean = !isFinishing && !isDestroyed

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
        setUpExitConfirmOnBackPress {
            val (kind, count) = resolveExitConfirmKind(
                unseenLikes = binding.bottomNav.currentBadgeCount(R.id.nav_match),
                unreadMessages = binding.bottomNav.currentBadgeCount(R.id.nav_chats)
            )
            ExitConfirmBottomSheet.show(supportFragmentManager, kind, count)
        }

        val openSignUp = View.OnClickListener {
            startActivity(Intent(this, SignUpActivity::class.java))
        }
        binding.guestSignUpBanner.setOnClickListener(openSignUp)
        binding.btnGuestSignUpBannerAction.setOnClickListener(openSignUp)

        // Kicked off before loadLikes's own network round trip — same
        // "preload ahead" reasoning as Home/Explore, so an ad is very likely
        // already sitting in the pool by the time the grid actually needs
        // one. No-ops for a Premium user, since NativeAdPool.refill
        // re-checks isPremium on every call.
        adPool.refill { ad -> backfillPendingAdSlot(ad) }

        loadLikes()
        loadUsersMatched()
    }

    /**
     * Native ads hold on to their own resources until explicitly released —
     * everything ever loaded, whether currently shown in the grid or still
     * waiting in the pool, needs destroy() so it doesn't leak past this
     * Activity. Same reasoning as HomeActivity/ExploreActivity.onDestroy.
     */
    override fun onDestroy() {
        adapter.currentList.forEach { item -> (item as? LikesGridItem.Ad)?.ad?.destroy() }
        adPool.destroyAll()
        super.onDestroy()
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
                if (!isUsable()) return@loadMatchedUsers
                binding.usersMatchedSection.visibility =
                    if (users.isEmpty()) View.GONE else View.VISIBLE
                matchedUserAdapter.submitList(users)
            },
            onError = {
                if (!isUsable()) return@loadMatchedUsers
                binding.usersMatchedSection.visibility = View.GONE
            }
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
                if (!isUsable()) return@addOnSuccessListener
                val isPremium = UserProfile.from(snapshot).isPremium
                loadReceivedLikesAndShow(selfUid, isPremium)
                loadProfileViewersStrip(selfUid, isPremium)
            }
            .addOnFailureListener {
                if (!isUsable()) return@addOnFailureListener
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
                if (!isUsable()) return@loadProfileViewers
                binding.profileViewersSection.visibility =
                    if (viewers.isEmpty()) View.GONE else View.VISIBLE
                binding.rvProfileViewers.visibility = View.VISIBLE
                binding.profileViewersLockedContainer.visibility = View.GONE
                profileViewerAdapter.submitList(viewers)
            },
            onError = {
                if (!isUsable()) return@loadProfileViewers
                binding.profileViewersSection.visibility = View.GONE
            }
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
                if (!isUsable()) return@loadReceivedLikes
                if (likes.isEmpty()) showNoLikes() else showLikes(likes, isPremium)
                // Marks seen even when the display list is empty but unseen ids
                // exist (all likers' profiles were missing), so the badge still
                // clears.
                markLikesSeen(firestore, selfUid, unseenMatchIds)
            },
            onError = {
                if (!isUsable()) return@loadReceivedLikes
                showNoLikes()
            }
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
            adapter.submitList(buildLikesGridItems(likes, isPremium = true))
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
        adapter.submitList(buildLikesGridItems(remainder, isPremium = false))

        binding.guestSignUpBanner.visibility = View.GONE
    }

    // ----- Native ads (free signed-in users only — see class doc comment for guest access) ---

    // TODO: Ad insertion not confirmed working on-device as of 2026-07-25 —
    // revisit once there's a larger real user base to test against properly.

    /**
     * Weaves a native ad in after every real like [FirstTwoThenFourAdGap]
     * says gets one, using only whatever's already sitting in [adPool] —
     * never waiting on a fresh load. If the pool is empty when a slot comes
     * up, that slot is recorded in [pendingAdSlots] rather than lost
     * outright, and [backfillPendingAdSlot] converts it to an ad in place
     * once one finishes loading. Same shape as HomeActivity.buildDisplayItems
     * / ExploreActivity.buildDiscoverGridItems — see either one's own doc
     * comment for the full reasoning, identical here, including [isPremium]
     * being checked here rather than trusted from the caller: this is the
     * one place every tile the grid ever shows passes through. Guests never
     * reach this at all — see [showGuestTeaser], which only ever fills
     * [binding.featuredContainer], never [adapter] — so in practice this is
     * already scoped to free signed-in users specifically once [isPremium]
     * is false.
     */
    private fun buildLikesGridItems(likes: List<ReceivedLike>, isPremium: Boolean): List<LikesGridItem> {
        if (isPremium) return likes.map { LikesGridItem.Like(it) }

        val adGap = FirstTwoThenFourAdGap()
        val items = mutableListOf<LikesGridItem>()
        likes.forEach { like ->
            items += LikesGridItem.Like(like)
            if (adGap.afterLike()) {
                adPool.poll()?.let { ad ->
                    items += LikesGridItem.Ad(ad)
                    adPool.refill { backfillAd -> backfillPendingAdSlot(backfillAd) }
                } ?: run {
                    pendingAdSlots += items.size
                    adPool.refill { backfillAd -> backfillPendingAdSlot(backfillAd) }
                }
            }
        }
        return items
    }

    /**
     * Converts the earliest still-pending entry in [pendingAdSlots] into
     * [ad], now that it's finished loading. No position is off-limits:
     * LikesAdapter is a real ListAdapter, so re-submitting the list safely
     * re-binds whichever position changed type regardless of scroll
     * position. Returns whether [ad] was used this way, so [adPool] knows
     * whether to fall back to pooling it instead (see [NativeAdPool.refill]).
     */
    private fun backfillPendingAdSlot(ad: NativeAd): Boolean {
        val currentItems = adapter.currentList
        val index = pendingAdSlots.firstOrNull { it < currentItems.size } ?: return false
        pendingAdSlots.remove(index)

        adapter.submitList(
            currentItems.toMutableList().apply { this[index] = LikesGridItem.Ad(ad) }
        )
        return true
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
