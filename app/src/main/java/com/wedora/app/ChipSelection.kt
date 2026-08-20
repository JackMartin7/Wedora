package com.wedora.app

import android.view.LayoutInflater
import androidx.core.content.ContextCompat
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup

/**
 * Fills a [ChipGroup] with option chips, replacing whatever was there.
 *
 * Rebuilt from a list rather than declared in XML because the Looking For
 * options change with the selected gender, and there is no static set of chips
 * that would be right for both.
 *
 * [selected] is filtered against [options] rather than trusted, which is what
 * makes a gender change safe: a stored "Second Wife" simply doesn't survive a
 * switch to a list that has no such option, so the group can't come back
 * holding a value the user can no longer see.
 *
 * [onChanged] fires only on user taps. Populating the group checks chips
 * programmatically, and treating that as a change would make every screen look
 * edited the moment it loaded.
 */
fun ChipGroup.setOptions(
    options: List<String>,
    selected: Collection<String>,
    multiSelect: Boolean = false,
    onChanged: () -> Unit = {}
) {
    removeAllViews()
    isSingleSelection = !multiSelect

    val inflater = LayoutInflater.from(context)
    val valid = selected.filter { it in options }

    options.forEach { option ->
        val chip = inflater.inflate(R.layout.item_choice_chip, this, false) as Chip
        chip.text = option
        chip.isChecked = option in valid
        chip.setOnClickListener { onChanged() }
        addView(chip)
    }
}

/** Every checked chip's label, in the order they appear. */
fun ChipGroup.selectedOptions(): List<String> =
    (0 until childCount)
        .mapNotNull { getChildAt(it) as? Chip }
        .filter { it.isChecked }
        .map { it.text.toString() }

/** The single checked label, or null when nothing is selected. */
fun ChipGroup.selectedOption(): String? = selectedOptions().firstOrNull()

/**
 * Fills a [ChipGroup] with every [Interest], icon and label, checked
 * according to [selected] — the [Interest.firestoreValue] set stored on the
 * profile. Always multi-select, unlike [setOptions]: there's no single-choice
 * use of the interest picker anywhere it appears.
 *
 * Selection is read back by [selectedInterestIds] off each chip's tag rather
 * than its label text, since the label is user-facing display copy and the
 * tag is the stable [Interest.firestoreValue] that's actually stored.
 */
fun ChipGroup.setInterestOptions(selected: Collection<String>, onChanged: () -> Unit = {}) {
    removeAllViews()
    isSingleSelection = false

    val inflater = LayoutInflater.from(context)
    Interest.values().forEach { interest ->
        val chip = inflater.inflate(R.layout.item_interest_chip, this, false) as Chip
        chip.text = context.getString(interest.labelRes)
        chip.chipIcon = ContextCompat.getDrawable(context, interest.iconRes)
        chip.tag = interest.firestoreValue
        chip.isChecked = interest.firestoreValue in selected
        chip.setOnClickListener { onChanged() }
        addView(chip)
    }
}

/**
 * Fills a [ChipGroup] with a profile's OWN interests, for display only.
 *
 * Deliberately different from [setInterestOptions] in two ways. It renders
 * only the interests this profile actually has, rather than every Interest
 * with some of them checked — the latter reads as an editor somebody forgot
 * to disable. And the chips are inert.
 *
 * [shared] are the viewer's own interests; anything in both sets sorts to the
 * front and takes an accent stroke, so what you have in common is the first
 * thing you see. Pass an empty set to render them plainly, which is what
 * happens for a guest (no viewer profile exists to compare against).
 *
 * The chips stay CHECKABLE while being non-clickable, which looks like a
 * contradiction and is not: Material's setCheckable(false) forces isChecked
 * back to false, which would drop the filled style and leave them looking
 * disabled. Blocking click and focus is what actually makes them inert.
 */
fun ChipGroup.setInterestsReadOnly(
    interests: Collection<String>,
    shared: Set<String> = emptySet()
) {
    removeAllViews()
    isSingleSelection = false

    // Interest.values() order is the canonical display order everywhere else
    // in the app; sorting shared-first preserves it within each group.
    val ordered = Interest.values()
        .filter { it.firestoreValue in interests }
        .sortedByDescending { it.firestoreValue in shared }

    val inflater = LayoutInflater.from(context)
    ordered.forEach { interest ->
        val chip = inflater.inflate(R.layout.item_interest_chip, this, false) as Chip
        chip.text = context.getString(interest.labelRes)
        chip.chipIcon = ContextCompat.getDrawable(context, interest.iconRes)
        chip.tag = interest.firestoreValue
        chip.isChecked = true
        chip.isClickable = false
        chip.isFocusable = false
        if (interest.firestoreValue in shared) {
            chip.chipStrokeWidth = 1.5f * context.resources.displayMetrics.density
            chip.chipStrokeColor = ContextCompat.getColorStateList(context, R.color.wedora_accent)
        }
        addView(chip)
    }
}

/** Every checked chip's [Interest.firestoreValue] tag, in display order. */
fun ChipGroup.selectedInterestIds(): List<String> =
    (0 until childCount)
        .mapNotNull { getChildAt(it) as? Chip }
        .filter { it.isChecked }
        .mapNotNull { it.tag as? String }
