package com.wedora.app

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.wedora.app.databinding.ActivityProfileBinding
import com.wedora.app.databinding.ItemSettingsRowBinding

class ProfileActivity : AppCompatActivity() {

    private companion object {
        const val TAG = "WedoraProfile"
    }

    private lateinit var binding: ActivityProfileBinding
    private val firestore: FirebaseFirestore by lazy { FirebaseFirestore.getInstance() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityProfileBinding.inflate(layoutInflater)
        setContentView(binding.root)

        showSignedInUser()
        setUpDarkModeSwitch()
        setUpSettingsRows()
        setUpWedoraBottomNav(binding.bottomNav, R.id.nav_profile)

        binding.btnHistory.setOnClickListener { toast(getString(R.string.cd_history)) }
        binding.btnEditProfile.setOnClickListener { toast(getString(R.string.profile_edit_button)) }
    }

    /**
     * Name and email are real Firebase account data; the photo is the
     * device-local file saved at sign-up (see [LocalProfilePrefs]), not a
     * Firebase photoUrl; age/city/country are read from Firestore below. The
     * stats card stays a placeholder — there's no backend field for it yet.
     */
    private fun showSignedInUser() {
        if (GuestPrefs.isGuest(this)) {
            binding.tvProfileName.text = getString(R.string.guest_label)
            binding.tvProfileEmail.visibility = View.GONE
            binding.tvProfileAgeLocation.visibility = View.GONE
            return
        }

        val user = FirebaseAuth.getInstance().currentUser
        binding.tvProfileName.text = user?.displayName?.takeIf { it.isNotBlank() }
            ?: getString(R.string.default_profile_name)

        val email = user?.email
        if (!email.isNullOrBlank()) {
            binding.tvProfileEmail.text = email
            binding.tvProfileEmail.visibility = View.VISIBLE
        } else {
            binding.tvProfileEmail.visibility = View.GONE
        }

        user?.uid?.let {
            binding.ivProfilePhoto.loadLocalProfilePhoto(this, it)
            showAgeAndLocation(it)
        }
    }

    /**
     * Populates "{age} years old • {city}, {country}" from the user's Firestore
     * doc. Stays hidden if the read fails or the fields aren't there — the
     * Complete Profile gate normally guarantees they are, but an older session
     * or a network failure shouldn't render a half-empty line.
     */
    private fun showAgeAndLocation(uid: String) {
        firestore.collection(UserProfile.COLLECTION).document(uid).get()
            .addOnSuccessListener { snapshot ->
                val line = UserProfile.from(snapshot).ageLocationLine(this)
                if (line == null) {
                    binding.tvProfileAgeLocation.visibility = View.GONE
                } else {
                    binding.tvProfileAgeLocation.text = line
                    binding.tvProfileAgeLocation.visibility = View.VISIBLE
                }
            }
            .addOnFailureListener { e ->
                Log.w(TAG, "Couldn't load age/location for profile", e)
                binding.tvProfileAgeLocation.visibility = View.GONE
            }
    }

    private fun setUpDarkModeSwitch() {
        // Set the initial state before attaching the listener so restoring it
        // doesn't immediately re-trigger a mode change.
        binding.switchDarkMode.isChecked = ThemePrefs.isDarkEnabled(this)
        binding.switchDarkMode.setOnCheckedChangeListener { _, isChecked ->
            ThemePrefs.setDarkEnabled(this, isChecked)
        }
    }

    private fun setUpSettingsRows() {
        val rows = listOf(
            SettingsRow(R.drawable.ic_account, R.string.settings_account) {
                toast(getString(R.string.settings_account))
            },
            SettingsRow(R.drawable.ic_notifications, R.string.settings_notifications) {
                toast(getString(R.string.settings_notifications))
            },
            SettingsRow(R.drawable.ic_privacy, R.string.settings_privacy) {
                toast(getString(R.string.settings_privacy))
            },
            SettingsRow(R.drawable.ic_payment, R.string.settings_payment) {
                toast(getString(R.string.settings_payment))
            },
            SettingsRow(R.drawable.ic_help, R.string.settings_help) {
                toast(getString(R.string.settings_help))
            },
            SettingsRow(R.drawable.ic_logout, R.string.settings_logout) {
                logOut()
            }
        )

        val inflater = LayoutInflater.from(this)
        rows.forEach { row ->
            val rowBinding = ItemSettingsRowBinding.inflate(inflater, binding.settingsContainer, true)
            rowBinding.ivRowIcon.setImageResource(row.iconRes)
            rowBinding.tvRowLabel.setText(row.labelRes)
            rowBinding.root.setOnClickListener { row.onClick() }
        }
    }

    private fun logOut() {
        FirebaseAuth.getInstance().signOut()
        GuestPrefs.clearGuest(this)
        startActivity(
            Intent(this, LoginActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        )
        finish()
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }
}
