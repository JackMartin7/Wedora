package com.wedora.app

import android.content.Context
import com.google.android.gms.ads.nativead.NativeAd

/**
 * Decides which real profiles get an ad inserted right after them — an
 * alternating gap of 3, then 4, then 3, then 4... rather than a fixed
 * interval, so a non-Premium user (signed-in free or guest) doesn't see ads
 * land on a perfectly predictable rhythm. Shared by HomeActivity's swipe
 * stack and ExploreActivity's Discover grid so the two screens agree on
 * cadence without each hardcoding their own copy of the pattern.
 *
 * Stateful and single-use: create one per build (each call to
 * HomeActivity.buildDisplayItems / ExploreActivity.buildDiscoverGridItems),
 * never reused across builds — a fresh list always restarts the pattern at
 * a gap of 3, the same way the old fixed-interval version always restarted
 * counting from profile 1.
 */
class AlternatingAdGap {
    private companion object {
        const val FIRST_GAP = 3
        const val SECOND_GAP = 4
    }

    private var sinceLastAd = 0
    private var nextGap = FIRST_GAP

    /**
     * Call exactly once per real profile appended to the display list, in
     * order. Returns true exactly when an ad belongs immediately after the
     * profile just appended.
     */
    fun afterProfile(): Boolean {
        sinceLastAd++
        if (sinceLastAd < nextGap) return false
        sinceLastAd = 0
        nextGap = if (nextGap == FIRST_GAP) SECOND_GAP else FIRST_GAP
        return true
    }
}

/**
 * Keeps up to [target] native ads loaded and ready, and refills itself as
 * they're consumed — the shared shape behind both HomeActivity's swipe stack
 * and ExploreActivity's Discover grid, extracted so the pool/refill/backfill
 * machinery isn't duplicated between the two. What differs between the two
 * screens is only what "insert this ad" means for their own display list
 * (a SwipeCardStackView position vs. a RecyclerView/ListAdapter position),
 * which is why this class owns loading and pooling only, not insertion.
 *
 * [poll] never waits on a fresh load — a caller building its display list
 * draws only from whatever's already here, so a slow or failed ad request
 * never delays or blocks the screen; it just means fewer ad slots get filled
 * this pass. [refill] is how a caller both tops the pool back up AND gets
 * first refusal on every ad that finishes loading, for backfilling a slot it
 * had to skip earlier — see HomeActivity/ExploreActivity's own
 * `backfillPendingAdSlot` for that half of the pattern.
 */
class NativeAdPool(
    private val context: Context,
    private val target: Int = DEFAULT_TARGET
) {
    private companion object {
        /** How many native ads to keep loaded and ready at once. */
        const val DEFAULT_TARGET = 2
    }

    private val pool = ArrayDeque<NativeAd>()
    private var inFlight = 0

    /** Takes the next ready ad out of the pool, or null if none is ready yet. */
    fun poll(): NativeAd? = pool.removeFirstOrNull()

    /**
     * Tops the pool back up to [target], fire-and-forget — except a freshly
     * loaded ad tries [onBackfilled] first: if it returns true (the caller
     * used the ad to backfill a slot it had to skip earlier), the ad never
     * enters the pool; if false, it's added to the pool for a future [poll].
     * A successful backfill calls back into [refill] rather than pooling, so
     * with more than one outstanding backfill the pool still chases back up
     * to target and keeps trying for the rest.
     *
     * No-ops for a Premium user — checked here, not just by callers, since
     * this is the one place every ad request in the app actually goes out
     * from.
     */
    fun refill(onBackfilled: (NativeAd) -> Boolean = { false }) {
        if (PremiumStatus.isPremium()) return
        while (pool.size + inFlight < target) {
            inFlight++
            NativeAdLoader.loadAd(
                context,
                onLoaded = { ad ->
                    inFlight--
                    if (onBackfilled(ad)) refill(onBackfilled) else pool.addLast(ad)
                },
                onFailed = { inFlight-- }
            )
        }
    }

    /** Releases every ad still sitting in the pool — call from onDestroy. */
    fun destroyAll() {
        pool.forEach { it.destroy() }
        pool.clear()
    }
}
