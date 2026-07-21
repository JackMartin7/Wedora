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
import java.io.IOException
import java.util.Locale
import java.util.concurrent.Executors

/**
 * Resolves the device's last-known coarse location into a city + country.
 *
 * Two-step: FusedLocationProviderClient for the coordinates, then [Geocoder]
 * to reverse-geocode them. Either step can legitimately come up empty — a
 * fresh emulator has no last-known location, and some devices ship without a
 * geocoder backend — so callers must handle [onFailure] as a normal outcome
 * and fall back to manual entry, not treat it as an error state.
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

    data class Place(val city: String, val country: String)

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
                    // Normal on a device that has never acquired a fix.
                    Log.w(TAG, "No last-known location available")
                    onFailure()
                } else {
                    reverseGeocode(location, onSuccess, onFailure)
                }
            }
            .addOnFailureListener { e ->
                Log.w(TAG, "Failed to get last-known location", e)
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
                        deliver(addresses.firstOrNull(), onSuccess, onFailure)
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
                deliver(address, onSuccess, onFailure)
            }
        }
    }

    /**
     * Called from a background/Geocoder callback thread on both paths, so it
     * always hops to main before invoking the caller's UI-touching callbacks.
     */
    private fun deliver(address: Address?, onSuccess: (Place) -> Unit, onFailure: () -> Unit) {
        val city = address?.locality
        val country = address?.countryName
        mainHandler.post {
            if (city.isNullOrBlank() || country.isNullOrBlank()) {
                Log.w(TAG, "Geocoder returned no locality/country")
                onFailure()
            } else {
                onSuccess(Place(city, country))
            }
        }
    }
}
