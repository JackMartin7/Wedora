package com.wedora.app

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes

/**
 * The fixed catalog of hobby/interest chips offered on Sign Up, Edit Profile
 * and Filters. [firestoreValue] is the canonical id written to and queried
 * against in Firestore ([UserProfile.FIELD_INTERESTS]), kept separate from
 * the display label so relabeling the copy never touches stored data — same
 * pattern as [Gender].
 */
enum class Interest(val firestoreValue: String, @StringRes val labelRes: Int, @DrawableRes val iconRes: Int) {
    GYM_FITNESS("gym_fitness", R.string.interest_gym_fitness, R.drawable.ic_interest_gym_fitness),
    MOVIES_TV("movies_tv", R.string.interest_movies_tv, R.drawable.ic_interest_movies_tv),
    PHOTOGRAPHY("photography", R.string.interest_photography, R.drawable.ic_interest_photography),
    TRAVEL("travel", R.string.interest_travel, R.drawable.ic_interest_travel),
    MUSIC("music", R.string.interest_music, R.drawable.ic_interest_music),
    FOOD_DINING("food_dining", R.string.interest_food_dining, R.drawable.ic_interest_food_dining),
    COOKING("cooking", R.string.interest_cooking, R.drawable.ic_interest_cooking),
    READING("reading", R.string.interest_reading, R.drawable.ic_interest_reading),
    SPORTS("sports", R.string.interest_sports, R.drawable.ic_interest_sports),
    ART_DESIGN("art_design", R.string.interest_art_design, R.drawable.ic_interest_art_design),
    VOLUNTEERING("volunteering", R.string.interest_volunteering, R.drawable.ic_interest_volunteering),
    PRAYER_ISLAMIC_STUDIES(
        "prayer_islamic_studies",
        R.string.interest_prayer_islamic_studies,
        R.drawable.ic_interest_prayer
    ),
    NATURE_OUTDOORS("nature_outdoors", R.string.interest_nature_outdoors, R.drawable.ic_interest_nature),
    FASHION("fashion", R.string.interest_fashion, R.drawable.ic_interest_fashion),
    GAMING("gaming", R.string.interest_gaming, R.drawable.ic_interest_gaming),
    PETS("pets", R.string.interest_pets, R.drawable.ic_interest_pets),
    BUSINESS_CAREER("business_career", R.string.interest_business_career, R.drawable.ic_interest_business),
    LANGUAGES("languages", R.string.interest_languages, R.drawable.ic_interest_languages),
    FAMILY_TIME("family_time", R.string.interest_family_time, R.drawable.ic_interest_family)
}
