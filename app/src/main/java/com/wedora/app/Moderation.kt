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
 * - contactShareAttempts/{id}: write-only from the client, admin-read (see
 *   [CONTACT_ATTEMPTS_COLLECTION]).
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

    /**
     * Blocked contact-sharing attempts (see [ContactShareDetector]) — a
     * signal feed for spotting repeat offenders, deliberately separate from
     * [REPORTS_COLLECTION].
     *
     * They're different things: a report is user-initiated, about a person's
     * behaviour generally, and carries a pending/resolved/banned workflow an
     * admin works through in AdminReportsActivity. These are
     * system-generated, about one specific message, append-only, and have no
     * resolution state. Folding them into reports would bury the real queue
     * under automated noise and skew its pending counts.
     *
     * Two limits worth knowing. This write comes from the client, so it only
     * captures users who hit the in-app warning — a modified client that
     * skips the UI check is stopped by firestore.rules but logs nothing,
     * because rules can't write. Capturing those would need sends routed
     * through a Cloud Function. And [FIELD_ATTEMPT_TEXT] stores the user's
     * own message indefinitely; there's no retention policy on it yet.
     */
    const val CONTACT_ATTEMPTS_COLLECTION = "contactShareAttempts"
    const val FIELD_ATTEMPT_SENDER_UID = "senderUid"
    const val FIELD_ATTEMPT_MATCH_ID = "matchId"
    const val FIELD_ATTEMPT_TEXT = "text"
    const val FIELD_ATTEMPT_CATEGORIES = "categories"
    const val FIELD_ATTEMPT_CREATED_AT = "createdAt"
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

/**
 * Records a blocked contact-sharing attempt. Fire-and-forget from the
 * caller's perspective — the message is already blocked whether or not this
 * write lands, so a failure is logged and otherwise ignored rather than
 * surfaced to the user, who would have no action to take about it.
 */
fun logContactShareAttempt(
    firestore: FirebaseFirestore,
    senderUid: String,
    matchId: String,
    text: String,
    categories: List<String>
): Task<DocumentReference> {
    val data = mapOf(
        Moderation.FIELD_ATTEMPT_SENDER_UID to senderUid,
        Moderation.FIELD_ATTEMPT_MATCH_ID to matchId,
        Moderation.FIELD_ATTEMPT_TEXT to text,
        Moderation.FIELD_ATTEMPT_CATEGORIES to categories,
        Moderation.FIELD_ATTEMPT_CREATED_AT to FieldValue.serverTimestamp()
    )
    return firestore.collection(Moderation.CONTACT_ATTEMPTS_COLLECTION).add(data)
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
