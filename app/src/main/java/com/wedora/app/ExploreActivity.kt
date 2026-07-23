package com.wedora.app

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Toast
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
class ExploreActivity : AppCompatActivity() {

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

        binding.btnFilter.setOnClickListener {
            filterLauncher.launch(Intent(this, FilterActivity::class.java))
        }
        showFilterIndicator()

        // Search has no backend yet; say so rather than open a dead screen.
        binding.btnSearch.setOnClickListener {
            toast(getString(R.string.search_coming_soon))
        }
        binding.tvNearbySeeAll.setOnClickListener {
            startActivity(Intent(this, NearbyListActivity::class.java))
        }

        loadFeed()
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
            showNearbyEmpty()
            showDiscoverEmpty()
            return
        }

        val nearby = cards.take(NEARBY_STRIP_MAX).map {
            NearbyPerson(it.id, it.name, it.distanceBadge())
        }
        showNearby(nearby)

        val discover = cards.map { DiscoverProfile(it.id, it.name, it.ageLocationLine(this)) }
        showDiscover(discover)
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

    private fun openFilters() {
        filterLauncher.launch(Intent(this, FilterActivity::class.java))
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }
}
