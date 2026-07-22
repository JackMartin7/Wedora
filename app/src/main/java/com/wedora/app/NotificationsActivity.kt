package com.wedora.app

import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.wedora.app.databinding.ActivityNotificationsBinding

/**
 * Who liked you, as a list. Tapping a row opens that person's profile.
 *
 * Shares [loadReceivedLikes] / [markLikesSeen] with the Likes grid — same data,
 * different presentation — so opening either screen clears the Home badge.
 */
class NotificationsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityNotificationsBinding
    private val firestore: FirebaseFirestore by lazy { FirebaseFirestore.getInstance() }

    private val adapter = NotificationsAdapter { item ->
        startActivity(ProfileDetailActivity.intent(this, item.likerUserId))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityNotificationsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnBack.setOnClickListener { finish() }

        binding.rvNotifications.layoutManager = LinearLayoutManager(this)
        binding.rvNotifications.adapter = adapter

        loadNotifications()
    }

    private fun loadNotifications() {
        val selfUid = FirebaseAuth.getInstance().currentUser?.uid
        if (selfUid == null) {
            showNotifications(emptyList())
            return
        }

        showLoading()
        loadReceivedLikes(
            firestore,
            selfUid,
            onResult = { likes, unseenMatchIds ->
                showNotifications(
                    likes.map {
                        NotificationItem(
                            matchId = it.matchId,
                            likerUserId = it.likerUserId,
                            likerName = it.likerName,
                            createdAt = it.createdAt
                        )
                    }
                )
                markLikesSeen(firestore, unseenMatchIds)
            },
            onError = { showNotifications(emptyList()) }
        )
    }

    private fun showLoading() {
        binding.progressLoading.visibility = View.VISIBLE
        binding.tvNotificationsEmpty.visibility = View.GONE
        binding.rvNotifications.visibility = View.GONE
    }

    private fun showNotifications(items: List<NotificationItem>) {
        binding.progressLoading.visibility = View.GONE

        val isEmpty = items.isEmpty()
        binding.tvNotificationsEmpty.visibility = if (isEmpty) View.VISIBLE else View.GONE
        binding.rvNotifications.visibility = if (isEmpty) View.GONE else View.VISIBLE

        adapter.submitList(items)
    }
}
