package com.wedora.app

import com.google.firebase.Timestamp
import java.util.Date

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
    val unreadCount: Int,
    /** For the online-status dot; batch-loaded alongside the match data. */
    val lastSeen: Date?,
    /** The other user's hosted photo, batch-loaded the same way as [lastSeen]; null on a demo/guest row or an account with none. */
    val photoUrl: String?
)
