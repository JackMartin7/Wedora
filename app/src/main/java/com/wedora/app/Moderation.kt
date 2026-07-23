package com.wedora.app

import android.util.Log
import android.view.View
import android.widget.PopupMenu
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.gms.tasks.Task
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.DocumentReference
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.QuerySnapshot

private const val TAG = "WedoraModeration"

/**
 * Firestore layout for reports and blocks.
 *
 * - reports/{id}: write-only from the client (an admin reviews them).
 * - blocks/{uid}/blockedUsers/{blockedUid}: each user owns their own block
 *   list, keyed by their UID.
 */
object Moderation {
    const val REPORTS_COLLECTION = "reports"
    const val FIELD_REPORTED_UID = "reportedUid"
    const val FIELD_REPORTER_UID = "reporterUid"
    const val FIELD_REASON = "reason"
    const val FIELD_REPORT_CREATED_AT = "createdAt"

    const val BLOCKS_COLLECTION = "blocks"
    const val BLOCKED_USERS_SUBCOLLECTION = "blockedUsers"
    const val FIELD_BLOCKED_AT = "blockedAt"
}

private fun blockedUsersRef(firestore: FirebaseFirestore, selfUid: String) =
    firestore.collection(Moderation.BLOCKS_COLLECTION)
        .document(selfUid)
        .collection(Moderation.BLOCKED_USERS_SUBCOLLECTION)

fun submitReport(
    firestore: FirebaseFirestore,
    reporterUid: String,
    reportedUid: String,
    reason: String
): Task<DocumentReference> {
    val data = mapOf(
        Moderation.FIELD_REPORTED_UID to reportedUid,
        Moderation.FIELD_REPORTER_UID to reporterUid,
        Moderation.FIELD_REASON to reason,
        Moderation.FIELD_REPORT_CREATED_AT to FieldValue.serverTimestamp()
    )
    return firestore.collection(Moderation.REPORTS_COLLECTION).add(data)
}

fun blockUser(
    firestore: FirebaseFirestore,
    selfUid: String,
    blockedUid: String
): Task<Void> =
    blockedUsersRef(firestore, selfUid)
        .document(blockedUid)
        .set(mapOf(Moderation.FIELD_BLOCKED_AT to FieldValue.serverTimestamp()))

/**
 * Removes [blockedUid] from [selfUid]'s block list. The rules scope the whole
 * subcollection to its owner, so a user can only ever unblock from their own
 * list.
 */
fun unblockUser(
    firestore: FirebaseFirestore,
    selfUid: String,
    blockedUid: String
): Task<Void> =
    blockedUsersRef(firestore, selfUid)
        .document(blockedUid)
        .delete()

/** The UIDs on [selfUid]'s block list, newest-blocked first where known. */
fun loadBlockedUserEntries(
    firestore: FirebaseFirestore,
    selfUid: String
): Task<QuerySnapshot> = blockedUsersRef(firestore, selfUid).get()

// ----- Shared report/block UI ----------------------------------------------

/**
 * Shows the ⋮ Report / Block menu anchored to [anchor], for the user [targetUid].
 * [onBlocked] runs after a successful block, so each screen can react (the feed
 * drops the card, the profile screen closes).
 *
 * Lives here so HomeActivity and ProfileDetailActivity share one implementation
 * rather than each wiring their own dialogs.
 */
fun AppCompatActivity.showReportBlockMenu(
    anchor: View,
    targetUid: String,
    onBlocked: () -> Unit
) {
    PopupMenu(this, anchor).apply {
        menu.add(0, MENU_REPORT, 0, R.string.action_report)
        menu.add(0, MENU_BLOCK, 1, R.string.action_block)
        setOnMenuItemClickListener { item ->
            when (item.itemId) {
                MENU_REPORT -> { showReportReasonDialog(targetUid); true }
                MENU_BLOCK -> { showBlockConfirmDialog(targetUid, onBlocked); true }
                else -> false
            }
        }
        show()
    }
}

private const val MENU_REPORT = 1
private const val MENU_BLOCK = 2

private fun AppCompatActivity.showReportReasonDialog(targetUid: String) {
    val reasons = resources.getStringArray(R.array.report_reasons)
    var selected = 0
    MaterialAlertDialogBuilder(this)
        .setTitle(R.string.report_reason_title)
        .setSingleChoiceItems(reasons, selected) { _, which -> selected = which }
        .setPositiveButton(R.string.report_submit) { _, _ ->
            submitReportFromUi(targetUid, reasons[selected])
        }
        .setNegativeButton(R.string.action_cancel, null)
        .show()
}

private fun AppCompatActivity.submitReportFromUi(targetUid: String, reason: String) {
    val selfUid = FirebaseAuth.getInstance().currentUser?.uid ?: return
    submitReport(FirebaseFirestore.getInstance(), selfUid, targetUid, reason)
        .addOnSuccessListener {
            Toast.makeText(this, R.string.report_submitted, Toast.LENGTH_SHORT).show()
        }
        .addOnFailureListener { e ->
            Log.w(TAG, "Failed to submit report", e)
            Toast.makeText(this, R.string.report_failed, Toast.LENGTH_LONG).show()
        }
}

private fun AppCompatActivity.showBlockConfirmDialog(targetUid: String, onBlocked: () -> Unit) {
    MaterialAlertDialogBuilder(this)
        .setTitle(R.string.block_confirm_title)
        .setMessage(R.string.block_confirm_message)
        .setPositiveButton(R.string.block_confirm_button) { _, _ ->
            blockFromUi(targetUid, onBlocked)
        }
        .setNegativeButton(R.string.action_cancel, null)
        .show()
}

private fun AppCompatActivity.blockFromUi(targetUid: String, onBlocked: () -> Unit) {
    val selfUid = FirebaseAuth.getInstance().currentUser?.uid ?: return
    blockUser(FirebaseFirestore.getInstance(), selfUid, targetUid)
        .addOnSuccessListener {
            Toast.makeText(this, R.string.block_success, Toast.LENGTH_SHORT).show()
            onBlocked()
        }
        .addOnFailureListener { e ->
            Log.w(TAG, "Failed to block user", e)
            Toast.makeText(this, R.string.block_failed, Toast.LENGTH_LONG).show()
        }
}
