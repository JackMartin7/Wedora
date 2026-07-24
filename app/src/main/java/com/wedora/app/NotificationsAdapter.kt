package com.wedora.app

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.wedora.app.databinding.ItemNotificationBinding

class NotificationsAdapter(
    private val onClick: (NotificationItem) -> Unit = {}
) : ListAdapter<NotificationItem, NotificationsAdapter.NotificationViewHolder>(DIFF) {

    private companion object {
        val DIFF = object : DiffUtil.ItemCallback<NotificationItem>() {
            override fun areItemsTheSame(a: NotificationItem, b: NotificationItem) =
                a.matchId == b.matchId

            override fun areContentsTheSame(a: NotificationItem, b: NotificationItem) = a == b
        }
    }

    inner class NotificationViewHolder(
        private val binding: ItemNotificationBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(item: NotificationItem) = with(binding) {
            ivNotificationAvatar.loadRemoteProfilePhoto(item.photoUrl)
            tvNotificationName.text = item.likerName
            onlineDot.root.bindOnlineDot(item.lastSeen)
            tvNotificationTime.text = formatRelativeShort(root.context, item.createdAt)

            root.setOnClickListener { onClick(item) }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): NotificationViewHolder {
        val binding = ItemNotificationBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return NotificationViewHolder(binding)
    }

    override fun onBindViewHolder(holder: NotificationViewHolder, position: Int) {
        holder.bind(getItem(position))
    }
}
