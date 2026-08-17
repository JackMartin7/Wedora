package com.wedora.app

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.FragmentManager
import com.wedora.app.databinding.SheetNoticeBinding

/**
 * Shown when [ContactShareDetector] flags an outgoing message — the message
 * is not sent, and there is deliberately no "send anyway" action: the
 * warning IS the block.
 *
 * A notice rather than a confirm, so it extends [WedoraBottomSheetDialog]
 * directly with its own single-button layout instead of [ConfirmBottomSheet],
 * whose base always renders a secondary button that would have nothing to do
 * here. The composer keeps whatever the user typed (see
 * ChatThreadActivity.sendMessage) so they can edit it down and try again.
 */
class ContactShareBlockedBottomSheet : WedoraBottomSheetDialog() {

    private var binding: SheetNoticeBinding? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = SheetNoticeBinding.inflate(inflater, container, false)
        .also { binding = it }.root

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val b = binding ?: return
        b.ivNoticeIcon.setImageResource(R.drawable.ic_shield_lock)
        b.tvNoticeTitle.setText(R.string.contact_share_blocked_title)
        b.tvNoticeSubtitle.setText(R.string.contact_share_blocked_subtitle)
        b.btnNoticePrimary.setText(R.string.action_got_it)

        b.btnNoticePrimary.addPressScale()
        b.btnNoticePrimary.setOnClickListener { dismiss() }

        springIn(view)
    }

    override fun onDestroyView() {
        binding = null
        super.onDestroyView()
    }

    companion object {
        private const val TAG = "contact_share_blocked"

        fun show(fragmentManager: FragmentManager) {
            ContactShareBlockedBottomSheet().show(fragmentManager, TAG)
        }
    }
}
