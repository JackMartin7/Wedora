package com.wedora.app

import androidx.annotation.DrawableRes

/** One row in the Chats screen's conversation list. */
data class ChatPreview(
    val id: Long,
    val name: String,
    @DrawableRes val avatarRes: Int,
    val lastMessage: String,
    val timestamp: String,
    val unreadCount: Int
) {
    companion object {
        fun sampleConversations(): List<ChatPreview> = listOf(
            ChatPreview(1, "Amelia Hart", R.drawable.chat_row1, "Sounds great, see you then!", "9:41 AM", 2),
            ChatPreview(2, "Noah Bennett", R.drawable.chat_row2, "Haha that's exactly what I meant", "Yesterday", 0),
            ChatPreview(3, "Sofia Reyes", R.drawable.chat_row3, "Can't wait for the weekend 😊", "Yesterday", 5),
            ChatPreview(4, "Ethan Cole", R.drawable.chat_row4, "Let me know when you're free", "Mon", 0),
            ChatPreview(5, "Mia Turner", R.drawable.chat_row5, "That coffee place was amazing", "Mon", 1),
            ChatPreview(6, "Liam Foster", R.drawable.chat_row6, "You there?", "Sun", 0),
            ChatPreview(7, "Ava Simmons", R.drawable.chat_row7, "Thanks for last night, had fun!", "Sat", 0)
        )
    }
}
