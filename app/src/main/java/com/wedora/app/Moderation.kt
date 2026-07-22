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
import com.google.firebase.firestore.FirebaseFirestoreException

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
 * Reads the current user's block list for filtering the feed. Fails *open* —
 * an empty set on a read error — so a transient failure shows an unfiltered
 * feed rather than hiding everyone; the filter re-applies on the next load.
 */
fun loadBlockedUserIds(
    firestore: FirebaseFirestore,
    selfUid: String,
    onResult: (Set<String>) -> Unit
) {
    blockedUsersRef(firestore, selfUid).get()
        .addOnSuccessListener { snapshot -> onResult(snapshot.documents.map { it.id }.toSet()) }
        .addOnFailureListener { e ->
            Log.w(TAG, "Failed to load block list", e)
            onResult(emptySet())
        }
}

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
    val selfUid = FirebaseAuth.getInstance().currentUser?.uid
    if (selfUid == null) {
        Log.w(TAG, "[mod-debug] report aborted: no signed-in user")
        Toast.makeText(this, R.string.report_failed, Toast.LENGTH_LONG).show()
        return
    }
    Log.d(TAG, "[mod-debug] report reporter='$selfUid' reported='$targetUid' reason='$reason'")
    submitReport(FirebaseFirestore.getInstance(), selfUid, targetUid, reason)
        .addOnSuccessListener {
            Log.d(TAG, "[mod-debug] report SUCCESS")
            Toast.makeText(this, R.string.report_submitted, Toast.LENGTH_SHORT).show()
        }
        .addOnFailureListener { e ->
            // TEMPORARY: surface the error code to tell a rules rejection
            // (PERMISSION_DENIED) apart from connectivity. Remove once fixed.
            val code = (e as? FirebaseFirestoreException)?.code
            Log.e(TAG, "[mod-debug] report FAILED code=$code", e)
            Toast.makeText(this, "Report failed: ${code ?: e.javaClass.simpleName}", Toast.LENGTH_LONG).show()
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
    val selfUid = FirebaseAuth.getInstance().currentUser?.uid
    if (selfUid == null) {
        Log.w(TAG, "[mod-debug] block aborted: no signed-in user")
        Toast.makeText(this, R.string.block_failed, Toast.LENGTH_LONG).show()
        return
    }
    Log.d(TAG, "[mod-debug] block self='$selfUid' blocked='$targetUid' " +
        "path=${Moderation.BLOCKS_COLLECTION}/$selfUid/${Moderation.BLOCKED_USERS_SUBCOLLECTION}/$targetUid")
    blockUser(FirebaseFirestore.getInstance(), selfUid, targetUid)
        .addOnSuccessListener {
            Log.d(TAG, "[mod-debug] block SUCCESS")
            Toast.makeText(this, R.string.block_success, Toast.LENGTH_SHORT).show()
            onBlocked()
        }
        .addOnFailureListener { e ->
            // TEMPORARY: surface the error code. Remove once fixed.
            val code = (e as? FirebaseFirestoreException)?.code
            Log.e(TAG, "[mod-debug] block FAILED code=$code", e)
            Toast.makeText(this, "Block failed: ${code ?: e.javaClass.simpleName}", Toast.LENGTH_LONG).show()
        }
}
