package com.wedora.app

import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.GridLayoutManager
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.wedora.app.databinding.ActivityLikesBinding

/**
 * The Likes tab: a 2-column photo grid of everyone who liked you.
 *
 * Same data as [NotificationsActivity] (via [loadReceivedLikes]), shown as a
 * grid rather than a list. Opening it marks those likes seen, so reaching them
 * from the tab clears the Home badge just as opening the bell does.
 */
class LikesActivity : AppCompatActivity() {

    private companion object {
        const val GRID_COLUMNS = 2
    }

    private lateinit var binding: ActivityLikesBinding
    private val firestore: FirebaseFirestore by lazy { FirebaseFirestore.getInstance() }

    private val adapter = LikesAdapter { like ->
        startActivity(ProfileDetailActivity.intent(this, like.likerUserId))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLikesBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.rvLikes.layoutManager = GridLayoutManager(this, GRID_COLUMNS)
        binding.rvLikes.adapter = adapter

        setUpWedoraBottomNav(binding.bottomNav, R.id.nav_match)

        loadLikes()
    }

    private fun loadLikes() {
        if (GuestPrefs.isGuest(this)) {
            showEmpty(getString(R.string.likes_guest))
            return
        }

        val selfUid = FirebaseAuth.getInstance().currentUser?.uid
        if (selfUid == null) {
            showEmpty(getString(R.string.likes_empty))
            return
        }

        showLoading()
        loadReceivedLikes(
            firestore,
            selfUid,
            onResult = { likes, unseenMatchIds ->
                if (likes.isEmpty()) {
                    showEmpty(getString(R.string.likes_empty))
                } else {
                    showLikes(likes)
                }
                // Marks seen even when the display list is empty but unseen ids
                // exist (all likers' profiles were missing), so the badge still
                // clears.
                markLikesSeen(firestore, unseenMatchIds)
            },
            onError = { showEmpty(getString(R.string.likes_empty)) }
        )
    }

    private fun showLoading() {
        binding.progressLoading.visibility = View.VISIBLE
        binding.tvLikesEmpty.visibility = View.GONE
        binding.rvLikes.visibility = View.GONE
    }

    private fun showEmpty(message: String) {
        binding.progressLoading.visibility = View.GONE
        binding.rvLikes.visibility = View.GONE
        binding.tvLikesEmpty.visibility = View.VISIBLE
        binding.tvLikesEmpty.text = message
    }

    private fun showLikes(likes: List<ReceivedLike>) {
        binding.progressLoading.visibility = View.GONE
        binding.tvLikesEmpty.visibility = View.GONE
        binding.rvLikes.visibility = View.VISIBLE
        adapter.submitList(likes)
    }
}
