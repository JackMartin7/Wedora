package com.wedora.app

import android.os.Bundle
import android.util.Log
import android.view.View
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
        binding.btnApply.addPressScale()

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
    }

    /**
     * Reads the gender that decides the Looking For options, then builds the
     * chips. The chips are hidden behind a spinner until it lands, so the wrong
     * option set can't flash up and be tapped before the right one arrives.
     *
     * That gender is the user's `interestedIn`, NOT their own — see the class
     * comment. On a failed read it falls back to null, which
     * MarriageIntent.lookingForOptions resolves to the non-male "Marriage"
     * list; that's the safer default because it still contains every option a
     * non-male candidate can have, and "Any" is in every list regardless.
     */
    private fun loadCandidateGender() {
        binding.progressLookingFor.visibility = View.VISIBLE
        binding.chipsLookingForFilter.visibility = View.GONE

        val uid = auth.realUid
        if (uid == null) {
            showIntentChips()
            return
        }
        firestore.collection(UserProfile.COLLECTION).document(uid).get()
            .addOnSuccessListener { snapshot ->
                candidateGender = UserProfile.from(snapshot).interestedIn
                showIntentChips()
            }
            .addOnFailureListener { e ->
                Log.w(TAG, "Couldn't read interestedIn for the filter options; using the default list", e)
                candidateGender = null
                showIntentChips()
            }
    }

    /**
     * Builds both filter groups now that the gender is known, and reveals the
     * Looking For chips.
     *
     * A stored Looking For filter that doesn't fit the current options — a
     * male-worded value left over for what is now a female-candidate list — is
     * dropped from FilterPrefs rather than silently ignored. Left in place it
     * would keep narrowing the feed by values the user can no longer see or
     * clear on this screen.
     */
    private fun showIntentChips() {
        val statusOptions = MarriageIntent.statusOptions(candidateGender)
        binding.chipsStatusFilter.setOptions(
            options = statusOptions,
            selected = FilterPrefs.getRawMyStatusFilter(this).ifEmpty { statusOptions.toSet() },
            multiSelect = true
        )

        val lookingForOptions = MarriageIntent.lookingForOptions(candidateGender)
        val storedLookingFor = FilterPrefs.getRawLookingForFilter(this, lookingForOptions)
        if (!lookingForOptions.containsAll(storedLookingFor)) {
            FilterPrefs.clearLookingForFilter(this)
        }
        binding.chipsLookingForFilter.setOptions(
            options = lookingForOptions,
            selected = FilterPrefs.getRawLookingForFilter(this, lookingForOptions)
                .ifEmpty { lookingForOptions.toSet() },
            multiSelect = true
        )

        binding.progressLookingFor.visibility = View.GONE
        binding.chipsLookingForFilter.visibility = View.VISIBLE
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
