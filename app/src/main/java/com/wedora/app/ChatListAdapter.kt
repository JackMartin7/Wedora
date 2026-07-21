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
            override fun areItemsTheSame(a: ChatPreview, b: ChatPreview) = a.matchId == b.matchId
            override fun areContentsTheSame(a: ChatPreview, b: ChatPreview) = a == b
        }
    }

    inner class ChatViewHolder(
        private val binding: ItemChatRowBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(chat: ChatPreview) = with(binding) {
            // No photo backend for other users, so every row uses the neutral
            // placeholder rather than a stock face.
            ivChatAvatar.setImageResource(R.drawable.ic_avatar_placeholder)
            tvChatName.text = chat.name

            tvChatPreview.text =
                chat.lastMessage ?: root.context.getString(R.string.chat_say_hi)
            tvChatTimestamp.text = formatChatTimestamp(root.context, chat.lastMessageAt)

            // Nothing tracks read state yet, so this stays hidden instead of
            // showing a made-up count.
            tvUnreadBadge.visibility = View.GONE

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
