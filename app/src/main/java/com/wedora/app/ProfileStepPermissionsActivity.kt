package com.wedora.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat

/**
 * Step 4 of 6: a primer for the two OS permissions the app benefits from —
 * location (distance to matches) and notifications (matches/messages/likes).
 *
 * Neither is required. Both cards start on "Enable" and flip to a granted
 * checkmark independently as each permission is answered; Continue is valid
 * from the moment the screen loads regardless of what the user chooses, since
 * nothing here is written to the profile — [stepUpdates] is empty and
 * [AuthRouting]'s setup gate has nothing to check for this step, which is why
 * a returning user who already has an age on file is routed straight past it
 * to Home rather than back through this screen.
 *
 * This is also now the only place POST_NOTIFICATIONS is asked for — see the
 * removed ad-hoc request that used to fire from HomeActivity. A denial here
 * isn't re-prompted elsewhere; local notifications just don't show, which is
 * an acceptable degraded state for a feature that was never guaranteed to
 * exist without push infrastructure anyway.
 */
class ProfileStepPermissionsActivity : ProfileStepActivity() {

    private lateinit var btnEnableLocation: TextView
    private lateinit var ivLocationGranted: ImageView
    private lateinit var btnEnableNotifications: TextView
    private lateinit var ivNotificationsGranted: ImageView

    private val requestLocationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {
            updateLocationCardState()
        }

    private val requestNotificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {
            updateNotificationsCardState()
        }

    override val stepNumber = 4
    override val stepId = OnboardingAnalytics.STEP_NAME_PERMISSIONS
    override val titleRes = R.string.step_permissions_title
    override val subtitleRes = R.string.step_permissions_subtitle
    override val contentLayoutRes = R.layout.view_step_permissions

    override fun bindContent(content: View) {
        btnEnableLocation = content.findViewById(R.id.btnEnableLocation)
        ivLocationGranted = content.findViewById(R.id.ivLocationGranted)
        btnEnableNotifications = content.findViewById(R.id.btnEnableNotifications)
        ivNotificationsGranted = content.findViewById(R.id.ivNotificationsGranted)

        btnEnableLocation.setOnClickListener {
            requestLocationPermission.launch(Manifest.permission.ACCESS_COARSE_LOCATION)
        }
        btnEnableNotifications.setOnClickListener {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                requestNotificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        updateLocationCardState()
        updateNotificationsCardState()

        // Informational only — no click listener, so the ripple in its
        // background (shared with step 5's actual Skip action) never fires.
        binding.tvStepSkip.text = getString(R.string.permissions_footer_note)
        binding.tvStepSkip.visibility = View.VISIBLE
    }

    private fun isLocationGranted(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    /** Below API 33 there is no such permission to grant — treated as already on. */
    private fun isNotificationsGranted(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED

    private fun updateLocationCardState() = applyCardState(
        granted = isLocationGranted(),
        button = btnEnableLocation,
        checkmark = ivLocationGranted
    )

    private fun updateNotificationsCardState() = applyCardState(
        granted = isNotificationsGranted(),
        button = btnEnableNotifications,
        checkmark = ivNotificationsGranted
    )

    private fun applyCardState(granted: Boolean, button: View, checkmark: View) {
        button.visibility = if (granted) View.GONE else View.VISIBLE
        checkmark.visibility = if (granted) View.VISIBLE else View.GONE
    }

    /** Permissions aren't stored on the profile — nothing here is Continue-blocking. */
    override fun isStepValid(): Boolean = true

    override fun stepUpdates(): Map<String, Any?> = emptyMap()

    override fun nextStep(): Class<*> = ProfileStep4DetailsActivity::class.java
}
