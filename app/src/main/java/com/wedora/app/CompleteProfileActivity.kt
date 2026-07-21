package com.wedora.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import com.wedora.app.databinding.ActivityCompleteProfileBinding

/**
 * One-time step after first verified login, gated by [LoginActivity]. Collects
 * age + city/country and writes them to the user's Firestore document.
 *
 * Location has two modes. If ACCESS_COARSE_LOCATION is granted we detect the
 * city via [LocationResolver] and show it read-only with a Refresh option; if
 * it's denied — or detection legitimately fails (no last-known fix, no
 * geocoder backend) — we fall back to manual City/Country inputs on the same
 * screen. There is deliberately no way to get stuck: every failure path lands
 * on manual entry.
 */
class CompleteProfileActivity : AppCompatActivity() {

    private companion object {
        const val TAG = "WedoraProfile"

        /** Hard minimum age policy for the app. Enforced in [saveAndContinue]. */
        const val MIN_AGE = 18

        /** Upper sanity bound, to reject typos like "999". */
        const val MAX_AGE = 120
    }

    private lateinit var binding: ActivityCompleteProfileBinding
    private val auth: FirebaseAuth by lazy { FirebaseAuth.getInstance() }
    private val firestore: FirebaseFirestore by lazy { FirebaseFirestore.getInstance() }
    private val locationResolver by lazy { LocationResolver(this) }

    /** Non-null once detection has succeeded; null means we're in manual mode. */
    private var detectedPlace: LocationResolver.Place? = null

    private val requestLocationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) detectLocation() else showManualLocationMode()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityCompleteProfileBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.etAge.addTextChangedListener(SimpleWatcher { updateContinueEnabled() })
        binding.etCity.addTextChangedListener(SimpleWatcher { updateContinueEnabled() })
        binding.etCountry.addTextChangedListener(SimpleWatcher { updateContinueEnabled() })

        binding.tvRefreshLocation.setOnClickListener { detectLocation() }
        binding.btnContinue.setOnClickListener { saveAndContinue() }

        updateContinueEnabled()
        requestLocationOrFallBack()
    }

    // ----- Location -------------------------------------------------------

    private fun requestLocationOrFallBack() {
        val alreadyGranted = ContextCompat.checkSelfPermission(
            this, Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

        if (alreadyGranted) {
            detectLocation()
        } else {
            requestLocationPermission.launch(Manifest.permission.ACCESS_COARSE_LOCATION)
        }
    }

    private fun detectLocation() {
        showDetectingLocationMode()
        locationResolver.resolve(
            onSuccess = { place ->
                detectedPlace = place
                binding.tvDetectedLocation.text =
                    getString(R.string.location_detected_format, place.city, place.country)
                updateContinueEnabled()
            },
            onFailure = {
                // Detection failing is a normal outcome, not an error — drop to
                // manual entry so the user is never blocked.
                Log.i(TAG, "Location detection unavailable; falling back to manual entry")
                showManualLocationMode()
            }
        )
    }

    private fun showDetectingLocationMode() {
        detectedPlace = null
        binding.tvDetectedLocation.setText(R.string.location_detecting)
        binding.groupDetectedLocation.visibility = View.VISIBLE
        binding.groupManualLocation.visibility = View.GONE
        updateContinueEnabled()
    }

    private fun showManualLocationMode() {
        detectedPlace = null
        binding.groupDetectedLocation.visibility = View.GONE
        binding.groupManualLocation.visibility = View.VISIBLE
        updateContinueEnabled()
    }

    private fun isInManualMode(): Boolean =
        binding.groupManualLocation.visibility == View.VISIBLE

    // ----- Validation -----------------------------------------------------

    /**
     * A syntactically plausible age, deliberately NOT applying the [MIN_AGE]
     * policy. The 18+ rule is enforced on Continue instead of here, so that an
     * under-age entry leaves the button tappable and the user gets an explicit
     * "you must be 18 or older" message — gating the button on it would reject
     * them silently with no way to find out why.
     */
    private fun enteredAge(): Int? =
        binding.etAge.text.toString().trim().toIntOrNull()?.takeIf { it in 1..MAX_AGE }

    /** City/country from whichever mode is active, or null if not yet available. */
    private fun currentPlace(): LocationResolver.Place? {
        if (!isInManualMode()) return detectedPlace

        val city = binding.etCity.text.toString().trim()
        val country = binding.etCountry.text.toString().trim()
        return if (city.isNotEmpty() && country.isNotEmpty()) {
            LocationResolver.Place(city, country)
        } else {
            null
        }
    }

    private fun updateContinueEnabled() {
        binding.btnContinue.isEnabled = enteredAge() != null && currentPlace() != null
    }

    // ----- Save -----------------------------------------------------------

    private fun saveAndContinue() {
        val ageText = binding.etAge.text.toString().trim()
        if (ageText.isEmpty()) {
            binding.etAge.error = getString(R.string.error_age_required)
            binding.etAge.requestFocus()
            return
        }
        val age = enteredAge()
        if (age == null) {
            binding.etAge.error = getString(R.string.error_age_invalid)
            binding.etAge.requestFocus()
            return
        }
        if (age < MIN_AGE) {
            binding.etAge.error = getString(R.string.error_age_minimum, MIN_AGE)
            binding.etAge.requestFocus()
            return
        }

        if (isInManualMode()) {
            if (binding.etCity.text.toString().isBlank()) {
                binding.etCity.error = getString(R.string.error_city_required)
                binding.etCity.requestFocus()
                return
            }
            if (binding.etCountry.text.toString().isBlank()) {
                binding.etCountry.error = getString(R.string.error_country_required)
                binding.etCountry.requestFocus()
                return
            }
        }

        val place = currentPlace() ?: return
        val uid = auth.currentUser?.uid
        if (uid == null) {
            Toast.makeText(this, R.string.error_generic_login, Toast.LENGTH_LONG).show()
            return
        }

        setLoading(true)
        val updates = mapOf(
            UserProfile.FIELD_AGE to age,
            UserProfile.FIELD_CITY to place.city,
            UserProfile.FIELD_COUNTRY to place.country
        )
        // merge, not update(): accounts created before the Firestore user-doc
        // feature existed have no document at all, and update() would fail on
        // a missing document. Those are exactly the accounts this gate catches.
        firestore.collection(UserProfile.COLLECTION).document(uid)
            .set(updates, SetOptions.merge())
            .addOnSuccessListener {
                startActivity(Intent(this, HomeActivity::class.java))
                finish()
            }
            .addOnFailureListener { e ->
                Log.w(TAG, "Failed to save profile completion", e)
                setLoading(false)
                Toast.makeText(this, R.string.error_profile_update_failed, Toast.LENGTH_LONG).show()
            }
    }

    private fun setLoading(loading: Boolean) {
        binding.btnContinue.isEnabled = !loading
        binding.btnContinue.setText(if (loading) R.string.btn_saving else R.string.btn_continue)
        if (!loading) updateContinueEnabled()
    }

    /** Minimal TextWatcher so the three fields can share one re-validate hook. */
    private class SimpleWatcher(private val onChanged: () -> Unit) : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
        override fun afterTextChanged(s: Editable?) = onChanged()
    }
}
