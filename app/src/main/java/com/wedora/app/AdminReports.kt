package com.wedora.app

import android.util.Log
import com.google.android.gms.tasks.Task
import com.google.android.gms.tasks.Tasks
import com.google.firebase.Timestamp
import com.google.firebase.firestore.FieldPath
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.QuerySnapshot

private const val TAG = "WedoraAdmin"

/** Firestore caps whereIn values, so profile lookups go out in chunks. */
private const val WHERE_IN_CHUNK = 10

/**
 * Firestore layout and access for the admin report queue — see
 * AdminReportsActivity / AdminReportDetailActivity. Read/update access is
 * gated to [WedoraAdmin.UID] alone (see firestore.rules' own `isAdmin()`),
 * so every function here is only ever reached from a screen already gated
 * on that same UID (see ProfileActivity's hidden row) — a PERMISSION_DENIED
 * out of these would mean that gate was somehow bypassed, not a real path
 * anyone hits in practice.
 */
object AdminReports {
    const val STATUS_PENDING = "pending"
    const val STATUS_RESOLVED = "resolved"
    const val STATUS_DISMISSED = "dismissed"
    const val STATUS_BANNED = "banned"

    const val FIELD_STATUS = "status"
    const val FIELD_RESOLVED_AT = "resolvedAt"
    const val FIELD_ACTION_TAKEN = "actionTaken"
}

/**
 * One report, resolved to both the reported and reporting user's display
 * names.
 *
 * [status] reads as [AdminReports.STATUS_PENDING] when the field is absent
 * — every report written before this field existed — same "never silently
 * drop old data" reasoning used everywhere else in this app a field was
 * added after documents already existed (e.g. Match.parseSeenBy).
 */
data class AdminReport(
    val reportId: String,
    val reportedUid: String,
    val reportedName: String,
    val reporterUid: String,
    val reporterName: String,
    val reason: String,
    val status: String,
    val createdAt: Timestamp?
)

/**
 * Loads every report, newest first. A report whose reported-or-reporter
 * profile is missing or unnamed still shows, falling back to the raw UID as
 * its name — unlike the user-facing lists elsewhere in this app that quietly
 * drop an entry with no resolvable name, an admin reviewing abuse reports
 * needs to see every report that exists, including ones about an account
 * that's since been deleted.
 */
fun loadAdminReports(
    firestore: FirebaseFirestore,
    onResult: (List<AdminReport>) -> Unit,
    onError: () -> Unit
) {
    firestore.collection(Moderation.REPORTS_COLLECTION)
        .orderBy(Moderation.FIELD_REPORT_CREATED_AT, Query.Direction.DESCENDING)
        .get()
        .addOnSuccessListener { snapshot ->
            val docs = snapshot.documents
            if (docs.isEmpty()) {
                onResult(emptyList())
                return@addOnSuccessListener
            }

            val uids = docs.flatMap {
                listOfNotNull(
                    it.getString(Moderation.FIELD_REPORTED_UID),
                    it.getString(Moderation.FIELD_REPORTER_UID)
                )
            }.distinct()

            val tasks = uids.chunked(WHERE_IN_CHUNK).map { chunk ->
                firestore.collection(UserProfile.COLLECTION)
                    .whereIn(FieldPath.documentId(), chunk)
                    .get()
            }

            Tasks.whenAllSuccess<QuerySnapshot>(tasks)
                .addOnSuccessListener { snapshots ->
                    val namesByUid = snapshots.flatMap { it.documents }
                        .associate { doc ->
                            doc.id to UserProfile.from(doc).displayName?.takeIf { it.isNotBlank() }
                        }

                    val reports = docs.mapNotNull { doc ->
                        val reportedUid = doc.getString(Moderation.FIELD_REPORTED_UID)
                            ?: return@mapNotNull null
                        val reporterUid = doc.getString(Moderation.FIELD_REPORTER_UID)
                            ?: return@mapNotNull null
                        AdminReport(
                            reportId = doc.id,
                            reportedUid = reportedUid,
                            reportedName = namesByUid[reportedUid] ?: reportedUid,
                            reporterUid = reporterUid,
                            reporterName = namesByUid[reporterUid] ?: reporterUid,
                            reason = doc.getString(Moderation.FIELD_REASON).orEmpty(),
                            status = doc.getString(AdminReports.FIELD_STATUS)
                                ?: AdminReports.STATUS_PENDING,
                            createdAt = doc.getTimestamp(Moderation.FIELD_REPORT_CREATED_AT)
                        )
                    }
                    onResult(reports)
                }
                .addOnFailureListener { e ->
                    Log.w(TAG, "Failed to resolve report profiles", e)
                    onError()
                }
        }
        .addOnFailureListener { e ->
            Log.w(TAG, "Failed to load admin reports", e)
            onError()
        }
}

/** Dismiss: status only — nothing else about the report or the reported user changes. */
fun dismissReport(firestore: FirebaseFirestore, reportId: String): Task<Void> =
    firestore.collection(Moderation.REPORTS_COLLECTION).document(reportId)
        .update(
            AdminReports.FIELD_STATUS, AdminReports.STATUS_DISMISSED,
            AdminReports.FIELD_RESOLVED_AT, FieldValue.serverTimestamp()
        )

/**
 * Bans [reportedUid]: sets isBanned/banReason on their profile and marks
 * every *pending* report against them as resolved (status "banned") — a
 * report against them already dismissed or otherwise resolved stays as it
 * was, since this ban doesn't retroactively change what an admin already
 * decided about a separate incident.
 *
 * Re-reads the reports for [reportedUid] rather than trusting whatever
 * [AdminReportDetailActivity] had in memory, since that screen only ever
 * holds the single report the admin tapped into, not every report against
 * this person — this is the query that actually finds the rest. One batch
 * for the profile flag plus every report update, so a ban is never half-
 * applied.
 *
 * Disabling the Auth account itself — the part that actually stops the
 * banned user from signing in, rather than just recording that they should
 * be stopped — is a separate call to the disableUserAccount Cloud Function,
 * made by the caller after this succeeds (see
 * AdminReportDetailActivity.banUser).
 */
fun banUser(
    firestore: FirebaseFirestore,
    reportedUid: String,
    banReason: String,
    onResult: () -> Unit,
    onError: () -> Unit
) {
    firestore.collection(Moderation.REPORTS_COLLECTION)
        .whereEqualTo(Moderation.FIELD_REPORTED_UID, reportedUid)
        .get()
        .addOnSuccessListener { snapshot ->
            val batch = firestore.batch()
            batch.update(
                firestore.collection(UserProfile.COLLECTION).document(reportedUid),
                UserProfile.FIELD_IS_BANNED, true,
                UserProfile.FIELD_BAN_REASON, banReason
            )
            snapshot.documents.forEach { doc ->
                val status = doc.getString(AdminReports.FIELD_STATUS) ?: AdminReports.STATUS_PENDING
                if (status == AdminReports.STATUS_PENDING) {
                    batch.update(
                        doc.reference,
                        AdminReports.FIELD_STATUS, AdminReports.STATUS_BANNED,
                        AdminReports.FIELD_RESOLVED_AT, FieldValue.serverTimestamp()
                    )
                }
            }
            batch.commit()
                .addOnSuccessListener { onResult() }
                .addOnFailureListener { e ->
                    Log.w(TAG, "Ban write failed for $reportedUid", e)
                    onError()
                }
        }
        .addOnFailureListener { e ->
            Log.w(TAG, "Failed to load $reportedUid's reports before ban", e)
            onError()
        }
}

/**
 * Clears isBanned/banReason on [reportedUid]'s profile — the Firestore
 * half of an unban. Deliberately touches no report documents: an unban
 * doesn't retroactively un-decide whatever an admin already recorded
 * about the reports that led to it, the same "this doesn't rewrite
 * history" reasoning [banUser] itself follows.
 *
 * banReason is deleted outright rather than set to an empty string or
 * null — there's nothing left to explain once the ban it explained is
 * gone. Re-enabling the Auth account itself is a separate call to the
 * enableUserAccount Cloud Function, made by the caller after this
 * succeeds (see AdminReportDetailActivity.unbanReportedUser).
 */
fun unbanUser(firestore: FirebaseFirestore, reportedUid: String): Task<Void> =
    firestore.collection(UserProfile.COLLECTION).document(reportedUid)
        .update(
            UserProfile.FIELD_IS_BANNED, false,
            UserProfile.FIELD_BAN_REASON, FieldValue.delete()
        )
