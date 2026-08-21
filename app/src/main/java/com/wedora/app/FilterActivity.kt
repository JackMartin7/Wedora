package com.wedora.app

import android.os.Bundle
import android.util.Log
import android.view.View
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.wedora.app.databinding.ActivityFilterBinding

/**
 * Feed filters: age range, marital status, what they're looking for,
 * distance, and interests.
 *
 * Interests narrows the feed like the rest of them now, matching on ANY: a
 * profile qualifies with one interest in common. It stays opt-in — its default
 * and its Reset state are both empty, unlike Status and Looking For, whose
 * default is everything ticked.
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
 * Distance is a real filter (see Feed.kt's matchesDistanceFilter) — it
 * narrows the feed using each side's stored coordinates, failing open
 * (keeping the card) whenever either side has none, same as everywhere else
 * distance is used in this app.
 *
 * Values are written on Apply, not as the controls move, so backing out leaves
 * the feed as it was.
 */
class FilterActivity : WedoraBaseActivity() {

    private companion object {
        const val TAG = "WedoraFilter"
    }

    private lateinit var binding: ActivityFilterBinding
    private val auth: FirebaseAuth by lazy { FirebaseAuth.getInstance() }
    private val firestore: FirebaseFirestore by lazy { FirebaseFirestore.getInstance() }

    /** Gender of the people being filtered; drives both chip option lists. */
    private var candidateGender: String? = null

    /**
     * The country chosen in the picker but not yet applied. Mirrors how the
     * chips and sliders behave - nothing on this screen narrows the feed until
     * Apply is tapped - so backing out discards the choice.
     */
    private var pendingCountry: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityFilterBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applyEdgeInsets(binding.root)

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
        binding.chipsInterestsFilter.setInterestOptions(
            selected = FilterPrefs.getInterestsFilter(this)
        )
        binding.switchActiveToday.isChecked = FilterPrefs.getActiveToday(this)
        pendingCountry = FilterPrefs.getCountryFilter(this)
        showCountrySelection()
        binding.inputCountry.setOnClickListener { openCountryPicker() }

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
            showCountrySelection()
            return
        }
        firestore.collection(UserProfile.COLLECTION).document(uid).get()
            .addOnSuccessListener { snapshot ->
                candidateGender = UserProfile.from(snapshot).interestedIn
                showIntentChips()
                showCountrySelection()
            }
            .addOnFailureListener { e ->
                Log.w(TAG, "Couldn't read interestedIn for the filter options; using the default list", e)
                candidateGender = null
                showIntentChips()
                showCountrySelection()
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
    /**
     * Shows the currently selected country, and opens the picker on tap.
     *
     * The options are now the canonical ISO list rather than the distinct
     * values found in the cached pool. That is a deliberate trade: the derived
     * list only ever offered countries someone could actually match, but it
     * also surfaced the free text already stored - "USA" and "United States"
     * as separate rows - and could not show flags. The canonical list will
     * offer countries nobody in the pool has; Countries.matches is what keeps
     * a canonical selection matching the old free-text values meanwhile.
     */
    private fun showCountrySelection() {
        val label = pendingCountry?.let { name ->
            Countries.flagFor(name)?.let { "$it  $name" } ?: name
        } ?: getString(R.string.filter_country_any)
        binding.inputCountry.text = label
    }

    private fun openCountryPicker() {
        CountryPickerBottomSheet
            .newInstance(selected = pendingCountry, allowAny = true)
            .also { sheet ->
                sheet.onCountryPicked = { name ->
                    // Held, not saved. Every other control on this screen only
                    // takes effect on Apply, and a country that saved itself on
                    // selection would still be filtering after the user backed
                    // out without applying.
                    pendingCountry = name
                    showCountrySelection()
                }
            }
            .show(supportFragmentManager, "country_picker")
    }

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
        binding.chipsInterestsFilter.setInterestOptions(selected = emptyList())
        binding.switchActiveToday.isChecked = false
        pendingCountry = null
        showCountrySelection()

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

    /**
     * "Within 50 km" for everywhere on the slider except its very top —
     * "Within 20,020 km" would read as a strange, arbitrary number rather
     * than the "no real limit" it actually means, so the last couple of km
     * show "Worldwide" instead. matchesDistanceFilter needs no equivalent
     * special case: MAX_DISTANCE_KM already exceeds any real distance on
     * Earth, so the filter itself is already a no-op up here — this is
     * purely how that same value reads to the user.
     */
    private fun showDistanceLabel() {
        val km = selectedDistance()
        binding.tvDistanceValue.text = if (km >= FilterPrefs.MAX_DISTANCE_KM - 1) {
            getString(R.string.filter_distance_worldwide)
        } else {
            getString(R.string.filter_distance_value, km)
        }
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

        FilterPrefs.setDistanceKm(this, selectedDistance())
        FilterPrefs.setInterestsFilter(this, binding.chipsInterestsFilter.selectedInterestIds().toSet())
        FilterPrefs.setActiveToday(this, binding.switchActiveToday.isChecked)
        FilterPrefs.setCountryFilter(this, pendingCountry)

        setResult(RESULT_OK)
        finish()
    }
}
