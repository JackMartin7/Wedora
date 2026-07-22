package com.wedora.app

import android.content.Context
import androidx.annotation.DrawableRes

/**
 * One card in the Home feed. `id` is the Firestore user document ID (the
 * other user's Firebase Auth UID).
 *
 * [age], [city] and [country] come from the other user's Complete Profile
 * step, so they are real data. They are still nullable: an account that
 * hasn't been through that step yet (or predates it) has none, and the card
 * simply omits the line rather than inventing one.
 *
 * `role`, `bio` and `distanceKm` have no backing Firestore field at all yet —
 * real cards leave `role` blank and `distanceKm` null (both hidden when the
 * card is bound). Nothing here invents data the backend doesn't have.
 */
data class MatchCard(
    val id: String,
    val name: String,
    val role: String,
    @DrawableRes val avatarRes: Int,
    @DrawableRes val photoRes: Int,
    /** Distance in km, rendered into the accent pill. Null hides the pill entirely. */
    val distanceKm: Int?,
    val bio: String,
    val age: Int?,
    val city: String?,
    val country: String?
) {
    /** "24 • Islamabad, Pakistan", or null if this user hasn't completed their profile. */
    fun ageLocationLine(context: Context): String? =
        formatAgeLocation(context, R.string.match_card_age_location_format, age, city, country)
}
