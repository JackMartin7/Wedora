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
 * Decides which real like cards get an ad inserted right after them — a
 * single gap of 2, then a fixed gap of 4 forever after (2, 4, 4, 4...), per
 * LikesActivity's own spec. Deliberately its own small sequencer rather than
 * a variant of [AlternatingAdGap]: that one toggles forever (3, 4, 3, 4...)
 * for the swipe stack and Discover grid, a genuinely different cadence from
 * this screen's "one 2, then repeating 4s".
 *
 * The first gap is 2, not 3: [LikesActivity]'s main grid is what's left
 * after its 2-tile featured teaser is carved out of the total like count
 * (see LikesActivity.showLikes), so a gap of 3 here meant an account needed
 * 5+ total likes before ever seeing an ad — confirmed via on-device
 * diagnostics showing adsInserted=0 for a real account with 4 total likes
 * (a remainder of 2, one short of the old threshold). 2 was chosen
 * specifically so the ad appears as soon as the grid has any real content
 * at all, without requiring an unrealistically large like count first.
 *
 * Stateful and single-use: create one per build (each call to
 * LikesActivity.buildLikesGridItems), never reused across builds.
 */
class FirstTwoThenFourAdGap {
    private companion object {
        const val FIRST_GAP = 2
        const val REPEATING_GAP = 4
    }

    private var sinceLastAd = 0
    private var isFirstGap = true

    /**
     * Call exactly once per real like card appended to the display list, in
     * order. Returns true exactly when an ad belongs immediately after the
     * card just appended.
     */
    fun afterLike(): Boolean {
        sinceLastAd++
        val gap = if (isFirstGap) FIRST_GAP else REPEATING_GAP
        if (sinceLastAd < gap) return false
        sinceLastAd = 0
        isFirstGap = false
        return true
    }
}

/**
 * Ad cadence for the conversation list: never before the 5th chat, then one
 * every 5 after that.
 *
 * Wider than the feed sequencers ([AlternatingAdGap]'s 3/4) on purpose. A
 * chat list is a utility surface people scan to find a specific person, not
 * a browse surface — the same density that reads as acceptable between
 * discovery cards reads as clutter here, and AdMob's ad-density guidance
 * points the same way. The leading gap of 5 also keeps an ad off the top of
 * the list entirely, where it would sit above real conversations.
 *
 * Stateful and single-use, like the other two: create one per build.
 */
class ChatAdGap {
    private companion object {
        const val GAP = 5
    }

    private var sinceLastAd = 0

    /** Call once per real chat row appended, in order. */
    fun afterChat(): Boolean {
        sinceLastAd++
        if (sinceLastAd < GAP) return false
        sinceLastAd = 0
        return true
    }
}

/**
 * Keeps up to [target] native ads loaded and ready, and refills itself as
 * they're consumed — the shared shape behind HomeActivity's swipe stack,
 * ExploreActivity's Discover grid, and LikesActivity's likes grid, extracted
 * so the pool/refill/backfill machinery isn't duplicated between them. Each
 * screen creates its own instance (ads aren't shared between screens); what
 * differs is only what "insert this ad" means for a given display list (a
 * SwipeCardStackView position vs. a RecyclerView/ListAdapter position),
 * which is why this class owns loading and pooling only, not insertion.
 *
 * [poll] never waits on a fresh load — a caller building its display list
 * draws only from whatever's already here, so a slow or failed ad request
 * never delays or blocks the screen; it just means fewer ad slots get filled
 * this pass. [refill] is how a caller both tops the pool back up AND gets
 * first refusal on every ad that finishes loading, for backfilling a slot it
 * had to skip earlier — see each screen's own `backfillPendingAdSlot` for
 * that half of the pattern.
 */
class NativeAdPool(
    private val context: Context,
    private val adUnitId: String,
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
                adUnitId,
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
