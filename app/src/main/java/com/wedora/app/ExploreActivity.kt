package com.wedora.app

import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.activity.addCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.firebase.firestore.FirebaseFirestore
import com.wedora.app.databinding.ActivityExploreBinding

/**
 * The Explore tab: the closest discoverable people along the top ("People
 * Nearby"), and a preview grid of the rest below ("Discover").
 *
 * Both sections are the same feed — opposite gender, minus everyone blocked,
 * passed or already liked, within the distance filter — sorted closest first
 * (see [loadDiscoveryFeed]). Both are previews, not the full lists: Nearby's
 * "See All" opens [DiscoverListActivity]; Discover's own "See All" and "Load
 * More" both open [DiscoverListActivity] — two entry points to the same
 * destination, not two different ones (Load More is just a second, more
 * natural place to reach it from the bottom of the preview grid).
 */
class ExploreActivity : WedoraBaseActivity(), GuestProfileLimitBottomSheet.Host {

    private companion object {
        /**
         * Below this many qualifying people, the Trending strip hides entirely
         * rather than showing a near-empty row. Two avatars under a "TRENDING"
         * heading reads as broken, not as a shortlist.
         */
        const val MIN_TRENDING = 5

        /** How many of the closest people the horizontal strip previews. */
        const val NEARBY_STRIP_MAX = 12
        /** How many of the Discover grid's profiles this screen previews. */
        const val DISCOVER_GRID_MAX = 12
        const val GRID_COLUMNS = 2
    }

    private lateinit var binding: ActivityExploreBinding
    private val firestore: FirebaseFirestore by lazy { FirebaseFirestore.getInstance() }

    private val nearbyAdapter = NearbyAdapter { person ->
        startActivity(ProfileDetailActivity.intent(this, person.userId))
    }
    private val trendingAdapter = NearbyAdapter { person ->
        startActivity(ProfileDetailActivity.intent(this, person.userId))
    }

    private val discoverAdapter = DiscoverAdapter { profile ->
        startActivity(ProfileDetailActivity.intent(this, profile.userId))
    }

    /**
     * The full loaded feed, kept so search can filter it in memory rather than
     * re-querying. Search matches name/city/country, which is why the source is
     * the [MatchCard]s and not the display-only [DiscoverProfile]s.
     */
    private var discoverCards: List<MatchCard> = emptyList()

    /**
     * Weaves native ads into the Discover grid — shared with
     * [DiscoverListActivity] rather than each screen carrying its own copy
     * of the pooling/backfill logic. See [DiscoverAdWeaver]'s own doc
     * comment.
     */
    private val adWeaver by lazy { DiscoverAdWeaver(this, discoverAdapter) }

    /**
     * Only reloads on RESULT_OK — i.e. Apply — matching Home. Backing out of the
     * filter screen changes nothing, so there's no need to re-query.
     */
    private val filterLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            showFilterIndicator()
            loadFeed()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityExploreBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applyBottomNavScreenInsets(binding.root, binding.bottomNav)

        binding.rvNearby.layoutManager =
            LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        binding.rvNearby.adapter = nearbyAdapter

        binding.rvTrending.layoutManager =
            LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        binding.rvTrending.adapter = trendingAdapter

        binding.rvDiscover.layoutManager = GridLayoutManager(this, GRID_COLUMNS)
        binding.rvDiscover.adapter = discoverAdapter

        setUpWedoraBottomNav(binding.bottomNav, R.id.nav_maps)
        // Registered before the search-collapse callback below, so that one
        // (added later, i.e. on top of the back-press callback stack) always
        // gets first refusal — search collapses before this ever fires.
        setUpExitConfirmOnBackPress {
            val (kind, count) = resolveExitConfirmKind(
                unseenLikes = binding.bottomNav.currentBadgeCount(R.id.nav_match),
                unreadMessages = binding.bottomNav.currentBadgeCount(R.id.nav_chats)
            )
            ExitConfirmBottomSheet.show(supportFragmentManager, kind, count)
        }

        binding.btnFilter.setOnClickListener {
            filterLauncher.launch(Intent(this, FilterActivity::class.java))
        }
        showFilterIndicator()

        binding.btnSearch.setOnClickListener { expandSearch() }
        binding.btnSearchClose.setOnClickListener { collapseSearch() }
        binding.etSearch.addTextChangedListener(SimpleTextWatcher {
            applyDiscoverFilter(currentQuery())
        })

        binding.tvNearbySeeAll.setOnClickListener {
            startActivity(DiscoverListActivity.intent(this, DiscoverListActivity.Mode.NEARBY))
        }

        binding.tvTrendingSeeAll.setOnClickListener {
            startActivity(DiscoverListActivity.intent(this, DiscoverListActivity.Mode.TRENDING))
        }

        // See All and Load More both just open the full grid — Load More is
        // a second, more natural entry point at the bottom of the preview
        // rather than a different destination.
        binding.tvDiscoverSeeAll.setOnClickListener {
            startActivity(DiscoverListActivity.intent(this, DiscoverListActivity.Mode.DISCOVER))
        }
        binding.btnDiscoverLoadMore.setOnClickListener {
            startActivity(DiscoverListActivity.intent(this, DiscoverListActivity.Mode.DISCOVER))
        }

        // Kicked off before loadFeed's own network round trip — same
        // "preload ahead" reasoning as HomeActivity's swipe stack, so an ad
        // is very likely already sitting in the pool by the time the grid
        // actually needs one. No-ops for a Premium user, since
        // NativeAdPool.refill re-checks isPremium on every call.
        adWeaver.preload()

        // While the search bar is open, back collapses it rather than leaving
        // the tab.
        onBackPressedDispatcher.addCallback(this) {
            if (binding.searchBar.visibility == View.VISIBLE) {
                collapseSearch()
            } else {
                isEnabled = false
                onBackPressedDispatcher.onBackPressed()
            }
        }

        loadFeed()
    }

    /**
     * Native ads hold on to their own resources until explicitly released —
     * everything ever loaded, whether currently shown in the grid or still
     * waiting in the pool, needs destroy() so it doesn't leak past this
     * Activity. Same reasoning as HomeActivity.onDestroy.
     */
    override fun onDestroy() {
        adWeaver.destroy()
        super.onDestroy()
    }

    // ----- Search -----------------------------------------------------------

    private fun currentQuery(): String = binding.etSearch.text?.toString().orEmpty()

    private fun expandSearch() {
        setTopBarButtonsVisible(false)
        binding.searchBar.visibility = View.VISIBLE
        binding.searchBar.alpha = 0f
        binding.searchBar.translationX = 16 * resources.displayMetrics.density
        binding.searchBar.animate().alpha(1f).translationX(0f).setDuration(200).start()
        binding.etSearch.showKeyboard()
    }

    private fun collapseSearch() {
        binding.etSearch.hideKeyboard()
        // Clearing the field restores the full grid through the text watcher.
        binding.etSearch.setText("")
        binding.searchBar.animate().alpha(0f).setDuration(150).withEndAction {
            binding.searchBar.visibility = View.GONE
            binding.searchBar.translationX = 0f
        }.start()
        setTopBarButtonsVisible(true)
    }

    private fun setTopBarButtonsVisible(visible: Boolean) {
        val v = if (visible) View.VISIBLE else View.GONE
        binding.tvExploreTitle.visibility = v
        binding.btnSearch.visibility = v
        binding.btnFilter.visibility = v
        // The dot follows the filter state, not a blanket toggle.
        if (visible) showFilterIndicator() else binding.filterDot.visibility = View.GONE
    }

    /**
     * Filters the loaded feed by [query] against name/city/country, in memory —
     * no Firestore round trip. Blank query restores the full grid.
     */
    private fun applyDiscoverFilter(query: String) {
        // Nothing loaded (empty feed / guest): render already showed the right
        // empty state, so don't overwrite it with an empty grid.
        if (discoverCards.isEmpty()) return

        val q = query.trim()
        val filtered =
            if (q.isEmpty()) discoverCards
            else discoverCards.filter { it.matchesSearch(q) }

        if (filtered.isEmpty()) {
            showDiscoverSearchEmpty(q)
        } else {
            showDiscover(
                filtered.map {
                    DiscoverProfile(it.id, it.name, it.ageLocationLine(this), it.photoUrl, it.distanceBadge())
                }
            )
        }
    }

    private fun MatchCard.matchesSearch(query: String): Boolean {
        val q = query.lowercase()
        return name.lowercase().contains(q) ||
            city?.lowercase()?.contains(q) == true ||
            country?.lowercase()?.contains(q) == true
    }

    /** Accent dot over the filter icon whenever anything differs from default. */
    private fun showFilterIndicator() {
        binding.filterDot.visibility =
            if (FilterPrefs.hasActiveFilters(this)) View.VISIBLE else View.GONE
    }

    // ----- Feed -------------------------------------------------------------

    /**
     * Whether it's still safe to touch views or start a Glide/native-ad load
     * on this Activity instance. Toggling dark/light mode recreates the
     * Activity, and [loadDiscoveryFeed]'s one-shot Firestore read has no way
     * to be cancelled once in flight — same class of bug HomeActivity's
     * feed load had (see its own isUsable doc comment), applied here
     * proactively rather than waiting to hit the identical crash.
     */
    private fun isUsable(): Boolean = !isFinishing && !isDestroyed

    private fun loadFeed() {
        showLoading()
        loadDiscoveryFeed(this, firestore) { cards -> render(cards) }
    }

    private fun render(cards: List<MatchCard>) {
        if (!isUsable()) return
        binding.progressDiscover.visibility = View.GONE

        if (cards.isEmpty()) {
            discoverCards = cards
            showNearbyEmpty()
            binding.sectionTrending.visibility = View.GONE
            showDiscoverEmpty()
            return
        }

        val nearby = cards.take(NEARBY_STRIP_MAX).map {
            NearbyPerson(it.id, it.name, it.distanceBadge(), it.photoUrl)
        }
        showNearby(nearby)
        showTrending(cards)

        // Neither strip above is part of this cap — only the Discover grid is,
        // per applyGuestProfileViewLimit's own doc comment — so this only ever
        // trims what showDiscover / applyDiscoverFilter render, not what
        // showNearby and showTrending put on screen. Trending follows Nearby
        // here deliberately: they are the same pool in a different order, so
        // capping one and not the other would be arbitrary.
        discoverCards = applyGuestProfileViewLimit(cards)
        if (discoverCards.isEmpty() && cards.isNotEmpty() && GuestPrefs.isGuest(this)) {
            showDiscoverGuestLimitReached()
            return
        }

        // Re-apply any active search rather than always showing the full grid,
        // so a reload (e.g. after Apply Filters) doesn't drop the user's query.
        applyDiscoverFilter(currentQuery())
    }

    private fun showDiscoverGuestLimitReached() {
        binding.rvDiscover.visibility = View.GONE
        binding.btnDiscoverLoadMore.visibility = View.GONE
        adWeaver.clear()
        binding.discoverEmpty.show(
            R.drawable.ic_sparkle_heart,
            R.string.guest_limit_empty_title,
            R.string.guest_limit_empty_subtitle,
            R.string.guest_limit_empty_action,
            onAction = { startActivity(Intent(this, SignUpActivity::class.java)) }
        )
    }

    override fun onSignUpFromGuestLimitRequested() {
        startActivity(Intent(this, SignUpActivity::class.java))
    }

    private fun showLoading() {
        binding.discoverEmpty.root.visibility = View.GONE
        binding.rvDiscover.visibility = View.GONE
        binding.btnDiscoverLoadMore.visibility = View.GONE
        binding.progressDiscover.visibility = View.VISIBLE
        // Nearby has no separate spinner; it just fills in with the same result.
        binding.rvNearby.visibility = View.GONE
        binding.tvNearbyEmpty.visibility = View.GONE
        binding.sectionTrending.visibility = View.GONE
    }

    private fun showNearby(people: List<NearbyPerson>) {
        binding.tvNearbyEmpty.visibility = View.GONE
        binding.rvNearby.visibility = View.VISIBLE
        nearbyAdapter.submitList(people)
    }

    /**
     * Fills the Trending strip from the SAME pool the rest of this screen uses,
     * re-sorted by likes received - no second query, and no risk of showing
     * someone the feed itself would have excluded.
     *
     * The strip hides rather than shrinks below [MIN_TRENDING] qualifying
     * people. There is no empty-state message: unlike Nearby, whose emptiness
     * the user can act on by widening their distance filter, an absent Trending
     * strip is not something they can do anything about, so saying so would be
     * noise.
     */
    private fun showTrending(cards: List<MatchCard>) {
        val trending = cards.asTrending()
        if (trending.size < MIN_TRENDING) {
            binding.sectionTrending.visibility = View.GONE
            return
        }
        binding.sectionTrending.visibility = View.VISIBLE
        trendingAdapter.submitList(
            trending.take(NEARBY_STRIP_MAX).map {
                NearbyPerson(
                    it.id,
                    it.name,
                    resources.getQuantityString(
                        R.plurals.trending_like_count,
                        it.likesReceivedCount,
                        formatCompactCount(it.likesReceivedCount)
                    ),
                    it.photoUrl
                )
            }
        )
    }

    private fun showNearbyEmpty() {
        binding.rvNearby.visibility = View.GONE
        nearbyAdapter.submitList(emptyList())
        binding.tvNearbyEmpty.visibility = View.VISIBLE
    }

    /**
     * Caps the grid at [DISCOVER_GRID_MAX] — the same "truncate once, up
     * front" reasoning as [applyGuestProfileViewLimit] just above it, and for
     * the same structural reason: rvDiscover has nested scrolling disabled
     * inside a NestedScrollView, so GridLayoutManager lays out every item at
     * once rather than binding lazily as the user scrolls. Load More and See
     * All both just open [DiscoverListActivity] with the full list — see
     * this Activity's own doc comment — so the button here is shown whenever
     * there's more than the preview to see, regardless of whether that's the
     * full feed or a search's filtered results.
     */
    private fun showDiscover(profiles: List<DiscoverProfile>) {
        binding.discoverEmpty.root.visibility = View.GONE
        binding.rvDiscover.visibility = View.VISIBLE
        adWeaver.submitProfiles(profiles.take(DISCOVER_GRID_MAX))
        binding.btnDiscoverLoadMore.visibility =
            if (profiles.size > DISCOVER_GRID_MAX) View.VISIBLE else View.GONE
    }

    private fun showDiscoverEmpty() {
        binding.rvDiscover.visibility = View.GONE
        binding.btnDiscoverLoadMore.visibility = View.GONE
        adWeaver.clear()
        binding.discoverEmpty.show(
            R.drawable.ic_sparkle_heart,
            R.string.empty_discover_title,
            R.string.empty_discover_subtitle,
            R.string.empty_action_adjust_filters,
            ::openFilters
        )
    }

    /** No feed matched the search query (as opposed to no feed at all). */
    private fun showDiscoverSearchEmpty(query: String) {
        binding.rvDiscover.visibility = View.GONE
        binding.btnDiscoverLoadMore.visibility = View.GONE
        adWeaver.clear()
        binding.discoverEmpty.show(
            R.drawable.ic_search,
            R.string.empty_search_title,
            getString(R.string.empty_search_no_results, query)
        )
    }

    private fun openFilters() {
        filterLauncher.launch(Intent(this, FilterActivity::class.java))
    }
}
