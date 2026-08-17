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
    val photoUrl: String?,
    /** Null when either this user or the profile has no coordinates on file. */
    val distanceBadge: String? = null
)

/**
 * One tile in the grid — either a real profile or (for non-Premium users) a
 * native ad, woven in by ExploreActivity at [AlternatingAdGap]'s cadence —
 * the same pattern and shared [NativeAdPool] HomeActivity's swipe stack uses.
 */
sealed class DiscoverGridItem {
    data class Profile(val profile: DiscoverProfile) : DiscoverGridItem()

    /**
     * A position reserved for an ad before any ad exists, at the cadence
     * [AlternatingAdGap] decides — the reserve-then-fill half of the design.
     *
     * [ad] is null until one loads, and the slot renders a placeholder in the
     * meantime. What matters is that the slot is in the list from the first
     * render: filling it changes an item's contents, never the list's length,
     * so nothing around it moves. The previous model appended ads as they
     * arrived, which shifted every following tile down.
     *
     * [slotId] is the slot's identity, deliberately independent of [ad]:
     * DiffUtil uses it for areItemsTheSame, so an arriving ad reads as "this
     * item's contents changed" (rebind one position) rather than "a new item
     * appeared" (insert and shift).
     */
    data class AdSlot(val slotId: Int, val ad: NativeAd?) : DiscoverGridItem()
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
                    // By slotId, not by ad: a slot that just received its ad
                    // is still the SAME item, which is what turns a fill into
                    // a one-position rebind instead of an insertion.
                    a is DiscoverGridItem.AdSlot && b is DiscoverGridItem.AdSlot ->
                        a.slotId == b.slotId
                    else -> false
                }

            override fun areContentsTheSame(a: DiscoverGridItem, b: DiscoverGridItem): Boolean =
                when {
                    // Identity, not equals: NativeAd doesn't define equality,
                    // and "did this slot get filled" is exactly an identity
                    // question — null to non-null, or one ad swapped for
                    // another on a rebuild.
                    a is DiscoverGridItem.AdSlot && b is DiscoverGridItem.AdSlot ->
                        a.ad === b.ad
                    else -> a == b
                }
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

            if (profile.distanceBadge == null) {
                tvDiscoverDistance.visibility = View.GONE
            } else {
                tvDiscoverDistance.visibility = View.VISIBLE
                tvDiscoverDistance.text = profile.distanceBadge
            }

            root.setOnClickListener { onClick(profile) }
        }
    }

    inner class AdViewHolder(
        private val binding: ItemNativeAdGridBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        /**
         * [ad] null means the slot is reserved but unfilled — render the
         * placeholder and don't touch the NativeAdView's ad wiring.
         *
         * Both states are this one inflated layout, which is what makes the
         * "nothing resizes when the real ad arrives" guarantee structural
         * rather than a matter of matching two sets of dimensions by hand.
         */
        fun bind(ad: NativeAd?) = with(binding) {
            if (ad == null) {
                adLoading.visibility = View.VISIBLE
                adContent.visibility = View.INVISIBLE
                return@with
            }

            adLoading.visibility = View.GONE
            adContent.visibility = View.VISIBLE

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
        // Constant for the life of the slot, filled or not — the type never
        // flips under an already-inflated view.
        is DiscoverGridItem.AdSlot -> VIEW_TYPE_AD
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
            is DiscoverGridItem.AdSlot -> (holder as AdViewHolder).bind(item.ad)
        }
    }
}
