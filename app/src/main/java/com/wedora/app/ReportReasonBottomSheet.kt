package com.wedora.app

import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.wedora.app.databinding.SheetReportReasonBinding

/**
 * Second step of a report: pick a reason chip, then submit. Writes the report
 * directly (see [submitReport]) — reports are write-only from the client, so
 * there's nothing to read back.
 */
class ReportReasonBottomSheet : WedoraBottomSheetDialog() {

    companion object {
        const val ARG_TARGET_UID = "arg_target_uid"

        fun newInstance(targetUid: String) = ReportReasonBottomSheet().apply {
            arguments = Bundle().apply { putString(ARG_TARGET_UID, targetUid) }
        }
    }

    private var binding: SheetReportReasonBinding? = null

    private val targetUid: String
        get() = requireArguments().getString(ARG_TARGET_UID).orEmpty()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = SheetReportReasonBinding.inflate(inflater, container, false).also { binding = it }.root

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val b = binding ?: return
        val reasons = resources.getStringArray(R.array.report_reasons).toList()

        // Submit is disabled until a reason is chosen — a report with no reason
        // isn't actionable by whoever reviews it.
        b.chipsReasons.setOptions(reasons, selected = emptyList()) {
            b.btnSubmitReport.isEnabled = b.chipsReasons.selectedOption() != null
        }
        b.btnSubmitReport.isEnabled = false

        b.btnSubmitReport.addPressScale()
        b.btnSubmitReport.setOnClickListener {
            val reason = b.chipsReasons.selectedOption() ?: return@setOnClickListener
            submit(reason)
            dismiss()
        }
        springIn(view)
    }

    private fun submit(reason: String) {
        val selfUid = FirebaseAuth.getInstance().currentUser?.uid ?: return
        val context = requireContext().applicationContext
        submitReport(FirebaseFirestore.getInstance(), selfUid, targetUid, reason)
            .addOnSuccessListener {
                Toast.makeText(context, R.string.report_submitted, Toast.LENGTH_SHORT).show()
            }
            .addOnFailureListener { e ->
                Log.w("WedoraModeration", "Failed to submit report", e)
                Toast.makeText(context, R.string.report_failed, Toast.LENGTH_LONG).show()
            }
    }

    override fun onDestroyView() {
        binding = null
        super.onDestroyView()
    }
}
