package com.wedora.app

import android.os.Bundle
import android.util.Log
import androidx.appcompat.app.AppCompatActivity
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.wedora.app.databinding.ActivityFilterBinding

/**
 * Feed filters: age range, marital status, what they're looking for, and
 * distance.
 *
 * Status and Looking For are worded differently per gender — a man is a
 * widower and looks for a wife; everyone else is widowed and looks for a
 * marriage — so the chips are built once the relevant gender is known.
 *
 * That gender is the user's `interestedIn`, NOT their own. These filters
 * narrow the people in the feed, and the feed shows users whose gender matches
 * `interestedIn` — so their values are the ones being compared against. Using
 * the viewer's own gender would offer a man "First Wife", which no woman in
 * his feed can ever have stored, and the filter would return nothing.
 *
 * Distance is stored but inert: there are no coordinates on either side to
 * measure between. The screen says so rather than implying a live filter.
 *
 * Values are written on Apply, not as the controls move, so backing out leaves
 * the feed as it was.
 */
class FilterActivity : AppCompatActivity() {

    private companion object {
        const val TAG = "WedoraFilter"
    }

    private lateinit var binding: ActivityFilterBinding
    private val auth: FirebaseAuth by lazy { FirebaseAuth.getInstance() }
    private val firestore: FirebaseFirestore by lazy { FirebaseFirestore.getInstance() }

    /** Gender of the people being filtered; drives both chip option lists. */
    private var candidateGender: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityFilterBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnClose.setOnClickListener { finish() }
        binding.btnReset.setOnClickListener { resetToDefaults() }
        binding.btnApply.setOnClickListener { applyAndFinish() }

        binding.sliderAge.addOnChangeListener { _, _, _ -> showAgeLabel() }
        binding.sliderDistance.addOnChangeListener { _, _, _ -> showDistanceLabel() }

        loadSavedFilters()
        loadCandidateGender()
    }

    // ----- Load / reset ---------------------------------------------------

    private fun loadSavedFilters() {
        binding.sliderAge.values = listOf(
            FilterPrefs.getAgeMin(this)
                .coerceIn(FilterPrefs.AGE_FLOOR, FilterPrefs.AGE_CEILING).toFloat(),
            FilterPrefs.getAgeMax(this)
                .coerceIn(FilterPrefs.AGE_FLOOR, FilterPrefs.AGE_CEILING).toFloat()
        )
        binding.sliderDistance.value = FilterPrefs.getDistanceKm(this)
            .coerceIn(FilterPrefs.MIN_DISTANCE_KM, FilterPrefs.MAX_DISTANCE_KM)
            .toFloat()

        showAgeLabel()
        showDistanceLabel()
        showIntentChips()
    }

    /**
     * Reads whose profiles this user sees, so the chips are worded for them.
     * On failure the fallback list still contains every shared option, so the
     * screen stays usable rather than empty.
     */
    private fun loadCandidateGender() {
        val uid = auth.currentUser?.uid ?: return
        firestore.collection(UserProfile.COLLECTION).document(uid).get()
            .addOnSuccessListener { snapshot ->
                candidateGender = UserProfile.from(snapshot).interestedIn
                showIntentChips()
            }
            .addOnFailureListener { e ->
                Log.w(TAG, "Couldn't read interestedIn for the filter options", e)
            }
    }

    /** (Re)builds both filter groups, restoring whatever was saved. */
    private fun showIntentChips() {
        val statusOptions = MarriageIntent.statusOptions(candidateGender)
        binding.chipsStatusFilter.setOptions(
            options = statusOptions,
            selected = FilterPrefs.getRawMyStatusFilter(this).ifEmpty { statusOptions.toSet() },
            multiSelect = true
        )

        val lookingForOptions = MarriageIntent.lookingForOptions(candidateGender)
        binding.chipsLookingForFilter.setOptions(
            options = lookingForOptions,
            selected = FilterPrefs.getRawLookingForFilter(this, lookingForOptions)
                .ifEmpty { lookingForOptions.toSet() },
            multiSelect = true
        )
    }

    /**
     * Resets the controls only. Nothing is stored until Apply, so a Reset the
     * user then backs out of leaves their saved filters untouched — the same
     * rule every other control on this screen follows.
     */
    private fun resetToDefaults() {
        binding.sliderAge.values = listOf(
            FilterPrefs.DEFAULT_AGE_MIN.toFloat(),
            FilterPrefs.DEFAULT_AGE_MAX.toFloat()
        )
        binding.sliderDistance.value = FilterPrefs.DEFAULT_DISTANCE_KM.toFloat()

        // Default is everything ticked, which reads as "don't narrow".
        val statusOptions = MarriageIntent.statusOptions(candidateGender)
        binding.chipsStatusFilter.setOptions(statusOptions, statusOptions, multiSelect = true)

        val lookingForOptions = MarriageIntent.lookingForOptions(candidateGender)
        binding.chipsLookingForFilter.setOptions(
            lookingForOptions, lookingForOptions, multiSelect = true
        )

        showAgeLabel()
        showDistanceLabel()
    }

    // ----- Labels ---------------------------------------------------------

    private fun showAgeLabel() {
        binding.tvAgeValue.text =
            getString(R.string.filter_age_value, selectedAgeMin(), selectedAgeMax())
    }

    private fun showDistanceLabel() {
        binding.tvDistanceValue.text =
            getString(R.string.filter_distance_value, selectedDistance())
    }

    private fun selectedAgeMin(): Int =
        binding.sliderAge.values.firstOrNull()?.toInt() ?: FilterPrefs.DEFAULT_AGE_MIN

    private fun selectedAgeMax(): Int =
        binding.sliderAge.values.lastOrNull()?.toInt() ?: FilterPrefs.DEFAULT_AGE_MAX

    private fun selectedDistance(): Int = binding.sliderDistance.value.toInt()

    // ----- Apply ----------------------------------------------------------

    /**
     * An empty selection is stored as the full option set rather than as
     * nothing. Unticking every chip means "no preference" to a user, but as a
     * filter it would match nobody and empty the feed with no obvious cause.
     */
    private fun applyAndFinish() {
        FilterPrefs.setAgeRange(this, selectedAgeMin(), selectedAgeMax())

        val statusOptions = MarriageIntent.statusOptions(candidateGender)
        FilterPrefs.setMyStatusFilter(
            this,
            binding.chipsStatusFilter.selectedOptions()
                .ifEmpty { statusOptions }
                .toSet()
        )

        val lookingForOptions = MarriageIntent.lookingForOptions(candidateGender)
        FilterPrefs.setLookingForFilter(
            this,
            binding.chipsLookingForFilter.selectedOptions()
                .ifEmpty { lookingForOptions }
                .toSet()
        )

        // TODO: wire to real distance calculation when Maps is implemented
        FilterPrefs.setDistanceKm(this, selectedDistance())

        setResult(RESULT_OK)
        finish()
    }
}
