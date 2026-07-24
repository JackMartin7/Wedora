package com.wedora.app

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes

/**
 * One chevron row in Profile's settings list.
 *
 * [guestEnabled] opts a row out of the locked/dimmed guest treatment
 * ProfileActivity.setUpSettingsRows applies by default — Logout is the one
 * row set to true, since exiting guest mode needs no account, unlike
 * everything else in this list.
 */
data class SettingsRow(
    @DrawableRes val iconRes: Int,
    @StringRes val labelRes: Int,
    val guestEnabled: Boolean = false,
    val onClick: () -> Unit
)
