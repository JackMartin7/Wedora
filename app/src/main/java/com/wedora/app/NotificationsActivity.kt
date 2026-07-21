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
import com.wedora.app.databinding.ActivityNotificationsBinding

/**
 * Who liked you. Tapping a row opens that person's profile.
 *
 * Opening the screen marks everything in it as seen, which is what clears the
 * badge on Home.
 */
class NotificationsActivity : AppCompatActivity() {

    private companion object {
        const val TAG = "WedoraNotifications"

        /** Firestore caps whereIn values, so profile lookups go out in chunks. */
        const val WHERE_IN_CHUNK = 10
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

    /**
     * Reads every match the user is in and keeps the ones somebody else
     * initiated.
     *
     * The `likedBy != me` half is applied client-side rather than as a query
     * filter: pairing an inequality with the array-contains would need a
     * composite index, and matches predating the likedBy field have no value
     * to compare against at all. Filtering here handles both — an
     * unattributable match is simply dropped.
     */
    private fun loadNotifications() {
        val selfUid = FirebaseAuth.getInstance().currentUser?.uid
        if (selfUid == null) {
            showNotifications(emptyList())
            return
        }

        showLoading()
        firestore.collection(Match.COLLECTION)
            .whereArrayContains(Match.FIELD_USERS, selfUid)
            .get()
            .addOnSuccessListener { snapshot ->
                val likes = snapshot.documents
                    .mapNotNull { Match.from(it) }
                    .filter { it.isLikeFor(selfUid) }
                    .sortedByDescending { it.createdAt?.toDate()?.time ?: Long.MAX_VALUE }

                if (likes.isEmpty()) {
                    showNotifications(emptyList())
                } else {
                    loadLikerProfiles(likes, selfUid)
                }
            }
            .addOnFailureListener { e ->
                Log.w(TAG, "Failed to load likes", e)
                showNotifications(emptyList())
            }
    }

    private fun loadLikerProfiles(likes: List<Match>, selfUid: String) {
        val likerUids = likes.mapNotNull { it.likedBy }.distinct()

        val profileTasks = likerUids.chunked(WHERE_IN_CHUNK).map { chunk ->
            firestore.collection(UserProfile.COLLECTION)
                .whereIn(FieldPath.documentId(), chunk)
                .get()
        }

        Tasks.whenAllSuccess<QuerySnapshot>(profileTasks)
            .addOnSuccessListener { snapshots ->
                val namesByUid = snapshots
                    .flatMap { it.documents }
                    .mapNotNull { doc ->
                        UserProfile.from(doc).displayName
                            ?.takeIf { it.isNotBlank() }
                            ?.let { doc.id to it }
                    }
                    .toMap()

                // A liker whose profile is missing or unnamed is dropped rather
                // than rendered as a blank row.
                val items = likes.mapNotNull { match ->
                    val likerUid = match.likedBy ?: return@mapNotNull null
                    val name = namesByUid[likerUid] ?: return@mapNotNull null
                    NotificationItem(
                        matchId = match.id,
                        likerUserId = likerUid,
                        likerName = name,
                        createdAt = match.createdAt
                    )
                }

                showNotifications(items)
                markAsSeen(likes.filter { it.isUnseenLikeFor(selfUid) })
            }
            .addOnFailureListener { e ->
                Log.w(TAG, "Failed to load liker profiles", e)
                showNotifications(emptyList())
            }
    }

    /**
     * Clears the Home badge by flipping seenByRecipient on everything just
     * shown, in one batched write.
     *
     * Marks them seen even for rows dropped above for a missing profile — the
     * user can't act on those, so leaving them counting toward the badge would
     * strand it at a number they could never clear.
     */
    private fun markAsSeen(unseen: List<Match>) {
        if (unseen.isEmpty()) return

        // Firestore caps a batch at 500 writes; a user reaching that many
        // unseen likes in one sitting is well beyond this stage.
        val batch = firestore.batch()
        unseen.forEach { match ->
            val ref = firestore.collection(Match.COLLECTION).document(match.id)
            batch.update(ref, Match.FIELD_SEEN_BY_RECIPIENT, true)
        }

        batch.commit().addOnFailureListener { e ->
            // Non-fatal: the notifications are on screen either way, the badge
            // just doesn't clear until this succeeds on a later visit.
            Log.w(TAG, "Failed to mark notifications seen", e)
        }
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
