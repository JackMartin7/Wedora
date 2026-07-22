package com.wedora.app

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.wedora.app.databinding.ItemLikeGridBinding

/** Grid of users who liked you. */
class LikesAdapter(
    private val onClick: (ReceivedLike) -> Unit = {}
) : ListAdapter<ReceivedLike, LikesAdapter.LikeViewHolder>(DIFF) {

    private companion object {
        val DIFF = object : DiffUtil.ItemCallback<ReceivedLike>() {
            override fun areItemsTheSame(a: ReceivedLike, b: ReceivedLike) = a.matchId == b.matchId
            override fun areContentsTheSame(a: ReceivedLike, b: ReceivedLike) = a == b
        }
    }

    inner class LikeViewHolder(
        private val binding: ItemLikeGridBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(like: ReceivedLike) = with(binding) {
            // No photo backend for other users, so the tile keeps the neutral
            // placeholder set in the layout.
            tvLikeName.text = like.likerName

            val location = formatCityCountry(like.profile)
            if (location == null) {
                tvLikeLocation.visibility = View.GONE
            } else {
                tvLikeLocation.visibility = View.VISIBLE
                tvLikeLocation.text = location
            }

            root.setOnClickListener { onClick(like) }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): LikeViewHolder {
        val binding = ItemLikeGridBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return LikeViewHolder(binding)
    }

    override fun onBindViewHolder(holder: LikeViewHolder, position: Int) {
        holder.bind(getItem(position))
    }
}

/** "City, Country", or null if either half is missing. */
private fun formatCityCountry(profile: UserProfile): String? {
    val city = profile.city?.takeIf { it.isNotBlank() }
    val country = profile.country?.takeIf { it.isNotBlank() }
    return when {
        city != null && country != null -> "$city, $country"
        else -> city ?: country
    }
}
