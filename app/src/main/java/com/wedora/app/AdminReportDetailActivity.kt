package com.wedora.app

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Toast
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.functions.FirebaseFunctions
import com.wedora.app.databinding.ActivityAdminReportDetailBinding

/**
 * One report's full detail: the reported user's profile, the report itself,
 * and the two admin actions — Dismiss or Ban. Reachable only from
 * [AdminReportsActivity]'s own list, which is itself gated to
 * [WedoraAdmin.UID]; re-checked here too for the same defense-in-depth
 * reasoning as that screen's own onCreate guard.
 */
class AdminReportDetailActivity : WedoraBaseActivity() {

    companion object {
        private const val TAG = "WedoraAdmin"

        private const val EXTRA_REPORT_ID = "extra_report_id"
        private const val EXTRA_REPORTED_UID = "extra_reported_uid"
        private const val EXTRA_REPORTED_NAME = "extra_reported_name"
        private const val EXTRA_REPORTER_UID = "extra_reporter_uid"
        private const val EXTRA_REASON = "extra_reason"
        private const val EXTRA_STATUS = "extra_status"

        /** Plain extras rather than a Parcelable AdminReport — this app's convention (see e.g. ChatThreadActivity.intent). */
        fun intent(context: Context, report: AdminReport): Intent =
            Intent(context, AdminReportDetailActivity::class.java)
                .putExtra(EXTRA_REPORT_ID, report.reportId)
                .putExtra(EXTRA_REPORTED_UID, report.reportedUid)
                .putExtra(EXTRA_REPORTED_NAME, report.reportedName)
                .putExtra(EXTRA_REPORTER_UID, report.reporterUid)
                .putExtra(EXTRA_REASON, report.reason)
                .putExtra(EXTRA_STATUS, report.status)
    }

    private lateinit var binding: ActivityAdminReportDetailBinding
    private val firestore: FirebaseFirestore by lazy { FirebaseFirestore.getInstance() }

    private lateinit var reportId: String
    private lateinit var reportedUid: String
    private lateinit var reporterUid: String
    private lateinit var reason: String

    /**
     * Whether the reported user is currently banned — decides which of
     * btnBanUser/btnUnbanUser [showProfile] shows (never both). Defaults
     * to false (Ban shown) until the profile load resolves, and stays
     * false if that load fails — the same fail-open-to-reviewable
     * reasoning [loadReportedProfile] already documents; banning an
     * already-banned account is harmless and idempotent on both the
     * Firestore write and the Cloud Function call, so defaulting to
     * "show Ban" when the real state is unknown never makes things worse.
     */
    private var reportedIsBanned = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (FirebaseAuth.getInstance().currentUser?.uid != WedoraAdmin.UID) {
            finish()
            return
        }

        reportId = intent.getStringExtra(EXTRA_REPORT_ID).orEmpty()
        reportedUid = intent.getStringExtra(EXTRA_REPORTED_UID).orEmpty()
        reporterUid = intent.getStringExtra(EXTRA_REPORTER_UID).orEmpty()
        reason = intent.getStringExtra(EXTRA_REASON).orEmpty()
        if (reportId.isEmpty() || reportedUid.isEmpty()) {
            finish()
            return
        }

        binding = ActivityAdminReportDetailBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applyEdgeInsets(binding.root)

        binding.btnBack.setOnClickListener { finish() }

        binding.tvReason.text = reason
        binding.tvReporterUid.text = reporterUid
        bindStatus(binding.tvStatus, intent.getStringExtra(EXTRA_STATUS) ?: AdminReports.STATUS_PENDING)
        // Shown immediately from the tapped row rather than waiting on the
        // profile load below, same reasoning as ProfileDetailActivity
        // showing its own passed-in name before the fresh read lands.
        binding.tvReportedName.text = intent.getStringExtra(EXTRA_REPORTED_NAME).orEmpty()

        binding.btnDismiss.setOnClickListener { onDismissTapped() }
        binding.btnBanUser.setOnClickListener { onBanTapped() }
        binding.btnUnbanUser.setOnClickListener { onUnbanTapped() }

        loadReportedProfile()
    }

    private fun loadReportedProfile() {
        showLoading()
        firestore.collection(UserProfile.COLLECTION).document(reportedUid).get()
            .addOnSuccessListener { snapshot ->
                showProfile(UserProfile.from(snapshot))
            }
            .addOnFailureListener { e ->
                // Fails open to the report itself still being reviewable —
                // an admin needs to be able to act on a report even if the
                // reported account's profile has since been deleted.
                Log.w(TAG, "Failed to load reported user's profile for $reportedUid", e)
                showProfile(null)
            }
    }

    private fun showLoading() {
        binding.progressLoading.visibility = View.VISIBLE
        binding.scrollContent.visibility = View.GONE
        binding.actionRow.visibility = View.GONE
    }

    private fun showProfile(profile: UserProfile?) {
        binding.progressLoading.visibility = View.GONE
        binding.scrollContent.visibility = View.VISIBLE
        binding.actionRow.visibility = View.VISIBLE
        // Defensive reset, not a fix for a confirmed cause: every action's
        // own tap handler already disables the row for the duration of its
        // write and re-enables on failure, and a fresh onCreate/View
        // inflation starts enabled by default regardless — but resetting
        // explicitly here means a freshly (re)loaded screen can never be
        // stuck disabled by some earlier state this screen didn't itself
        // set, whatever the cause.
        setActionsEnabled(true)

        reportedIsBanned = profile?.isBanned == true
        binding.btnBanUser.visibility = if (reportedIsBanned) View.GONE else View.VISIBLE
        binding.btnUnbanUser.visibility = if (reportedIsBanned) View.VISIBLE else View.GONE

        if (profile == null) return

        profile.displayName?.takeIf { it.isNotBlank() }?.let { binding.tvReportedName.text = it }
        binding.ivReportedPhoto.loadRemoteProfilePhoto(profile.photoUrl)

        val ageLocation = formatAgeLocation(
            this, R.string.match_card_age_location_format, profile.age, profile.city, profile.country
        )
        if (ageLocation == null) {
            binding.tvReportedAgeLocation.visibility = View.GONE
        } else {
            binding.tvReportedAgeLocation.text = ageLocation
            binding.tvReportedAgeLocation.visibility = View.VISIBLE
        }

        val intentLine = MarriageIntent.summaryLine(this, profile.myStatus, profile.lookingFor)
        if (intentLine == null) {
            binding.tvReportedIntent.visibility = View.GONE
        } else {
            binding.tvReportedIntent.text = intentLine
            binding.tvReportedIntent.visibility = View.VISIBLE
        }

        val bio = profile.bio?.trim()
        if (bio.isNullOrEmpty()) {
            binding.tvReportedBio.visibility = View.GONE
        } else {
            binding.tvReportedBio.text = bio
            binding.tvReportedBio.visibility = View.VISIBLE
        }
    }

    /** No confirmation — dismissing is low-stakes and reversible in spirit (the report just stops being pending). */
    private fun onDismissTapped() {
        setActionsEnabled(false)
        dismissReport(firestore, reportId)
            .addOnSuccessListener {
                Toast.makeText(this, R.string.admin_report_dismissed, Toast.LENGTH_SHORT).show()
                finish()
            }
            .addOnFailureListener { e ->
                logFirestoreWriteFailure(TAG, "Failed to dismiss report $reportId", e)
                setActionsEnabled(true)
                Toast.makeText(this, R.string.admin_action_failed, Toast.LENGTH_LONG).show()
            }
    }

    /** A ban is irreversible in effect (the account is locked out), so it gets a confirmation dialog first. */
    private fun onBanTapped() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.admin_ban_confirm_title)
            .setMessage(getString(R.string.admin_ban_confirm_message, binding.tvReportedName.text))
            .setNegativeButton(R.string.action_cancel, null)
            .setPositiveButton(R.string.admin_action_ban) { _, _ -> banReportedUser() }
            .show()
    }

    /**
     * Sets isBanned/banReason on the reported user's profile and resolves
     * every pending report against them (see [banUser]'s own doc comment),
     * then calls the disableUserAccount Cloud Function so the ban actually
     * blocks sign-in immediately rather than only being enforced the next
     * time [AuthRouting]'s isBanned safety-net check happens to run.
     */
    private fun banReportedUser() {
        setActionsEnabled(false)
        banUser(
            firestore, reportedUid, banReason = reason,
            onResult = {
                disableAuthAccount(reportedUid)
                Toast.makeText(this, R.string.admin_user_banned, Toast.LENGTH_SHORT).show()
                finish()
            },
            onError = {
                setActionsEnabled(true)
                Toast.makeText(this, R.string.admin_action_failed, Toast.LENGTH_LONG).show()
            }
        )
    }

    /**
     * Fire-and-forget: the admin's ban action is already complete and
     * confirmed to the UI by the time this is called — the Firestore write
     * in [banReportedUser] is what the app's own AuthRouting.isBanned check
     * enforces regardless of whether this call ever lands. A failure here
     * is logged, not surfaced, and doesn't reopen the actions or block
     * finish(): the account is already blocked from signing in on its next
     * attempt via that safety net, just not with the immediate
     * revokeRefreshTokens kick of any *currently* open session this
     * function would otherwise provide.
     */
    private fun disableAuthAccount(targetUid: String) {
        FirebaseFunctions.getInstance()
            .getHttpsCallable("disableUserAccount")
            .call(hashMapOf("targetUid" to targetUid))
            .addOnSuccessListener {
                Log.i(TAG, "disableUserAccount succeeded for $targetUid")
            }
            .addOnFailureListener { e ->
                Log.w(TAG, "disableUserAccount call failed for $targetUid; Firestore-level ban still applies", e)
            }
    }

    /** Same "confirm first, it changes account access" reasoning as [onBanTapped]. */
    private fun onUnbanTapped() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.admin_unban_confirm_title)
            .setMessage(getString(R.string.admin_unban_confirm_message, binding.tvReportedName.text))
            .setNegativeButton(R.string.action_cancel, null)
            .setPositiveButton(R.string.admin_action_unban) { _, _ -> unbanReportedUser() }
            .show()
    }

    /**
     * Clears isBanned/banReason (see [unbanUser]'s own doc comment), then
     * calls the enableUserAccount Cloud Function so the account can
     * actually sign in again — clearing the Firestore flag alone doesn't
     * do that; AuthRouting's isBanned check only ever blocks a sign-in,
     * it was never what disabled the Auth account being disabled in the
     * first place.
     */
    private fun unbanReportedUser() {
        setActionsEnabled(false)
        unbanUser(firestore, reportedUid)
            .addOnSuccessListener {
                enableAuthAccount(reportedUid)
                Toast.makeText(this, R.string.admin_user_unbanned, Toast.LENGTH_SHORT).show()
                finish()
            }
            .addOnFailureListener { e ->
                logFirestoreWriteFailure(TAG, "Failed to unban $reportedUid", e)
                setActionsEnabled(true)
                Toast.makeText(this, R.string.admin_action_failed, Toast.LENGTH_LONG).show()
            }
    }

    /**
     * Fire-and-forget, same reasoning as [disableAuthAccount]: the
     * Firestore write is what AuthRouting's isBanned check reads, so it's
     * already the source of truth for whether this account is allowed to
     * sign in going forward — a failure here just means the *Auth*
     * account itself stays disabled a while longer, not that the unban
     * silently failed.
     */
    private fun enableAuthAccount(targetUid: String) {
        FirebaseFunctions.getInstance()
            .getHttpsCallable("enableUserAccount")
            .call(hashMapOf("targetUid" to targetUid))
            .addOnSuccessListener {
                Log.i(TAG, "enableUserAccount succeeded for $targetUid")
            }
            .addOnFailureListener { e ->
                Log.w(TAG, "enableUserAccount call failed for $targetUid; Firestore-level unban still applies", e)
            }
    }

    private fun setActionsEnabled(enabled: Boolean) {
        binding.btnDismiss.isEnabled = enabled
        binding.btnBanUser.isEnabled = enabled
        binding.btnUnbanUser.isEnabled = enabled
    }
}
