package com.wedora.app

import android.os.Bundle
import android.view.View
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.wedora.app.databinding.ActivityMatchHistoryBinding

/**
 * Every match this user has, newest first — a plain historical list, distinct
 * from Chats (which is about conversations) and Likes (which is about who liked
 * whom). The row opens the profile; the "Message" pill opens the conversation.
 */
class MatchHistoryActivity : WedoraBaseActivity() {

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
        loadSelfCoordinates(this, firestore) { myLat, myLon ->
            loadMatchedUsers(
                firestore,
                selfUid,
                myLat, myLon,
                onResult = { users ->
                    val items = users.map {
                        MatchHistoryItem(
                            matchId = it.matchId,
                            otherUserId = it.otherUserId,
                            name = it.name,
                            matchedOn = it.createdAt?.toDate(),
                            lastSeen = it.lastSeen,
                            photoUrl = it.photoUrl,
                            distanceBadge = it.distanceBadge
                        )
                    }
                    if (items.isEmpty()) showEmpty() else showHistory(items)
                },
                onError = { showEmpty() }
            )
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
