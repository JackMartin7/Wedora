package com.wedora.app

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.wedora.app.databinding.ItemMatchHistoryBinding
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * One match in the history list. [matchedOn] is null only for a just-written
 * match whose server timestamp hasn't resolved yet — the date line is hidden in
 * that case rather than showing a placeholder.
 */
data class MatchHistoryItem(
    val matchId: String,
    val otherUserId: String,
    val name: String,
    val matchedOn: Date?,
    /** For the online-status dot; batch-loaded alongside the match data. */
    val lastSeen: Date?,
    val photoUrl: String?,
    /** Null when either this user or the match has no coordinates on file. */
    val distanceBadge: String?
)

/**
 * The full list of a user's matches, newest first. The row opens the profile;
 * the "Message" pill opens the conversation — two callbacks so each tap target
 * goes where it says.
 */
class MatchHistoryAdapter(
    private val onRowClick: (MatchHistoryItem) -> Unit = {},
    private val onMessageClick: (MatchHistoryItem) -> Unit = {}
) : ListAdapter<MatchHistoryItem, MatchHistoryAdapter.MatchViewHolder>(DIFF) {

    private companion object {
        val DIFF = object : DiffUtil.ItemCallback<MatchHistoryItem>() {
            override fun areItemsTheSame(a: MatchHistoryItem, b: MatchHistoryItem) =
                a.matchId == b.matchId
            override fun areContentsTheSame(a: MatchHistoryItem, b: MatchHistoryItem) = a == b
        }
    }

    inner class MatchViewHolder(
        private val binding: ItemMatchHistoryBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        /** "24 Jul 2026" — a fixed pattern so the date reads the same everywhere. */
        private val dateFormat = SimpleDateFormat("d MMM yyyy", Locale.getDefault())

        fun bind(item: MatchHistoryItem) = with(binding) {
            ivAvatar.loadRemoteProfilePhoto(item.photoUrl)
            tvName.text = item.name
            onlineDot.root.bindOnlineDot(item.lastSeen)

            if (item.matchedOn == null) {
                tvMatchedOn.visibility = android.view.View.GONE
            } else {
                tvMatchedOn.visibility = android.view.View.VISIBLE
                tvMatchedOn.text = root.context.getString(
                    R.string.match_history_matched_on, dateFormat.format(item.matchedOn)
                )
            }

            if (item.distanceBadge == null) {
                tvDistance.visibility = android.view.View.GONE
            } else {
                tvDistance.visibility = android.view.View.VISIBLE
                tvDistance.text = item.distanceBadge
            }

            root.setOnClickListener { onRowClick(item) }
            btnMessage.setOnClickListener { onMessageClick(item) }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): MatchViewHolder {
        val binding = ItemMatchHistoryBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return MatchViewHolder(binding)
    }

    override fun onBindViewHolder(holder: MatchViewHolder, position: Int) {
        holder.bind(getItem(position))
    }
}
