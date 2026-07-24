package com.wedora.app

import androidx.fragment.app.FragmentManager

/**
 * Shown when a guest taps Send in a demo chat thread (see
 * ChatThreadActivity.setUpDemoThread) — the message is never actually sent,
 * this is what happens instead. "Keep Exploring" (the base class's default
 * secondary action) just dismisses, leaving the guest exactly where they
 * were in the demo thread — deliberately not an auto-navigate-to-Sign-Up
 * the way some other guest gates in the app are, since blocking mid-typing
 * should feel like an interruption to acknowledge, not a redirect that
 * happens to you.
 */
class GuestChatBlockedBottomSheet : ConfirmBottomSheet() {

    /** Implemented by any screen that can show this sheet. */
    interface Host {
        fun onSignUpFromChatBlockedRequested()
    }

    override val iconRes = R.drawable.ic_support_chat
    override val titleRes = R.string.guest_chat_blocked_title
    override val subtitleRes = R.string.guest_chat_blocked_subtitle
    override val primaryLabelRes = R.string.premium_banner_signup_action
    override val secondaryLabelRes = R.string.guest_keep_exploring_action

    override fun onPrimary() {
        (activity as? Host)?.onSignUpFromChatBlockedRequested()
    }

    companion object {
        private const val TAG = "guest_chat_blocked"

        fun show(fragmentManager: FragmentManager) {
            GuestChatBlockedBottomSheet().show(fragmentManager, TAG)
        }
    }
}
