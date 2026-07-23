package com.wedora.app

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.os.bundleOf
import androidx.fragment.app.FragmentManager
import com.wedora.app.databinding.SheetConfirmBinding

/**
 * "Delete X chat(s)?" confirmation sheet for Chats' multi-select delete.
 *
 * Not a [ConfirmBottomSheet] subclass because its title needs the selection
 * count, and [ConfirmBottomSheet] only takes fixed string resources — this
 * reuses the same sheet_confirm.xml layout directly instead.
 */
class DeleteChatsBottomSheet : WedoraBottomSheetDialog() {

    /** Implemented by the screen that shows this sheet (ChatsActivity). */
    interface Host {
        fun onDeleteChatsConfirmed()
    }

    private var binding: SheetConfirmBinding? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = SheetConfirmBinding.inflate(inflater, container, false)
        .also { binding = it }.root

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val b = binding ?: return
        val count = arguments?.getInt(ARG_COUNT) ?: 0

        b.ivSheetIcon.setImageResource(R.drawable.ic_delete)
        b.tvSheetTitle.text = resources.getQuantityString(
            R.plurals.delete_chats_sheet_title, count, count
        )
        b.tvSheetSubtitle.setText(R.string.delete_chats_sheet_subtitle)
        b.btnSheetPrimary.setText(R.string.delete_chats_sheet_confirm)

        b.btnSheetPrimary.addPressScale()
        b.btnSheetPrimary.setOnClickListener {
            (activity as? Host)?.onDeleteChatsConfirmed()
            dismiss()
        }
        b.btnSheetSecondary.setOnClickListener { dismiss() }

        springIn(view)
    }

    override fun onDestroyView() {
        binding = null
        super.onDestroyView()
    }

    companion object {
        private const val ARG_COUNT = "count"
        private const val TAG = "delete_chats"

        fun show(fragmentManager: FragmentManager, count: Int) {
            DeleteChatsBottomSheet()
                .apply { arguments = bundleOf(ARG_COUNT to count) }
                .show(fragmentManager, TAG)
        }
    }
}
