package com.wedora.app

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FirebaseFirestore
import com.wedora.app.databinding.ActivityHomeBinding

class HomeActivity : AppCompatActivity() {

    private companion object {
        const val TAG = "WedoraMatching"
    }

    private lateinit var binding: ActivityHomeBinding
    private val firestore: FirebaseFirestore by lazy { FirebaseFirestore.getInstance() }

    private val adapter by lazy {
        MatchCardAdapter(
            onCardClick = { startActivity(ProfileDetailActivity.intent(this, it.id)) },
            // Liking is a guest-gated action; passing/dismissing only affect the
            // local feed, so they stay available.
            onLike = { requireAccount { createMatchWith(it) } },
            onSuperlike = { requireAccount { createMatchWith(it) } },
            onPass = { toast("Passed on ${it.name}") },
            onChat = { requireAccount { openChatWith(it) } },
            onDismiss = { toast("Dismissed ${it.name}") },
            onMore = { toast("More options for ${it.name}") }
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityHomeBinding.inflate(layoutInflater)
        setContentView(binding.root)

        showSignedInUser()
        setUpFeed()
        loadMatches()
        setUpWedoraBottomNav(binding.bottomNav, R.id.nav_home)

        binding.btnDarkMode.setOnClickListener { toggleDarkMode() }

        binding.btnMenu.setOnClickListener {
            // TODO: open the navigation drawer / overflow menu
            toast(getString(R.string.cd_menu))
        }
    }

    /**
     * Greet the signed-in user by name and load their device-local avatar, if
     * any (see [LocalProfilePrefs] — there is no Firebase photoUrl to fall
     * back on, since profile photos are never uploaded).
     */
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

    private fun setUpFeed() {
        binding.rvMatches.layoutManager = LinearLayoutManager(this)
        binding.rvMatches.adapter = adapter
    }

    /**
     * Real matching feed: users whose `gender` matches the current user's own
     * `interestedIn`, excluding the current user. Self is filtered out
     * client-side rather than in the query, which avoids needing a composite
     * Firestore index for an early-stage, likely-low-volume collection.
     */
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
                    queryMatches(interestedIn, uid)
                }
            }
            .addOnFailureListener { e ->
                Log.w(TAG, "Failed to load own profile for matching", e)
                showEmptyState(getString(R.string.home_empty_error))
            }
    }

    private fun queryMatches(interestedIn: String, selfUid: String) {
        firestore.collection(UserProfile.COLLECTION)
            .whereEqualTo(UserProfile.FIELD_GENDER, interestedIn)
            .get()
            .addOnSuccessListener { snapshot ->
                val cards = snapshot.documents
                    .filter { it.id != selfUid }
                    .mapNotNull { it.toMatchCard() }

                if (cards.isEmpty()) {
                    showEmptyState(getString(R.string.home_empty_no_matches))
                } else {
                    showCards(cards)
                }
            }
            .addOnFailureListener { e ->
                Log.w(TAG, "Failed to query matches", e)
                showEmptyState(getString(R.string.home_empty_error))
            }
    }

    /**
     * Age/city/country are real, supplied by the other user's Complete Profile
     * step. Role/bio/distance still have no backend field, so they stay
     * blank/null (MatchCardAdapter hides or honestly labels them) rather than
     * inventing plausible-looking data, and the photo slots use a neutral
     * placeholder since there is no photo backend either.
     */
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

    private fun showLoading() {
        binding.progressLoading.visibility = View.VISIBLE
        binding.tvEmptyState.visibility = View.GONE
        binding.rvMatches.visibility = View.GONE
    }

    private fun showEmptyState(message: String) {
        binding.progressLoading.visibility = View.GONE
        binding.rvMatches.visibility = View.GONE
        binding.tvEmptyState.visibility = View.VISIBLE
        binding.tvEmptyState.text = message
    }

    private fun showCards(cards: List<MatchCard>) {
        binding.progressLoading.visibility = View.GONE
        binding.tvEmptyState.visibility = View.GONE
        binding.rvMatches.visibility = View.VISIBLE
        adapter.submitList(cards)
    }

    /**
     * Instant match: liking someone writes the match document straight away,
     * so there is no pending/one-sided like state. See [createMatchDocument].
     */
    private fun createMatchWith(card: MatchCard) {
        val selfUid = FirebaseAuth.getInstance().currentUser?.uid
        if (selfUid == null) {
            toast(getString(R.string.error_match_failed))
            return
        }

        createMatchDocument(firestore, selfUid, card.id)
            .addOnSuccessListener { toast(getString(R.string.match_created)) }
            .addOnFailureListener { e ->
                Log.w(TAG, "Failed to create match with ${card.id}", e)
                toast(getString(R.string.error_match_failed))
            }
    }

    /**
     * Opens the conversation with this user, creating the match first if there
     * isn't one. Checking first rather than always writing avoids a redundant
     * write — and avoids bumping an existing match's createdAt, which would
     * reshuffle the Chats list every time someone opened a chat.
     */
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
                            toast(getString(R.string.match_created))
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

    /**
     * Runs [action] for signed-in users. Guests are redirected to sign-up instead,
     * which is the single gate for every account-only feature on this screen.
     */
    private fun requireAccount(action: () -> Unit) {
        if (GuestPrefs.isGuest(this)) {
            Toast.makeText(this, R.string.guest_action_blocked, Toast.LENGTH_SHORT).show()
            startActivity(Intent(this, SignUpActivity::class.java))
        } else {
            action()
        }
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
