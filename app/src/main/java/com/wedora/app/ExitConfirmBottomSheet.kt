package com.wedora.app

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.os.bundleOf
import androidx.fragment.app.FragmentManager
import com.wedora.app.databinding.SheetConfirmBinding

/**
 * "Are you sure you want to leave?" — shown on back-press from one of the
 * root tab screens instead of letting the app close outright (see
 * ExitConfirm.kt's setUpExitConfirmOnBackPress). Not a [ConfirmBottomSheet]
 * subclass because its title needs a live count baked in via
 * [android.content.res.Resources.getQuantityString] for two of its five
 * [Kind]s, and [ConfirmBottomSheet] only takes fixed string resources — same
 * reasoning as [DeleteChatsBottomSheet].
 *
 * "Exit" always does the exact same thing regardless of which screen showed
 * it (finish the current, already-task-root activity), so unlike most other
 * sheets in this app this one needs no per-host Host interface — the action
 * is self-contained here.
 */
class ExitConfirmBottomSheet : WedoraBottomSheetDialog() {

    /**
     * Which personalized title to show, in the priority order the caller
     * already resolved (see ExitConfirm.kt's resolveExitConfirmKind) — this
     * sheet only renders whichever one it's given, it doesn't decide.
     */
    enum class Kind { GUEST, UNSEEN_LIKES, UNREAD_MESSAGES, UNSWIPED_PROFILES, GENERIC }

    private var binding: SheetConfirmBinding? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = SheetConfirmBinding.inflate(inflater, container, false)
        .also { binding = it }.root

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val b = binding ?: return
        val kind = Kind.valueOf(requireArguments().getString(ARG_KIND)!!)
        val count = requireArguments().getInt(ARG_COUNT)

        b.ivSheetIcon.setImageResource(R.drawable.ic_sparkle_heart)
        b.tvSheetTitle.text = titleFor(kind, count)
        b.tvSheetSubtitle.setText(R.string.exit_confirm_subtitle)
        b.btnSheetPrimary.setText(R.string.exit_confirm_stay)
        b.btnSheetSecondary.setText(R.string.exit_confirm_exit)

        b.btnSheetPrimary.addPressScale()
        b.btnSheetPrimary.setOnClickListener { dismiss() }
        b.btnSheetSecondary.setOnClickListener {
            // finish() directly, not onBackPressedDispatcher — this is what
            // keeps Exit working now that setUpExitConfirmOnBackPress no
            // longer suppresses the prompt after a first exit. Routing
            // through the dispatcher here would re-enter that callback and
            // re-show this sheet.
            dismiss()
            activity?.finish()
        }

        springIn(view)
    }

    private fun titleFor(kind: Kind, count: Int): String = when (kind) {
        Kind.GUEST -> getString(R.string.exit_confirm_guest_title)
        Kind.UNSEEN_LIKES -> resources.getQuantityString(R.plurals.exit_confirm_likes_title, count, count)
        Kind.UNREAD_MESSAGES -> resources.getQuantityString(R.plurals.exit_confirm_messages_title, count, count)
        Kind.UNSWIPED_PROFILES -> getString(R.string.exit_confirm_unswiped_title)
        Kind.GENERIC -> getString(R.string.exit_confirm_generic_title)
    }

    override fun onDestroyView() {
        binding = null
        super.onDestroyView()
    }

    companion object {
        private const val ARG_KIND = "kind"
        private const val ARG_COUNT = "count"
        private const val TAG = "exit_confirm"

        fun show(fragmentManager: FragmentManager, kind: Kind, count: Int = 0) {
            ExitConfirmBottomSheet()
                .apply { arguments = bundleOf(ARG_KIND to kind.name, ARG_COUNT to count) }
                .show(fragmentManager, TAG)
        }
    }
}
