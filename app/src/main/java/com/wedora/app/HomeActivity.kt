package com.wedora.app

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.ImageButton
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
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
        loadMatches()
        setUpWedoraBottomNav(binding.bottomNav, R.id.nav_home)

        binding.btnDarkMode.setOnClickListener { toggleDarkMode() }

        binding.btnNotifications.setOnClickListener {
            startActivity(Intent(this, NotificationsActivity::class.java))
        }

        // Shown to guests too — they're the likeliest upgrade, and the
        // subscription screen doesn't read any account data.
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
        // Guests are gated the same way the Profile tab gates them. Profile is
        // account-only however you reach it, so a second route to it can't be
        // the one that skips the check.
        binding.greetingContainer.setOnClickListener {
            if (GuestPrefs.isGuest(this)) {
                toast(getString(R.string.guest_action_blocked))
                startActivity(Intent(this, SignUpActivity::class.java))
                return@setOnClickListener
            }
            startActivity(Intent(this, ProfileActivity::class.java))
            finish()
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
                val candidates = snapshot.documents
                    .filter { it.id != selfUid && it.id !in blockedUids }
                    .mapNotNull { it.toMatchCard() }

                val loaded = candidates.filter { matchesFilters(it) }

                when {
                    // Distinguishes "nobody at all" from "nobody once your
                    // filters applied" — the second is fixable by the user, and
                    // pointing at the filters is the useful thing to say.
                    loaded.isNotEmpty() -> showCards(loaded)
                    candidates.isNotEmpty() ->
                        showEmptyState(getString(R.string.home_empty_filtered))
                    else -> showEmptyState(getString(R.string.home_empty_no_matches))
                }
            }
            .addOnFailureListener { e ->
                Log.w(TAG, "Failed to query matches", e)
                showEmptyState(getString(R.string.home_empty_error))
            }
    }

    /**
     * Client-side filtering, consistent with the existing self and block
     * filters — pairing more conditions onto the gender query would need
     * composite indexes, and an inequality on age would silently drop every
     * document that has no age rather than letting us decide.
     *
     * A card with no age is excluded, deliberately: an age filter that keeps
     * unknown ages isn't filtering by age. That's the one case where "handle
     * missing gracefully" and "respect what the user asked for" disagree, and
     * the user's request wins.
     *
     * Relationship type and distance are not applied — there is no
     * relationshipType field to compare and no coordinates to measure between.
     * They're stored in FilterPrefs, and the filter screen says so.
     */
    private fun matchesFilters(card: MatchCard): Boolean {
        val age = card.age ?: return false
        if (age < FilterPrefs.getAgeMin(this) || age > FilterPrefs.getAgeMax(this)) return false

        // Empty means "don't narrow" — there is no UI setting this yet, so it
        // stays empty and the gender query alone decides.
        val genders = FilterPrefs.getInterestedIn(this)
        return genders.isEmpty() || card.gender in genders
    }

    /** Accent dot over the filter icon whenever anything differs from default. */
    private fun showFilterIndicator() {
        binding.filterDot.visibility =
            if (FilterPrefs.hasActiveFilters(this)) View.VISIBLE else View.GONE
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
            bio = profile.bio.orEmpty(),
            age = profile.age,
            city = profile.city,
            country = profile.country,
            gender = profile.gender
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
                logMatchWriteFailure(TAG, "Failed to like ${card.id}", e)
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
                            logMatchWriteFailure(TAG, "Failed to create match before chat", e)
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
