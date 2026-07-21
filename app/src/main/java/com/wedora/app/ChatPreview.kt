package com.wedora.app

import com.google.firebase.Timestamp

/**
 * One row in the Chats list: a match, plus its most recent message if there is
 * one.
 *
 * [lastMessage] is null for a match nobody has written in yet, which the
 * adapter renders as "Say hi 👋" rather than an empty line.
 *
 * There is no unread-count field: nothing tracks read state, so the row's
 * unread badge stays hidden rather than showing an invented number.
 */
data class ChatPreview(
    val matchId: String,
    val otherUserId: String,
    val name: String,
    val lastMessage: String?,
    val lastMessageAt: Timestamp?
)
