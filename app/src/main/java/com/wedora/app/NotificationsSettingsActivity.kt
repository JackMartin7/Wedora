package com.wedora.app

import android.os.Bundle
import android.view.LayoutInflater
import androidx.appcompat.app.AppCompatActivity
import com.wedora.app.databinding.ActivityNotificationsSettingsBinding
import com.wedora.app.databinding.ItemNotificationToggleBinding

/**
 * Which notifications the user wants. Each switch writes straight to
 * [NotificationPrefs] — there's no Save button, so there's no state to lose by
 * backing out and nothing to reconcile.
 *
 * Rows are built from [NotificationPrefs.Toggle] rather than laid out one by
 * one, so a row can't exist without a stored key behind it (or the reverse).
 */
class NotificationsSettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityNotificationsSettingsBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityNotificationsSettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnBack.setOnClickListener { finish() }
        buildToggleRows()
    }

    private fun buildToggleRows() {
        val inflater = LayoutInflater.from(this)

        NotificationPrefs.Toggle.values().forEach { toggle ->
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
                // have no trigger source yet, local or server-side:
                // TODO: wire to admin announcement system when available
                NotificationPrefs.setEnabled(this, toggle, isChecked)
            }

            // The whole row toggles, not just the switch itself — a 14dp-tall
            // label is a much easier target than the thumb.
            row.root.setOnClickListener { row.switchToggle.toggle() }
        }
    }
}
