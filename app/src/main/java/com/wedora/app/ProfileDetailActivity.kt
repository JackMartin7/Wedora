package com.wedora.app

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.activity.addCallback
import androidx.annotation.StringRes
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.wedora.app.databinding.ActivityProfileDetailBinding

/**
 * Another user's profile, opened from a Home feed card.
 *
 * Takes only the user's UID and loads the profile fresh from Firestore, rather
 * than passing the already-loaded card fields through the Intent — one source
 * of truth, and the detail view can't show a stale copy of the feed's data.
 */
class ProfileDetailActivity : WedoraBaseActivity(), DailyLimitReachedBottomSheet.Host {

    companion object {
        private const val TAG = "WedoraMatching"
        private const val EXTRA_USER_ID = "extra_user_id"

        /** Keeps the extra key private to this screen. */
        fun intent(context: Context, userId: String): Intent =
            Intent(context, ProfileDetailActivity::class.java)
                .putExtra(EXTRA_USER_ID, userId)
    }

    private lateinit var binding: ActivityProfileDetailBinding
    private val firestore: FirebaseFirestore by lazy { FirebaseFirestore.getInstance() }

    private lateinit var userId: String

    /** Held so the chat thread can be opened with the right header name. */
    private var userName: String = ""

    /**
     * Set once [loadProfile] resolves, and combined with [selfLat]/[selfLon]
     * (order-independent — whichever of the two async reads lands second
     * calls [applyDistanceBadge]) into the distance line. Neither of these
     * screens' async loads is otherwise coupled, so this is the only point
     * where they need to know about each other.
     */
    private var otherProfile: UserProfile? = null
    private var selfLat: Double? = null
    private var selfLon: Double? = null
    private var selfCoordsLoaded = false

    /**
     * The VIEWER's own interests, for cross-referencing against the profile
     * being viewed. Free: loadSelfProfile is the same single read that was
     * already happening for the distance badge, which previously discarded
     * everything but the coordinates.
     *
     * Empty for a guest, who has no profile to compare against - the chips
     * then render plainly rather than claiming nothing is shared.
     */
    private var selfInterests: Set<String> = emptySet()

    /**
     * Whether the current user has liked this person (a match doc exists with
     * likedBy == me). Null until the check resolves — the heart shows a spinner
     * rather than guessing red or grey. Single source of truth for both the
     * heart icon and the Message button label.
     */
    private var hasLiked: Boolean? = null

    /**
     * Live rather than the one-time get() used on list screens: this is the
     * one screen where the viewer keeps looking at a single person, so it's
     * worth staying current on their presence while it's open.
     */
    private var presenceListener: ListenerRegistration? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityProfileDetailBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applyEdgeInsets(binding.root)

        val id = intent.getStringExtra(EXTRA_USER_ID)
        if (id.isNullOrBlank()) {
            Log.w(TAG, "Opened without a user id")
            toast(getString(R.string.error_profile_load_failed))
            finish()
            return
        }
        userId = id

        binding.btnBack.setOnClickListener { closeWithInterstitial() }
        onBackPressedDispatcher.addCallback(this) { closeWithInterstitial() }

        binding.btnMore.setOnClickListener {
            // Blocking from here closes the profile — there's nothing left to
            // show once they're blocked. Deliberately a plain finish(): an ad
            // in the middle of a moderation action is indefensible.
            showReportBlockSheet(userId) { finish() }
        }

        // Passing is local-only: there is no `passes` collection, so a passed
        // user reappears in the feed on the next load.
        binding.btnPass.setOnClickListener { closeWithInterstitial() }

        binding.btnLike.setOnClickListener { onHeartTapped() }
        binding.btnMessage.setOnClickListener { messageUser() }

        loadProfile()
        loadSelfProfileThenApply()
        checkLikeState()
        observePresence()
        recordThisView()
    }

    private fun loadSelfProfileThenApply() {
        loadSelfProfile(this, firestore) { self ->
            selfLat = self?.latitude
            selfLon = self?.longitude
            selfInterests = self?.interests?.toSet() ?: emptySet()
            selfCoordsLoaded = true
            applyDistanceBadge()
            applyInterests()
        }
    }

    /**
     * Runs once both halves are in: [otherProfile] loaded and [selfCoordsLoaded].
     * Called from both loaders' success paths, so whichever finishes second is
     * the one that actually shows the badge.
     */
    private fun applyInterests() {
        val profile = otherProfile ?: return
        // Same two-halves guard as applyDistanceBadge, and for the same
        // reason: this loader and loadProfile race, so whichever finishes
        // second is the one that actually renders. Without it the chips
        // would paint before selfInterests arrived and silently show no
        // shared highlighting at all.
        if (!selfCoordsLoaded) return

        if (profile.interests.isEmpty()) {
            binding.sectionInterests.visibility = View.GONE
            return
        }
        binding.sectionInterests.visibility = View.VISIBLE
        binding.chipsDetailInterests.setInterestsReadOnly(profile.interests, selfInterests)
    }

    private fun applyDistanceBadge() {
        val profile = otherProfile ?: return
        if (!selfCoordsLoaded) return

        val badge = distanceBadgeBetween(selfLat, selfLon, profile.latitude, profile.longitude)
        if (badge == null) {
            binding.tvDetailDistance.visibility = View.GONE
        } else {
            // "9775 km away" - the design phrases it, where the swipe card's
            // cramped pill shows the bare figure.
            binding.tvDetailDistance.text =
                getString(R.string.profile_detail_distance_away_format, badge)
            binding.tvDetailDistance.visibility = View.VISIBLE
        }
    }

    /**
     * Records that the signed-in user viewed this profile — skipped for a
     * guest (no stable UID worth recording, matching how the rest of the app
     * treats guest identity) and, inside recordProfileView itself, for
     * viewing your own profile.
     */
    private fun recordThisView() {
        FirebaseAuth.getInstance().currentUser
            ?.takeUnless { GuestPrefs.isGuest(this) }
            ?.uid
            ?.let { selfUid -> recordProfileView(firestore, userId, selfUid) }
    }

    /**
     * Fails open: a read error just leaves the dot hidden, same as an unknown
     * lastSeen.
     */
    private fun observePresence() {
        presenceListener = firestore.collection(UserProfile.COLLECTION).document(userId)
            .addSnapshotListener { snapshot, error ->
                if (error != null || snapshot == null) {
                    if (error != null) Log.w(TAG, "Presence listener failed for $userId", error)
                    return@addSnapshotListener
                }
                binding.onlineDot.root.bindOnlineDot(UserProfile.from(snapshot).lastSeen)
            }
    }

    override fun onDestroy() {
        presenceListener?.remove()
        super.onDestroy()
    }

    private fun loadProfile() {
        showLoading()
        firestore.collection(UserProfile.COLLECTION).document(userId).get()
            .addOnSuccessListener { snapshot ->
                val profile = UserProfile.from(snapshot)
                val name = profile.displayName?.takeIf { it.isNotBlank() }
                if (name == null) {
                    // No usable profile — the document is missing or has no name.
                    Log.w(TAG, "No displayName for user $userId")
                    toast(getString(R.string.error_profile_load_failed))
                    finish()
                    return@addOnSuccessListener
                }
                showProfile(name, profile)
            }
            .addOnFailureListener { e ->
                Log.w(TAG, "Failed to load profile $userId", e)
                toast(getString(R.string.error_profile_load_failed))
                finish()
            }
    }

    /**
     * Reads whether the current user has already liked this person, AND
     * whether a match document exists at all. These are different questions:
     * a match's `users` array — and so membership, and so chat access —
     * exists the moment EITHER side has liked, not only once it's mutual
     * (see firestore.rules' isValidNewMatch/isMatchMember). So if they liked
     * me first and I haven't liked back, [Match.isLikeBy] correctly says
     * false (the heart stays grey, since that's specifically about MY like),
     * but a match document — and therefore somewhere for Message to open —
     * already exists. [applyLikeState]'s two parameters keep those separate
     * rather than collapsing "matched" down to "I liked them."
     *
     * On failure it defaults to grey / no Message: the like create is
     * idempotent, so offering to like when a match already exists is harmless,
     * whereas showing red would let an unlike fire against a doc that may not
     * be deletable by this user, and showing Message with no confirmed match
     * would risk opening a thread the rules end up denying.
     */
    private fun checkLikeState() {
        val selfUid = FirebaseAuth.getInstance().realUid
        if (selfUid == null) {
            applyLikeState(liked = false, matchExists = false)
            return
        }
        matchExistsQuery(firestore, selfUid, userId)
            .addOnSuccessListener { snapshot ->
                val match = snapshot.documents.firstOrNull()?.let { Match.from(it) }
                applyLikeState(liked = match?.isLikeBy(selfUid) == true, matchExists = match != null)
            }
            .addOnFailureListener { e ->
                Log.w(TAG, "Failed to check like state for $userId", e)
                applyLikeState(liked = false, matchExists = false)
            }
    }

    /**
     * Reflects [liked] into the heart icon, and [matchExists] into whether
     * Message shows at all — see activity_profile_detail.xml's own comment on
     * btnMessage. [matchExists] defaults to [liked] because every OTHER
     * caller (likeUser/unlikeUser) is about this user's own like, which
     * always moves the two together: liking is what creates the match
     * document, unliking is what deletes it outright. Only [checkLikeState]
     * — the one place a match can exist for a reason other than something
     * this call did — passes both explicitly.
     */
    private fun applyLikeState(liked: Boolean, matchExists: Boolean = liked) {
        hasLiked = liked

        binding.likeLoading.visibility = View.GONE
        // The wrapper, not the button: the heart and its count share one pill
        // now, and showing the button alone would reveal a pill with an empty
        // slot beside it. See activity_profile_detail.xml.
        binding.likeControl.visibility = View.VISIBLE
        binding.btnLike.setImageResource(
            if (liked) R.drawable.ic_like_filled else R.drawable.ic_like_outline
        )

        binding.btnMessage.visibility = if (matchExists) View.VISIBLE else View.GONE
    }

    private fun onHeartTapped() {
        when (hasLiked) {
            true -> unlikeUser()
            false -> likeUser()
            null -> Unit // still resolving; the heart is a spinner, not tappable
        }
    }

    /**
     * Grey heart -> create the match, go red. No toast for a signed-in
     * failure: the heart is feedback. A guest gets a toast, since for them
     * there's no heart-state change to serve as feedback at all — realUid is
     * null before the write is even attempted, not after it fails.
     */
    private fun likeUser() {
        val selfUid = FirebaseAuth.getInstance().realUid
        if (selfUid == null) {
            if (GuestPrefs.isGuest(this)) redirectGuestToSignUp(R.string.guest_like_blocked)
            return
        }

        applyLikeState(true) // optimistic
        likeUserRespectingDailyLimit(firestore, selfUid, userId) { attempt ->
            when (attempt) {
                is LikeAttempt.DailyLimitReached -> {
                    applyLikeState(false)
                    DailyLimitReachedBottomSheet.show(supportFragmentManager, DailyLimitReachedBottomSheet.Kind.LIKES)
                }
                is LikeAttempt.Started -> attempt.task.addOnFailureListener { e ->
                    logFirestoreWriteFailure(TAG, "Failed to like $userId", e)
                    applyLikeState(false)
                    toast(getString(R.string.error_match_failed))
                }
            }
        }
    }

    override fun onUpgradeFromDailyLimitRequested() {
        startActivity(Intent(this, PaymentSubscriptionActivity::class.java))
    }

    override fun onWatchAdForBonusRequested(kind: DailyLimitReachedBottomSheet.Kind) {
        runRewardedBonusFlow(kind)
    }

    /**
     * Closes the screen, showing the between-screens interstitial first if
     * one is due — see [InterstitialAds] for the shared budget all three
     * trigger sources draw from.
     *
     * The ad has to go BEFORE finish(), not after: an Activity that has
     * already finished can't host one. finish() therefore runs from the
     * onClosed callback, which InterstitialAds guarantees fires exactly
     * once even when there's no ad to show — so this always closes.
     */
    private fun closeWithInterstitial() {
        if (InterstitialAds.onEvent(this, InterstitialAds.Trigger.PROFILE_CLOSE)) {
            InterstitialAds.show(this) { finish() }
        } else {
            finish()
        }
    }

    /** Red heart -> delete the match, go grey. */
    private fun unlikeUser() {
        val selfUid = FirebaseAuth.getInstance().realUid ?: return

        applyLikeState(false) // optimistic
        deleteMatchDocument(firestore, selfUid, userId)
            .addOnFailureListener { e ->
                Log.w(TAG, "Failed to unlike $userId", e)
                applyLikeState(true)
                toast(getString(R.string.error_unlike_failed))
            }
    }

    /**
     * Just opens the thread — no like-first fallback. Message is only ever
     * visible once [checkLikeState] has confirmed a match document exists
     * (see [applyLikeState]'s matchExists parameter), so by the time this can
     * be tapped there's always somewhere for it to open; liking is no longer
     * something tapping Message can trigger on the user's behalf.
     */
    private fun messageUser() {
        val selfUid = FirebaseAuth.getInstance().realUid
        if (selfUid == null) {
            if (GuestPrefs.isGuest(this)) {
                redirectGuestToSignUp(R.string.guest_chat_blocked)
            } else {
                toast(getString(R.string.error_match_failed))
            }
            return
        }

        openChatThread()
    }

    private fun openChatThread() {
        startActivity(ChatThreadActivity.intent(this, userId, userName))
        finish()
    }

    private fun showProfile(name: String, profile: UserProfile) {
        userName = name
        otherProfile = profile
        // Name and age on one line, overlaid on the photo. formatAgeLocation
        // is no longer used here: the design splits age onto the name line and
        // location onto its own, so the combined helper has nothing to combine.
        val age = profile.age
        binding.tvDetailName.text = if (age == null) {
            name
        } else {
            getString(R.string.profile_detail_name_age_format, name, age)
        }
        binding.ivDetailPhoto.loadRemoteProfilePhoto(profile.photoUrl)
        binding.tvLikeCount.text = formatCompactCount(profile.likesReceivedCount)

        val location = listOfNotNull(
            profile.city?.takeIf { it.isNotBlank() },
            profile.country?.takeIf { it.isNotBlank() }
        ).joinToString(", ")
        if (location.isEmpty()) {
            binding.tvDetailLocation.visibility = View.GONE
        } else {
            binding.tvDetailLocation.text = location
            binding.tvDetailLocation.visibility = View.VISIBLE
        }

        // Looking For and Status as two labelled columns, from the RAW fields.
        // MarriageIntent.summaryLine is deliberately not used: it wraps
        // lookingFor in "Looking for ..." and joins the pair, which under a
        // column already labelled LOOKING FOR would read as a stutter.
        //
        // Each column hides on its own, the divider only survives when both do,
        // and the card goes when neither does - otherwise an empty card with a
        // stray divider is left sitting under the photo.
        val looking = profile.lookingFor?.takeIf { it.isNotBlank() }
        val status = profile.myStatus?.takeIf { it.isNotBlank() }
        looking?.let { binding.tvDetailLookingFor.text = it }
        status?.let { binding.tvDetailStatus.text = it }
        binding.colLookingFor.visibility = if (looking == null) View.GONE else View.VISIBLE
        binding.colStatus.visibility = if (status == null) View.GONE else View.VISIBLE
        binding.intentDivider.visibility =
            if (looking != null && status != null) View.VISIBLE else View.GONE
        binding.cardIntent.visibility =
            if (looking == null && status == null) View.GONE else View.VISIBLE

        val bio = profile.bio?.trim()
        if (bio.isNullOrEmpty()) {
            binding.sectionAbout.visibility = View.GONE
        } else {
            binding.tvDetailBio.text = bio
            binding.sectionAbout.visibility = View.VISIBLE
        }

        applyDistanceBadge()
        applyInterests()

        binding.progressLoading.visibility = View.GONE
        binding.scrollContent.visibility = View.VISIBLE
        binding.actionRow.visibility = View.VISIBLE
    }

    private fun showLoading() {
        binding.progressLoading.visibility = View.VISIBLE
        binding.scrollContent.visibility = View.GONE
        binding.actionRow.visibility = View.GONE
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    /**
     * Toast-then-navigate, the same shape HomeActivity's own guest gates use
     * (see its own doc comment on redirectGuestToSignUp) — a guest reaching
     * this screen via a feed card tap and hitting Like or Message sees why,
     * then lands on Sign Up rather than the tap silently doing nothing.
     */
    private fun redirectGuestToSignUp(@StringRes message: Int) {
        toast(getString(message))
        startActivity(Intent(this, SignUpActivity::class.java))
    }
}
