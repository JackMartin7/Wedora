package com.wedora.app

import android.os.Bundle
import android.view.View
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.wedora.app.databinding.ActivityAdminReportsBinding

/**
 * The admin report queue — reachable only via the hidden "Admin: Reports"
 * row ProfileActivity shows exclusively to [WedoraAdmin.UID] (see
 * setUpSettingsRows there). That gate is UI-only, so it's re-checked here on
 * every launch as defense in depth: anyone who reaches this Activity by some
 * other means (a deep link, adb, a saved task) without actually being
 * signed in as the admin is bounced immediately, before a single report
 * loads.
 */
class AdminReportsActivity : WedoraBaseActivity() {

    private lateinit var binding: ActivityAdminReportsBinding
    private val firestore: FirebaseFirestore by lazy { FirebaseFirestore.getInstance() }

    private val adapter = AdminReportsAdapter { report ->
        startActivity(AdminReportDetailActivity.intent(this, report))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (FirebaseAuth.getInstance().currentUser?.uid != WedoraAdmin.UID) {
            finish()
            return
        }

        binding = ActivityAdminReportsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.rvAdminReports.layoutManager = LinearLayoutManager(this)
        binding.rvAdminReports.adapter = adapter

        binding.btnBack.setOnClickListener { finish() }

        loadReports()
    }

    /**
     * Reloaded on every resume, not just onCreate — returning from a report's
     * detail screen (Dismiss or Ban both finish() back to this list) should
     * reflect that decision immediately rather than showing stale statuses
     * until the next cold open.
     */
    override fun onResume() {
        super.onResume()
        loadReports()
    }

    private fun loadReports() {
        showLoading()
        loadAdminReports(
            firestore,
            onResult = { reports ->
                if (reports.isEmpty()) showEmpty() else showReports(reports)
            },
            onError = { showError() }
        )
    }

    private fun showLoading() {
        binding.emptyState.hide()
        binding.rvAdminReports.visibility = View.GONE
        binding.progressLoading.visibility = View.VISIBLE
    }

    private fun showReports(reports: List<AdminReport>) {
        binding.progressLoading.visibility = View.GONE
        binding.emptyState.hide()
        binding.rvAdminReports.visibility = View.VISIBLE
        adapter.submitList(reports)
    }

    private fun showEmpty() {
        binding.progressLoading.visibility = View.GONE
        binding.rvAdminReports.visibility = View.GONE
        binding.emptyState.show(
            R.drawable.ic_sparkle_heart,
            R.string.admin_reports_empty_title,
            R.string.admin_reports_empty_subtitle
        )
    }

    private fun showError() {
        binding.progressLoading.visibility = View.GONE
        binding.rvAdminReports.visibility = View.GONE
        binding.emptyState.show(
            R.drawable.ic_sparkle_heart,
            R.string.admin_reports_error_title,
            R.string.admin_reports_error_subtitle
        )
    }
}
