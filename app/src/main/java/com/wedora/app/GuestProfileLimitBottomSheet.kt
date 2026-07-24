package com.wedora.app

import androidx.fragment.app.FragmentManager

/**
 * Shown once a guest hits [GuestPrefs.DAILY_PROFILE_VIEW_LIMIT] for the day —
 * on Home when the limit-th profile becomes the top card, or on Explore when
 * loading the Discover grid finds the pool already spent. "Maybe Later" (the
 * base class's default secondary action) just dismisses; the host is then
 * responsible for freezing its own feed into a persistent empty state, since
 * that's specific to each screen's layout, not something this sheet controls.
 */
class GuestProfileLimitBottomSheet : ConfirmBottomSheet() {

    /** Implemented by any screen that can show this sheet. */
    interface Host {
        fun onSignUpFromGuestLimitRequested()
    }

    override val iconRes = R.drawable.ic_sparkle_heart
    override val titleRes = R.string.guest_limit_sheet_title
    override val subtitleRes = R.string.guest_limit_sheet_subtitle
    override val primaryLabelRes = R.string.guest_sign_up_now
    override val secondaryLabelRes = R.string.daily_limit_sheet_maybe_later

    override fun onPrimary() {
        (activity as? Host)?.onSignUpFromGuestLimitRequested()
    }

    companion object {
        private const val TAG = "guest_profile_limit"

        fun show(fragmentManager: FragmentManager) {
            GuestProfileLimitBottomSheet().show(fragmentManager, TAG)
        }
    }
}
