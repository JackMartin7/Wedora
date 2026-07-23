package com.wedora.app

import android.content.Context
import androidx.annotation.DrawableRes
import java.util.Date

/**
 * One card in the Home feed. `id` is the Firestore user document ID (the
 * other user's Firebase Auth UID).
 *
 * [age], [city] and [country] come from the other user's Complete Profile
 * step, so they are real data. They are still nullable: an account that
 * hasn't been through that step yet (or predates it) has none, and the card
 * simply omits the line rather than inventing one.
 *
 * [bio] is real data, written by the Edit Profile screen. `role` has no backing
 * Firestore field, so real cards leave it blank (hidden when bound).
 *
 * [distanceKm] is computed after loading from this user's [latitude]/[longitude]
 * and the viewer's own, via [withDistanceFrom]. It's null whenever either side
 * has no coordinates — a manually typed city, or an account predating them — and
 * the distance pill is hidden rather than showing a placeholder.
 */
data class MatchCard(
    val id: String,
    val name: String,
    val role: String,
    @DrawableRes val avatarRes: Int,
    @DrawableRes val photoRes: Int,
    /** Distance in km from the viewer, or null when it can't be computed. */
    val distanceKm: Double?,
    val bio: String,
    val age: Int?,
    val city: String?,
    val country: String?,
    /** This user's stored coordinates, for distance. Null for a typed city. */
    val latitude: Double?,
    val longitude: Double?,
    /**
     * This user's own gender, as stored in Firestore. Carried so the feed can
     * apply a gender filter client-side; null on a profile that never set one.
     */
    val gender: String?,
    /** Null on accounts predating the field — the badge is hidden, not blank. */
    val myStatus: String?,
    val lookingFor: String?,
    /** For the online-status dot; null on accounts predating presence tracking. */
    val lastSeen: Date?
) {

    /**
     * A copy carrying the distance from the viewer at ([myLat], [myLon]). Null
     * distance when either side lacks coordinates — see [distanceKm].
     */
    fun withDistanceFrom(myLat: Double?, myLon: Double?): MatchCard {
        val distance = if (myLat != null && myLon != null &&
            latitude != null && longitude != null
        ) {
            DistanceUtils.distanceKm(myLat, myLon, latitude, longitude)
        } else {
            null
        }
        return copy(distanceKm = distance)
    }

    /** "2.4 km" for the distance pill, or null when there's no distance to show. */
    fun distanceBadge(): String? = distanceKm?.let { DistanceUtils.formatDistance(it) }

    /** "Divorced • Looking for Second Wife", or null when neither is set. */
    fun marriageIntentLine(context: Context): String? =
        MarriageIntent.summaryLine(context, myStatus, lookingFor)
    /** "24 • Islamabad, Pakistan", or null if this user hasn't completed their profile. */
    fun ageLocationLine(context: Context): String? =
        formatAgeLocation(context, R.string.match_card_age_location_format, age, city, country)

    /**
     * The bio shortened for the card, or null when there isn't one so the
     * caller can hide the view. Cut on a word boundary where there is one
     * within reach, so the preview doesn't end mid-word.
     */
    fun bioPreview(): String? {
        val trimmed = bio.trim()
        if (trimmed.isEmpty()) return null
        if (trimmed.length <= BIO_PREVIEW_LENGTH) return trimmed

        val cut = trimmed.take(BIO_PREVIEW_LENGTH)
        val lastSpace = cut.lastIndexOf(' ')
        val body = if (lastSpace >= BIO_PREVIEW_LENGTH / 2) cut.take(lastSpace) else cut
        return body.trimEnd() + "…"
    }

    private companion object {
        const val BIO_PREVIEW_LENGTH = 60
    }
}
