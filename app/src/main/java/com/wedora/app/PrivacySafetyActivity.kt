package com.wedora.app

import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.gms.tasks.Tasks
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldPath
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.QuerySnapshot
import com.google.firebase.firestore.SetOptions
import com.wedora.app.databinding.ActivityPrivacySafetyBinding

/**
 * Privacy & Safety: manage the block list and who is allowed to message you.
 *
 * The message setting is stored on the user's own document and enforced in
 * firestore.rules, which is what makes it a privacy control rather than a UI
 * preference — see the messages create rule.
 */
class PrivacySafetyActivity : WedoraBaseActivity() {

    private companion object {
        const val TAG = "WedoraPrivacy"

        /** Firestore caps whereIn values, so name lookups go out in chunks. */
        const val WHERE_IN_CHUNK = 10
    }

    private lateinit var binding: ActivityPrivacySafetyBinding
    private val firestore: FirebaseFirestore by lazy { FirebaseFirestore.getInstance() }

    private lateinit var selfUid: String

    private val adapter = BlockedUserAdapter { user -> confirmUnblock(user) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPrivacySafetyBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applyEdgeInsets(binding.root)

        val uid = FirebaseAuth.getInstance().realUid
        if (uid == null) {
            Toast.makeText(this, R.string.error_generic_login, Toast.LENGTH_LONG).show()
            finish()
            return
        }
        selfUid = uid

        binding.btnBack.setOnClickListener { finish() }
        binding.rvBlockedUsers.layoutManager = LinearLayoutManager(this)
        binding.rvBlockedUsers.adapter = adapter

        loadBlockedUsers()
    }


    // ----- Blocked users --------------------------------------------------

    /**
     * Reads the block list, then resolves each UID to a display name.
     *
     * Unlike the feed's [loadFeedExclusions], this fails *closed* — a read
     * error shows an error rather than an empty list, because "you haven't
     * blocked anyone" is a claim about a safety setting and must not be shown
     * when it isn't known to be true.
     */
    private fun loadBlockedUsers() {
        showLoading()
        loadBlockedUserEntries(firestore, selfUid)
            .addOnSuccessListener { snapshot ->
                val uids = snapshot.documents.map { it.id }
                if (uids.isEmpty()) {
                    showEmpty()
                } else {
                    resolveNames(uids)
                }
            }
            .addOnFailureListener { e ->
                Log.w(TAG, "Failed to load block list", e)
                showError()
            }
    }

    /**
     * A blocked user whose profile is missing or unnamed still gets a row,
     * under a generic label — dropping it would leave an entry the user can
     * see the effects of but has no way to undo.
     */
    private fun resolveNames(uids: List<String>) {
        val nameTasks = uids.chunked(WHERE_IN_CHUNK).map { chunk ->
            firestore.collection(UserProfile.COLLECTION)
                .whereIn(FieldPath.documentId(), chunk)
                .get()
        }

        Tasks.whenAllSuccess<QuerySnapshot>(nameTasks)
            .addOnSuccessListener { snapshots ->
                val namesByUid = snapshots
                    .flatMap { it.documents }
                    .mapNotNull { doc ->
                        UserProfile.from(doc).displayName
                            ?.takeIf { it.isNotBlank() }
                            ?.let { doc.id to it }
                    }
                    .toMap()

                showBlocked(
                    uids.map { uid ->
                        BlockedUser(
                            uid = uid,
                            name = namesByUid[uid] ?: getString(R.string.blocked_user_unknown)
                        )
                    }
                )
            }
            .addOnFailureListener { e ->
                // The names failed, but the block list itself was read fine —
                // show the rows unnamed rather than hiding them, so unblocking
                // still works.
                Log.w(TAG, "Failed to resolve blocked user names", e)
                showBlocked(
                    uids.map { BlockedUser(it, getString(R.string.blocked_user_unknown)) }
                )
            }
    }

    private fun confirmUnblock(user: BlockedUser) {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.unblock_confirm_title)
            .setMessage(R.string.unblock_confirm_message)
            .setPositiveButton(R.string.action_unblock) { _, _ -> unblock(user) }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    /**
     * Removed from the list optimistically so the row disappears on tap, and
     * restored by a full reload if the delete fails — otherwise the UI would
     * claim someone is unblocked when they're still blocked.
     */
    private fun unblock(user: BlockedUser) {
        val remaining = adapter.currentList.filterNot { it.uid == user.uid }
        showBlocked(remaining)

        unblockUser(firestore, selfUid, user.uid)
            .addOnSuccessListener {
                Toast.makeText(this, R.string.unblock_success, Toast.LENGTH_SHORT).show()
            }
            .addOnFailureListener { e ->
                Log.w(TAG, "Failed to unblock ${user.uid}", e)
                Toast.makeText(this, R.string.unblock_failed, Toast.LENGTH_LONG).show()
                loadBlockedUsers()
            }
    }

    // ----- View state -----------------------------------------------------

    private fun showLoading() {
        binding.progressLoading.visibility = View.VISIBLE
        binding.rvBlockedUsers.visibility = View.GONE
        binding.emptyState.hide()
    }

    private fun showBlocked(users: List<BlockedUser>) {
        if (users.isEmpty()) {
            showEmpty()
            return
        }
        binding.progressLoading.visibility = View.GONE
        binding.emptyState.hide()
        binding.rvBlockedUsers.visibility = View.VISIBLE
        adapter.submitList(users)
    }

    private fun showEmpty() {
        binding.progressLoading.visibility = View.GONE
        binding.rvBlockedUsers.visibility = View.GONE
        binding.emptyState.show(
            R.drawable.ic_check_accent,
            R.string.empty_blocked_title,
            R.string.empty_blocked_subtitle
        )
    }

    /**
     * Fails closed, as before: an error is stated rather than shown as "you
     * haven't blocked anyone", which would be a claim about a safety setting
     * that isn't known to be true.
     */
    private fun showError() {
        binding.progressLoading.visibility = View.GONE
        binding.rvBlockedUsers.visibility = View.GONE
        binding.emptyState.show(
            R.drawable.ic_check_accent,
            R.string.empty_blocked_error_title,
            R.string.empty_blocked_error_subtitle
        )
    }
}
