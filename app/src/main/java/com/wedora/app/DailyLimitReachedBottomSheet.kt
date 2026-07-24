package com.wedora.app

import androidx.core.os.bundleOf
import androidx.fragment.app.FragmentManager

/**
 * "Daily Limit Reached" sheet, shown when a free-tier user hits either of the
 * two independent daily caps — 10 likes or 10 messages. Same title/buttons
 * either way; only the subtitle names which one. "Maybe Later" just dismisses
 * (the base class's default secondary action); "Upgrade Now" hands off to the
 * host to open PaymentSubscriptionActivity.
 */
class DailyLimitReachedBottomSheet : ConfirmBottomSheet() {

    enum class Kind(val subtitleRes: Int) {
        LIKES(R.string.like_limit_sheet_subtitle),
        MESSAGES(R.string.message_limit_sheet_subtitle)
    }

    /** Implemented by any screen that can show this sheet. */
    interface Host {
        fun onUpgradeFromDailyLimitRequested()
    }

    private val kind: Kind
        get() = Kind.valueOf(requireArguments().getString(ARG_KIND)!!)

    override val iconRes = R.drawable.ic_crown_accent
    override val titleRes = R.string.daily_limit_sheet_title
    override val subtitleRes: Int get() = kind.subtitleRes
    override val primaryLabelRes = R.string.daily_limit_sheet_upgrade
    override val secondaryLabelRes = R.string.daily_limit_sheet_maybe_later

    override fun onPrimary() {
        (activity as? Host)?.onUpgradeFromDailyLimitRequested()
    }

    companion object {
        private const val ARG_KIND = "kind"
        private const val TAG = "daily_limit_reached"

        fun show(fragmentManager: FragmentManager, kind: Kind) {
            DailyLimitReachedBottomSheet()
                .apply { arguments = bundleOf(ARG_KIND to kind.name) }
                .show(fragmentManager, TAG)
        }
    }
}
