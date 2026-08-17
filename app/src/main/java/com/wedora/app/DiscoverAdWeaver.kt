package com.wedora.app

import android.content.Context
import com.google.android.gms.ads.nativead.NativeAd

/**
 * Reserves ad positions in a Discover grid's item list at [AlternatingAdGap]'s
 * cadence and fills them as ads load, backed by its own [NativeAdPool] —
 * extracted out of ExploreActivity so it and [DiscoverListActivity] share one
 * copy rather than each carrying a parallel implementation.
 *
 * Reserve-then-fill, not insert-on-arrival: every slot exists from the first
 * render, so the list's length never changes and no tile ever shifts. See
 * [buildItems] and [fillEmptySlot].
 *
 * One instance per screen — owns its own pool, never shared across
 * activities, same reasoning as [NativeAdPool] itself.
 */
class DiscoverAdWeaver(context: Context, private val adapter: DiscoverAdapter) {

    private companion object {
        /**
         * How many ad positions this screen reserves, regardless of how many
         * the cadence would otherwise produce.
         *
         * Under reserve-then-fill an unfilled slot is visible, so this is a
         * direct trade rather than a free maximum: a 24-profile page would
         * hit the 3/4 cadence about seven times, and reserving all seven
         * against a pool that realistically fills three would leave four
         * placeholders sitting there permanently on a no-fill.
         */
        const val MAX_AD_SLOTS = 3
    }

    /**
     * Target matches [MAX_AD_SLOTS] — the pool exists to fill the slots this
     * screen reserves, so loading more than that would buy nothing. 3 rather
     * than the default 2 because reservation makes an unfilled slot visible
     * (a placeholder that never resolves), so the number is now a direct
     * trade: more inventory against more ways to look broken on a no-fill.
     * This unit has returned code=3 (NO_FILL) in production.
     *
     * Scoped to this screen only — Home, Likes and Chat keep the default.
     */
    private val adPool =
        NativeAdPool(context, NativeAdLoader.AD_UNIT_ID_EXPLORE, target = MAX_AD_SLOTS)

    /**
     * The list last handed to [adapter], tracked here rather than read back
     * from `adapter.currentList`.
     *
     * This is the fix for a real race: submitList is asynchronous
     * (AsyncListDiffer), so `currentList` still returns the PREVIOUS list
     * until the diff commits — typically empty on a first render. An ad
     * finishing inside that window would find no slots to fill and be pooled
     * instead of placed. Home never had this bug because its own list is
     * plainly assigned rather than read back from a differ.
     *
     * Kept correct by routing every submission through [submitItems] —
     * nothing else may call adapter.submitList, or this drifts out of step
     * with what's on screen.
     */
    private var submittedItems: List<DiscoverGridItem> = emptyList()

    /**
     * Kicks off a preload so a slot is very likely already filled by the
     * time [buildItems] first needs one. No-op for a Premium user, since
     * [NativeAdPool.refill] re-checks isPremium on every call.
     */
    fun preload() {
        adPool.refill { ad -> fillEmptySlot(ad) }
    }

    /**
     * Renders [profiles] with ad slots reserved, and submits the result.
     *
     * Submission belongs to the weaver rather than the caller so
     * [submittedItems] can't fall out of step with what the adapter holds —
     * that invariant is what lets [fillEmptySlot] find a slot by searching
     * the list it just submitted.
     */
    fun submitProfiles(profiles: List<DiscoverProfile>) {
        submitItems(buildItems(profiles))
    }

    /**
     * Points [submittedItems] and the adapter at [items], destroying any ad
     * the outgoing list held that the incoming one doesn't.
     *
     * [buildItems] reconstructs purely from profiles, so ads placed in the
     * previous list are dropped from it — and they came from adPool.poll(),
     * so they aren't back in the pool either. That left them in neither set
     * [destroy] cleans up, and a NativeAd holds its resources until it is
     * explicitly released. DiscoverListActivity rebuilds on every Load More
     * tap, so the orphans accumulated a page's worth at a time.
     *
     * Compares by identity, which is what NativeAd gives us: a retained ad is
     * literally the same object carried into the new list, as happens when
     * [fillEmptySlot] copies a slot forward with its ad attached.
     *
     * The destroy runs before submitList, which is asynchronous — so a
     * visible ad view can briefly be bound to a just-destroyed ad and render
     * blank until the diff commits. Destroying afterwards wouldn't close that
     * window either (the diff still lands later), and it only shows on a
     * rebuild that drops a currently-visible ad.
     */
    private fun submitItems(items: List<DiscoverGridItem>) {
        val retained = items.mapNotNullTo(mutableSetOf()) { (it as? DiscoverGridItem.AdSlot)?.ad }
        submittedItems.forEach { item ->
            val ad = (item as? DiscoverGridItem.AdSlot)?.ad ?: return@forEach
            if (ad !in retained) ad.destroy()
        }
        submittedItems = items
        adapter.submitList(items)
    }

    /**
     * Empties the grid — for the empty, search-empty and guest-limit states.
     *
     * Nothing to reset beyond the list itself: with the slots living in the
     * list, emptying it removes them, and a later fillEmptySlot simply finds
     * no empty slot and pools the ad instead.
     */
    fun clear() {
        // Through submitItems, so emptying the grid releases the ads it was
        // holding rather than orphaning them — the empty-state variant of the
        // same leak.
        submitItems(emptyList())
    }

    /**
     * [profiles] with ad slots RESERVED at [AlternatingAdGap]'s cadence —
     * every slot present from the first render, filled or not.
     *
     * This is the reserve-then-fill model that replaced insert-and-shift. The
     * old build produced a list whose length grew as ads arrived, pushing
     * every following tile down; here the length is final the moment this
     * returns, and an arriving ad only changes one item's contents.
     *
     * poll() is a fast path, not the mechanism: a warm pool means a slot is
     * born filled and never shows its placeholder. An empty pool costs
     * nothing but a placeholder until [fillEmptySlot] runs.
     *
     * Capped at [MAX_AD_SLOTS] rather than honouring every cadence position.
     * Reserving a position the pool can't fill leaves a placeholder that never
     * resolves, so the cap is what bounds how much of the grid can be sitting
     * empty on a no-fill. Later cadence hits are simply skipped — adGap still
     * ticks, so raising the cap needs no other change.
     */
    private fun buildItems(profiles: List<DiscoverProfile>): List<DiscoverGridItem> {
        if (PremiumStatus.isPremium()) return profiles.map { DiscoverGridItem.Profile(it) }

        val adGap = AlternatingAdGap()
        val items = mutableListOf<DiscoverGridItem>()
        var slotsUsed = 0
        profiles.forEach { profile ->
            items += DiscoverGridItem.Profile(profile)
            if (adGap.afterProfile() && slotsUsed < MAX_AD_SLOTS) {
                items += DiscoverGridItem.AdSlot(slotId = slotsUsed, ad = adPool.poll())
                slotsUsed++
            }
        }
        adPool.refill { ad -> fillEmptySlot(ad) }
        return items
    }

    /**
     * Puts [ad] in the first reserved slot that hasn't got one yet.
     *
     * No index arithmetic at all — the slot is found by searching for an
     * empty one, so there are no stored positions to keep in step, nothing to
     * shift when the list changes, and no bounds check to get wrong. That
     * whole class of bookkeeping (the old pendingAdSlots list) is what this
     * architecture removes.
     *
     * The `this[index] = ...` below is a REPLACEMENT, and here that is
     * correct — the opposite of the bug it looks like. It swaps an AdSlot for
     * the same AdSlot carrying an ad; nothing is displaced, and the list
     * length is unchanged. The earlier defect was this same operator applied
     * to a position holding a *Profile*, which destroyed it. Do not "fix"
     * this into an insert: an insert here would add a second slot beside the
     * placeholder and shift the grid, which is exactly what reservation
     * exists to prevent.
     *
     * Returns whether [ad] was used, so [adPool] knows whether to pool it
     * instead (see [NativeAdPool.refill]).
     */
    private fun fillEmptySlot(ad: NativeAd): Boolean {
        val currentItems = submittedItems
        val index = currentItems.indexOfFirst {
            it is DiscoverGridItem.AdSlot && it.ad == null
        }
        if (index < 0) return false

        val slot = currentItems[index] as DiscoverGridItem.AdSlot
        submitItems(
            currentItems.toMutableList().apply { this[index] = slot.copy(ad = ad) }
        )
        return true
    }

    /**
     * Releases every ad this instance ever loaded — shown in the grid right
     * now or still sitting in the pool. Must be called from the host
     * Activity's onDestroy; a native ad holds its own resources until
     * explicitly told to let go.
     */
    fun destroy() {
        submittedItems.forEach { item -> (item as? DiscoverGridItem.AdSlot)?.ad?.destroy() }
        adPool.destroyAll()
    }
}
