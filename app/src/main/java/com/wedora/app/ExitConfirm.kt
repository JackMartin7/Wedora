package com.wedora.app

import androidx.activity.addCallback
import androidx.appcompat.app.AppCompatActivity

/**
 * Intercepts back-press on a root tab screen (Home, Explore, Likes, Profile
 * — see each Activity's onCreate) and shows [ExitConfirmBottomSheet] instead
 * of letting the app close outright, but only when back-press would
 * *actually* exit: [AppCompatActivity.isTaskRoot] is true because the
 * bottom-nav helper finishes each tab as it switches (see
 * BottomNavHelper.kt), so by the time any of these screens is on screen via
 * the normal navigation flow, there's nothing left underneath it in the
 * task. A back-press reached some other way — e.g. this screen sitting
 * beneath a forward-navigated one that didn't finish itself — isn't
 * intercepted at all; [showExitConfirm] simply isn't consulted and the
 * default finish()-and-reveal-whatever's-underneath behavior runs instead.
 *
 * [showExitConfirm] is a lambda, not a precomputed value, so it can read
 * whatever personalized counts the screen has *at the moment back is
 * pressed* rather than whatever was true when onCreate ran.
 *
 * The prompt shows on every qualifying back-press. There used to be a
 * once-per-process suppression (ExitConfirmState) that let a second exit go
 * through silently, on the reasoning that someone bouncing back in via a
 * notification shouldn't be asked twice. That's been removed deliberately:
 * an exit is worth confirming every time, and the silent close it produced
 * was the more surprising of the two behaviours. There is no loop risk —
 * the sheet's Exit action calls finish() directly rather than routing back
 * through this dispatcher.
 *
 * ChatsActivity deliberately isn't one of the four callers — it already
 * intercepts back itself to redirect to Home rather than exit, and that
 * existing behavior is left untouched rather than layering this on top of
 * it (see ChatsActivity.goToHome's own doc comment).
 */
fun AppCompatActivity.setUpExitConfirmOnBackPress(showExitConfirm: () -> Unit) {
    onBackPressedDispatcher.addCallback(this) {
        if (isTaskRoot) {
            showExitConfirm()
        } else {
            finish()
        }
    }
}

/**
 * The [ExitConfirmBottomSheet.Kind] (and count, where relevant) a screen
 * should show, in the priority order the feature spec asks for: guest copy
 * first — a guest has no real likes/messages data to personalize with, so
 * there's no point checking — then unseen likes, then unread messages, then
 * [hasUnswipedProfiles] if the caller has that data at all (only
 * HomeActivity's swipe stack does; every other screen leaves it false and
 * falls straight through to the generic copy), then generic copy.
 */
fun AppCompatActivity.resolveExitConfirmKind(
    unseenLikes: Int,
    unreadMessages: Int,
    hasUnswipedProfiles: Boolean = false
): Pair<ExitConfirmBottomSheet.Kind, Int> = when {
    GuestPrefs.isGuest(this) -> ExitConfirmBottomSheet.Kind.GUEST to 0
    unseenLikes > 0 -> ExitConfirmBottomSheet.Kind.UNSEEN_LIKES to unseenLikes
    unreadMessages > 0 -> ExitConfirmBottomSheet.Kind.UNREAD_MESSAGES to unreadMessages
    hasUnswipedProfiles -> ExitConfirmBottomSheet.Kind.UNSWIPED_PROFILES to 0
    else -> ExitConfirmBottomSheet.Kind.GENERIC to 0
}
