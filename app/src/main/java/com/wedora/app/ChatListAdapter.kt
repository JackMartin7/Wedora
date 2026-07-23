package com.wedora.app

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.wedora.app.databinding.ItemChatRowBinding

class ChatListAdapter(
    private val onClick: (ChatPreview) -> Unit = {}
) : ListAdapter<ChatPreview, ChatListAdapter.ChatViewHolder>(DIFF) {

    private companion object {
        val DIFF = object : DiffUtil.ItemCallback<ChatPreview>() {
            override fun areItemsTheSame(a: ChatPreview, b: ChatPreview) = a.matchId == b.matchId
            override fun areContentsTheSame(a: ChatPreview, b: ChatPreview) = a == b
        }
    }

    inner class ChatViewHolder(
        private val binding: ItemChatRowBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(chat: ChatPreview) = with(binding) {
            val context = root.context

            // No photo backend for other users, so every row uses the neutral
            // placeholder rather than a stock face.
            ivChatAvatar.setImageResource(R.drawable.ic_avatar_placeholder)
            tvChatName.text = chat.name
            onlineDot.root.bindOnlineDot(chat.lastSeen)

            tvChatPreview.text = chat.lastMessage ?: context.getString(R.string.chat_say_hi)
            tvChatTimestamp.text = formatChatTimestamp(context, chat.lastMessageAt)

            // Both branches set every property explicitly — view holders are
            // recycled, so a read row reusing an unread row's view would keep
            // the bold/accent styling otherwise.
            if (chat.isUnread) {
                tvUnreadBadge.visibility = View.VISIBLE
                tvUnreadBadge.text = chat.unreadCount.toString()

                tvChatPreview.typeface = ResourcesCompat.getFont(context, R.font.poppins_semibold)
                tvChatTimestamp.typeface = ResourcesCompat.getFont(context, R.font.poppins_semibold)
                tvChatTimestamp.setTextColor(ContextCompat.getColor(context, R.color.wedora_accent))
            } else {
                tvUnreadBadge.visibility = View.GONE

                tvChatPreview.typeface = ResourcesCompat.getFont(context, R.font.poppins)
                tvChatTimestamp.typeface = ResourcesCompat.getFont(context, R.font.poppins)
                tvChatTimestamp.setTextColor(
                    ContextCompat.getColor(context, R.color.wedora_text_secondary)
                )
            }

            root.setOnClickListener { onClick(chat) }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ChatViewHolder {
        val binding = ItemChatRowBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return ChatViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ChatViewHolder, position: Int) {
        holder.bind(getItem(position))
    }
}
