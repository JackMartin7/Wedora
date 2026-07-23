package com.wedora.app

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.gms.tasks.Tasks
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldPath
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.QuerySnapshot
import com.wedora.app.databinding.ActivityExploreBinding

/**
 * The Explore tab: the people this user has matched with along the top ("People
 * Nearby"), and a browsable grid of new profiles below ("Discover").
 *
 * Neither section is new backend work. Nearby reuses the matches query that
 * drives Chats; Discover reuses the same feed query as Home — opposite gender,
 * minus everyone blocked, passed or already liked — through the shared helpers
 * in [Feed.kt], so the two screens can't disagree on who's discoverable.
 */
class ExploreActivity : AppCompatActivity() {

    private companion object {
        const val TAG = "WedoraExplore"
        const val GRID_COLUMNS = 2

        /** Firestore caps `whereIn` values, so profile lookups go out in chunks. */
        const val WHERE_IN_CHUNK = 10
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
     * Only reloads Discover on RESULT_OK — i.e. Apply — matching Home. Backing
     * out of the filter screen changes nothing, so there's no need to re-query.
     */
    private val filterLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            showFilterIndicator()
            loadDiscover()
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
        // The strip is a placeholder view of the user's matches; a dedicated
        // "all nearby" screen doesn't exist, so See All is honest about that.
        binding.tvNearbySeeAll.setOnClickListener {
            toast(getString(R.string.explore_coming_soon))
        }

        loadNearby()
        loadDiscover()
    }

    /** Accent dot over the filter icon whenever anything differs from default. */
    private fun showFilterIndicator() {
        binding.filterDot.visibility =
            if (FilterPrefs.hasActiveFilters(this)) View.VISIBLE else View.GONE
    }

    // ----- People Nearby ----------------------------------------------------

    /**
     * The user's matched people, resolved to names for the avatar strip. Same
     * matches query as Chats; a match whose profile is missing or unnamed is
     * dropped rather than shown as a blank avatar.
     */
    private fun loadNearby() {
        val selfUid = FirebaseAuth.getInstance().currentUser
            ?.takeUnless { GuestPrefs.isGuest(this) }?.uid
        if (selfUid == null) {
            showNearbyEmpty()
            return
        }

        firestore.collection(Match.COLLECTION)
            .whereArrayContains(Match.FIELD_USERS, selfUid)
            .get()
            .addOnSuccessListener { snapshot ->
                val otherUids = snapshot.documents
                    .mapNotNull { Match.from(it) }
                    .mapNotNull { it.otherUserId(selfUid) }
                    .distinct()

                if (otherUids.isEmpty()) {
                    showNearbyEmpty()
                } else {
                    resolveNearbyNames(otherUids)
                }
            }
            .addOnFailureListener { e ->
                Log.w(TAG, "Failed to load nearby matches", e)
                showNearbyEmpty()
            }
    }

    private fun resolveNearbyNames(uids: List<String>) {
        val tasks = uids.chunked(WHERE_IN_CHUNK).map { chunk ->
            firestore.collection(UserProfile.COLLECTION)
                .whereIn(FieldPath.documentId(), chunk)
                .get()
        }

        Tasks.whenAllSuccess<QuerySnapshot>(tasks)
            .addOnSuccessListener { snapshots ->
                val people = snapshots.flatMap { it.documents }.mapNotNull { doc ->
                    val name = UserProfile.from(doc).displayName?.takeIf { it.isNotBlank() }
                        ?: return@mapNotNull null
                    NearbyPerson(userId = doc.id, name = name)
                }

                if (people.isEmpty()) showNearbyEmpty() else showNearby(people)
            }
            .addOnFailureListener { e ->
                Log.w(TAG, "Failed to resolve nearby names", e)
                showNearbyEmpty()
            }
    }

    private fun showNearby(people: List<NearbyPerson>) {
        binding.tvNearbyEmpty.visibility = View.GONE
        binding.rvNearby.visibility = View.VISIBLE
        nearbyAdapter.submitList(people)
    }

    private fun showNearbyEmpty() {
        binding.rvNearby.visibility = View.GONE
        binding.tvNearbyEmpty.visibility = View.VISIBLE
    }

    // ----- Discover ---------------------------------------------------------

    /**
     * Mirrors [HomeActivity]'s feed load: read the user's own profile for who
     * they're interested in, gather the exclusion set, then query and filter
     * the same candidate pool — only rendered as a grid rather than a stack.
     */
    private fun loadDiscover() {
        val uid = FirebaseAuth.getInstance().currentUser
            ?.takeUnless { GuestPrefs.isGuest(this) }?.uid
        if (uid == null) {
            showDiscoverEmpty()
            return
        }

        showDiscoverLoading()
        firestore.collection(UserProfile.COLLECTION).document(uid).get()
            .addOnSuccessListener { selfDoc ->
                val interestedIn = UserProfile.from(selfDoc).interestedIn
                if (interestedIn.isNullOrBlank()) {
                    showDiscoverEmpty()
                } else {
                    loadFeedExclusions(firestore, uid) { excluded ->
                        queryDiscover(interestedIn, uid, excluded)
                    }
                }
            }
            .addOnFailureListener { e ->
                Log.w(TAG, "Failed to load own profile for discover", e)
                showDiscoverEmpty()
            }
    }

    private fun queryDiscover(interestedIn: String, selfUid: String, excludedUids: Set<String>) {
        firestore.collection(UserProfile.COLLECTION)
            .whereEqualTo(UserProfile.FIELD_GENDER, interestedIn)
            .get()
            .addOnSuccessListener { snapshot ->
                val profiles = snapshot.documents
                    .filter { it.id != selfUid && it.id !in excludedUids }
                    .mapNotNull { it.toMatchCard() }
                    .filter { matchesActiveFilters(this, it) }
                    .map { DiscoverProfile(it.id, it.name, it.ageLocationLine(this)) }

                if (profiles.isEmpty()) showDiscoverEmpty() else showDiscover(profiles)
            }
            .addOnFailureListener { e ->
                Log.w(TAG, "Failed to query discover profiles", e)
                showDiscoverEmpty()
            }
    }

    private fun showDiscoverLoading() {
        binding.discoverEmpty.root.visibility = View.GONE
        binding.rvDiscover.visibility = View.GONE
        binding.progressDiscover.visibility = View.VISIBLE
    }

    private fun showDiscover(profiles: List<DiscoverProfile>) {
        binding.progressDiscover.visibility = View.GONE
        binding.discoverEmpty.root.visibility = View.GONE
        binding.rvDiscover.visibility = View.VISIBLE
        discoverAdapter.submitList(profiles)
    }

    private fun showDiscoverEmpty() {
        binding.progressDiscover.visibility = View.GONE
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
