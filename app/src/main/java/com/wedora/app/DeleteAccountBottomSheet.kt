package com.wedora.app

/**
 * "Delete Account?" confirmation sheet. Tapping the primary button hands back
 * to the host, which runs the existing password re-auth and deletion — that
 * logic and its Firebase re-authentication stay in AccountSettingsActivity.
 */
class DeleteAccountBottomSheet : ConfirmBottomSheet() {

    interface Host {
        fun onDeleteAccountConfirmed()
    }

    override val iconRes = R.drawable.ic_delete
    override val titleRes = R.string.delete_sheet_title
    override val subtitleRes = R.string.delete_sheet_subtitle
    override val primaryLabelRes = R.string.delete_sheet_confirm

    override fun onPrimary() {
        (activity as? Host)?.onDeleteAccountConfirmed()
    }
}
