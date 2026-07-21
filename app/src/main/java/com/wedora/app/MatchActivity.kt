package com.wedora.app

import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.ImageView
import androidx.appcompat.app.AppCompatActivity
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldPath
import com.google.firebase.firestore.FirebaseFirestore
import com.wedora.app.databinding.ActivityMatchBinding

/**
 * The match "radar": the signed-in user in the middle, their matches scattered
 * around them. Tapping a match opens that user's profile.
 */
class MatchActivity : AppCompatActivity() {

    private companion object {
        const val TAG = "WedoraMatching"

        /**
         * The radar has eight fixed slots, so it shows at most eight matches.
         * Also keeps the profile lookup within Firestore's whereIn limit, which
         * caps at ten values.
         */
        const val MAX_VISIBLE_MATCHES = 8
    }

    private lateinit var binding: ActivityMatchBinding
    private val firestore: FirebaseFirestore by lazy { FirebaseFirestore.getInstance() }

    /** The eight surrounding slots, in layout order. */
    private val matchSlots: List<ImageView> by lazy {
        listOf(
            binding.ivMatch1, binding.ivMatch2, binding.ivMatch3, binding.ivMatch4,
            binding.ivMatch5, binding.ivMatch6, binding.ivMatch7, binding.ivMatch8
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMatchBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setUpWedoraBottomNav(binding.bottomNav, R.id.nav_match)
        showHeroAvatar()
        loadMatches()
    }

    /** The hero is the signed-in user, so their own device-local photo applies. */
    private fun showHeroAvatar() {
        FirebaseAuth.getInstance().currentUser?.uid?.let {
            binding.ivMatchHero.loadLocalProfilePhoto(this, it)
        }
    }

    private fun loadMatches() {
        if (GuestPrefs.isGuest(this)) {
            showEmpty(getString(R.string.match_empty_guest))
            return
        }

        val selfUid = FirebaseAuth.getInstance().currentUser?.uid
        if (selfUid == null) {
            showEmpty(getString(R.string.match_load_error))
            return
        }

        showLoading()
        firestore.collection(Match.COLLECTION)
            .whereArrayContains(Match.FIELD_USERS, selfUid)
            .get()
            .addOnSuccessListener { snapshot ->
                // Sorted newest-first client-side rather than with orderBy:
                // combining that with whereArrayContains would require a
                // composite Firestore index, which isn't worth it at this
                // volume. A just-written match has a null createdAt until the
                // server timestamp resolves, so those sort first.
                val otherUids = snapshot.documents
                    .mapNotNull { Match.from(it) }
                    .sortedByDescending { it.createdAt?.seconds ?: Long.MAX_VALUE }
                    .mapNotNull { it.otherUserId(selfUid) }
                    .distinct()
                    .take(MAX_VISIBLE_MATCHES)

                if (otherUids.isEmpty()) {
                    showEmpty(getString(R.string.match_empty))
                } else {
                    loadMatchedProfiles(otherUids)
                }
            }
            .addOnFailureListener { e ->
                Log.w(TAG, "Failed to load matches", e)
                showEmpty(getString(R.string.match_load_error))
            }
    }

    /**
     * Resolves the matched UIDs to profiles in one query. Profiles are loaded
     * rather than just rendering the UIDs so that a match whose user document
     * has gone missing is dropped — otherwise tapping it would open a profile
     * screen that immediately errors out.
     */
    private fun loadMatchedProfiles(uids: List<String>) {
        firestore.collection(UserProfile.COLLECTION)
            .whereIn(FieldPath.documentId(), uids)
            .get()
            .addOnSuccessListener { snapshot ->
                val matched = snapshot.documents.mapNotNull { doc ->
                    val name = UserProfile.from(doc).displayName?.takeIf { it.isNotBlank() }
                        ?: return@mapNotNull null
                    doc.id to name
                }

                if (matched.isEmpty()) {
                    showEmpty(getString(R.string.match_empty))
                } else {
                    showMatches(matched)
                }
            }
            .addOnFailureListener { e ->
                Log.w(TAG, "Failed to load matched profiles", e)
                showEmpty(getString(R.string.match_load_error))
            }
    }

    private fun showMatches(matched: List<Pair<String, String>>) {
        binding.progressLoading.visibility = View.GONE
        binding.tvMatchEmpty.visibility = View.GONE

        matchSlots.forEachIndexed { index, slot ->
            val entry = matched.getOrNull(index)
            if (entry == null) {
                slot.visibility = View.GONE
                slot.setOnClickListener(null)
            } else {
                val (uid, name) = entry
                slot.visibility = View.VISIBLE
                // No photo backend for other users, so the slot keeps its
                // placeholder image; the name carries identity for a11y.
                slot.contentDescription = name
                slot.setOnClickListener {
                    startActivity(ProfileDetailActivity.intent(this, uid))
                }
            }
        }
    }

    private fun showLoading() {
        binding.progressLoading.visibility = View.VISIBLE
        binding.tvMatchEmpty.visibility = View.GONE
        matchSlots.forEach { it.visibility = View.GONE }
    }

    private fun showEmpty(message: String) {
        binding.progressLoading.visibility = View.GONE
        binding.tvMatchEmpty.visibility = View.VISIBLE
        binding.tvMatchEmpty.text = message
        matchSlots.forEach { it.visibility = View.GONE }
    }
}
