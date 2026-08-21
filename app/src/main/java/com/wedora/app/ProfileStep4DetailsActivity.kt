package com.wedora.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.view.View
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import com.google.firebase.firestore.FieldValue

/**
 * Step 4: age and location.
 *
 * Location has two modes. With ACCESS_COARSE_LOCATION granted the city is
 * detected via [LocationResolver] and shown read-only with a Refresh; if it's
 * denied — or detection legitimately fails (no last-known fix, no geocoder
 * backend) — it falls back to manual City/Country. Every failure path lands on
 * manual entry, so there is deliberately no way to get stuck.
 *
 * Manual entry still gets coordinates when possible: once city/country settle
 * (debounced — see [scheduleManualGeocode]), they're forward-geocoded via
 * [LocationResolver.resolveCoordinates] into an approximate [manualCoords],
 * which [stepUpdates] uses when there's no GPS-detected fix. This needs no
 * location permission, so it applies equally whether the user declined it or
 * simply prefers typing their city.
 */
class ProfileStep4DetailsActivity : ProfileStepActivity() {

    private companion object {
        /** Hard minimum age policy for the app, enforced in the rules too. */
        const val MIN_AGE = 18

        /** Upper sanity bound, to reject typos like "999". */
        const val MAX_AGE = 120

        /** How long manual city/country typing must settle before geocoding it. */
        const val GEOCODE_DEBOUNCE_MS = 600L
    }

    private lateinit var etAge: EditText
    private lateinit var etCity: EditText
    private lateinit var etCountry: EditText
    private lateinit var groupDetected: View
    private lateinit var groupManual: View
    private lateinit var tvDetectedLocation: TextView

    private val locationResolver by lazy { LocationResolver(this) }

    /** Non-null once detection has succeeded; null means we're in manual mode. */
    private var detectedPlace: LocationResolver.Place? = null

    /**
     * Forward-geocoded coordinates for the currently-typed manual city/
     * country, once [scheduleManualGeocode] resolves them. Null until then,
     * on a geocode failure, or whenever the text has changed since the last
     * successful resolve — see [geocodeToken].
     */
    private var manualCoords: Pair<Double, Double>? = null

    private val geocodeHandler = Handler(Looper.getMainLooper())
    private var geocodeRunnable: Runnable? = null

    /**
     * Bumped on every text change that invalidates [manualCoords]; a
     * resolve's result is only applied if this hasn't moved on since it
     * started, so a slow geocode for stale text can't clobber a newer one
     * that returned first.
     */
    private var geocodeToken = 0

    /**
     * Whether this profile already carries a createdAt, so [stepUpdates]
     * knows not to write a fresh one. This step has a back arrow, so it can
     * legitimately be saved more than once — and since createdAt now drives
     * "newest first" in the feed (see Feed.kt), rewriting it on a re-save
     * would silently reset an existing account's position to brand new.
     *
     * Defaults to false so a profile whose read failed still gets one
     * written: a missing createdAt sorts as oldest, which is worse for that
     * user than a slightly late one.
     */
    private var hasStoredCreatedAt = false

    private val requestLocationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) detectLocation() else showManualLocationMode()
        }

    // Visual step number only — class name and its own string resources
    // (step4_title/step4_subtitle) stay as-is; see the comment on those.
    override val stepNumber = 5
    override val stepId = OnboardingAnalytics.STEP_NAME_DETAILS
    override val titleRes = R.string.step4_title
    override val subtitleRes = R.string.step4_subtitle
    override val contentLayoutRes = R.layout.view_step_details

    override fun bindContent(content: View) {
        etAge = content.findViewById(R.id.etAge)
        etCity = content.findViewById(R.id.etCity)
        etCountry = content.findViewById(R.id.etCountry)
        groupDetected = content.findViewById(R.id.groupDetectedLocation)
        groupManual = content.findViewById(R.id.groupManualLocation)
        tvDetectedLocation = content.findViewById(R.id.tvDetectedLocation)

        val watcher = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) = Unit
            override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) = Unit
            override fun afterTextChanged(s: Editable?) = updateContinueEnabled()
        }
        etAge.addTextChangedListener(watcher)
        etCity.addTextChangedListener(watcher)
        etCountry.addTextChangedListener(watcher)
        // Tap-to-open rather than typing. The field keeps its TextWatchers, so
        // setting the text from the picker still triggers the debounced
        // forward-geocode that turns city + country into coordinates - which
        // the distance filter depends on.
        etCountry.setOnClickListener { openCountryPicker() }

        // Separate from `watcher` above: only city/country affect the geocode
        // query, and age changes shouldn't re-trigger it.
        val geocodeWatcher = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) = Unit
            override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                if (isInManualMode()) scheduleManualGeocode()
            }
        }
        etCity.addTextChangedListener(geocodeWatcher)
        etCountry.addTextChangedListener(geocodeWatcher)

        content.findViewById<View>(R.id.tvRefreshLocation)
            .setOnClickListener { detectLocation() }

        requestLocationOrFallBack()
    }

    override fun onExistingProfile(profile: UserProfile) {
        hasStoredCreatedAt = profile.createdAt != null
        profile.age?.let { if (etAge.text.isEmpty()) etAge.setText(it.toString()) }
        // Only pre-fills the manual fields; a stored place shouldn't override a
        // fresh detection that may already have landed.
        if (isInManualMode()) {
            if (etCity.text.isEmpty()) etCity.setText(profile.city.orEmpty())
            if (etCountry.text.isEmpty()) etCountry.setText(profile.country.orEmpty())
        }
    }

    // ----- Location -------------------------------------------------------

    private fun requestLocationOrFallBack() {
        val alreadyGranted = ContextCompat.checkSelfPermission(
            this, Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        // Confirms what actually reaches this screen after a grant on the
        // permissions primer — checkSelfPermission is process-wide and live,
        // so this should read true whenever it was granted there.
        Log.d(TAG, "ACCESS_COARSE_LOCATION granted=$alreadyGranted on step $stepNumber load")

        if (alreadyGranted) {
            detectLocation()
        } else {
            requestLocationPermission.launch(Manifest.permission.ACCESS_COARSE_LOCATION)
        }
    }

    private fun detectLocation() {
        detectedPlace = null
        tvDetectedLocation.setText(R.string.location_detecting)
        groupDetected.visibility = View.VISIBLE
        groupManual.visibility = View.GONE
        updateContinueEnabled()

        locationResolver.resolve(
            onSuccess = { place ->
                detectedPlace = place
                tvDetectedLocation.text =
                    getString(R.string.location_detected_format, place.city, place.country)
                updateContinueEnabled()
            },
            onFailure = {
                // Detection failing is a normal outcome, not an error.
                Log.i(TAG, "Location detection unavailable; falling back to manual entry")
                showManualLocationMode()
            }
        )
    }

    private fun showManualLocationMode() {
        detectedPlace = null
        groupDetected.visibility = View.GONE
        groupManual.visibility = View.VISIBLE
        updateContinueEnabled()
    }

    private fun isInManualMode(): Boolean = groupManual.visibility == View.VISIBLE

    private fun openCountryPicker() {
        CountryPickerBottomSheet
            .newInstance(selected = etCountry.text.toString(), allowAny = false)
            .also { it.onCountryPicked = { name -> etCountry.setText(name.orEmpty()) } }
            .show(supportFragmentManager, "country_picker")
    }

    /**
     * Debounced forward-geocode of the typed city/country into [manualCoords].
     * Any pending attempt is cancelled and [manualCoords] invalidated
     * immediately — the old text no longer describes what's on screen — then
     * a fresh one is scheduled once the fields stop changing, so a still-
     * typing user doesn't fire a geocode per keystroke.
     */
    private fun scheduleManualGeocode() {
        geocodeRunnable?.let { geocodeHandler.removeCallbacks(it) }
        manualCoords = null

        val city = etCity.text.toString().trim()
        val country = etCountry.text.toString().trim()
        if (city.isEmpty() || country.isEmpty()) return

        val token = ++geocodeToken
        val runnable = Runnable {
            locationResolver.resolveCoordinates(
                city, country,
                onSuccess = { lat, lon -> if (token == geocodeToken) manualCoords = lat to lon },
                onFailure = {
                    // Fails open, same as everywhere else location can't be
                    // resolved: this user simply has no coordinates for now.
                    Log.i(TAG, "Could not forward-geocode manual city/country")
                }
            )
        }
        geocodeRunnable = runnable
        geocodeHandler.postDelayed(runnable, GEOCODE_DEBOUNCE_MS)
    }

    /** City/country from whichever mode is active, or null if not yet available. */
    private fun currentPlace(): LocationResolver.Place? {
        if (!isInManualMode()) return detectedPlace

        val city = etCity.text.toString().trim()
        val country = etCountry.text.toString().trim()
        return if (city.isNotEmpty() && country.isNotEmpty()) {
            LocationResolver.Place(city, country)
        } else {
            null
        }
    }

    // ----- Age ------------------------------------------------------------

    /**
     * A syntactically plausible age, deliberately NOT applying [MIN_AGE]. The
     * 18+ rule is checked on Continue instead, so an under-age entry leaves
     * the button tappable and gets an explicit message — gating the button on
     * it would reject them silently with no way to find out why.
     */
    private fun enteredAge(): Int? =
        etAge.text.toString().trim().toIntOrNull()?.takeIf { it in 1..MAX_AGE }

    override fun isStepValid(): Boolean = enteredAge() != null && currentPlace() != null

    override fun stepUpdates(): Map<String, Any?> {
        val place = currentPlace() ?: return emptyMap()
        val updates = mutableMapOf<String, Any?>(
            UserProfile.FIELD_AGE to enteredAge(),
            UserProfile.FIELD_CITY to place.city,
            UserProfile.FIELD_COUNTRY to place.country
        )
        // Set here rather than at sign-up: this is the point the profile
        // becomes real enough to appear in anyone's feed. Written once and
        // only once — see [hasStoredCreatedAt] for why a re-save must not
        // refresh it.
        if (!hasStoredCreatedAt) {
            updates[UserProfile.FIELD_CREATED_AT] = FieldValue.serverTimestamp()
        }
        // A GPS-detected place carries its own fix; a manually typed one falls
        // back to whatever scheduleManualGeocode() resolved for it, if the
        // debounce settled in time. Neither being available leaves the fields
        // absent rather than written null — there's simply nothing to record,
        // and distance for this user falls open.
        val coords = if (place.latitude != null && place.longitude != null) {
            place.latitude to place.longitude
        } else {
            manualCoords
        }
        if (coords != null) {
            // Coordinates are rounded to ~1km precision before storage as a
            // privacy measure — since Firestore rules currently allow any
            // signed-in user to read this field directly (needed for the
            // matching feed), this prevents raw coordinates from revealing
            // someone's precise/exact location even if read outside the
            // app's own distance-badge UI.
            updates[UserProfile.FIELD_LATITUDE] = fuzzCoordinate(coords.first)
            updates[UserProfile.FIELD_LONGITUDE] = fuzzCoordinate(coords.second)
        }
        return updates
    }

    /**
     * The 18+ check runs before the write, not as part of validity, so the
     * user sees why. The security rules enforce the same floor server-side —
     * this is the explanation, not the protection.
     */
    override fun onContinueRequested(): Boolean {
        val age = enteredAge()
        if (age == null) {
            etAge.error = getString(R.string.error_age_invalid)
            etAge.requestFocus()
            return false
        }
        if (age < MIN_AGE) {
            etAge.error = getString(R.string.error_age_minimum, MIN_AGE)
            etAge.requestFocus()
            return false
        }
        if (currentPlace() == null) {
            Toast.makeText(this, R.string.error_city_required, Toast.LENGTH_SHORT).show()
            return false
        }
        return true
    }

    override fun nextStep(): Class<*> = ProfileStep5PhotoActivity::class.java
}
