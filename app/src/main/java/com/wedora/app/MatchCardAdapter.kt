package com.wedora.app

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.wedora.app.databinding.ItemMatchCardBinding

class MatchCardAdapter(
    private val onCardClick: (MatchCard) -> Unit = {},
    private val onLike: (MatchCard) -> Unit = {},
    private val onSuperlike: (MatchCard) -> Unit = {},
    private val onPass: (MatchCard) -> Unit = {},
    private val onChat: (MatchCard) -> Unit = {},
    private val onDismiss: (MatchCard) -> Unit = {},
    private val onMore: (MatchCard) -> Unit = {}
) : ListAdapter<MatchCard, MatchCardAdapter.CardViewHolder>(DIFF) {

    private companion object {
        val DIFF = object : DiffUtil.ItemCallback<MatchCard>() {
            override fun areItemsTheSame(a: MatchCard, b: MatchCard) = a.id == b.id
            override fun areContentsTheSame(a: MatchCard, b: MatchCard) = a == b
        }
    }

    /**
     * UIDs liked during this session, so the filled heart survives the card
     * being recycled out of view and scrolled back to.
     *
     * Session-scoped only — it isn't seeded from Firestore, so a like made on
     * a previous run shows as unliked until the screen is revisited. Seeding it
     * would need the match query that commit 2 introduces.
     */
    private val likedUserIds = mutableSetOf<String>()

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

            // Prefer the real age/location line now that Complete Profile
            // supplies it; fall back to a bio if one ever exists, then to the
            // honest "No bio yet" placeholder.
            tvCardBio.text = card.ageLocationLine(root.context)
                ?: card.bio.ifBlank { root.context.getString(R.string.match_card_no_bio) }

            val distanceKm = card.distanceKm
            if (distanceKm == null) {
                tvDistance.visibility = View.GONE
            } else {
                tvDistance.visibility = View.VISIBLE
                tvDistance.text = root.context.getString(R.string.home_distance_format, distanceKm.toString())
            }

            // The action buttons consume their own taps, so this only fires
            // for presses on the card body itself.
            root.setOnClickListener { onCardClick(card) }

            btnLike.setImageResource(
                if (likedUserIds.contains(card.id)) R.drawable.ic_like_filled
                else R.drawable.ic_like_outline
            )

            btnLike.setOnClickListener {
                // Filled straight away rather than waiting on the write: the
                // match write is idempotent, and an unresponsive heart is worse
                // than one that turns red slightly optimistically. Updating the
                // view directly avoids a rebind and a position lookup that can
                // come back NO_POSITION mid-recycle.
                likedUserIds.add(card.id)
                btnLike.setImageResource(R.drawable.ic_like_filled)
                onLike(card)
            }
            btnSuperlike.setOnClickListener { onSuperlike(card) }
            btnPass.setOnClickListener { onPass(card) }
            btnChat.setOnClickListener { onChat(card) }
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
