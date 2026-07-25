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
) {
    val selfUid = FirebaseAuth.getInstance().currentUser
        ?.takeUnless { GuestPrefs.isGuest(context) }?.uid
    if (selfUid == null) {
        onResult(null, null)
        return
    }
    firestore.collection(UserProfile.COLLECTION).document(selfUid).get()
        .addOnSuccessListener { snapshot ->
            val self = UserProfile.from(snapshot)
            onResult(self.latitude, self.longitude)
        }
        .addOnFailureListener { onResult(null, null) }
}
