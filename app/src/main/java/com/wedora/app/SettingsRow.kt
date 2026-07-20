package com.wedora.app

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes

/** One chevron row in Profile's settings list. */
data class SettingsRow(
    @DrawableRes val iconRes: Int,
    @StringRes val labelRes: Int,
    val onClick: () -> Unit
)
