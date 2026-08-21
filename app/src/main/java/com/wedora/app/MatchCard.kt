package com.wedora.app

import android.content.Context
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
    /**
     * Likes this profile has received, already floored at zero by
     * [UserProfile.from]. Shown on the swipe card beside the heart; see
     * item_match_card.xml. Zero renders as "0" rather than hiding, so the
     * control keeps a stable width as the deck advances.
     */
    val likesReceivedCount: Int,
    /**
     * This profile's interests, as [Interest.firestoreValue] ids. Empty on
     * anyone who skipped that step, which the interests filter treats as
     * "cannot match" — see matchesActiveFilters.
     */
    val interests: List<String>,
    /** Null on accounts predating the field — the badge is hidden, not blank. */
    val myStatus: String?,
    val lookingFor: String?,
    /** For the online-status dot; null on accounts predating presence tracking. */
    val lastSeen: Date?,
    /**
     * When this profile became visible in the feed — see
     * [UserProfile.createdAt]. Drives "newest first" ordering; null on
     * accounts predating the field, which sort as oldest.
     */
    val createdAt: Date?,
    /** This user's own Premium status — see withPremiumPriority() in Feed.kt. */
    val isPremium: Boolean,
    /**
     * This user's hosted photo (see PhotoUploadService), or null if they've
     * never uploaded one — callers load it via [loadRemoteProfilePhoto],
     * which falls back to the neutral placeholder either way (null, or a
     * failed load).
     */
    val photoUrl: String?
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

    /**
     * Whether this profile became visible within [NEW_SIGNUP_WINDOW_MS] —
     * the Explore visibility band (see Feed.kt's withNewSignupPriority).
     *
     * A missing [createdAt] is not new: an account predating the field is
     * old by definition, and treating unknown as new would put every legacy
     * profile in the band permanently.
     */
    fun isNewSignup(nowMs: Long = System.currentTimeMillis()): Boolean {
        val created = createdAt?.time ?: return false
        return nowMs - created in 0..NEW_SIGNUP_WINDOW_MS
    }

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

    companion object {
        private const val BIO_PREVIEW_LENGTH = 60

        /**
         * How long a profile counts as "new" for Explore's visibility band.
         *
         * 7 days rather than something tighter, deliberately. The band is
         * self-limiting — inside it cards still sort by distance — so a wide
         * window degrades toward the plain distance sort rather than
         * distorting it, while a window that's too narrow is simply empty
         * most of the time on a small user base and the band does nothing.
         * It also can't cause repeat exposure: once someone likes or passes
         * a profile it leaves their feed entirely (see Feed.kt's tier 1), so
         * this only governs where a profile lands on first sight.
         */
        const val NEW_SIGNUP_WINDOW_MS = 7L * 24 * 60 * 60 * 1000
    }
}
