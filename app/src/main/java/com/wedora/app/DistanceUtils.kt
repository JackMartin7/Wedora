package com.wedora.app

import android.content.Context
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import java.util.Locale
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Great-circle distance between two coordinates, and how to show it.
 *
 * The app has no server-side geo query, so "nearby" is computed on the client
 * from the coordinates stored on each profile — good enough for a city-level
 * "how far away" without a Maps backend or geohash indexing.
 */
object DistanceUtils {

    private const val EARTH_RADIUS_KM = 6371.0

    /** Haversine distance in kilometres between two lat/lng points. */
    fun distanceKm(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2) * sin(dLat / 2) +
            cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
            sin(dLon / 2) * sin(dLon / 2)
        val c = 2 * atan2(sqrt(a), sqrt(1 - a))
        return EARTH_RADIUS_KM * c
    }

    /**
     * Distance for display. Precision follows how far away it is: a decimal
     * under 10 km ("2.4 km"), whole kilometres above that ("12 km", "240 km") —
     * a tenth of a kilometre only reads as meaningful when you're close.
     */
    fun formatDistance(km: Double): String = when {
        km < 10.0 -> String.format(Locale.US, "%.1f km", km)
        else -> "${km.roundToInt()} km"
    }
}

/**
 * Rounds a latitude or longitude to roughly a 1km grid, for storage rather
 * than display — see call sites in ProfileStep4DetailsActivity and
 * EditProfileActivity. 1 degree of latitude is ~111km, so 2 decimal places
 * is ~1.11km precision (3 decimal places would be ~111m, too precise for
 * this purpose).
 *
 * Coordinates are rounded to ~1km precision before storage as a privacy
 * measure — since Firestore rules currently allow any signed-in user to
 * read this field directly (needed for the matching feed), this prevents
 * raw coordinates from revealing someone's precise/exact location even if
 * read outside the app's own distance-badge UI. The ~1-2km of extra
 * imprecision this adds to a calculated distance (both sides' coordinates
 * are rounded) is an accepted tradeoff, not a bug — see
 * [DistanceUtils.distanceKm], which is unchanged and simply receives
 * already-rounded values.
 */
fun fuzzCoordinate(value: Double): Double = Math.round(value * 100.0) / 100.0

/**
 * "2.4 km" between two points, or null when either side lacks coordinates —
 * the single fail-open check every distance badge in the app shares. Screens
 * whose display model isn't [MatchCard] (Likes, Notifications, Match
 * History, Chats, Profile Detail, ...) use this directly instead of each
 * re-implementing the same null handling [MatchCard.withDistanceFrom]/
 * [MatchCard.distanceBadge] already do for the feed screens.
 */
fun distanceBadgeBetween(myLat: Double?, myLon: Double?, otherLat: Double?, otherLon: Double?): String? {
    if (myLat == null || myLon == null || otherLat == null || otherLon == null) return null
    return DistanceUtils.formatDistance(DistanceUtils.distanceKm(myLat, myLon, otherLat, otherLon))
}

/**
 * The signed-in user's own coordinates, for a screen that needs them for a
 * distance badge but has no other reason to load its own profile. Delivers
 * (null, null) for a guest, a signed-out user, a read failure, or an account
 * with no coordinates on file — every one of those means "no distance to
 * show" to a caller, so they're deliberately not told apart.
 */
fun loadSelfCoordinates(
    context: Context,
    firestore: FirebaseFirestore,
    onResult: (lat: Double?, lon: Double?) -> Unit
) = loadSelfProfile(context, firestore) { self ->
    onResult(self?.latitude, self?.longitude)
}

/**
 * The signed-in viewer's own profile, or null for a guest or on failure.
 *
 * [loadSelfCoordinates] was always doing this read and then throwing all but
 * two fields away. Callers that need more than coordinates — ProfileDetail,
 * which cross-references the viewer's interests against the profile being
 * viewed — use this instead, at no extra cost: it is the same single read.
 *
 * Kept as a separate function rather than widening loadSelfCoordinates'
 * callback, which five screens depend on.
 *
 * Null for a guest is deliberate and load-bearing: a guest has no profile to
 * compare against, so features built on this must degrade rather than guess.
 */
fun loadSelfProfile(
    context: Context,
    firestore: FirebaseFirestore,
    onResult: (UserProfile?) -> Unit
) {
    val selfUid = FirebaseAuth.getInstance().currentUser
        ?.takeUnless { GuestPrefs.isGuest(context) }?.uid
    if (selfUid == null) {
        onResult(null)
        return
    }
    firestore.collection(UserProfile.COLLECTION).document(selfUid).get()
        .addOnSuccessListener { onResult(UserProfile.from(it)) }
        .addOnFailureListener { onResult(null) }
}
