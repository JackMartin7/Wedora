package com.wedora.app

import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.activity.addCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.firebase.firestore.FirebaseFirestore
import com.wedora.app.databinding.ActivityExploreBinding

/**
 * The Explore tab: the closest discoverable people along the top ("People
 * Nearby"), and a browsable grid of the rest below ("Discover").
 *
 * Both sections are the same feed — opposite gender, minus everyone blocked,
 * passed or already liked, within the distance filter — sorted closest first
 * (see [loadDiscoveryFeed]). The strip is a quick glance at the nearest few;
 * the grid is the full list; "See All" opens it as its own scrollable screen.
 */
class ExploreActivity : AppCompatActivity(), GuestProfileLimitBottomSheet.Host {

    private companion object {
        /** How many of the closest people the horizontal strip previews. */
        const val NEARBY_STRIP_MAX = 12
        const val GRID_COLUMNS = 2
    }

    private lateinit var binding: ActivityExploreBinding
    private val firestore: FirebaseFirestore by lazy { FirebaseFirestore.getInstance() }

    private val nearbyAdapter = NearbyAdapter { person ->
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

        binding.rvNearby.layoutManager =
            LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        binding.rvNearby.adapter = nearbyAdapter

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
            startActivity(Intent(this, NearbyListActivity::class.java))
        }

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
            showDiscover(filtered.map { DiscoverProfile(it.id, it.name, it.ageLocationLine(this), it.photoUrl) })
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

    private fun loadFeed() {
        showLoading()
        loadDiscoveryFeed(this, firestore) { cards -> render(cards) }
    }

    private fun render(cards: List<MatchCard>) {
        binding.progressDiscover.visibility = View.GONE

        if (cards.isEmpty()) {
            discoverCards = cards
            showNearbyEmpty()
            showDiscoverEmpty()
            return
        }

        val nearby = cards.take(NEARBY_STRIP_MAX).map {
            NearbyPerson(it.id, it.name, it.distanceBadge(), it.photoUrl)
        }
        showNearby(nearby)

        // The Nearby strip above isn't part of this cap — only the Discover
        // grid is, per applyGuestProfileViewLimit's own doc comment — so this
        // only ever trims what showDiscover / applyDiscoverFilter render, not
        // showNearby's own list.
        discoverCards = applyGuestProfileViewLimit(cards)
        if (discoverCards.isEmpty() && cards.isNotEmpty() && GuestPrefs.isGuest(this)) {
            showDiscoverGuestLimitReached()
            return
        }

        // Re-apply any active search rather than always showing the full grid,
        // so a reload (e.g. after Apply Filters) doesn't drop the user's query.
        applyDiscoverFilter(currentQuery())
    }

    /**
     * Guests only — signed-in users get the full list back unchanged. Shares
     * GuestPrefs' single daily pool with Home's swipe stack (same reasoning
     * as that screen's own onTopCardChanged: "just seeing the card counts"),
     * so a guest can't dodge Home's cap by switching to Explore instead.
     *
     * Only the Discover grid is capped, not the Nearby strip above it — the
     * strip previews the same closest few people the grid would show anyway,
     * and gating it too would mean a guest at the daily limit sees an empty
     * Explore screen top to bottom rather than a normal strip with a frozen
     * grid underneath. Scoped this way for now; revisit if Nearby turns out
     * to be a real bypass in practice.
     *
     * Truncated once here at load, not counted incrementally per grid tile —
     * rvDiscover has nested scrolling disabled inside a NestedScrollView, so
     * GridLayoutManager lays out every item at once rather than binding them
     * lazily as the user scrolls, meaning "count on bind" would spend the
     * whole day's pool the instant the screen opens either way. Deciding the
     * cut once, up front, against what's already loaded produces the same
     * result without pretending this is a scroll-driven reveal it isn't.
     */
    private fun applyGuestProfileViewLimit(cards: List<MatchCard>): List<MatchCard> {
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

    private fun showDiscoverGuestLimitReached() {
        binding.rvDiscover.visibility = View.GONE
        discoverAdapter.submitList(emptyList())
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
        binding.progressDiscover.visibility = View.VISIBLE
        // Nearby has no separate spinner; it just fills in with the same result.
        binding.rvNearby.visibility = View.GONE
        binding.tvNearbyEmpty.visibility = View.GONE
    }

    private fun showNearby(people: List<NearbyPerson>) {
        binding.tvNearbyEmpty.visibility = View.GONE
        binding.rvNearby.visibility = View.VISIBLE
        nearbyAdapter.submitList(people)
    }

    private fun showNearbyEmpty() {
        binding.rvNearby.visibility = View.GONE
        nearbyAdapter.submitList(emptyList())
        binding.tvNearbyEmpty.visibility = View.VISIBLE
    }

    private fun showDiscover(profiles: List<DiscoverProfile>) {
        binding.discoverEmpty.root.visibility = View.GONE
        binding.rvDiscover.visibility = View.VISIBLE
        discoverAdapter.submitList(profiles)
    }

    private fun showDiscoverEmpty() {
        binding.rvDiscover.visibility = View.GONE
        discoverAdapter.submitList(emptyList())
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
        discoverAdapter.submitList(emptyList())
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
