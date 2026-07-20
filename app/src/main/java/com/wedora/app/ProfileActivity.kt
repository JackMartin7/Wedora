package com.wedora.app

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.firebase.auth.FirebaseAuth
import com.wedora.app.databinding.ActivityProfileBinding
import com.wedora.app.databinding.ItemSettingsRowBinding

class ProfileActivity : AppCompatActivity() {

    private lateinit var binding: ActivityProfileBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityProfileBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setUpDarkModeSwitch()
        setUpSettingsRows()
        setUpWedoraBottomNav(binding.bottomNav, R.id.nav_profile)

        binding.btnHistory.setOnClickListener { toast(getString(R.string.cd_history)) }
        binding.btnEditProfile.setOnClickListener { toast(getString(R.string.profile_edit_button)) }
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
