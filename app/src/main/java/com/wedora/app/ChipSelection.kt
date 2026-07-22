package com.wedora.app

import android.view.LayoutInflater
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
