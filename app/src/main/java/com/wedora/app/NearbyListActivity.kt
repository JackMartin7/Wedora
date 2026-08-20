package com.wedora.app

import android.content.Context
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
 * cap. Gated by [applyGuestProfileViewLimit], same as Explore's own Discover
 * grid — a guest can't bypass the daily cap just by tapping through to this
 * screen's full list.
 */
class NearbyListActivity : WedoraBaseActivity(), GuestProfileLimitBottomSheet.Host {

    /**
     * Which ordering this screen is showing. Both modes are the same feed from
     * [loadDiscoveryFeed]; only the sort, the title and the card caption differ,
     * which is why Trending reuses this screen rather than duplicating it.
     */
    enum class Mode { NEARBY, TRENDING }

    companion object {
        private const val EXTRA_MODE = "mode"

        fun intent(context: Context, mode: Mode = Mode.NEARBY): Intent =
            Intent(context, NearbyListActivity::class.java)
                .putExtra(EXTRA_MODE, mode.name)
    }

    /**
     * Defaults to NEARBY on anything unexpected. This Activity is declared in
     * the manifest and could be started without the extra, and an unknown enum
     * name would otherwise throw rather than degrade.
     */
    private val mode: Mode by lazy {
        runCatching { Mode.valueOf(intent.getStringExtra(EXTRA_MODE) ?: Mode.NEARBY.name) }
            .getOrDefault(Mode.NEARBY)
    }

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

        binding.tvTitle.setText(
            if (mode == Mode.TRENDING) R.string.trending_list_title
            else R.string.nearby_list_title
        )

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

        val allowed = applyGuestProfileViewLimit(cards)
        if (allowed.isEmpty() && GuestPrefs.isGuest(this)) {
            showGuestLimitReached()
            return
        }

        val ordered = if (mode == Mode.TRENDING) allowed.asTrending() else allowed
        if (ordered.isEmpty()) {
            showEmpty()
            return
        }

        binding.emptyState.hide()
        binding.rvNearbyList.visibility = View.VISIBLE
        adapter.submitList(
            ordered.map {
                val badge = if (mode == Mode.TRENDING) {
                    resources.getQuantityString(
                        R.plurals.trending_like_count,
                        it.likesReceivedCount,
                        formatCompactCount(it.likesReceivedCount)
                    )
                } else {
                    it.distanceBadge()
                }
                NearbyRow(it.id, it.name, it.ageLocationLine(this), badge, it.photoUrl)
            }
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
            if (mode == Mode.TRENDING) R.string.empty_trending_title else R.string.empty_nearby_title,
            if (mode == Mode.TRENDING) R.string.empty_trending_subtitle else R.string.empty_nearby_subtitle,
            R.string.empty_action_adjust_filters,
            ::openFilters
        )
    }

    private fun showGuestLimitReached() {
        binding.rvNearbyList.visibility = View.GONE
        adapter.submitList(emptyList())
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

    private fun openFilters() {
        startActivity(Intent(this, FilterActivity::class.java))
    }
}
