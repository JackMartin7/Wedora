package com.wedora.app

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.ImageButton
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.wedora.app.databinding.ActivityHomeBinding
import com.wedora.app.databinding.ItemMatchCardBinding
import java.util.Calendar

class HomeActivity : AppCompatActivity() {

    private companion object {
        const val TAG = "WedoraMatching"
    }

    private lateinit var binding: ActivityHomeBinding
    private val firestore: FirebaseFirestore by lazy { FirebaseFirestore.getInstance() }

    /** Live view of the user's matches; drives the badge and the liked hearts. */
    private var matchesListener: ListenerRegistration? = null

    /** The feed, in swipe order. Bound into the card stack by position. */
    private var cards: List<MatchCard> = emptyList()

    /**
     * UIDs the user has liked. Seeded from Firestore (so already-liked people
     * show a filled heart on a fresh launch) and updated as they like/unlike.
     * Additive from the listener, per the reasoning in [observeMatches].
     */
    private val likedUserIds = mutableSetOf<String>()

    private val stackListener = object : SwipeCardStackView.Listener {
        override fun onBindCard(cardView: View, position: Int) {
            cards.getOrNull(position)?.let { bindCard(cardView, it) }
        }

        override fun onSwipedRight(position: Int) {
            cards.getOrNull(position)?.let { likeUser(it) }
        }

        override fun onSwipedLeft(position: Int) {
            // Pass: no Firestore write, the card is simply gone.
        }

        override fun onEmptied() {
            showEmptyState(getString(R.string.home_empty_all_swiped))
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityHomeBinding.inflate(layoutInflater)
        setContentView(binding.root)

        showGreeting()
        showSignedInUser()
        loadMatches()
        setUpWedoraBottomNav(binding.bottomNav, R.id.nav_home)

        binding.btnDarkMode.setOnClickListener { toggleDarkMode() }

        binding.btnNotifications.setOnClickListener {
            startActivity(Intent(this, NotificationsActivity::class.java))
        }
    }

    override fun onStart() {
        super.onStart()
        observeMatches()
    }

    override fun onStop() {
        matchesListener?.remove()
        matchesListener = null
        super.onStop()
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
            showEmptyState(getString(R.string.home_empty_guest))
            return
        }

        val uid = FirebaseAuth.getInstance().currentUser?.uid
        if (uid == null) {
            showEmptyState(getString(R.string.home_empty_error))
            return
        }

        showLoading()
        firestore.collection(UserProfile.COLLECTION).document(uid).get()
            .addOnSuccessListener { selfDoc ->
                val interestedIn = UserProfile.from(selfDoc).interestedIn
                if (interestedIn.isNullOrBlank()) {
                    showEmptyState(getString(R.string.home_empty_no_matches))
                } else {
                    // Block list is read before the feed query so blocked users
                    // are filtered out (client-side, same reasoning as the self
                    // filter — no composite index, and the block set is small).
                    loadBlockedUserIds(firestore, uid) { blocked ->
                        queryMatches(interestedIn, uid, blocked)
                    }
                }
            }
            .addOnFailureListener { e ->
                Log.w(TAG, "Failed to load own profile for matching", e)
                showEmptyState(getString(R.string.home_empty_error))
            }
    }

    private fun queryMatches(interestedIn: String, selfUid: String, blockedUids: Set<String>) {
        firestore.collection(UserProfile.COLLECTION)
            .whereEqualTo(UserProfile.FIELD_GENDER, interestedIn)
            .get()
            .addOnSuccessListener { snapshot ->
                val loaded = snapshot.documents
                    .filter { it.id != selfUid && it.id !in blockedUids }
                    .mapNotNull { it.toMatchCard() }

                if (loaded.isEmpty()) {
                    showEmptyState(getString(R.string.home_empty_no_matches))
                } else {
                    showCards(loaded)
                }
            }
            .addOnFailureListener { e ->
                Log.w(TAG, "Failed to query matches", e)
                showEmptyState(getString(R.string.home_empty_error))
            }
    }

    private fun DocumentSnapshot.toMatchCard(): MatchCard? {
        val profile = UserProfile.from(this)
        val name = profile.displayName?.takeIf { it.isNotBlank() } ?: return null
        return MatchCard(
            id = id,
            name = name,
            role = "",
            avatarRes = R.drawable.ic_avatar_placeholder,
            photoRes = R.drawable.ic_avatar_placeholder,
            distanceKm = null,
            bio = "",
            age = profile.age,
            city = profile.city,
            country = profile.country
        )
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

        if (card.role.isBlank()) {
            b.tvCardRole.visibility = View.GONE
        } else {
            b.tvCardRole.visibility = View.VISIBLE
            b.tvCardRole.text = card.role
        }

        b.tvCardBio.text = card.ageLocationLine(this)
            ?: card.bio.ifBlank { getString(R.string.match_card_no_bio) }

        if (card.distanceKm == null) {
            b.tvDistance.visibility = View.GONE
        } else {
            b.tvDistance.visibility = View.VISIBLE
            b.tvDistance.text = getString(R.string.home_distance_format, card.distanceKm.toString())
        }

        b.btnLike.setImageResource(
            if (likedUserIds.contains(card.id)) R.drawable.ic_like_filled
            else R.drawable.ic_like_outline
        )

        b.root.setOnClickListener { startActivity(ProfileDetailActivity.intent(this, card.id)) }
        b.btnLike.setOnClickListener { onHeartTapped(card, b.btnLike) }
        b.btnPass.setOnClickListener { binding.cardStack.swipeLeft() }
        b.btnDismiss.setOnClickListener { binding.cardStack.swipeLeft() }
        b.btnChat.setOnClickListener { openChatWith(card) }
        b.btnMore.setOnClickListener { view ->
            // Blocking dismisses the (top) card the menu was opened from.
            showReportBlockMenu(view, card.id) { binding.cardStack.dismissTop() }
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
    private fun likeUser(card: MatchCard) {
        val selfUid = FirebaseAuth.getInstance().currentUser?.uid ?: return

        // Optimistic: the card has already swiped away, so there's no heart to
        // update — just record the like and write it. No success toast: the
        // card leaving is the feedback.
        likedUserIds.add(card.id)
        createMatchDocument(firestore, selfUid, card.id)
            .addOnFailureListener { e ->
                Log.w(TAG, "Failed to create match with ${card.id}", e)
                likedUserIds.remove(card.id)
                toast(getString(R.string.error_match_failed))
            }
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
                } else {
                    createMatchDocument(firestore, selfUid, card.id)
                        .addOnSuccessListener {
                            likedUserIds.add(card.id)
                            openChatThread(card)
                        }
                        .addOnFailureListener { e ->
                            Log.w(TAG, "Failed to create match before chat", e)
                            toast(getString(R.string.error_match_failed))
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

    private fun showLoading() {
        binding.progressLoading.visibility = View.VISIBLE
        binding.tvEmptyState.visibility = View.GONE
        binding.cardStack.visibility = View.GONE
    }

    private fun showEmptyState(message: String) {
        binding.progressLoading.visibility = View.GONE
        binding.cardStack.visibility = View.GONE
        binding.tvEmptyState.visibility = View.VISIBLE
        binding.tvEmptyState.text = message
    }

    private fun showCards(loaded: List<MatchCard>) {
        cards = loaded
        binding.progressLoading.visibility = View.GONE
        binding.tvEmptyState.visibility = View.GONE
        binding.cardStack.visibility = View.VISIBLE
        binding.cardStack.setup(R.layout.item_match_card, loaded.size, stackListener)
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
