package com.wedora.app

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.wedora.app.databinding.ItemExploreNearbyBinding

/**
 * One discoverable person in an Explore strip.
 *
 * [badge] is whatever that strip captions its cards with - a formatted
 * distance ("2.4 km") for People Nearby, a like count ("12 likes") for
 * Trending - or null to hide the pill entirely. It was distanceBadge until
 * Trending reused this adapter; the name is deliberately neutral now, since
 * the strip does not care which it is being handed.
 */
data class NearbyPerson(
    val userId: String,
    val name: String,
    val badge: String?,
    val photoUrl: String?
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
            ivNearbyAvatar.loadRemoteProfilePhoto(person.photoUrl)
            tvNearbyName.text = person.name

            if (person.badge == null) {
                tvNearbyDistance.visibility = View.GONE
            } else {
                tvNearbyDistance.visibility = View.VISIBLE
                tvNearbyDistance.text = person.badge
            }

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
