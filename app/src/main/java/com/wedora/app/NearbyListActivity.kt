package com.wedora.app

import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.firebase.firestore.FirebaseFirestore
import com.wedora.app.databinding.ActivityNearbyListBinding

/**
 * The full People Nearby list, opened from Explore's "See All": every
 * discoverable person in one scrollable column, sorted closest first.
 *
 * Reuses [loadDiscoveryFeed], so it shows exactly what the Explore strip and
 * grid do — the same feed, the same distance filter — just without the preview
 * cap.
 */
class NearbyListActivity : WedoraBaseActivity() {

    private lateinit var binding: ActivityNearbyListBinding
    private val firestore: FirebaseFirestore by lazy { FirebaseFirestore.getInstance() }

    private val adapter = NearbyListAdapter { row ->
        startActivity(ProfileDetailActivity.intent(this, row.userId))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityNearbyListBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applyEdgeInsets(binding.root)

        binding.rvNearbyList.layoutManager = LinearLayoutManager(this)
        binding.rvNearbyList.adapter = adapter

        binding.btnBack.setOnClickListener { finish() }
    }

    /**
     * Loaded in onStart rather than onCreate so returning from the filter screen
     * (reached via the empty state) re-queries with the new distance rather than
     * showing a stale list.
     */
    override fun onStart() {
        super.onStart()
        load()
    }

    private fun load() {
        showLoading()
        loadDiscoveryFeed(this, firestore) { cards -> render(cards) }
    }

    private fun render(cards: List<MatchCard>) {
        binding.progressLoading.visibility = View.GONE

        if (cards.isEmpty()) {
            showEmpty()
            return
        }

        binding.emptyState.hide()
        binding.rvNearbyList.visibility = View.VISIBLE
        adapter.submitList(
            cards.map { NearbyRow(it.id, it.name, it.ageLocationLine(this), it.distanceBadge()) }
        )
    }

    private fun showLoading() {
        binding.emptyState.hide()
        binding.rvNearbyList.visibility = View.GONE
        binding.progressLoading.visibility = View.VISIBLE
    }

    private fun showEmpty() {
        binding.rvNearbyList.visibility = View.GONE
        binding.emptyState.show(
            R.drawable.ic_sparkle_heart,
            R.string.empty_nearby_title,
            R.string.empty_nearby_subtitle,
            R.string.empty_action_adjust_filters,
            ::openFilters
        )
    }

    private fun openFilters() {
        startActivity(Intent(this, FilterActivity::class.java))
    }
}
