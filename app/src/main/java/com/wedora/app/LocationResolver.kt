package com.wedora.app

import android.annotation.SuppressLint
import android.content.Context
import android.location.Address
import android.location.Geocoder
import android.location.Location
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import java.io.IOException
import java.util.Locale
import java.util.concurrent.Executors

/**
 * Resolves the device's coarse location into a city + country.
 *
 * Two-step: FusedLocationProviderClient for the coordinates, then [Geocoder]
 * to reverse-geocode them. Either step can legitimately come up empty — some
 * devices ship without a geocoder backend — so callers must handle
 * [onFailure] as a normal outcome and fall back to manual entry, not treat it
 * as an error state.
 *
 * The coordinate step itself tries twice: [lastLocation] first (instant, no
 * new fix needed, but only populated if *something* on the device has
 * requested a location recently), then [getCurrentLocation] if that comes up
 * empty (slower — it actively waits for a fresh fix — but this is exactly the
 * case that matters most here: permission was very likely *just* granted on
 * the permissions primer screen, with nothing else on the device having ever
 * requested a fix, so lastLocation is reliably null on a fresh grant and
 * detection would silently fall back to manual every time without this).
 */
class LocationResolver(private val context: Context) {

    private companion object {
        const val TAG = "WedoraLocation"
    }

    private val fusedClient by lazy {
        LocationServices.getFusedLocationProviderClient(context)
    }

    /** Single background thread for the pre-API-33 blocking Geocoder call. */
    private val geocodeExecutor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())

    /**
     * [latitude]/[longitude] are the raw coordinates the fix came from, carried
     * through so callers can store them for distance. They're nullable because
     * a manually typed place (built without a fix) has no coordinates; a
     * detected place always sets them.
     */
    data class Place(
        val city: String,
        val country: String,
        val latitude: Double? = null,
        val longitude: Double? = null
    )

    /**
     * Caller MUST have ACCESS_COARSE_LOCATION granted before calling this —
     * hence [SuppressLint]. Both callbacks are delivered on the main thread.
     */
    @SuppressLint("MissingPermission")
    fun resolve(onSuccess: (Place) -> Unit, onFailure: () -> Unit) {
        if (!Geocoder.isPresent()) {
            Log.w(TAG, "No geocoder backend on this device")
            onFailure()
            return
        }

        fusedClient.lastLocation
            .addOnSuccessListener { location ->
                if (location == null) {
                    // Normal right after a fresh grant — nothing has requested
                    // a fix yet for the OS to have cached one. Not a failure;
                    // request one directly instead of giving up here.
                    Log.d(TAG, "No cached last-known location; requesting a fresh fix")
                    requestFreshLocation(onSuccess, onFailure)
                } else {
                    Log.d(TAG, "Using cached last-known location")
                    reverseGeocode(location, onSuccess, onFailure)
                }
            }
            .addOnFailureListener { e ->
                Log.w(TAG, "Failed to get last-known location; requesting a fresh fix", e)
                requestFreshLocation(onSuccess, onFailure)
            }
    }

    /**
     * Actively waits for a fresh fix, unlike [FusedLocationProviderClient.getLastLocation]
     * which only ever returns a cached one. Balanced accuracy is plenty for
     * city-level resolution and settles faster than high accuracy would.
     */
    @SuppressLint("MissingPermission")
    private fun requestFreshLocation(onSuccess: (Place) -> Unit, onFailure: () -> Unit) {
        fusedClient.getCurrentLocation(Priority.PRIORITY_BALANCED_POWER_ACCURACY, null)
            .addOnSuccessListener { location ->
                if (location == null) {
                    Log.w(TAG, "Fresh location request returned no fix")
                    onFailure()
                } else {
                    Log.d(TAG, "Using freshly requested location")
                    reverseGeocode(location, onSuccess, onFailure)
                }
            }
            .addOnFailureListener { e ->
                Log.w(TAG, "Fresh location request failed", e)
                onFailure()
            }
    }

    private fun reverseGeocode(
        location: Location,
        onSuccess: (Place) -> Unit,
        onFailure: () -> Unit
    ) {
        val geocoder = Geocoder(context, Locale.getDefault())

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // API 33+ provides an async variant; the blocking one is deprecated.
            geocoder.getFromLocation(location.latitude, location.longitude, 1,
                object : Geocoder.GeocodeListener {
                    override fun onGeocode(addresses: MutableList<Address>) {
                        deliver(addresses.firstOrNull(), location, onSuccess, onFailure)
                    }

                    override fun onError(errorMessage: String?) {
                        Log.w(TAG, "Geocoder error: $errorMessage")
                        mainHandler.post { onFailure() }
                    }
                })
        } else {
            // Blocking call — does network I/O, so it must stay off the main thread.
            geocodeExecutor.execute {
                val address = try {
                    @Suppress("DEPRECATION")
                    geocoder.getFromLocation(location.latitude, location.longitude, 1)
                        ?.firstOrNull()
                } catch (e: IOException) {
                    Log.w(TAG, "Reverse geocode failed", e)
                    null
                }
                deliver(address, location, onSuccess, onFailure)
            }
        }
    }

    /**
     * Called from a background/Geocoder callback thread on both paths, so it
     * always hops to main before invoking the caller's UI-touching callbacks.
     *
     * The city/country name comes from the reverse-geocoded [address], but the
     * coordinates come straight off [location] — they're what the fix actually
     * reported, independent of whether the geocoder could name them.
     */
    private fun deliver(
        address: Address?,
        location: Location,
        onSuccess: (Place) -> Unit,
        onFailure: () -> Unit
    ) {
        val city = address?.locality
        val country = address?.countryName
        mainHandler.post {
            if (city.isNullOrBlank() || country.isNullOrBlank()) {
                Log.w(TAG, "Geocoder returned no locality/country")
                onFailure()
            } else {
                onSuccess(Place(city, country, location.latitude, location.longitude))
            }
        }
    }
}
