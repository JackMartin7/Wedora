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
 * [bio] is real data, written by the Edit Profile screen. `role` and
 * `distanceKm` still have no backing Firestore field — real cards leave `role`
 * blank and `distanceKm` null (both hidden when the card is bound). Nothing
 * here invents data the backend doesn't have.
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
    val country: String?,
    /**
     * This user's own gender, as stored in Firestore. Carried so the feed can
     * apply a gender filter client-side; null on a profile that never set one.
     */
    val gender: String?,
    /** Null on accounts predating the field — the badge is hidden, not blank. */
    val myStatus: String?,
    val lookingFor: String?
) {

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
