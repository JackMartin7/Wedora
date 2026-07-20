package com.wedora.app

import android.content.Context
import androidx.appcompat.app.AppCompatDelegate

/**
 * Dark-mode preference.
 *
 * Three states are supported so the Profile screen can later offer an explicit
 * toggle without losing the "just do whatever the system does" default:
 *
 *  - [Mode.SYSTEM] (default) — follow the OS light/dark setting
 *  - [Mode.LIGHT] / [Mode.DARK] — user override
 *
 * Sits alongside [OnboardingPrefs] and shares the same SharedPreferences file.
 */
object ThemePrefs {

    private const val PREFS_NAME = "wedora_prefs"
    private const val KEY_DARK_MODE = "dark_mode"

    enum class Mode { SYSTEM, LIGHT, DARK }

    fun getMode(context: Context): Mode =
        when (prefs(context).getString(KEY_DARK_MODE, Mode.SYSTEM.name)) {
            Mode.LIGHT.name -> Mode.LIGHT
            Mode.DARK.name -> Mode.DARK
            else -> Mode.SYSTEM
        }

    fun setMode(context: Context, mode: Mode) {
        prefs(context).edit().putString(KEY_DARK_MODE, mode.name).apply()
        apply(mode)
    }

    /** Boolean helper for a Profile-settings switch: on = dark, off = follow system. */
    fun isDarkEnabled(context: Context): Boolean = getMode(context) == Mode.DARK

    fun setDarkEnabled(context: Context, enabled: Boolean) {
        setMode(context, if (enabled) Mode.DARK else Mode.SYSTEM)
    }

    /** Applies the stored preference. Call once from Application.onCreate(). */
    fun applyStoredMode(context: Context) = apply(getMode(context))

    private fun apply(mode: Mode) {
        AppCompatDelegate.setDefaultNightMode(
            when (mode) {
                Mode.LIGHT -> AppCompatDelegate.MODE_NIGHT_NO
                Mode.DARK -> AppCompatDelegate.MODE_NIGHT_YES
                Mode.SYSTEM -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
            }
        )
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
