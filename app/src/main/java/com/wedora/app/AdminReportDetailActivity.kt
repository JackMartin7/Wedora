package com.wedora.app

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.wedora.app.databinding.ActivityAdminReportDetailBinding

/**
 * One report's full detail: the reported user's profile, the report itself,
 * and the two admin actions — Dismiss or Ban. Reachable only from
 * [AdminReportsActivity]'s own list, which is itself gated to
 * [WedoraAdmin.UID]; re-checked here too for the same defense-in-depth
 * reasoning as that screen's own onCreate guard.
 */
class AdminReportDetailActivity : AppCompatActivity() {

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
     * every pending report against them — see [banUser]'s own doc comment.
     *
     * TODO (part 2): once disableUserAccount is deployed, call it here after
     * this succeeds, so the ban actually blocks sign-in rather than just
     * recording that it should. AuthRouting's own isBanned check (added
     * alongside this) is the safety net for exactly that gap in the
     * meantime — a banned user is signed back out on their next launch even
     * without the Cloud Function, just not immediately.
     */
    private fun banReportedUser() {
        setActionsEnabled(false)
        banUser(
            firestore, reportedUid, banReason = reason,
            onResult = {
                Toast.makeText(this, R.string.admin_user_banned, Toast.LENGTH_SHORT).show()
                finish()
            },
            onError = {
                setActionsEnabled(true)
                Toast.makeText(this, R.string.admin_action_failed, Toast.LENGTH_LONG).show()
            }
        )
    }

    private fun setActionsEnabled(enabled: Boolean) {
        binding.btnDismiss.isEnabled = enabled
        binding.btnBanUser.isEnabled = enabled
    }
}
