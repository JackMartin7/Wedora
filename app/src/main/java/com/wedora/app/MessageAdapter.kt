package com.wedora.app

import android.content.res.ColorStateList
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.widget.ImageViewCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.google.firebase.Timestamp

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

    /**
     * The other participant's lastReadAt, live from ChatThreadActivity's match
     * listener. Null until they've ever opened this thread. Read status for
     * every sent bubble is computed off this single value at bind time rather
     * than stored per-message, so one update re-evaluates every sent bubble
     * instead of needing a per-message Firestore field.
     */
    private var otherLastReadAt: Timestamp? = null

    /** Re-binds every row so sent bubbles pick up the new read/unread split. */
    fun updateReadReceipt(timestamp: Timestamp?) {
        if (timestamp == otherLastReadAt) return
        otherLastReadAt = timestamp
        notifyItemRangeChanged(0, itemCount)
    }

    abstract class MessageViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val text: TextView = view.findViewById(R.id.tvMessageText)
        private val time: TextView = view.findViewById(R.id.tvMessageTime)

        protected fun bindTextAndTime(message: Message) {
            text.text = message.text
            val stamp = formatChatTimestamp(itemView.context, message.sentAt)
            time.text = stamp
            // A message still awaiting its server timestamp has nothing
            // meaningful to show, so hide the line rather than leave a gap.
            time.visibility = if (stamp.isBlank()) View.GONE else View.VISIBLE
        }

        abstract fun bind(message: Message, otherLastReadAt: Timestamp?)
    }

    private class ReceivedViewHolder(view: View) : MessageViewHolder(view) {
        override fun bind(message: Message, otherLastReadAt: Timestamp?) {
            bindTextAndTime(message)
        }
    }

    /**
     * Sent bubbles only: single grey checkmark while the write is still
     * local ([Message.pending]), double grey once Firestore has confirmed
     * it, and double blue once the recipient's lastReadAt has caught up to
     * this message's sentAt. This architecture has no delivery
     * acknowledgement distinct from "written" — double-grey stands in for
     * both sent and delivered, matching how most simple chat apps behave
     * without a dedicated presence/delivery system.
     */
    private class SentViewHolder(view: View) : MessageViewHolder(view) {
        private val status: ImageView = view.findViewById(R.id.ivMessageStatus)

        override fun bind(message: Message, otherLastReadAt: Timestamp?) {
            bindTextAndTime(message)

            val sentAt = message.sentAt
            val readAt = otherLastReadAt
            val read = !message.pending && sentAt != null && readAt != null && readAt >= sentAt

            status.setImageResource(
                if (message.pending) R.drawable.ic_check_single else R.drawable.ic_check_double
            )
            ImageViewCompat.setImageTintList(
                status,
                ColorStateList.valueOf(
                    ContextCompat.getColor(
                        itemView.context,
                        if (read) R.color.wedora_read_receipt else R.color.wedora_text_secondary
                    )
                )
            )
        }
    }

    override fun getItemViewType(position: Int): Int =
        if (getItem(position).senderId == selfUid) TYPE_SENT else TYPE_RECEIVED

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): MessageViewHolder {
        val layout =
            if (viewType == TYPE_SENT) R.layout.item_message_sent
            else R.layout.item_message_received
        val view = LayoutInflater.from(parent.context).inflate(layout, parent, false)
        return if (viewType == TYPE_SENT) SentViewHolder(view) else ReceivedViewHolder(view)
    }

    override fun onBindViewHolder(holder: MessageViewHolder, position: Int) {
        holder.bind(getItem(position), otherLastReadAt)
    }
}
