package com.wedora.app

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.wedora.app.databinding.ItemBlockedUserBinding

/**
 * One person on the block list. [name] falls back to a generic label when the
 * blocked user's profile can't be read or has no display name — the row still
 * has to be shown, or the user would have no way to unblock them.
 */
data class BlockedUser(
    val uid: String,
    val name: String
)

class BlockedUserAdapter(
    private val onUnblock: (BlockedUser) -> Unit
) : ListAdapter<BlockedUser, BlockedUserAdapter.BlockedViewHolder>(DIFF) {

    private companion object {
        val DIFF = object : DiffUtil.ItemCallback<BlockedUser>() {
            override fun areItemsTheSame(a: BlockedUser, b: BlockedUser) = a.uid == b.uid
            override fun areContentsTheSame(a: BlockedUser, b: BlockedUser) = a == b
        }
    }

    inner class BlockedViewHolder(
        private val binding: ItemBlockedUserBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(user: BlockedUser) = with(binding) {
            // The avatar stays the neutral placeholder — a block list is the
            // one place we shouldn't be rendering the other person's photo.
            ivBlockedAvatar.setImageResource(R.drawable.ic_avatar_placeholder)
            tvBlockedName.text = user.name
            btnUnblock.setOnClickListener { onUnblock(user) }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): BlockedViewHolder {
        val binding = ItemBlockedUserBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return BlockedViewHolder(binding)
    }

    override fun onBindViewHolder(holder: BlockedViewHolder, position: Int) {
        holder.bind(getItem(position))
    }
}
