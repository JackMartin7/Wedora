package com.wedora.app

/**
 * "Daily Limit Reached" sheet, shown when a free-tier user tries to like a
 * 11th person on the same calendar day. "Maybe Later" just dismisses (the
 * base class's default secondary action); "Upgrade Now" hands off to the host
 * to open PaymentSubscriptionActivity.
 */
class DailyLimitReachedBottomSheet : ConfirmBottomSheet() {

    /** Implemented by any screen that can show this sheet. */
    interface Host {
        fun onUpgradeFromLikeLimitRequested()
    }

    override val iconRes = R.drawable.ic_crown_accent
    override val titleRes = R.string.like_limit_sheet_title
    override val subtitleRes = R.string.like_limit_sheet_subtitle
    override val primaryLabelRes = R.string.like_limit_sheet_upgrade
    override val secondaryLabelRes = R.string.like_limit_sheet_maybe_later

    override fun onPrimary() {
        (activity as? Host)?.onUpgradeFromLikeLimitRequested()
    }
}
