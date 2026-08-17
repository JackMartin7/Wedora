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

/** Every checked chip's [Interest.firestoreValue] tag, in display order. */
fun ChipGroup.selectedInterestIds(): List<String> =
    (0 until childCount)
        .mapNotNull { getChildAt(it) as? Chip }
        .filter { it.isChecked }
        .mapNotNull { it.tag as? String }
