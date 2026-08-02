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

/**
 * Long-press any row to enter multi-select; once in it, a plain tap on any
 * row toggles selection instead of opening the thread. Deselecting the last
 * row exits selection mode automatically, same as a Cancel tap.
 *
 * The adapter owns selection state (not the underlying [ChatPreview] list) so
 * toggling a row doesn't need to rebuild or re-diff the whole list — it's
 * purely a rendering concern, notified with a plain [notifyDataSetChanged],
 * which is cheap enough for a conversation list this size.
 */
class ChatListAdapter(
    private val onClick: (ChatPreview) -> Unit = {},
    /** (selectionMode, selectedCount) — fired whenever either changes. */
    private val onSelectionChanged: (Boolean, Int) -> Unit = { _, _ -> }
) : ListAdapter<ChatPreview, ChatListAdapter.ChatViewHolder>(DIFF) {

    private companion object {
        val DIFF = object : DiffUtil.ItemCallback<ChatPreview>() {
            override fun areItemsTheSame(a: ChatPreview, b: ChatPreview) = a.matchId == b.matchId
            override fun areContentsTheSame(a: ChatPreview, b: ChatPreview) = a == b
        }
    }

    private val selectedIds = mutableSetOf<String>()

    var selectionMode = false
        private set

    /** Deselects everything and leaves selection mode — the Cancel action. */
    fun exitSelectionMode() {
        selectedIds.clear()
        selectionMode = false
        onSelectionChanged(false, 0)
        notifyDataSetChanged()
    }

    fun selectedMatchIds(): Set<String> = selectedIds.toSet()

    /**
     * A row was tapped or long-pressed. Outside selection mode, a tap opens
     * the thread and a long-press starts selecting; inside it, either
     * gesture just toggles that row.
     */
    private fun onRowInteracted(chat: ChatPreview, longPress: Boolean) {
        if (!selectionMode) {
            if (!longPress) {
                onClick(chat)
                return
            }
            selectionMode = true
        }

        if (!selectedIds.remove(chat.matchId)) selectedIds.add(chat.matchId)
        if (selectedIds.isEmpty()) selectionMode = false

        onSelectionChanged(selectionMode, selectedIds.size)
        notifyDataSetChanged()
    }

    inner class ChatViewHolder(
        private val binding: ItemChatRowBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(chat: ChatPreview) = with(binding) {
            val context = root.context

            ivChatAvatar.loadRemoteProfilePhoto(chat.photoUrl)
            tvChatName.text = chat.name
            onlineDot.root.bindOnlineDot(chat.lastSeen)

            ivSelectedOverlay.visibility =
                if (chat.matchId in selectedIds) View.VISIBLE else View.GONE

            tvChatPreview.text = when {
                chat.lastMessageDeleted -> context.getString(R.string.message_deleted_placeholder)
                chat.lastMessage != null -> chat.lastMessage
                else -> context.getString(R.string.chat_say_hi)
            }
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

            root.setOnClickListener { onRowInteracted(chat, longPress = false) }
            root.setOnLongClickListener {
                onRowInteracted(chat, longPress = true)
                true
            }
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
