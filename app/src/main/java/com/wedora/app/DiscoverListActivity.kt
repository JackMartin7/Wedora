package com.wedora.app

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.recyclerview.widget.GridLayoutManager
import com.google.firebase.firestore.FirebaseFirestore
import com.wedora.app.databinding.ActivityDiscoverListBinding

/**
 * The full Discover grid, opened from Explore's Discover section's "See All"
 * and "Load More" — both the same destination, not two different ones (see
 * ExploreActivity's own doc comment: Load More is just a second, closer-at-
 * hand entry point to it).
 *
 * Reuses [loadDiscoveryFeed], so it shows exactly what Explore's own grid
 * does — the same feed, the same filters — just without the 12-profile
 * preview cap. What IS shared with Explore — the ad-weaving into the grid —
 * lives in [DiscoverAdWeaver] so the two don't each carry their own copy.
 *
 * This screen backs the "See All" of all three Explore sections via [Mode].
 * It used to have a sibling, NearbyListActivity, and the reason given for
 * keeping them apart was that one was a single-column list with no filter bar
 * while this is a 2-column grid with one — different enough that branching on
 * a mode flag would have cost more than their small overlap saved. That
 * reasoning held until Nearby and Trending were asked for the same grid, Load
 * More and filter button this already had, at which point the difference it
 * rested on disappeared and the sibling was merged in here.
 *
 * "Load More" here reveals more of the one list already fetched rather than
 * running a second Firestore query: unlike chat's message pagination, the
 * discovery feed's filtering (age/status/looking-for/distance/exclusions)
 * all happens client-side after a single unbounded fetch — see Feed.kt's own
 * comments on why, the same reason HomeActivity/ExploreActivity/
 * ExploreActivity all already fetch the whole matching pool in one shot
 * rather than paging the query itself. Cursoring the raw query here would
 * mean a raw page of documents might yield zero profiles that pass the
 * client-side filters, forcing a fetch-more-until-full loop for a dataset
 * every sibling screen already has in memory after one round trip anyway.
 *
 * Gated by [applyGuestProfileViewLimit], same as Explore's own Discover grid
 * — a guest can't bypass the daily cap just by
 * tapping through to this screen's full list. Applied once, to the whole
 * fetched-and-filtered list, before [revealMore] starts paging over it — so
 * a guest's Load More taps can only ever reveal up to however many of that
 * day's allowance were still left when the screen first loaded, never more.
 */
class DiscoverListActivity : WedoraBaseActivity(), GuestProfileLimitBottomSheet.Host {

    /**
     * Which ordering this screen shows. All three are the SAME pool from
     * [loadDiscoveryFeed] — filters decide who is eligible, the mode only
     * decides order and labelling, so no mode can surface someone the others
     * would have excluded.
     *
     * NEARBY is deliberately a title-only difference. [loadDiscoveryFeed]
     * already returns distance-sorted (see Feed.kt's sortedForDiscovery), so a
     * Nearby grid and a Discover grid hold the same people in the same order;
     * the mode exists so that tapping "People Nearby → See All" does not land
     * on a screen headed "Discover".
     */
    enum class Mode { DISCOVER, NEARBY, TRENDING }


    /**
     * Defaults to DISCOVER on a missing or unrecognised extra: this Activity is
     * manifest-declared and could be started without one, where an unknown enum
     * name would throw rather than degrade.
     */
    private val mode: Mode by lazy {
        runCatching { Mode.valueOf(intent.getStringExtra(EXTRA_MODE) ?: Mode.DISCOVER.name) }
            .getOrDefault(Mode.DISCOVER)
    }

    companion object {
        private const val GRID_COLUMNS = 2

        /** How many profiles "Load More" reveals at a time. */
        private const val PAGE_SIZE = 24

        private const val EXTRA_MODE = "mode"

        fun intent(context: Context, mode: Mode = Mode.DISCOVER): Intent =
            Intent(context, DiscoverListActivity::class.java)
                .putExtra(EXTRA_MODE, mode.name)
    }

    private lateinit var binding: ActivityDiscoverListBinding
    private val firestore: FirebaseFirestore by lazy { FirebaseFirestore.getInstance() }

    private val adapter = DiscoverAdapter { profile ->
        startActivity(ProfileDetailActivity.intent(this, profile.userId))
    }

    private val adWeaver by lazy { DiscoverAdWeaver(this, adapter) }

    /**
     * The full, already-filtered feed from the one fetch [loadFeed] makes —
     * kept so [revealMore] can show more of it without re-querying.
     */
    private var allProfiles: List<DiscoverProfile> = emptyList()

    /** How many of [allProfiles] are currently shown — grows by [PAGE_SIZE] per Load More tap. */
    private var shownCount = 0

    /**
     * Only reloads on RESULT_OK — i.e. Apply — matching Explore/Home. Backing
     * out of the filter screen changes nothing, so there's no need to re-query.
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
        binding = ActivityDiscoverListBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applyEdgeInsets(binding.root)

        binding.rvDiscoverList.layoutManager = GridLayoutManager(this, GRID_COLUMNS)
        binding.rvDiscoverList.adapter = adapter

        binding.btnBack.setOnClickListener { finish() }
        binding.btnFilter.setOnClickListener {
            filterLauncher.launch(Intent(this, FilterActivity::class.java))
        }
        binding.tvTitle.setText(
            when (mode) {
                Mode.DISCOVER -> R.string.discover_list_title
                Mode.NEARBY -> R.string.nearby_list_title
                Mode.TRENDING -> R.string.trending_list_title
            }
        )

        binding.btnLoadMore.setOnClickListener { revealMore() }
        showFilterIndicator()

        // Same "preload ahead" reasoning as Explore/Home — an ad is likely
        // already sitting in the pool by the time the grid needs one.
        adWeaver.preload()
    }

    /**
     * Loaded in onStart rather than onCreate, same reasoning as
     * ExploreActivity: returning from the filter screen (reached via the
     * top bar or the empty state) re-queries with whatever changed, rather
     * than showing a stale list.
     */
    override fun onStart() {
        super.onStart()
        loadFeed()
    }

    override fun onDestroy() {
        adWeaver.destroy()
        super.onDestroy()
    }

    private fun loadFeed() {
        showLoading()
        loadDiscoveryFeed(this, firestore) { cards -> render(cards) }
    }

    private fun render(cards: List<MatchCard>) {
        binding.progressLoading.visibility = View.GONE

        if (cards.isEmpty()) {
            allProfiles = emptyList()
            showEmpty()
            return
        }

        val allowed = applyGuestProfileViewLimit(cards)
        if (allowed.isEmpty() && GuestPrefs.isGuest(this)) {
            allProfiles = emptyList()
            showGuestLimitReached()
            return
        }

        // Sort AFTER the guest cap, not before: the cap is about how many
        // profiles a guest may see in a day, so which ones they are must not
        // depend on the ordering this screen happens to use.
        val ordered = if (mode == Mode.TRENDING) allowed.asTrending() else allowed
        if (ordered.isEmpty()) {
            allProfiles = emptyList()
            showEmpty()
            return
        }

        allProfiles = ordered.map {
            val badge = if (mode == Mode.TRENDING) {
                resources.getQuantityString(
                    R.plurals.trending_like_count,
                    it.likesReceivedCount,
                    formatCompactCount(it.likesReceivedCount)
                )
            } else {
                it.distanceBadge()
            }
            DiscoverProfile(it.id, it.name, it.ageLocationLine(this), it.photoUrl, badge)
        }
        shownCount = 0
        binding.emptyState.hide()
        revealMore()
    }

    /** Grows [shownCount] by [PAGE_SIZE] and re-renders — the Load More action. */
    private fun revealMore() {
        shownCount = (shownCount + PAGE_SIZE).coerceAtMost(allProfiles.size)

        binding.rvDiscoverList.visibility = View.VISIBLE
        adWeaver.submitProfiles(allProfiles.take(shownCount))
        binding.btnLoadMore.visibility =
            if (shownCount < allProfiles.size) View.VISIBLE else View.GONE
    }

    private fun showLoading() {
        binding.emptyState.hide()
        binding.rvDiscoverList.visibility = View.GONE
        binding.btnLoadMore.visibility = View.GONE
        binding.progressLoading.visibility = View.VISIBLE
    }

    /**
     * Copy follows the mode, because the three empty states mean different
     * things. Discover and Nearby are empty when the filters are too narrow,
     * which the user can act on. Trending is empty when nobody in the pool has
     * been liked yet — widening a filter may help, so the action is still
     * offered, but leading with "adjust your filters" would misdescribe why
     * the screen is bare.
     */
    private fun showEmpty() {
        binding.rvDiscoverList.visibility = View.GONE
        binding.btnLoadMore.visibility = View.GONE
        adWeaver.clear()
        val (title, subtitle) = when (mode) {
            Mode.DISCOVER -> R.string.empty_discover_title to R.string.empty_discover_subtitle
            Mode.NEARBY -> R.string.empty_nearby_title to R.string.empty_nearby_subtitle
            Mode.TRENDING -> R.string.empty_trending_title to R.string.empty_trending_subtitle
        }
        binding.emptyState.show(
            R.drawable.ic_sparkle_heart,
            title,
            subtitle,
            R.string.empty_action_adjust_filters,
            ::openFilters
        )
    }

    private fun showGuestLimitReached() {
        binding.rvDiscoverList.visibility = View.GONE
        binding.btnLoadMore.visibility = View.GONE
        adWeaver.clear()
        binding.emptyState.show(
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

    private fun showFilterIndicator() {
        binding.filterDot.visibility =
            if (FilterPrefs.hasActiveFilters(this)) View.VISIBLE else View.GONE
    }

    private fun openFilters() {
        filterLauncher.launch(Intent(this, FilterActivity::class.java))
    }
}
