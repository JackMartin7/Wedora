package com.wedora.app

import androidx.annotation.StringRes

/**
 * The 2 options offered by both the Gender and Interested In selectors on
 * Sign Up. [firestoreValue] is the canonical string written to and queried
 * against in Firestore, kept separate from the display label so it never
 * changes if the label copy does.
 */
enum class Gender(val firestoreValue: String, @StringRes val labelRes: Int) {
    MALE("male", R.string.gender_male),
    FEMALE("female", R.string.gender_female)
}
