package com.wedora.app

/**
 * "Log Out?" confirmation sheet. The actual sign-out lives on the host so it
 * keeps the navigation and task-clearing it always had; the sheet just asks.
 */
class LogoutBottomSheet : ConfirmBottomSheet() {

    /** Implemented by any screen that shows this sheet. */
    interface Host {
        fun onLogoutConfirmed()
    }

    override val iconRes = R.drawable.ic_logout
    override val titleRes = R.string.logout_sheet_title
    override val subtitleRes = R.string.logout_sheet_subtitle
    override val primaryLabelRes = R.string.settings_logout

    override fun onPrimary() {
        (activity as? Host)?.onLogoutConfirmed()
    }
}
