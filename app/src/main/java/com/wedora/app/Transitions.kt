package com.wedora.app

import android.app.Activity

/**
 * Overrides for the few navigations the window animation can't get right on
 * its own.
 *
 * Push and pop are handled by [R.style.WedoraWindowAnimation] on the theme, so
 * ordinary startActivity and finish calls need nothing here. Two cases don't
 * fit that model:
 *
 *  - Screens that go "back" by starting their parent and finishing themselves
 *    (chat thread to chats, chats to home). Android sees a new activity
 *    starting and plays the forward animation, so the parent slides in from
 *    the *right* — reading as going deeper, and showing a bare frame of the
 *    reloading screen as it arrives. That's the flash.
 *
 *  - Bottom-nav tab switches, which are also start-and-finish but aren't
 *    hierarchical at all. A slide would imply one tab sits under another.
 */

/** Plays the pop animation on a navigation that's really a "back". */
fun Activity.applyBackTransition() {
    @Suppress("DEPRECATION")
    overridePendingTransition(R.anim.slide_in_left, R.anim.slide_out_right)
}

/** Cross-fade for lateral moves between peers, where nothing is nested. */
fun Activity.applyLateralTransition() {
    @Suppress("DEPRECATION")
    overridePendingTransition(R.anim.fade_in, R.anim.fade_out)
}

/**
 * Cross-fade for the launch and authentication path — splash to onboarding,
 * onboarding to login, login to home.
 *
 * These aren't navigation in the usual sense: nothing is being pushed onto a
 * stack the user can come back through, the app is handing off from one phase
 * to the next. A slide would suggest they could swipe back to the splash
 * screen. Shares the fade with [applyLateralTransition] and stays a separate
 * name so the two reasons don't get merged by a later edit.
 */
fun Activity.applyHandoffTransition() {
    @Suppress("DEPRECATION")
    overridePendingTransition(R.anim.fade_in, R.anim.fade_out)
}
