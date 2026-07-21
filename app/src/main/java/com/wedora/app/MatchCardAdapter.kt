package com.wedora.app

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.wedora.app.databinding.ItemMatchCardBinding

class MatchCardAdapter(
    private val onLike: (MatchCard) -> Unit = {},
    private val onSuperlike: (MatchCard) -> Unit = {},
    private val onPass: (MatchCard) -> Unit = {},
    private val onDismiss: (MatchCard) -> Unit = {},
    private val onMore: (MatchCard) -> Unit = {}
) : ListAdapter<MatchCard, MatchCardAdapter.CardViewHolder>(DIFF) {

    private companion object {
        val DIFF = object : DiffUtil.ItemCallback<MatchCard>() {
            override fun areItemsTheSame(a: MatchCard, b: MatchCard) = a.id == b.id
            override fun areContentsTheSame(a: MatchCard, b: MatchCard) = a == b
        }
    }

    inner class CardViewHolder(
        private val binding: ItemMatchCardBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(card: MatchCard) = with(binding) {
            ivCardAvatar.setImageResource(card.avatarRes)
            ivCardPhoto.setImageResource(card.photoRes)
            tvCardName.text = card.name

            if (card.role.isBlank()) {
                tvCardRole.visibility = View.GONE
            } else {
                tvCardRole.visibility = View.VISIBLE
                tvCardRole.text = card.role
            }

            tvCardBio.text = card.bio.ifBlank { root.context.getString(R.string.match_card_no_bio) }

            val distanceKm = card.distanceKm
            if (distanceKm == null) {
                tvDistance.visibility = View.GONE
            } else {
                tvDistance.visibility = View.VISIBLE
                tvDistance.text = root.context.getString(R.string.home_distance_format, distanceKm.toString())
            }

            btnLike.setOnClickListener { onLike(card) }
            btnSuperlike.setOnClickListener { onSuperlike(card) }
            btnPass.setOnClickListener { onPass(card) }
            btnDismiss.setOnClickListener { onDismiss(card) }
            btnMore.setOnClickListener { onMore(card) }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): CardViewHolder {
        val binding = ItemMatchCardBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return CardViewHolder(binding)
    }

    override fun onBindViewHolder(holder: CardViewHolder, position: Int) {
        holder.bind(getItem(position))
    }
}
