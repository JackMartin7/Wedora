package com.wedora.app

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.google.android.gms.ads.nativead.NativeAd
import com.wedora.app.databinding.ItemDiscoverGridBinding
import com.wedora.app.databinding.ItemNativeAdGridBinding

/**
 * A browsable profile in the Explore "Discover" grid. [meta] is the pre-built
 * age/location line (null when the person hasn't filled in enough to show one).
 */
data class DiscoverProfile(
    val userId: String,
    val name: String,
    val meta: String?,
    val photoUrl: String?
)

/**
 * One tile in the grid — either a real profile or (for non-Premium users) a
 * native ad, woven in every [AD_INTERVAL] profiles by ExploreActivity, the
 * same cadence and shared [NativeAdPool] HomeActivity's swipe stack uses.
 */
sealed class DiscoverGridItem {
    data class Profile(val profile: DiscoverProfile) : DiscoverGridItem()
    data class Ad(val ad: NativeAd) : DiscoverGridItem()
}

/**
 * 2-column grid of discoverable profiles (and, interleaved, ads), mirroring
 * the Likes grid's tile style. Every tile — profile or ad — is the same 1:1
 * square footprint, so no custom span logic is needed for the ad type.
 */
class DiscoverAdapter(
    private val onClick: (DiscoverProfile) -> Unit = {}
) : ListAdapter<DiscoverGridItem, RecyclerView.ViewHolder>(DIFF) {

    private companion object {
        const val VIEW_TYPE_PROFILE = 0
        const val VIEW_TYPE_AD = 1

        val DIFF = object : DiffUtil.ItemCallback<DiscoverGridItem>() {
            override fun areItemsTheSame(a: DiscoverGridItem, b: DiscoverGridItem): Boolean =
                when {
                    a is DiscoverGridItem.Profile && b is DiscoverGridItem.Profile ->
                        a.profile.userId == b.profile.userId
                    a is DiscoverGridItem.Ad && b is DiscoverGridItem.Ad -> a.ad === b.ad
                    else -> false
                }

            override fun areContentsTheSame(a: DiscoverGridItem, b: DiscoverGridItem) = a == b
        }
    }

    inner class DiscoverViewHolder(
        private val binding: ItemDiscoverGridBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(profile: DiscoverProfile) = with(binding) {
            ivDiscoverPhoto.loadRemoteProfilePhoto(profile.photoUrl)
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

    inner class AdViewHolder(
        private val binding: ItemNativeAdGridBinding
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
        is DiscoverGridItem.Profile -> VIEW_TYPE_PROFILE
        is DiscoverGridItem.Ad -> VIEW_TYPE_AD
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return if (viewType == VIEW_TYPE_AD) {
            AdViewHolder(ItemNativeAdGridBinding.inflate(inflater, parent, false))
        } else {
            DiscoverViewHolder(ItemDiscoverGridBinding.inflate(inflater, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val item = getItem(position)) {
            is DiscoverGridItem.Profile -> (holder as DiscoverViewHolder).bind(item.profile)
            is DiscoverGridItem.Ad -> (holder as AdViewHolder).bind(item.ad)
        }
    }
}
