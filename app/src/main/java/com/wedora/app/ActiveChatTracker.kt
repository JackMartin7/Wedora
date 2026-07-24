package com.wedora.app

/**
 * Which conversation, if any, is currently on screen — set by
 * [ChatThreadActivity] itself (onResume/onPause), read by
 * [MatchNotificationWatcher] so it doesn't notify about a message the user is
 * already looking at.
 *
 * A single mutable field rather than a broadcast or callback: there is at most
 * one visible Activity at a time, so "what's open right now" is exactly the
 * kind of process-global state a singleton is for.
 */
object ActiveChatTracker {
    var openThreadUid: String? = null
}
