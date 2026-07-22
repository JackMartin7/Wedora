package com.wedora.app

import android.os.Bundle
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.wedora.app.databinding.ActivityFilterBinding

/**
 * Feed filters: age range, relationship type and distance.
 *
 * Only the age range narrows the feed. Relationship type has no
 * `relationshipType` field on user documents to compare against, and distance
 * has no coordinates stored on either side to measure between — both are saved
 * and restored so the screen remembers the choice, and the screen says so
 * rather than implying a filter that isn't running.
 *
 * Values are written on Apply, not as the controls move, so backing out leaves
 * the feed as it was.
 */
class FilterActivity : AppCompatActivity() {

    private lateinit var binding: ActivityFilterBinding

    private var relationship = FilterPrefs.DEFAULT_RELATIONSHIP

    /** Chip views paired with the value each one stores. */
    private lateinit var chips: List<Pair<TextView, String>>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityFilterBinding.inflate(layoutInflater)
        setContentView(binding.root)

        chips = listOf(
            binding.chipSerious to FilterPrefs.RELATIONSHIP_SERIOUS,
            binding.chipCasual to FilterPrefs.RELATIONSHIP_CASUAL,
            binding.chipFriendship to FilterPrefs.RELATIONSHIP_FRIENDSHIP,
            binding.chipOpen to FilterPrefs.RELATIONSHIP_OPEN
        )
        chips.forEach { (view, value) ->
            view.setOnClickListener { selectRelationship(value) }
        }

        binding.btnClose.setOnClickListener { finish() }
        binding.btnReset.setOnClickListener { resetToDefaults() }
        binding.btnApply.setOnClickListener { applyAndFinish() }

        binding.sliderAge.addOnChangeListener { _, _, _ -> showAgeLabel() }
        binding.sliderDistance.addOnChangeListener { _, _, _ -> showDistanceLabel() }

        loadSavedFilters()
    }

    // ----- Load / reset ---------------------------------------------------

    private fun loadSavedFilters() {
        applyValues(
            ageMin = FilterPrefs.getAgeMin(this),
            ageMax = FilterPrefs.getAgeMax(this),
            distance = FilterPrefs.getDistanceKm(this),
            relationshipType = FilterPrefs.getRelationshipType(this)
        )
    }

    /**
     * Resets the controls only. Nothing is stored until Apply, so a Reset the
     * user then backs out of leaves their saved filters untouched — the same
     * rule every other control on this screen follows.
     */
    private fun resetToDefaults() {
        applyValues(
            ageMin = FilterPrefs.DEFAULT_AGE_MIN,
            ageMax = FilterPrefs.DEFAULT_AGE_MAX,
            distance = FilterPrefs.DEFAULT_DISTANCE_KM,
            relationshipType = FilterPrefs.DEFAULT_RELATIONSHIP
        )
    }

    private fun applyValues(ageMin: Int, ageMax: Int, distance: Int, relationshipType: String) {
        // Clamped because a stored pair could sit outside the slider's range if
        // the bounds are ever narrowed; RangeSlider throws rather than clamping
        // for itself.
        val min = ageMin.coerceIn(FilterPrefs.AGE_FLOOR, FilterPrefs.AGE_CEILING)
        val max = ageMax.coerceIn(min, FilterPrefs.AGE_CEILING)
        binding.sliderAge.values = listOf(min.toFloat(), max.toFloat())

        binding.sliderDistance.value = distance
            .coerceIn(FilterPrefs.MIN_DISTANCE_KM, FilterPrefs.MAX_DISTANCE_KM)
            .toFloat()

        selectRelationship(relationshipType)
        showAgeLabel()
        showDistanceLabel()
    }

    // ----- Controls -------------------------------------------------------

    private fun selectRelationship(value: String) {
        relationship = value
        chips.forEach { (view, chipValue) ->
            val selected = chipValue == value
            view.setBackgroundResource(
                if (selected) R.drawable.bg_chip_selected else R.drawable.bg_chip_unselected
            )
            view.setTextColor(
                ContextCompat.getColor(
                    this,
                    if (selected) R.color.white else R.color.wedora_text
                )
            )
        }
    }

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

    private fun applyAndFinish() {
        FilterPrefs.setAgeRange(this, selectedAgeMin(), selectedAgeMax())
        FilterPrefs.setRelationshipType(this, relationship)
        // TODO: wire to real distance calculation when Maps is implemented
        FilterPrefs.setDistanceKm(this, selectedDistance())

        setResult(RESULT_OK)
        finish()
    }
}
