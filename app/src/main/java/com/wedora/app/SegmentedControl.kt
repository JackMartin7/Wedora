package com.wedora.app

import android.widget.TextView
import androidx.core.content.ContextCompat

/**
 * Manages exclusive single-selection across a small row of TextViews styled
 * to look like segmented-control options (see bg_segment_option_selected /
 * bg_segment_option_unselected). Used for Sign Up's Gender and Interested In
 * rows — two independent instances, same 3 [Gender] options.
 */
class SegmentedControl(
    private val options: List<Pair<TextView, Gender>>,
    /**
     * Fired only on user taps, not on a programmatic [select]. Edit Profile
     * pre-selects the stored value while populating the form, and treating
     * that as a change would enable Save before the user had touched anything.
     */
    private val onSelected: () -> Unit = {}
) {

    var selected: Gender? = null
        private set

    init {
        options.forEach { (view, gender) ->
            view.setOnClickListener {
                select(gender)
                onSelected()
            }
        }
    }

    fun select(gender: Gender) {
        selected = gender
        options.forEach { (view, optionGender) ->
            val isSelected = optionGender == gender
            view.setBackgroundResource(
                if (isSelected) R.drawable.bg_segment_option_selected
                else R.drawable.bg_segment_option_unselected
            )
            view.setTextColor(
                ContextCompat.getColor(
                    view.context,
                    if (isSelected) R.color.white else R.color.wedora_text
                )
            )
        }
    }
}
