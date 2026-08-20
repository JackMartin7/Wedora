package com.wedora.app

import androidx.fragment.app.FragmentActivity

/**
 * Guests only — signed-in users get [cards] back unchanged. Shares
 * [GuestPrefs]' single daily pool across every screen that renders the
 * discovery feed, bounded preview or full list alike — Home's swipe stack,
 * Explore's Discover grid/strip, and both of Explore's own "See All"
 * screens ([DiscoverListActivity]) — so a guest can't
 * dodge the cap by switching screens, extracted here so each of those
 * doesn't carry its own copy.
 *
 * Truncated once against whatever's already loaded, not counted
 * incrementally per item — every caller here renders its list (or a page of
 * it) all at once rather than binding lazily as the user scrolls, so
 * "count on bind" would spend the whole day's pool the instant the screen
 * opens either way. Deciding the cut once, up front, against what's already
 * loaded produces the same result without pretending this is a
 * scroll-driven reveal it isn't.
 *
 * Shows [GuestProfileLimitBottomSheet] the moment this call is what
 * exhausts the pool — the receiver must implement
 * [GuestProfileLimitBottomSheet.Host].
 */
fun <T> FragmentActivity.applyGuestProfileViewLimit(cards: List<T>): List<T> {
    if (!GuestPrefs.isGuest(this)) return cards

    val remaining = GuestPrefs.DAILY_PROFILE_VIEW_LIMIT - GuestPrefs.guestProfilesViewedToday(this)
    if (remaining <= 0) return emptyList()

    val allowed = cards.take(remaining)
    var newTotal = GuestPrefs.guestProfilesViewedToday(this)
    repeat(allowed.size) { newTotal = GuestPrefs.recordGuestProfileViewed(this) }
    if (newTotal >= GuestPrefs.DAILY_PROFILE_VIEW_LIMIT) {
        GuestProfileLimitBottomSheet.show(supportFragmentManager)
    }
    return allowed
}
