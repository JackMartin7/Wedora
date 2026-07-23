package com.wedora.app

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.wedora.app.databinding.ItemDiscoverGridBinding

/**
 * A browsable profile in the Explore "Discover" grid. [meta] is the pre-built
 * age/location line (null when the person hasn't filled in enough to show one).
 */
data class DiscoverProfile(
    val userId: String,
    val name: String,
    val meta: String?
)

/** 2-column grid of discoverable profiles, mirroring the Likes grid. */
class DiscoverAdapter(
    private val onClick: (DiscoverProfile) -> Unit = {}
) : ListAdapter<DiscoverProfile, DiscoverAdapter.DiscoverViewHolder>(DIFF) {

    private companion object {
        val DIFF = object : DiffUtil.ItemCallback<DiscoverProfile>() {
            override fun areItemsTheSame(a: DiscoverProfile, b: DiscoverProfile) = a.userId == b.userId
            override fun areContentsTheSame(a: DiscoverProfile, b: DiscoverProfile) = a == b
        }
    }

    inner class DiscoverViewHolder(
        private val binding: ItemDiscoverGridBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(profile: DiscoverProfile) = with(binding) {
            // No photo backend for other users, so the tile keeps the neutral
            // placeholder set in the layout.
            tvDiscoverName.text = profile.name

            if (profile.meta == null) {
                tvDiscoverMeta.visibility = View.GONE
            } else {
                tvDiscoverMeta.visibility = View.VISIBLE
                tvDiscoverMeta.text = profile.meta
            }

            root.setOnClickListener { onClick(profile) }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): DiscoverViewHolder {
        val binding = ItemDiscoverGridBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return DiscoverViewHolder(binding)
    }

    override fun onBindViewHolder(holder: DiscoverViewHolder, position: Int) {
        holder.bind(getItem(position))
    }
}
