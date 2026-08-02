package com.wedora.app

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.os.bundleOf
import androidx.fragment.app.FragmentManager
import com.wedora.app.databinding.ItemReactionEmojiBinding
import com.wedora.app.databinding.ItemSheetOptionBinding
import com.wedora.app.databinding.SheetMessageActionsBinding

/**
 * Long-press-a-message sheet: quick-react emoji row, then delete options.
 *
 * No emoji picker exists anywhere in this app — rather than build one, the
 * reaction row is a fixed 6-emoji set (WhatsApp's own default set), matching
 * "one reaction per user" simplicity. Tapping the emoji that's already this
 * user's reaction removes it; tapping any other one sets/replaces it — the
 * caller (ChatThreadActivity) decides which, since only it knows the
 * message's current reactions map.
 *
 * "Delete for everyone" only shows when [isOwnMessage] — mirrors the same
 * conditional-row approach ReportBlockActionsBottomSheet uses for its own
 * per-target row set. This is a UI convenience only; firestore.rules is what
 * actually enforces sender-only for that action.
 */
class MessageActionsBottomSheet : WedoraBottomSheetDialog() {

    /** Implemented by the screen that shows this sheet (ChatThreadActivity). */
    interface Host {
        fun onReactionPicked(messageId: String, emoji: String)
        fun onDeleteForMeRequested(messageId: String)
        fun onDeleteForEveryoneRequested(messageId: String)
    }

    private var binding: SheetMessageActionsBinding? = null

    private val messageId: String
        get() = requireArguments().getString(ARG_MESSAGE_ID).orEmpty()
    private val isOwnMessage: Boolean
        get() = requireArguments().getBoolean(ARG_IS_OWN_MESSAGE)
    private val currentReaction: String?
        get() = requireArguments().getString(ARG_CURRENT_REACTION)

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = SheetMessageActionsBinding.inflate(inflater, container, false)
        .also { binding = it }.root

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val b = binding ?: return

        QUICK_REACT_EMOJI.forEach { emoji ->
            val row = ItemReactionEmojiBinding.inflate(
                LayoutInflater.from(requireContext()), b.reactionRow, true
            )
            row.tvReactionEmoji.text = emoji
            // A subtle highlight on whichever emoji is already this user's
            // reaction, so the sheet shows current state, not just options.
            row.tvReactionEmoji.alpha = if (emoji == currentReaction) 1f else 0.55f
            row.tvReactionEmoji.setOnClickListener {
                (activity as? Host)?.onReactionPicked(messageId, emoji)
                dismiss()
            }
        }

        addOption(R.drawable.ic_delete, R.string.message_action_delete_for_me) {
            dismiss()
            (activity as? Host)?.onDeleteForMeRequested(messageId)
        }
        if (isOwnMessage) {
            addOption(R.drawable.ic_delete, R.string.message_action_delete_for_everyone) {
                dismiss()
                (activity as? Host)?.onDeleteForEveryoneRequested(messageId)
            }
        }

        springIn(view)
    }

    private fun addOption(icon: Int, label: Int, onClick: () -> Unit) {
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

    companion object {
        private const val ARG_MESSAGE_ID = "arg_message_id"
        private const val ARG_IS_OWN_MESSAGE = "arg_is_own_message"
        private const val ARG_CURRENT_REACTION = "arg_current_reaction"
        private const val TAG = "message_actions"

        /** WhatsApp's own default quick-react set. */
        private val QUICK_REACT_EMOJI =
            listOf("❤️", "😂", "😮", "😢", "🙏", "👍")

        fun show(
            fragmentManager: FragmentManager,
            messageId: String,
            isOwnMessage: Boolean,
            currentReaction: String?
        ) {
            MessageActionsBottomSheet()
                .apply {
                    arguments = bundleOf(
                        ARG_MESSAGE_ID to messageId,
                        ARG_IS_OWN_MESSAGE to isOwnMessage,
                        ARG_CURRENT_REACTION to currentReaction
                    )
                }
                .show(fragmentManager, TAG)
        }
    }
}
