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

    private companion object {
        /** Enough to fill the top of the list while the read is in flight. */
        const val SKELETON_ROWS = 3
    }

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
        val selfUid = FirebaseAuth.getInstance().realUid
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
                            createdAt = it.createdAt,
                            lastSeen = it.profile.lastSeen,
                            photoUrl = it.profile.photoUrl
                        )
                    }
                )
                markLikesSeen(firestore, selfUid, unseenMatchIds)
            },
            onError = { showNotifications(emptyList()) }
        )
    }

    private fun showLoading() {
        binding.progressLoading.visibility = View.GONE
        binding.emptyState.hide()
        binding.rvNotifications.visibility = View.GONE
        binding.skeletonNotifications.showSkeleton(
            R.layout.item_skeleton_notification_row, SKELETON_ROWS
        )
    }

    private fun showNotifications(items: List<NotificationItem>) {
        binding.progressLoading.visibility = View.GONE
        adapter.submitList(items)

        val isEmpty = items.isEmpty()
        if (isEmpty) {
            binding.skeletonNotifications.hideSkeleton()
            binding.rvNotifications.visibility = View.GONE
            binding.emptyState.show(
                R.drawable.ic_notification_bell,
                R.string.empty_notifications_title,
                R.string.empty_notifications_subtitle
            )
        } else {
            binding.emptyState.hide()
            binding.skeletonNotifications.crossfadeToContent(binding.rvNotifications)
        }
    }
}
