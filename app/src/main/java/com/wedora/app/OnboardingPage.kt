package com.wedora.app

import androidx.annotation.LayoutRes
import androidx.annotation.StringRes

/** A single onboarding slide. */
data class OnboardingPage(
    /** Layout holding this slide's illustration collage. */
    @LayoutRes val illustrationLayoutRes: Int,
    @StringRes val titleRes: Int,
    @StringRes val descriptionRes: Int,
    /**
     * Design width of [illustrationLayoutRes]'s fixed canvas, in dp.
     * Used to scale the collage down on screens too narrow to show it at 1:1.
     */
    val canvasWidthDp: Int
)
