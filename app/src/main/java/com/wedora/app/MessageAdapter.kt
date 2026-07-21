package com.wedora.app

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView

/**
 * Chat bubbles, with sent and received as two view types so each can use its
 * own layout (alignment, bubble drawable, text colour) rather than one layout
 * reconfigured at bind time.
 */
class MessageAdapter(
    private val selfUid: String
) : ListAdapter<Message, MessageAdapter.MessageViewHolder>(DIFF) {

    private companion object {
        const val TYPE_SENT = 1
        const val TYPE_RECEIVED = 2

        val DIFF = object : DiffUtil.ItemCallback<Message>() {
            override fun areItemsTheSame(a: Message, b: Message) = a.id == b.id
            override fun areContentsTheSame(a: Message, b: Message) = a == b
        }
    }

    class MessageViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val text: TextView = view.findViewById(R.id.tvMessageText)
        private val time: TextView = view.findViewById(R.id.tvMessageTime)

        fun bind(message: Message) {
            text.text = message.text
            val stamp = formatChatTimestamp(itemView.context, message.sentAt)
            time.text = stamp
            // A message still awaiting its server timestamp has nothing
            // meaningful to show, so hide the line rather than leave a gap.
            time.visibility = if (stamp.isBlank()) View.GONE else View.VISIBLE
        }
    }

    override fun getItemViewType(position: Int): Int =
        if (getItem(position).senderId == selfUid) TYPE_SENT else TYPE_RECEIVED

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): MessageViewHolder {
        val layout =
            if (viewType == TYPE_SENT) R.layout.item_message_sent
            else R.layout.item_message_received
        val view = LayoutInflater.from(parent.context).inflate(layout, parent, false)
        return MessageViewHolder(view)
    }

    override fun onBindViewHolder(holder: MessageViewHolder, position: Int) {
        holder.bind(getItem(position))
    }
}
