package com.wedora.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.wedora.app.databinding.ActivityNotificationsSettingsBinding
import com.wedora.app.databinding.ItemNotificationToggleBinding

/**
 * Which notifications the user wants. Each switch writes straight to
 * [NotificationPrefs] — there's no Save button, so there's no state to lose by
 * backing out and nothing to reconcile.
 *
 * Rows are built from [NotificationPrefs.Toggle] rather than laid out one by
 * one, so a row can't exist without a stored key behind it (or the reverse).
 *
 * Also asks for POST_NOTIFICATIONS here (API 33+) if it isn't granted yet.
 * ProfileStepPermissionsActivity is the primary place for that, but it only
 * runs for a fresh signup passing through the setup steps in order —
 * AuthRouting sends any account that already has an age on file (every
 * pre-existing account, and anyone who resumes mid-flow after that step)
 * straight past it, so they'd otherwise never be asked at all. Visiting this
 * screen is itself a clear, deliberate signal of wanting notification
 * control, and Android's own permission request throttles a repeat prompt
 * after a prior denial on its own, so this doesn't need its own "already
 * asked" guard to avoid nagging.
 */
class NotificationsSettingsActivity : AppCompatActivity() {

    private companion object {
        /**
         * Feature-flagged out of this release's UI: both have no trigger
         * source yet, local or server-side (see the TODO on their
         * setOnCheckedChangeListener below) — a switch a user can flip with
         * no effect reads as broken. The underlying [NotificationPrefs]
         * entries are untouched, so re-enabling later is just removing an
         * entry from this set, not restoring deleted code.
         */
        val HIDDEN_FROM_SETTINGS = setOf(
            NotificationPrefs.Toggle.APP_UPDATES,
            NotificationPrefs.Toggle.PROMOTIONS
        )
    }

    private lateinit var binding: ActivityNotificationsSettingsBinding

    private val requestNotificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {}

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityNotificationsSettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnBack.setOnClickListener { finish() }
        buildToggleRows()
        requestNotificationPermissionIfNeeded()
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            requestNotificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private fun buildToggleRows() {
        val inflater = LayoutInflater.from(this)

        NotificationPrefs.Toggle.values()
            .filterNot { it in HIDDEN_FROM_SETTINGS }
            .forEach { toggle ->
                val row = ItemNotificationToggleBinding.inflate(
                    inflater, binding.toggleContainer, true
                )
                row.tvToggleLabel.setText(toggle.labelRes)
                row.tvToggleCaption.setText(toggle.captionRes)

                // Restore the stored state before attaching the listener, or
                // setting it here would immediately fire and write it back.
                row.switchToggle.isChecked = NotificationPrefs.isEnabled(this, toggle)
                row.switchToggle.setOnCheckedChangeListener { _, isChecked ->
                    // NEW_MATCHES, MESSAGES and LIKES need nothing further here —
                    // MatchNotificationWatcher reads NotificationPrefs fresh at
                    // notify time, so flipping the switch takes effect on the very
                    // next event with no other wiring. APP_UPDATES and PROMOTIONS
                    // are filtered out of this loop entirely (see
                    // HIDDEN_FROM_SETTINGS) — TODO: wire to admin announcement
                    // system when available, then unhide them.
                    NotificationPrefs.setEnabled(this, toggle, isChecked)
                }

                // The whole row toggles, not just the switch itself — a 14dp-tall
                // label is a much easier target than the thumb.
                row.root.setOnClickListener { row.switchToggle.toggle() }
            }
    }
}
