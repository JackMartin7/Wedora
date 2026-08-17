package com.wedora.app

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.os.bundleOf
import androidx.fragment.app.FragmentManager
import com.wedora.app.databinding.SheetNoticeBinding

/**
 * Confirms a rewarded ad actually paid out — replacing the toast that used
 * to be the only acknowledgement.
 *
 * A toast was too quiet for the one moment in this flow where the user gets
 * something back for their attention; the failure case is still a toast,
 * because a modal for "no ad was available" would punish them twice for
 * something that isn't theirs to fix.
 *
 * A notice, not a confirm, so it uses [WedoraBottomSheetDialog] with
 * sheet_notice.xml directly — same reasoning as
 * [ContactShareBlockedBottomSheet]: there is only one thing to do here, and
 * [ConfirmBottomSheet] would render a second button with nothing to say.
 */
class RewardGrantedBottomSheet : WedoraBottomSheetDialog() {

    private var binding: SheetNoticeBinding? = null

    /** `get() =`, not `=` — a property initializer runs in the constructor,
     *  before [show] can attach the arguments. Same trap that crashed
     *  [DailyLimitReachedBottomSheet]; see its tertiaryLabelRes. */
    private val subtitleRes: Int
        get() = when (DailyLimitReachedBottomSheet.Kind.valueOf(requireArguments().getString(ARG_KIND)!!)) {
            DailyLimitReachedBottomSheet.Kind.LIKES -> R.string.reward_granted_like
            DailyLimitReachedBottomSheet.Kind.MESSAGES -> R.string.reward_granted_message
        }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = SheetNoticeBinding.inflate(inflater, container, false)
        .also { binding = it }.root

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val b = binding ?: return
        b.ivNoticeIcon.setImageResource(R.drawable.ic_star)
        b.tvNoticeTitle.setText(R.string.reward_granted_title)
        b.tvNoticeSubtitle.setText(subtitleRes)
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
        private const val ARG_KIND = "kind"
        private const val TAG = "reward_granted"

        fun show(fragmentManager: FragmentManager, kind: DailyLimitReachedBottomSheet.Kind) {
            RewardGrantedBottomSheet()
                .apply { arguments = bundleOf(ARG_KIND to kind.name) }
                .show(fragmentManager, TAG)
        }
    }
}
