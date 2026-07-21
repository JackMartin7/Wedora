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

        binding.btnLike.setOnClickListener { likeUser() }

        loadProfile()
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

    private fun showProfile(name: String, profile: UserProfile) {
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

    /** Same instant-match write as the Home feed's like action. */
    private fun likeUser() {
        val selfUid = FirebaseAuth.getInstance().currentUser?.uid
        if (selfUid == null) {
            toast(getString(R.string.error_match_failed))
            return
        }

        binding.btnLike.isEnabled = false
        createMatchDocument(firestore, selfUid, userId)
            .addOnSuccessListener {
                toast(getString(R.string.match_created))
                finish()
            }
            .addOnFailureListener { e ->
                Log.w(TAG, "Failed to create match with $userId", e)
                binding.btnLike.isEnabled = true
                toast(getString(R.string.error_match_failed))
            }
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }
}
