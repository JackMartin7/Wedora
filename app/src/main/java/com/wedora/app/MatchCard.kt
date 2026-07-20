package com.wedora.app

import androidx.annotation.DrawableRes

/** One card in the Home feed. */
data class MatchCard(
    val id: Long,
    val name: String,
    val role: String,
    @DrawableRes val avatarRes: Int,
    @DrawableRes val photoRes: Int,
    /** Distance in km, rendered into the accent pill. */
    val distanceKm: Int,
    val bio: String
) {
    companion object {
        /**
         * Placeholder feed. Replaced by real data in a later task — see
         * HomeActivity where this is loaded.
         */
        fun sampleCards(): List<MatchCard> = listOf(
            MatchCard(
                id = 1,
                name = "Amelia Hart",
                role = "Photographer",
                avatarRes = R.drawable.home_card1_avatar,
                photoRes = R.drawable.home_main_photo,
                distanceKm = 2,
                bio = "Weekend hiker, flat-white enthusiast, and forever chasing " +
                    "golden hour. Looking for someone to explore the coast with."
            ),
            MatchCard(
                id = 2,
                name = "Noah Bennett",
                role = "Music Producer",
                avatarRes = R.drawable.home_card2_avatar,
                photoRes = R.drawable.profile_photo,
                distanceKm = 5,
                bio = "I make beats at midnight and pancakes at noon. Tell me your " +
                    "favourite album and I'll tell you mine."
            ),
            MatchCard(
                id = 3,
                name = "Sofia Reyes",
                role = "Chef",
                avatarRes = R.drawable.home_card1_avatar,
                photoRes = R.drawable.home_main_photo,
                distanceKm = 8,
                bio = "Cooking is my love language. Bring your appetite and a good story."
            )
        )
    }
}
