package com.wedora.app

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
