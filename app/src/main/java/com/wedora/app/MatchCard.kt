package com.wedora.app

import androidx.annotation.DrawableRes

/**
 * One card in the Home feed. `id` is the Firestore user document ID (the
 * other user's Firebase Auth UID).
 *
 * `role`, `bio` and `distanceKm` have no backing Firestore field yet — real
 * cards leave `role` blank and `distanceKm` null (both hidden by
 * MatchCardAdapter) and `bio` empty (shown as an honest "No bio yet" rather
 * than fabricated text). Nothing here invents data the backend doesn't have.
 */
data class MatchCard(
    val id: String,
    val name: String,
    val role: String,
    @DrawableRes val avatarRes: Int,
    @DrawableRes val photoRes: Int,
    /** Distance in km, rendered into the accent pill. Null hides the pill entirely. */
    val distanceKm: Int?,
    val bio: String
)
