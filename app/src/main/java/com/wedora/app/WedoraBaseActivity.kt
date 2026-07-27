package com.wedora.app

import android.os.Bundle
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowInsetsControllerCompat

/**
 * Common base for every Activity in the app. Centralizes the two things
 * edge-to-edge (targetSdk 35, mandatory from Android 15) requires every
 * screen to do, rather than repeating them ~29 times: opt in via
 * enableEdgeToEdge(), and explicitly set both system bars' icon contrast,
 * now that neither bar paints an opaque background of its own for icons to
 * contrast against — that's what android:statusBarColor/windowLightStatusBar
 * used to hand-roll per theme, and both are deprecated/ignored under
 * edge-to-edge on API 35+.
 *
 * Icon appearance follows the same light/dark split @bool/wedora_light_status_bar
 * already resolves per values/values-night, for both bars: everything this
 * app draws under either system bar is one of the two backgrounds
 * (wedora_bg or wedora_surface) that flip together with that same bool (see
 * colors.xml), so one flag covers both bars on every regular screen.
 * Splash draws its own always-dark gradient behind both bars regardless of
 * theme, so it overrides [isLightSystemBars] with a fixed value instead of
 * reading the bool.
 */
abstract class WedoraBaseActivity : AppCompatActivity() {

    protected open val isLightSystemBars: Boolean
        get() = resources.getBoolean(R.bool.wedora_light_status_bar)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        val controller = WindowInsetsControllerCompat(window, window.decorView)
        controller.isAppearanceLightStatusBars = isLightSystemBars
        controller.isAppearanceLightNavigationBars = isLightSystemBars
    }
}
