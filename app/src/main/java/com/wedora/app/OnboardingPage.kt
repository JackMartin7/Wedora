package com.wedora.app

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes

/** A single onboarding slide. */
data class OnboardingPage(
    @DrawableRes val illustrationRes: Int,
    @StringRes val titleRes: Int,
    @StringRes val descriptionRes: Int
)
