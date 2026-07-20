package com.wedora.app

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.wedora.app.databinding.ItemChatRowBinding

class ChatListAdapter(
    private val onClick: (ChatPreview) -> Unit = {}
) : ListAdapter<ChatPreview, ChatListAdapter.ChatViewHolder>(DIFF) {

    private companion object {
        val DIFF = object : DiffUtil.ItemCallback<ChatPreview>() {
            override fun areItemsTheSame(a: ChatPreview, b: ChatPreview) = a.id == b.id
            override fun areContentsTheSame(a: ChatPreview, b: ChatPreview) = a == b
        }
    }

    inner class ChatViewHolder(
        private val binding: ItemChatRowBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(chat: ChatPreview) = with(binding) {
            ivChatAvatar.setImageResource(chat.avatarRes)
            tvChatName.text = chat.name
            tvChatPreview.text = chat.lastMessage
            tvChatTimestamp.text = chat.timestamp

            if (chat.unreadCount > 0) {
                tvUnreadBadge.text = chat.unreadCount.toString()
                tvUnreadBadge.visibility = View.VISIBLE
            } else {
                tvUnreadBadge.visibility = View.GONE
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
