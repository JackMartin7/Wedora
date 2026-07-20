package com.wedora.app

import androidx.annotation.DrawableRes

/** One avatar in the Chats screen's horizontal "new matches" strip. */
data class NewMatch(
    val id: Long,
    val name: String,
    @DrawableRes val avatarRes: Int,
    val isUnseen: Boolean
) {
    companion object {
        fun sampleMatches(): List<NewMatch> = listOf(
            NewMatch(1, "Amelia", R.drawable.chat_story1, isUnseen = true),
            NewMatch(2, "Noah", R.drawable.chat_story2, isUnseen = true),
            NewMatch(3, "Sofia", R.drawable.chat_story3, isUnseen = false),
            NewMatch(4, "Ethan", R.drawable.chat_story4, isUnseen = true)
        )
    }
}
