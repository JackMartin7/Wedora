package com.wedora.app

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.wedora.app.databinding.ItemNearbyRowBinding

/**
 * One row in the full People Nearby list. [meta] is the age/location line and
 * [badge] the distance or like count; either is null when unavailable and
 * the corresponding view is hidden.
 */
data class NearbyRow(
    val userId: String,
    val name: String,
    val meta: String?,
    /** Distance for the Nearby list, like count for Trending. See NearbyPerson. */
    val badge: String?,
    val photoUrl: String?
)

/** Single-column list of discoverable people, sorted closest first. */
class NearbyListAdapter(
    private val onClick: (NearbyRow) -> Unit = {}
) : ListAdapter<NearbyRow, NearbyListAdapter.RowViewHolder>(DIFF) {

    private companion object {
        val DIFF = object : DiffUtil.ItemCallback<NearbyRow>() {
            override fun areItemsTheSame(a: NearbyRow, b: NearbyRow) = a.userId == b.userId
            override fun areContentsTheSame(a: NearbyRow, b: NearbyRow) = a == b
        }
    }

    inner class RowViewHolder(
        private val binding: ItemNearbyRowBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(row: NearbyRow) = with(binding) {
            ivNearbyAvatar.loadRemoteProfilePhoto(row.photoUrl)
            tvNearbyName.text = row.name

            if (row.meta == null) {
                tvNearbyMeta.visibility = View.GONE
            } else {
                tvNearbyMeta.visibility = View.VISIBLE
                tvNearbyMeta.text = row.meta
            }

            if (row.badge == null) {
                tvNearbyDistance.visibility = View.GONE
            } else {
                tvNearbyDistance.visibility = View.VISIBLE
                tvNearbyDistance.text = row.badge
            }

            root.setOnClickListener { onClick(row) }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RowViewHolder {
        val binding = ItemNearbyRowBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return RowViewHolder(binding)
    }

    override fun onBindViewHolder(holder: RowViewHolder, position: Int) {
        holder.bind(getItem(position))
    }
}
