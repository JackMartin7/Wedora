package com.wedora.app

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.wedora.app.databinding.ActivityProfileDetailBinding

/**
 * Another user's profile, opened from a Home feed card.
 *
 * Takes only the user's UID and loads the profile fresh from Firestore, rather
 * than passing the already-loaded card fields through the Intent — one source
 * of truth, and the detail view can't show a stale copy of the feed's data.
 */
class ProfileDetailActivity : AppCompatActivity() {

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
     * Whether the current user has liked this person (a match doc exists with
     * likedBy == me). Null until the check resolves — the heart shows a spinner
     * rather than guessing red or grey. Single source of truth for both the
     * heart icon and the Message button label.
     */
    private var hasLiked: Boolean? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityProfileDetailBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val id = intent.getStringExtra(EXTRA_USER_ID)
        if (id.isNullOrBlank()) {
            Log.w(TAG, "Opened without a user id")
            toast(getString(R.string.error_profile_load_failed))
            finish()
            return
        }
        userId = id

        binding.btnBack.setOnClickListener { finish() }

        // Passing is local-only: there is no `passes` collection, so a passed
        // user reappears in the feed on the next load.
        binding.btnPass.setOnClickListener { finish() }

        binding.btnLike.setOnClickListener { onHeartTapped() }
        binding.btnMessage.setOnClickListener { messageUser() }

        loadProfile()
        checkLikeState()
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
        val selfUid = FirebaseAuth.getInstance().currentUser?.uid
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

    /** Grey heart -> create the match, go red. No toast: the heart is feedback. */
    private fun likeUser() {
        val selfUid = FirebaseAuth.getInstance().currentUser?.uid ?: return

        applyLikeState(true) // optimistic
        createMatchDocument(firestore, selfUid, userId)
            .addOnFailureListener { e ->
                Log.w(TAG, "Failed to like $userId", e)
                applyLikeState(false)
                toast(getString(R.string.error_match_failed))
            }
    }

    /** Red heart -> delete the match, go grey. */
    private fun unlikeUser() {
        val selfUid = FirebaseAuth.getInstance().currentUser?.uid ?: return

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
        val selfUid = FirebaseAuth.getInstance().currentUser?.uid
        if (selfUid == null) {
            toast(getString(R.string.error_match_failed))
            return
        }

        if (hasLiked == true) {
            openChatThread()
            return
        }

        binding.btnMessage.isEnabled = false
        createMatchDocument(firestore, selfUid, userId)
            .addOnSuccessListener { openChatThread() }
            .addOnFailureListener { e ->
                Log.w(TAG, "Failed to create match before chat with $userId", e)
                binding.btnMessage.isEnabled = true
                toast(getString(R.string.error_match_failed))
            }
    }

    private fun openChatThread() {
        startActivity(ChatThreadActivity.intent(this, userId, userName))
        finish()
    }

    private fun showProfile(name: String, profile: UserProfile) {
        userName = name
        binding.tvDetailName.text = name

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
}
