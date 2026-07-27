package com.wedora.app

import android.content.res.ColorStateList
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.wedora.app.databinding.ItemAdminReportBinding

/** The admin report queue — see AdminReportsActivity. */
class AdminReportsAdapter(
    private val onClick: (AdminReport) -> Unit = {}
) : ListAdapter<AdminReport, AdminReportsAdapter.ReportViewHolder>(DIFF) {

    private companion object {
        val DIFF = object : DiffUtil.ItemCallback<AdminReport>() {
            override fun areItemsTheSame(a: AdminReport, b: AdminReport) = a.reportId == b.reportId
            override fun areContentsTheSame(a: AdminReport, b: AdminReport) = a == b
        }
    }

    inner class ReportViewHolder(
        private val binding: ItemAdminReportBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(report: AdminReport) = with(binding) {
            tvReportedName.text = report.reportedName
            tvReason.text = report.reason
            tvReporterAndTime.text = root.context.getString(
                R.string.admin_report_reporter_and_time,
                report.reporterName,
                formatRelativeShort(root.context, report.createdAt)
            )
            bindStatus(tvStatus, report.status)

            root.setOnClickListener { onClick(report) }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ReportViewHolder {
        val binding = ItemAdminReportBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return ReportViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ReportViewHolder, position: Int) {
        holder.bind(getItem(position))
    }
}

/**
 * Shared by the list row and the detail screen's own status pill: label
 * text + background tint per [AdminReports] status constant. Falls back to
 * the pending styling for anything unrecognized, rather than leaving the
 * pill blank — every report has *some* status once [loadAdminReports]
 * resolves a missing field to "pending", so this should only ever see one
 * of the four known values in practice.
 */
fun bindStatus(pill: android.widget.TextView, status: String) {
    val (labelRes, colorRes) = when (status) {
        AdminReports.STATUS_RESOLVED -> R.string.admin_status_resolved to R.color.wedora_success
        AdminReports.STATUS_DISMISSED -> R.string.admin_status_dismissed to R.color.wedora_text_secondary
        AdminReports.STATUS_BANNED -> R.string.admin_status_banned to R.color.wedora_error
        else -> R.string.admin_status_pending to R.color.wedora_premium_gold
    }
    pill.setText(labelRes)
    pill.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(pill.context, colorRes))
}
