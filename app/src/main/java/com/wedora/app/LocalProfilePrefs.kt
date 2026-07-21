package com.wedora.app

import android.content.Context

/**
 * Stores the on-device path to the current user's profile photo.
 *
 * Photos are device-local only — they are never uploaded, and never written
 * to the Firestore user document. Keyed by Firebase Auth UID (not a single
 * flat key) so the right photo shows if more than one account signs in on
 * the same device.
 *
 * Shares the same SharedPreferences file as [OnboardingPrefs], [ThemePrefs]
 * and [GuestPrefs].
 */
object LocalProfilePrefs {

    private const val PREFS_NAME = "wedora_prefs"
    private const val KEY_PREFIX_PHOTO_PATH = "local_photo_path_"

    fun getPhotoPath(context: Context, uid: String): String? =
        prefs(context).getString(KEY_PREFIX_PHOTO_PATH + uid, null)

    fun setPhotoPath(context: Context, uid: String, path: String) {
        prefs(context).edit().putString(KEY_PREFIX_PHOTO_PATH + uid, path).apply()
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
