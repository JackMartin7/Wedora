package com.wedora.app

import android.os.Bundle
import android.util.Log
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.gms.tasks.Tasks
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldPath
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.QuerySnapshot
import com.wedora.app.databinding.ActivityMatchHistoryBinding

/**
 * Every match this user has, newest first — a plain historical list, distinct
 * from Chats (which is about conversations) and Likes (which is about who liked
 * whom). The row opens the profile; the "Message" pill opens the conversation.
 */
class MatchHistoryActivity : AppCompatActivity() {

    private companion object {
        const val TAG = "WedoraMatchHistory"

        /** Firestore caps `whereIn` values, so name lookups go out in chunks. */
        const val WHERE_IN_CHUNK = 10
    }

    private lateinit var binding: ActivityMatchHistoryBinding
    private val firestore: FirebaseFirestore by lazy { FirebaseFirestore.getInstance() }

    private val adapter = MatchHistoryAdapter(
        onRowClick = { startActivity(ProfileDetailActivity.intent(this, it.otherUserId)) },
        onMessageClick = { startActivity(ChatThreadActivity.intent(this, it.otherUserId, it.name)) }
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMatchHistoryBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.rvHistory.layoutManager = LinearLayoutManager(this)
        binding.rvHistory.adapter = adapter

        binding.btnBack.setOnClickListener { finish() }

        loadHistory()
    }

    private fun loadHistory() {
        val selfUid = FirebaseAuth.getInstance().currentUser
            ?.takeUnless { GuestPrefs.isGuest(this) }?.uid
        if (selfUid == null) {
            showEmpty()
            return
        }

        showLoading()
        // Sorted client-side rather than with orderBy: pairing an orderBy on
        // createdAt with the array-contains would require a composite index,
        // and the per-user match set is small enough to sort in memory —
        // consistent with how Likes and Chats read the same collection.
        firestore.collection(Match.COLLECTION)
            .whereArrayContains(Match.FIELD_USERS, selfUid)
            .get()
            .addOnSuccessListener { snapshot ->
                val matches = snapshot.documents
                    .mapNotNull { Match.from(it) }
                    .sortedByDescending { it.createdAt?.toDate()?.time ?: Long.MAX_VALUE }

                if (matches.isEmpty()) showEmpty() else resolveNamesThenRender(matches, selfUid)
            }
            .addOnFailureListener { e ->
                Log.w(TAG, "Failed to load match history", e)
                showEmpty()
            }
    }

    private fun resolveNamesThenRender(matches: List<Match>, selfUid: String) {
        val otherUids = matches.mapNotNull { it.otherUserId(selfUid) }.distinct()
        if (otherUids.isEmpty()) {
            showEmpty()
            return
        }

        val tasks = otherUids.chunked(WHERE_IN_CHUNK).map { chunk ->
            firestore.collection(UserProfile.COLLECTION)
                .whereIn(FieldPath.documentId(), chunk)
                .get()
        }

        Tasks.whenAllSuccess<QuerySnapshot>(tasks)
            .addOnSuccessListener { snapshots ->
                val profilesByUid = snapshots.flatMap { it.documents }
                    .associate { it.id to UserProfile.from(it) }

                // A match whose profile is missing or unnamed is dropped rather
                // than rendered as a blank row.
                val items = matches.mapNotNull { match ->
                    val otherUid = match.otherUserId(selfUid) ?: return@mapNotNull null
                    val profile = profilesByUid[otherUid] ?: return@mapNotNull null
                    val name = profile.displayName?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                    MatchHistoryItem(
                        matchId = match.id,
                        otherUserId = otherUid,
                        name = name,
                        matchedOn = match.createdAt?.toDate(),
                        lastSeen = profile.lastSeen
                    )
                }

                if (items.isEmpty()) showEmpty() else showHistory(items)
            }
            .addOnFailureListener { e ->
                Log.w(TAG, "Failed to load matched profiles", e)
                showEmpty()
            }
    }

    private fun showLoading() {
        binding.emptyState.hide()
        binding.rvHistory.visibility = View.GONE
        binding.progressLoading.visibility = View.VISIBLE
    }

    private fun showHistory(items: List<MatchHistoryItem>) {
        binding.progressLoading.visibility = View.GONE
        binding.emptyState.hide()
        binding.rvHistory.visibility = View.VISIBLE
        adapter.submitList(items)
    }

    private fun showEmpty() {
        binding.progressLoading.visibility = View.GONE
        binding.rvHistory.visibility = View.GONE
        binding.emptyState.show(
            R.drawable.ic_sparkle_heart,
            R.string.empty_match_history_title,
            R.string.empty_match_history_subtitle
        )
    }
}
