package com.wedora.app

import androidx.appcompat.app.AppCompatActivity
import com.google.android.gms.tasks.Task
import com.google.firebase.firestore.DocumentReference
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.QuerySnapshot

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
 * Opens the Report / Block flow for [targetUid] as a bottom sheet: an actions
 * sheet first, then the reason picker or the block confirm.
 *
 * [onBlocked] runs after a successful block, so each screen can react (the feed
 * drops the card, the profile screen closes). It's delivered through a fragment
 * result rather than a captured lambda, so it survives the sheet — and the host
 * — being recreated mid-flow.
 *
 * Lives here so HomeActivity and ProfileDetailActivity share one implementation.
 */
fun AppCompatActivity.showReportBlockSheet(
    targetUid: String,
    onBlocked: () -> Unit
) {
    supportFragmentManager.setFragmentResultListener(
        BlockConfirmBottomSheet.RESULT_BLOCKED, this
    ) { _, _ -> onBlocked() }

    ReportBlockActionsBottomSheet.newInstance(targetUid)
        .show(supportFragmentManager, "report_block_actions")
}
