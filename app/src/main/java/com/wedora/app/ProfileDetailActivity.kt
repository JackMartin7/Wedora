package com.wedora.app

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Toast
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

        binding.btnBack.setOnClickListener { finish() }

        binding.btnMore.setOnClickListener {
            // Blocking from here closes the profile — there's nothing left to
            // show once they're blocked.
            showReportBlockSheet(userId) { finish() }
        }

        // Passing is local-only: there is no `passes` collection, so a passed
        // user reappears in the feed on the next load.
        binding.btnPass.setOnClickListener { finish() }

        binding.btnLike.setOnClickListener { onHeartTapped() }
        binding.btnMessage.setOnClickListener { messageUser() }

        loadProfile()
        loadSelfCoordinatesThenApply()
        checkLikeState()
        observePresence()
        recordThisView()
    }

    private fun loadSelfCoordinatesThenApply() {
        loadSelfCoordinates(this, firestore) { lat, lon ->
            selfLat = lat
            selfLon = lon
            selfCoordsLoaded = true
            applyDistanceBadge()
        }
    }

    /**
     * Runs once both halves are in: [otherProfile] loaded and [selfCoordsLoaded].
     * Called from both loaders' success paths, so whichever finishes second is
     * the one that actually shows the badge.
     */
    private fun applyDistanceBadge() {
        val profile = otherProfile ?: return
        if (!selfCoordsLoaded) return

        val badge = distanceBadgeBetween(selfLat, selfLon, profile.latitude, profile.longitude)
        if (badge == null) {
            binding.tvDetailDistance.visibility = View.GONE
        } else {
            binding.tvDetailDistance.text = badge
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
     * Reads whether the current user has already liked this person: a match doc
     * exists AND its likedBy is me. A doc where they liked me (likedBy != me)
     * counts as not-yet-liked, so the heart is grey and the user can like back.
     *
     * On failure it defaults to grey / "Like & Message": the create is
     * idempotent, so offering to like when a match already exists is harmless,
     * whereas showing red would let an unlike fire against a doc that may not be
     * deletable by this user.
     */
    private fun checkLikeState() {
        val selfUid = FirebaseAuth.getInstance().realUid
        if (selfUid == null) {
            applyLikeState(false)
            return
        }
        matchExistsQuery(firestore, selfUid, userId)
            .addOnSuccessListener { snapshot ->
                val liked = snapshot.documents.firstOrNull()
                    ?.let { Match.from(it)?.isLikeBy(selfUid) } == true
                applyLikeState(liked)
            }
            .addOnFailureListener { e ->
                Log.w(TAG, "Failed to check like state for $userId", e)
                applyLikeState(false)
            }
    }

    /** Reflects [liked] into the heart icon and the Message button label. */
    private fun applyLikeState(liked: Boolean) {
        hasLiked = liked

        binding.likeLoading.visibility = View.GONE
        binding.btnLike.visibility = View.VISIBLE
        binding.btnLike.setImageResource(
            if (liked) R.drawable.ic_like_filled else R.drawable.ic_like_outline
        )

        binding.btnMessage.setText(
            if (liked) R.string.btn_message else R.string.btn_like_and_message
        )
        binding.btnMessage.visibility = View.VISIBLE
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
     * "Message" once liked -> straight to the thread. "Like & Message" -> like
     * first, then open. Both reuse the same match write as the heart.
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

        if (hasLiked == true) {
            openChatThread()
            return
        }

        binding.btnMessage.isEnabled = false
        likeUserRespectingDailyLimit(firestore, selfUid, userId) { attempt ->
            when (attempt) {
                is LikeAttempt.DailyLimitReached -> {
                    binding.btnMessage.isEnabled = true
                    DailyLimitReachedBottomSheet.show(supportFragmentManager, DailyLimitReachedBottomSheet.Kind.LIKES)
                }
                is LikeAttempt.Started -> attempt.task
                    .addOnSuccessListener { openChatThread() }
                    .addOnFailureListener { e ->
                        logFirestoreWriteFailure(TAG, "Failed to create match before chat with $userId", e)
                        binding.btnMessage.isEnabled = true
                        toast(getString(R.string.error_match_failed))
                    }
            }
        }
    }

    private fun openChatThread() {
        startActivity(ChatThreadActivity.intent(this, userId, userName))
        finish()
    }

    private fun showProfile(name: String, profile: UserProfile) {
        userName = name
        otherProfile = profile
        binding.tvDetailName.text = name
        binding.ivDetailPhoto.loadRemoteProfilePhoto(profile.photoUrl)

        val line = formatAgeLocation(
            this,
            R.string.match_card_age_location_format,
            profile.age,
            profile.city,
            profile.country
        )
        if (line == null) {
            binding.tvDetailAgeLocation.visibility = View.GONE
        } else {
            binding.tvDetailAgeLocation.text = line
            binding.tvDetailAgeLocation.visibility = View.VISIBLE
        }

        val intentLine = MarriageIntent.summaryLine(this, profile.myStatus, profile.lookingFor)
        if (intentLine == null) {
            binding.tvDetailIntent.visibility = View.GONE
        } else {
            binding.tvDetailIntent.text = intentLine
            binding.tvDetailIntent.visibility = View.VISIBLE
        }

        val bio = profile.bio?.trim()
        if (bio.isNullOrEmpty()) {
            binding.tvDetailBio.visibility = View.GONE
        } else {
            binding.tvDetailBio.text = bio
            binding.tvDetailBio.visibility = View.VISIBLE
        }

        applyDistanceBadge()

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
