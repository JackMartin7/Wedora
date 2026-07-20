package com.wedora.app

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.wedora.app.databinding.ItemChatStoryBinding

class NewMatchAdapter(
    private val matches: List<NewMatch>,
    private val onClick: (NewMatch) -> Unit = {}
) : RecyclerView.Adapter<NewMatchAdapter.MatchViewHolder>() {

    inner class MatchViewHolder(
        private val binding: ItemChatStoryBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(match: NewMatch) {
            binding.ivStoryAvatar.setImageResource(match.avatarRes)
            binding.vNewMatchDot.visibility = if (match.isUnseen) View.VISIBLE else View.GONE
            binding.root.setOnClickListener { onClick(match) }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): MatchViewHolder {
        val binding = ItemChatStoryBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return MatchViewHolder(binding)
    }

    override fun onBindViewHolder(holder: MatchViewHolder, position: Int) {
        holder.bind(matches[position])
    }

    override fun getItemCount(): Int = matches.size
}
