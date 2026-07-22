package com.wedora.app

import com.google.firebase.Timestamp

/**
 * One row in the Chats list: a match, plus its most recent message if there is
 * one.
 *
 * [lastMessage] is null for a match nobody has written in yet, which the
 * adapter renders as "Say hi 👋" rather than an empty line.
 *
 * [isUnread] is resolved where the signed-in UID is known, so the adapter just
 * renders it — it's true only when the newest message came from the other
 * person and hasn't been read.
 */
data class ChatPreview(
    val matchId: String,
    val otherUserId: String,
    val name: String,
    val lastMessage: String?,
    val lastMessageAt: Timestamp?,
    val isUnread: Boolean,
    val unreadCount: Int
)
