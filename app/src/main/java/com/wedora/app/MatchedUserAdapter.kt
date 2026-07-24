package com.wedora.app

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.wedora.app.databinding.ItemLikesMatchedUserBinding

/** Horizontal strip of circular avatars for the Likes tab's "Users Matched" row. */
class MatchedUserAdapter(
    private val onClick: (MatchedUser) -> Unit = {}
) : ListAdapter<MatchedUser, MatchedUserAdapter.MatchedUserViewHolder>(DIFF) {

    private companion object {
        val DIFF = object : DiffUtil.ItemCallback<MatchedUser>() {
            override fun areItemsTheSame(a: MatchedUser, b: MatchedUser) = a.matchId == b.matchId
            override fun areContentsTheSame(a: MatchedUser, b: MatchedUser) = a == b
        }
    }

    inner class MatchedUserViewHolder(
        private val binding: ItemLikesMatchedUserBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(user: MatchedUser) = with(binding) {
            // No photo backend for other users, so the avatar keeps the
            // neutral placeholder set in the layout.
            tvMatchedName.text = user.name
            onlineDot.root.bindOnlineDot(user.lastSeen)

            root.setOnClickListener { onClick(user) }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): MatchedUserViewHolder {
        val binding = ItemLikesMatchedUserBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return MatchedUserViewHolder(binding)
    }

    override fun onBindViewHolder(holder: MatchedUserViewHolder, position: Int) {
        holder.bind(getItem(position))
    }
}
