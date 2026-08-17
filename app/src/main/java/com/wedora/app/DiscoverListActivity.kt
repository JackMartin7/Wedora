package com.wedora.app

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
 * preview cap. A sibling to [NearbyListActivity] rather than a merge with
 * it: that one is a single-column list with no filter bar, this one is a
 * 2-column grid with one, different enough that forcing a shared activity to
 * branch on a mode flag would cost more than the two screens' genuinely
 * small overlap (both just wrap [loadDiscoveryFeed] behind a back arrow)
 * would save. What IS shared — the ad-weaving into the grid — lives in
 * [DiscoverAdWeaver] so this and Explore don't each carry their own copy.
 *
 * "Load More" here reveals more of the one list already fetched rather than
 * running a second Firestore query: unlike chat's message pagination, the
 * discovery feed's filtering (age/status/looking-for/distance/exclusions)
 * all happens client-side after a single unbounded fetch — see Feed.kt's own
 * comments on why, the same reason HomeActivity/ExploreActivity/
 * NearbyListActivity all already fetch the whole matching pool in one shot
 * rather than paging the query itself. Cursoring the raw query here would
 * mean a raw page of documents might yield zero profiles that pass the
 * client-side filters, forcing a fetch-more-until-full loop for a dataset
 * every sibling screen already has in memory after one round trip anyway.
 *
 * Gated by [applyGuestProfileViewLimit], same as Explore's own Discover grid
 * and [NearbyListActivity] — a guest can't bypass the daily cap just by
 * tapping through to this screen's full list. Applied once, to the whole
 * fetched-and-filtered list, before [revealMore] starts paging over it — so
 * a guest's Load More taps can only ever reveal up to however many of that
 * day's allowance were still left when the screen first loaded, never more.
 */
class DiscoverListActivity : WedoraBaseActivity(), GuestProfileLimitBottomSheet.Host {

    private companion object {
        const val GRID_COLUMNS = 2

        /** How many profiles "Load More" reveals at a time. */
        const val PAGE_SIZE = 24
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
        binding.btnLoadMore.setOnClickListener { revealMore() }
        showFilterIndicator()

        // Same "preload ahead" reasoning as Explore/Home — an ad is likely
        // already sitting in the pool by the time the grid needs one.
        adWeaver.preload()
    }

    /**
     * Loaded in onStart rather than onCreate, same reasoning as
     * NearbyListActivity: returning from the filter screen (reached via the
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

        allProfiles = allowed.map {
            DiscoverProfile(it.id, it.name, it.ageLocationLine(this), it.photoUrl, it.distanceBadge())
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

    private fun showEmpty() {
        binding.rvDiscoverList.visibility = View.GONE
        binding.btnLoadMore.visibility = View.GONE
        adWeaver.clear()
        binding.emptyState.show(
            R.drawable.ic_sparkle_heart,
            R.string.empty_discover_title,
            R.string.empty_discover_subtitle,
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
