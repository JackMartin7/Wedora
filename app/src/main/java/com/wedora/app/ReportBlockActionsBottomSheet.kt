package com.wedora.app

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import com.wedora.app.databinding.ItemSheetOptionBinding
import com.wedora.app.databinding.SheetActionsBinding

/**
 * First step of the ⋮ menu, as a sheet: tappable Report and Block rows.
 *
 * Selecting a row dismisses this sheet and opens the next — the reason picker
 * for Report, the confirm sheet for Block. The target UID rides along in the
 * arguments so each step is self-contained.
 */
class ReportBlockActionsBottomSheet : WedoraBottomSheetDialog() {

    companion object {
        const val ARG_TARGET_UID = "arg_target_uid"

        fun newInstance(targetUid: String) = ReportBlockActionsBottomSheet().apply {
            arguments = Bundle().apply { putString(ARG_TARGET_UID, targetUid) }
        }
    }

    private var binding: SheetActionsBinding? = null

    private val targetUid: String
        get() = requireArguments().getString(ARG_TARGET_UID).orEmpty()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = SheetActionsBinding.inflate(inflater, container, false).also { binding = it }.root

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        addOption(R.drawable.ic_flag, R.string.action_report) {
            dismiss()
            ReportReasonBottomSheet.newInstance(targetUid)
                .show(parentFragmentManager, "report_reason")
        }
        addOption(R.drawable.ic_block, R.string.action_block) {
            dismiss()
            BlockConfirmBottomSheet.newInstance(targetUid)
                .show(parentFragmentManager, "block_confirm")
        }
        springIn(view)
    }

    private fun addOption(@DrawableRes icon: Int, @StringRes label: Int, onClick: () -> Unit) {
        val container = binding?.optionsContainer ?: return
        val row = ItemSheetOptionBinding.inflate(
            LayoutInflater.from(requireContext()), container, true
        )
        row.ivOptionIcon.setImageResource(icon)
        row.tvOptionLabel.setText(label)
        row.root.setOnClickListener { onClick() }
    }

    override fun onDestroyView() {
        binding = null
        super.onDestroyView()
    }
}
