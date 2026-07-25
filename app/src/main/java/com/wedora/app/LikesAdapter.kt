package com.wedora.app

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.google.android.gms.ads.nativead.NativeAd
import com.wedora.app.databinding.ItemLikeGridBinding
import com.wedora.app.databinding.ItemNativeAdLikeGridBinding

/**
 * One tile in the Likes grid — either a real liker or (for non-Premium
 * signed-in users) a native ad, woven in by LikesActivity at
 * [FirstTwoThenFourAdGap]'s cadence — a single gap of 2 then a fixed
 * repeating gap of 4, deliberately different from the alternating 3/4
 * cadence the swipe stack and Discover grid share (see that class's own
 * doc comment).
 */
sealed class LikesGridItem {
    data class Like(val like: ReceivedLike) : LikesGridItem()
    data class Ad(val ad: NativeAd) : LikesGridItem()
}

/** Grid of users who liked you (and, interleaved, ads). */
class LikesAdapter(
    private val onClick: (ReceivedLike) -> Unit = {}
) : ListAdapter<LikesGridItem, RecyclerView.ViewHolder>(DIFF) {

    private companion object {
        const val VIEW_TYPE_LIKE = 0
        const val VIEW_TYPE_AD = 1

        val DIFF = object : DiffUtil.ItemCallback<LikesGridItem>() {
            override fun areItemsTheSame(a: LikesGridItem, b: LikesGridItem): Boolean =
                when {
                    a is LikesGridItem.Like && b is LikesGridItem.Like ->
                        a.like.matchId == b.like.matchId
                    a is LikesGridItem.Ad && b is LikesGridItem.Ad -> a.ad === b.ad
                    else -> false
                }

            override fun areContentsTheSame(a: LikesGridItem, b: LikesGridItem) = a == b
        }
    }

    inner class LikeViewHolder(
        private val binding: ItemLikeGridBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(like: ReceivedLike) = with(binding) {
            ivLikePhoto.loadRemoteProfilePhoto(like.profile.photoUrl)
            tvLikeName.text = like.likerName
            onlineDot.root.bindOnlineDot(like.profile.lastSeen)

            val location = formatCityCountry(like.profile)
            if (location == null) {
                tvLikeLocation.visibility = View.GONE
            } else {
                tvLikeLocation.visibility = View.VISIBLE
                tvLikeLocation.text = location
            }

            if (like.distanceBadge == null) {
                tvLikeDistance.visibility = View.GONE
            } else {
                tvLikeDistance.visibility = View.VISIBLE
                tvLikeDistance.text = like.distanceBadge
            }

            root.setOnClickListener { onClick(like) }
        }
    }

    inner class AdViewHolder(
        private val binding: ItemNativeAdLikeGridBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(ad: NativeAd) = with(binding) {
            tvAdHeadline.text = ad.headline
            tvAdCta.text = ad.callToAction
            tvAdCta.visibility = if (ad.callToAction.isNullOrBlank()) View.GONE else View.VISIBLE

            root.headlineView = tvAdHeadline
            root.callToActionView = tvAdCta
            root.mediaView = adMedia
            root.setNativeAd(ad)
        }
    }

    override fun getItemViewType(position: Int): Int = when (getItem(position)) {
        is LikesGridItem.Like -> VIEW_TYPE_LIKE
        is LikesGridItem.Ad -> VIEW_TYPE_AD
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return if (viewType == VIEW_TYPE_AD) {
            AdViewHolder(ItemNativeAdLikeGridBinding.inflate(inflater, parent, false))
        } else {
            LikeViewHolder(ItemLikeGridBinding.inflate(inflater, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val item = getItem(position)) {
            is LikesGridItem.Like -> (holder as LikeViewHolder).bind(item.like)
            is LikesGridItem.Ad -> (holder as AdViewHolder).bind(item.ad)
        }
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
