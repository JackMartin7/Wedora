package com.wedora.app

import android.content.Intent
import android.content.res.ColorStateList
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.ImageButton
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.gms.ads.nativead.NativeAd
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.wedora.app.databinding.ActivityHomeBinding
import com.wedora.app.databinding.ItemMatchCardBinding
import com.wedora.app.databinding.ItemNativeAdCardBinding
import java.util.Calendar

class HomeActivity :
    AppCompatActivity(),
    DailyLimitReachedBottomSheet.Host,
    GuestProfileLimitBottomSheet.Host {

    private companion object {
        const val TAG = "WedoraMatching"

        /** Enough to fill the screen and imply a stack, without scrolling. */
        const val SKELETON_CARDS = 3

        /** A native ad follows every AD_INTERVALth real profile — free users only. */
        const val AD_INTERVAL = 5

        /** How many native ads to keep loaded and ready at once. */
        const val AD_POOL_TARGET = 2
    }

    /** One slot in the swipe stack — either a real profile or a native ad. */
    private sealed class StackItem {
        data class Profile(val card: MatchCard) : StackItem()
        data class Ad(val ad: NativeAd) : StackItem()
    }

    private lateinit var binding: ActivityHomeBinding
    private val firestore: FirebaseFirestore by lazy { FirebaseFirestore.getInstance() }

    /** Live view of the user's matches; drives the badge and the liked hearts. */
    private var matchesListener: ListenerRegistration? = null

    /**
     * The feed, in swipe order — a mix of real profiles and (for free users)
     * native ads woven in every AD_INTERVAL profiles. Bound into the card
     * stack by position; only StackItem.Profile entries ever reach
     * likeUser/recordPass, so an ad never touches matching logic.
     */
    private var displayItems: List<StackItem> = emptyList()

    /**
     * Loaded-and-ready native ads, refilled as they're consumed so there are
     * always up to AD_POOL_TARGET ahead of where the stack needs them next.
     * buildDisplayItems only ever draws from what's already here — never
     * waits on a fresh load — so a slow or failed ad request never delays or
     * blocks the swipe flow; it just means fewer ad slots get filled this
     * pass (see buildDisplayItems).
     */
    private val adPool = ArrayDeque<NativeAd>()
    private var adsInFlight = 0

    /**
     * UIDs the user has liked, kept updated by the match listener.
     *
     * Already-liked people are now excluded from the feed by
     * [loadFeedExclusions], so in the normal case no card is ever bound for
     * one and the filled heart doesn't appear. This isn't dead weight though:
     * the exclusion read fails *open*, so a network failure can still put a
     * liked person on screen — and when that happens the heart should be red
     * and unlike-in-place should work, which is what this set drives.
     */
    private val likedUserIds = mutableSetOf<String>()

    private val stackListener = object : SwipeCardStackView.Listener {
        override fun onBindCard(cardView: View, position: Int) {
            when (val item = displayItems.getOrNull(position)) {
                is StackItem.Profile -> bindCard(cardView, item.card)
                is StackItem.Ad -> bindAdCard(cardView, item.ad)
                null -> Unit
            }
        }

        override fun onSwipedRight(position: Int) {
            (displayItems.getOrNull(position) as? StackItem.Profile)?.let { likeUser(it.card) }
        }

        override fun onSwipedLeft(position: Int) {
            (displayItems.getOrNull(position) as? StackItem.Profile)?.let { recordPass(it.card) }
        }

        override fun onEmptied() {
            showEmptyState(
                icon = R.drawable.ic_sparkle_heart,
                title = R.string.empty_home_title,
                subtitle = R.string.empty_home_subtitle,
                actionLabel = R.string.empty_action_adjust_filters,
                onAction = ::openFilters,
                // The stack was full a moment ago, so fade rather than blink.
                fade = true
            )
        }

        override fun onTopCardChanged(position: Int) {
            recordGuestProfileViewIfNeeded(displayItems.getOrNull(position))
        }
    }

    /**
     * Guests only — signed-in users, free or Premium, aren't tracked here at
     * all (this whole function is a no-op for them). Ads never count: they
     * aren't a profile, and StackItem.Ad simply doesn't match the Profile
     * branch below.
     *
     * The freeze and the sheet fire together rather than the freeze waiting
     * on "Maybe Later" specifically — the sheet is a modal dialog, so nothing
     * behind it is reachable anyway until it's dismissed one way or another,
     * and this way the frozen state is already correct underneath regardless
     * of which button (or a swipe-down, or a tap outside) closes it.
     */
    private fun recordGuestProfileViewIfNeeded(item: StackItem?) {
        if (!GuestPrefs.isGuest(this) || item !is StackItem.Profile) return
        val count = GuestPrefs.recordGuestProfileViewed(this)
        if (count >= GuestPrefs.DAILY_PROFILE_VIEW_LIMIT) {
            showGuestLimitReached()
            GuestProfileLimitBottomSheet.show(supportFragmentManager)
        }
    }

    /**
     * The persistent "come back tomorrow" state — reuses the same empty-state
     * plumbing every other Home state already goes through, just with its own
     * copy and a Sign Up action instead of Adjust Filters. Guarded at the top
     * of showCards too, so a guest who already spent today's pool elsewhere
     * (Explore, or an earlier session today) sees this immediately on load
     * rather than a fresh stack they'd only lose again on the very next card.
     */
    private fun showGuestLimitReached() {
        showEmptyState(
            icon = R.drawable.ic_sparkle_heart,
            title = R.string.guest_limit_empty_title,
            subtitle = R.string.guest_limit_empty_subtitle,
            actionLabel = R.string.guest_limit_empty_action,
            onAction = { startActivity(Intent(this, SignUpActivity::class.java)) }
        )
    }

    override fun onSignUpFromGuestLimitRequested() {
        startActivity(Intent(this, SignUpActivity::class.java))
    }

    /**
     * Only reloads on RESULT_OK — i.e. Apply. Backing out of the filter screen
     * changes nothing, so re-querying would throw away the user's place in the
     * card stack for no reason.
     */
    private val filterLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            showFilterIndicator()
            loadMatches()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityHomeBinding.inflate(layoutInflater)
        setContentView(binding.root)

        showGreeting()
        showSignedInUser()
        // Kicked off before loadMatches's own network round trip, so by the
        // time showCards actually needs an ad, one is very likely already
        // sitting in the pool — this is what "preload ahead" means in
        // buildDisplayItems. No-ops for a Premium user beyond this one queued
        // request, since refillAdPool re-checks isPremium on every call.
        refillAdPool()
        loadMatches()
        setUpWedoraBottomNav(binding.bottomNav, R.id.nav_home)

        binding.btnDarkMode.setOnClickListener { toggleDarkMode() }

        binding.btnNotifications.setOnClickListener {
            startActivity(Intent(this, NotificationsActivity::class.java))
        }

        // Shown to guests too — they're the likeliest upgrade, and the
        // subscription screen doesn't read any account data. Stays the same
        // destination for Premium users too: PaymentSubscriptionActivity
        // itself swaps to a "You're a Premium Member" confirmation instead of
        // the upsell once it sees isPremium == true.
        binding.btnPremium.setOnClickListener {
            startActivity(Intent(this, PaymentSubscriptionActivity::class.java))
        }

        binding.btnFilter.setOnClickListener {
            filterLauncher.launch(Intent(this, FilterActivity::class.java))
        }
        showFilterIndicator()

        // Tapping your own avatar/name opens your profile — the same
        // destination as the Profile tab, so it navigates the same way the tab
        // does: start it and finish this one.
        //
        // Leaving Home on the stack instead would mean that switching back via
        // the bottom nav (which itself starts-and-finishes) launches a *second*
        // Home on top of the first, leaving a duplicate underneath running its
        // own listeners. Profile carries the bottom nav, so it's still one tap
        // back to the feed.
        // Not guest-gated, matching the bottom nav's own Profile tab
        // (see setUpWedoraBottomNav) — Profile has its own guest state now,
        // so a guest tapping their avatar/name sees that preview rather than
        // being bounced to Sign Up before ever reaching it.
        binding.greetingContainer.setOnClickListener {
            startActivity(Intent(this, ProfileActivity::class.java))
            finish()
            // Profile is a bottom-nav peer, so this matches how the tab bar
            // moves between the two rather than sliding as though it were a
            // level deeper.
            applyLateralTransition()
        }
    }

    override fun onStart() {
        super.onStart()
        observeMatches()
        applyPremiumCrownTint()
    }

    /**
     * Gold once the cached status says Premium, the default accent pink
     * otherwise — a glance-only signal, not an upsell, once already
     * subscribed. Re-applied on every onStart so flipping isPremium via
     * Firebase Console mid-session shows correctly the next time Home is
     * returned to, without needing its own listener here.
     */
    private fun applyPremiumCrownTint() {
        binding.btnPremium.imageTintList = ColorStateList.valueOf(
            ContextCompat.getColor(
                this,
                if (PremiumStatus.isPremium()) R.color.wedora_premium_gold else R.color.wedora_accent
            )
        )
    }

    override fun onStop() {
        matchesListener?.remove()
        matchesListener = null
        super.onStop()
    }

    /**
     * Native ads hold on to their own resources (creative assets, click
     * tracking) until explicitly released — everything ever loaded, whether
     * currently shown, still waiting in the pool, or an in-flight request
     * that lands after this point, needs destroy() so it doesn't leak past
     * this Activity.
     */
    override fun onDestroy() {
        displayItems.forEach { item -> (item as? StackItem.Ad)?.ad?.destroy() }
        adPool.forEach { it.destroy() }
        adPool.clear()
        super.onDestroy()
    }

    /**
     * One listener serves both the unseen-like badge and the filled hearts —
     * they're two readings of the same set of match documents, so a second
     * query would be redundant. Being live means a like arriving while the
     * screen is open updates the badge without a refresh.
     *
     * The liked set is added to, never replaced, so a heart filled
     * optimistically on tap isn't cleared by a snapshot that hasn't caught up.
     * (Multi-device staleness — an unlike on another device — isn't handled
     * here; it corrects on the next full load.)
     *
     * Scoped to onStart/onStop so it isn't running while backgrounded.
     */
    private fun observeMatches() {
        if (GuestPrefs.isGuest(this)) {
            showNotificationBadge(0)
            return
        }

        val selfUid = FirebaseAuth.getInstance().currentUser?.uid
        if (selfUid == null) {
            showNotificationBadge(0)
            return
        }

        matchesListener = firestore.collection(Match.COLLECTION)
            .whereArrayContains(Match.FIELD_USERS, selfUid)
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    Log.w(TAG, "Match listener failed", error)
                    showNotificationBadge(0)
                    return@addSnapshotListener
                }

                val matches = snapshot?.documents
                    ?.mapNotNull { Match.from(it) }
                    .orEmpty()

                showNotificationBadge(matches.count { it.isUnseenLikeFor(selfUid) })

                likedUserIds.addAll(
                    matches.filter { it.isLikeBy(selfUid) }
                        .mapNotNull { it.otherUserId(selfUid) }
                )
                binding.cardStack.rebindVisibleCards()
            }
    }

    private fun showNotificationBadge(count: Int) {
        if (count <= 0) {
            binding.tvNotificationBadge.visibility = View.GONE
        } else {
            binding.tvNotificationBadge.visibility = View.VISIBLE
            binding.tvNotificationBadge.text = count.toString()
        }
    }

    /**
     * Greeting follows the device clock. Set in onCreate rather than onStart —
     * the boundaries are hours apart, so re-evaluating on every resume would
     * cost more than it's worth.
     */
    private fun showGreeting() {
        val greeting = when (Calendar.getInstance().get(Calendar.HOUR_OF_DAY)) {
            in 5..11 -> R.string.home_greeting_morning
            in 12..16 -> R.string.home_greeting_afternoon
            in 17..20 -> R.string.home_greeting_evening
            // 21:00-04:59, which wraps midnight and so can't be a single range.
            else -> R.string.home_greeting_night
        }
        binding.tvGreeting.setText(greeting)
    }

    private fun showSignedInUser() {
        if (GuestPrefs.isGuest(this)) {
            binding.tvUserName.text = getString(R.string.guest_label)
            return
        }

        val user = FirebaseAuth.getInstance().currentUser
        binding.tvUserName.text = user?.displayName?.takeIf { it.isNotBlank() }
            ?: getString(R.string.default_greeting_name)
        user?.uid?.let { binding.ivMyAvatar.loadLocalProfilePhoto(this, it) }
    }

    // ----- Feed loading -----------------------------------------------------

    private fun loadMatches() {
        if (GuestPrefs.isGuest(this)) {
            showEmptyState(
                icon = R.drawable.ic_sparkle_heart,
                title = R.string.empty_home_guest_title,
                subtitle = R.string.empty_home_guest_subtitle
            )
            return
        }

        val uid = FirebaseAuth.getInstance().currentUser?.uid
        if (uid == null) {
            showEmptyState(
                    icon = R.drawable.ic_sparkle_heart,
                    title = R.string.empty_home_error_title,
                    subtitle = R.string.empty_home_error_subtitle
                )
            return
        }

        showLoading()
        firestore.collection(UserProfile.COLLECTION).document(uid).get()
            .addOnSuccessListener { selfDoc ->
                val self = UserProfile.from(selfDoc)
                val interestedIn = self.interestedIn
                if (interestedIn.isNullOrBlank()) {
                    showEmptyState(
                        icon = R.drawable.ic_sparkle_heart,
                        title = R.string.empty_home_title,
                        subtitle = R.string.empty_home_subtitle,
                        actionLabel = R.string.empty_action_adjust_filters,
                        onAction = ::openFilters
                    )
                } else {
                    // Blocked, passed and already-liked users are gathered
                    // before the feed query and filtered client-side — the
                    // same reasoning as the self filter: no composite index,
                    // and the sets are small.
                    loadFeedExclusions(firestore, uid) { excluded ->
                        queryMatches(interestedIn, uid, excluded, self.latitude, self.longitude)
                    }
                }
            }
            .addOnFailureListener { e ->
                Log.w(TAG, "Failed to load own profile for matching", e)
                showEmptyState(
                    icon = R.drawable.ic_sparkle_heart,
                    title = R.string.empty_home_error_title,
                    subtitle = R.string.empty_home_error_subtitle
                )
            }
    }

    private fun queryMatches(
        interestedIn: String,
        selfUid: String,
        excludedUids: Set<String>,
        myLat: Double?,
        myLon: Double?
    ) {
        firestore.collection(UserProfile.COLLECTION)
            .whereEqualTo(UserProfile.FIELD_GENDER, interestedIn)
            .get()
            .addOnSuccessListener { snapshot ->
                val candidates = snapshot.documents
                    .filter { it.id != selfUid && it.id !in excludedUids }
                    .mapNotNull { it.toMatchCard()?.withDistanceFrom(myLat, myLon) }

                // Premium accounts to the front of the stack; otherwise the
                // order the query returned is left as-is (withPremiumPriority
                // is a stable sort).
                val loaded = candidates
                    .filter { matchesActiveFilters(this, it, myLat, myLon) }
                    .withPremiumPriority()

                when {
                    // Distinguishes "nobody at all" from "nobody once your
                    // filters applied" — the second is fixable by the user, and
                    // pointing at the filters is the useful thing to say.
                    loaded.isNotEmpty() -> showCards(loaded)
                    candidates.isNotEmpty() ->
                        showEmptyState(
                            icon = R.drawable.ic_sparkle_heart,
                            title = R.string.empty_home_filtered_title,
                            subtitle = R.string.empty_home_filtered_subtitle,
                            actionLabel = R.string.empty_action_adjust_filters,
                            onAction = ::openFilters
                        )
                    else -> showEmptyState(
                        icon = R.drawable.ic_sparkle_heart,
                        title = R.string.empty_home_title,
                        subtitle = R.string.empty_home_subtitle,
                        actionLabel = R.string.empty_action_adjust_filters,
                        onAction = ::openFilters
                    )
                }
            }
            .addOnFailureListener { e ->
                Log.w(TAG, "Failed to query matches", e)
                showEmptyState(
                    icon = R.drawable.ic_sparkle_heart,
                    title = R.string.empty_home_error_title,
                    subtitle = R.string.empty_home_error_subtitle
                )
            }
    }

    /** Accent dot over the filter icon whenever anything differs from default. */
    private fun showFilterIndicator() {
        binding.filterDot.visibility =
            if (FilterPrefs.hasActiveFilters(this)) View.VISIBLE else View.GONE
    }

    // ----- Card stack -------------------------------------------------------

    /**
     * Binds a card view for the stack. Reuses the feed-card layout; the action
     * buttons drive the same swipes as gestures (see the reconciliation in the
     * commit message):
     *  - heart: like/unlike toggle — unlike stays in place, a fresh like flings
     *    the card right
     *  - pass / dismiss: fling left
     *  - chat: open the conversation, matching first if needed
     *  - more (⋮): report or block this user
     *  - card body: open the full profile
     */
    private fun bindCard(cardView: View, card: MatchCard) {
        val b = ItemMatchCardBinding.bind(cardView)

        b.ivCardAvatar.setImageResource(card.avatarRes)
        b.ivCardPhoto.setImageResource(card.photoRes)
        b.tvCardName.text = card.name
        b.onlineDot.root.bindOnlineDot(card.lastSeen)

        if (card.role.isBlank()) {
            b.tvCardRole.visibility = View.GONE
        } else {
            b.tvCardRole.visibility = View.VISIBLE
            b.tvCardRole.text = card.role
        }

        // Age/location on one line, the bio preview on its own beneath it —
        // each hidden independently, since a user may have either, both or
        // neither.
        val ageLocation = card.ageLocationLine(this)
        if (ageLocation == null) {
            b.tvCardBio.visibility = View.GONE
        } else {
            b.tvCardBio.visibility = View.VISIBLE
            b.tvCardBio.text = ageLocation
        }

        val bioPreview = card.bioPreview()
        if (bioPreview == null) {
            b.tvCardBioPreview.visibility = View.GONE
        } else {
            b.tvCardBioPreview.visibility = View.VISIBLE
            b.tvCardBioPreview.text = bioPreview
        }

        val intent = card.marriageIntentLine(this)
        if (intent == null) {
            b.tvCardIntent.visibility = View.GONE
        } else {
            b.tvCardIntent.visibility = View.VISIBLE
            b.tvCardIntent.text = intent
        }

        // Real distance when both this user and the viewer have coordinates;
        // the pill is hidden entirely otherwise rather than showing "0 km".
        val distanceBadge = card.distanceBadge()
        if (distanceBadge == null) {
            b.tvDistance.visibility = View.GONE
        } else {
            b.tvDistance.visibility = View.VISIBLE
            b.tvDistance.text = distanceBadge
        }

        b.btnLike.setImageResource(
            if (likedUserIds.contains(card.id)) R.drawable.ic_like_filled
            else R.drawable.ic_like_outline
        )

        b.root.setOnClickListener { startActivity(ProfileDetailActivity.intent(this, card.id)) }
        // Shallower than a button: the card is large, so the same 5% dip would
        // read as the whole screen moving.
        b.root.addPressScale(pressedScale = 0.97f)
        b.btnLike.setOnClickListener { onHeartTapped(card, b.btnLike) }
        b.btnPass.setOnClickListener { binding.cardStack.swipeLeft() }
        b.btnDismiss.setOnClickListener { binding.cardStack.swipeLeft() }
        b.btnChat.setOnClickListener { openChatWith(card) }
        b.btnMore.setOnClickListener {
            // Blocking dismisses the (top) card the menu was opened from.
            showReportBlockSheet(card.id) { binding.cardStack.dismissTop() }
        }
    }

    /**
     * Grey heart -> like and advance (fling right). Red heart -> unlike in
     * place, so the heart is the one spot an already-liked person can be
     * un-liked without leaving the feed.
     */
    private fun onHeartTapped(card: MatchCard, heartButton: ImageButton) {
        if (likedUserIds.contains(card.id)) {
            unlikeInPlace(card, heartButton)
        } else {
            binding.cardStack.swipeRight()
        }
    }

    /** Called when a card is liked, by swipe or by the heart on a grey card. */
    /**
     * Records a pass so this person doesn't come back on the next load.
     *
     * Fired from [SwipeCardStackView.Listener.onSwipedLeft], which covers a
     * left swipe and both X buttons — they all call swipeLeft(). Blocking is
     * deliberately not routed here: it dismisses via dismissTop(), which
     * reports no swipe, and a block is already a stronger and permanent
     * exclusion.
     *
     * Nothing is awaited. The card has already gone, and a failed write only
     * means the profile can reappear in a later session — not worth
     * interrupting a run of swipes with an error.
     */
    private fun recordPass(card: MatchCard) {
        val selfUid = FirebaseAuth.getInstance().currentUser?.uid ?: return
        passUser(firestore, selfUid, card.id)
    }

    private fun likeUser(card: MatchCard) {
        val selfUid = FirebaseAuth.getInstance().currentUser?.uid ?: return

        // Not optimistic here the way the rest of this function used to be:
        // the daily-limit check needs a read before anything is written, so
        // likedUserIds/the card's departure only reflect a like that's
        // actually going through. The card has already swiped away by the
        // time this runs regardless — that's gesture feedback, not a promise
        // the like succeeded.
        likeUserRespectingDailyLimit(firestore, selfUid, card.id) { attempt ->
            when (attempt) {
                is LikeAttempt.DailyLimitReached -> showDailyLimitReachedSheet()
                is LikeAttempt.Started -> {
                    likedUserIds.add(card.id)
                    attempt.task.addOnFailureListener { e ->
                        logFirestoreWriteFailure(TAG, "Failed to like ${card.id}", e)
                        likedUserIds.remove(card.id)
                        toast(getString(R.string.error_match_failed))
                    }
                }
            }
        }
    }

    private fun showDailyLimitReachedSheet() {
        DailyLimitReachedBottomSheet.show(supportFragmentManager, DailyLimitReachedBottomSheet.Kind.LIKES)
    }

    override fun onUpgradeFromDailyLimitRequested() {
        startActivity(Intent(this, PaymentSubscriptionActivity::class.java))
    }

    private fun unlikeInPlace(card: MatchCard, heartButton: ImageButton) {
        val selfUid = FirebaseAuth.getInstance().currentUser?.uid ?: return

        // Optimistic grey; revert if the delete fails (e.g. offline).
        likedUserIds.remove(card.id)
        heartButton.setImageResource(R.drawable.ic_like_outline)

        deleteMatchDocument(firestore, selfUid, card.id)
            .addOnFailureListener { e ->
                Log.w(TAG, "Failed to unlike ${card.id}", e)
                likedUserIds.add(card.id)
                heartButton.setImageResource(R.drawable.ic_like_filled)
                toast(getString(R.string.error_unlike_failed))
            }
    }

    private fun openChatWith(card: MatchCard) {
        val selfUid = FirebaseAuth.getInstance().currentUser?.uid
        if (selfUid == null) {
            toast(getString(R.string.error_match_failed))
            return
        }

        matchExistsQuery(firestore, selfUid, card.id)
            .addOnSuccessListener { snapshot ->
                if (!snapshot.isEmpty) {
                    openChatThread(card)
                    return@addOnSuccessListener
                }
                likeUserRespectingDailyLimit(firestore, selfUid, card.id) { attempt ->
                    when (attempt) {
                        is LikeAttempt.DailyLimitReached -> showDailyLimitReachedSheet()
                        is LikeAttempt.Started -> attempt.task
                            .addOnSuccessListener {
                                likedUserIds.add(card.id)
                                openChatThread(card)
                            }
                            .addOnFailureListener { e ->
                                logFirestoreWriteFailure(TAG, "Failed to create match before chat", e)
                                toast(getString(R.string.error_match_failed))
                            }
                    }
                }
            }
            .addOnFailureListener { e ->
                Log.w(TAG, "Failed to check match state", e)
                toast(getString(R.string.error_match_failed))
            }
    }

    private fun openChatThread(card: MatchCard) {
        startActivity(ChatThreadActivity.intent(this, card.id, card.name))
    }

    // ----- Feed view state --------------------------------------------------

    /**
     * Skeleton rather than a spinner: the feed's shape is known, so showing
     * where the cards will be beats a dot on an empty screen and the layout
     * doesn't jump when they land.
     */
    private fun showLoading() {
        binding.progressLoading.visibility = View.GONE
        binding.emptyState.hide()
        binding.cardStack.visibility = View.GONE
        binding.skeletonFeed.showSkeleton(R.layout.item_skeleton_card, SKELETON_CARDS)
    }

    /**
     * [fade] is used when the empty state replaces something the user was just
     * looking at — the last card leaving the stack — so the screen doesn't
     * appear to blink. A first load has nothing to fade from.
     */
    private fun showEmptyState(
        @DrawableRes icon: Int,
        @StringRes title: Int,
        @StringRes subtitle: Int,
        @StringRes actionLabel: Int? = null,
        onAction: (() -> Unit)? = null,
        fade: Boolean = false
    ) {
        binding.skeletonFeed.hideSkeleton()
        binding.progressLoading.visibility = View.GONE
        binding.cardStack.visibility = View.GONE
        binding.emptyState.show(icon, title, subtitle, actionLabel, onAction)
        if (fade) binding.emptyState.fadeIn()
    }

    /** Opens the filter screen from the empty state's call to action. */
    private fun openFilters() {
        filterLauncher.launch(Intent(this, FilterActivity::class.java))
    }

    private fun showCards(loaded: List<MatchCard>) {
        if (GuestPrefs.isGuest(this) &&
            GuestPrefs.guestProfilesViewedToday(this) >= GuestPrefs.DAILY_PROFILE_VIEW_LIMIT
        ) {
            showGuestLimitReached()
            return
        }

        displayItems = buildDisplayItems(loaded)
        binding.progressLoading.visibility = View.GONE
        binding.emptyState.hide()

        // Set up before the crossfade so the first card is bound and drawn as
        // it fades in, rather than appearing a frame later.
        binding.cardStack.setup(
            layoutResFor = { position ->
                if (displayItems.getOrNull(position) is StackItem.Ad) R.layout.item_native_ad_card
                else R.layout.item_match_card
            },
            count = displayItems.size,
            listener = stackListener
        )
        binding.skeletonFeed.crossfadeToContent(binding.cardStack)
    }

    // ----- Native ads (free users only) --------------------------------------

    /**
     * Weaves a native ad in after every AD_INTERVALth real profile, using
     * only whatever's already sitting in [adPool] — never waiting on a fresh
     * load. If the pool is empty when a slot comes up (nothing preloaded yet,
     * or a run of failed loads), that slot is simply skipped: no gap, no
     * broken card, the next real profile follows immediately. A later reload
     * (e.g. applying filters) gets another chance once the pool has refilled.
     *
     * Premium is checked here, not just at insertion time elsewhere — this is
     * the single point every card the stack ever shows passes through, so
     * it's the one place that has to get "never for Premium" right.
     */
    private fun buildDisplayItems(profiles: List<MatchCard>): List<StackItem> {
        if (PremiumStatus.isPremium()) return profiles.map { StackItem.Profile(it) }

        val items = mutableListOf<StackItem>()
        profiles.forEachIndexed { index, card ->
            items += StackItem.Profile(card)
            if ((index + 1) % AD_INTERVAL == 0) {
                adPool.removeFirstOrNull()?.let { ad ->
                    items += StackItem.Ad(ad)
                    refillAdPool()
                }
            }
        }
        return items
    }

    /** Tops the pool back up to AD_POOL_TARGET, fire-and-forget. */
    private fun refillAdPool() {
        if (PremiumStatus.isPremium()) return
        while (adPool.size + adsInFlight < AD_POOL_TARGET) {
            adsInFlight++
            NativeAdLoader.loadAd(
                this,
                onLoaded = { ad -> adsInFlight--; adPool.addLast(ad) },
                onFailed = { adsInFlight-- }
            )
        }
    }

    private fun bindAdCard(cardView: View, ad: NativeAd) {
        val b = ItemNativeAdCardBinding.bind(cardView)
        val adView = b.root

        b.tvAdHeadline.text = ad.headline
        b.tvAdBody.text = ad.body
        b.tvAdBody.visibility = if (ad.body.isNullOrBlank()) View.GONE else View.VISIBLE
        b.btnAdCta.text = ad.callToAction
        b.btnAdCta.visibility = if (ad.callToAction.isNullOrBlank()) View.GONE else View.VISIBLE

        adView.headlineView = b.tvAdHeadline
        adView.bodyView = b.tvAdBody
        adView.callToActionView = b.btnAdCta
        adView.mediaView = b.adMedia
        adView.setNativeAd(ad)

        // The only tap-to-dismiss control an ad card gets; swiping already
        // works on any card regardless of content, since SwipeCardStackView
        // drags whatever's on top without knowing what it is.
        b.btnAdDismiss.setOnClickListener { binding.cardStack.swipeLeft() }
    }

    private fun toggleDarkMode() {
        val enabled = ThemePrefs.isDarkEnabled(this)
        // Recreates the activity so the new night-mode resources are applied.
        ThemePrefs.setDarkEnabled(this, !enabled)
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }
}
