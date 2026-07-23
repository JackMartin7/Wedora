package com.wedora.app

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.wedora.app.databinding.ItemExploreNearbyBinding

/** One of the current user's matched people, shown in the Explore Nearby strip. */
data class NearbyPerson(
    val userId: String,
    val name: String
)

/** Horizontal strip of circular avatars for the Explore "People Nearby" row. */
class NearbyAdapter(
    private val onClick: (NearbyPerson) -> Unit = {}
) : ListAdapter<NearbyPerson, NearbyAdapter.NearbyViewHolder>(DIFF) {

    private companion object {
        val DIFF = object : DiffUtil.ItemCallback<NearbyPerson>() {
            override fun areItemsTheSame(a: NearbyPerson, b: NearbyPerson) = a.userId == b.userId
            override fun areContentsTheSame(a: NearbyPerson, b: NearbyPerson) = a == b
        }
    }

    inner class NearbyViewHolder(
        private val binding: ItemExploreNearbyBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(person: NearbyPerson) = with(binding) {
            // No photo backend for other users, so the avatar keeps the neutral
            // placeholder set in the layout.
            tvNearbyName.text = person.name
            root.setOnClickListener { onClick(person) }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): NearbyViewHolder {
        val binding = ItemExploreNearbyBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return NearbyViewHolder(binding)
    }

    override fun onBindViewHolder(holder: NearbyViewHolder, position: Int) {
        holder.bind(getItem(position))
    }
}
