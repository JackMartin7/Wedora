package com.wedora.app

import android.content.Context
import androidx.annotation.StringRes

/**
 * Which notifications the user wants to receive.
 *
 * Device-local, like every other preference in the app — these are stored in
 * the shared `wedora_prefs` file alongside [OnboardingPrefs], [ThemePrefs],
 * [GuestPrefs] and [LocalProfilePrefs], and are cleared with them on account
 * deletion (see clearAllWedoraData).
 *
 * The settings are recorded but nothing acts on them yet: there is no push
 * delivery in the app, so no code path currently reads these to decide whether
 * to notify. They exist so the choices survive until FCM is wired up — see the
 * TODO in [NotificationsSettingsActivity].
 *
 * The toggles are an enum rather than five pairs of getters and setters, so
 * the screen can build itself from the list and adding a category is a
 * one-line change in one place.
 */
object NotificationPrefs {

    private const val PREFS_NAME = "wedora_prefs"

    /**
     * Defaults follow what a user would expect to be told about unprompted:
     * things another person did that involves them (matches, messages) are on;
     * everything else is opt-in, and the two marketing-adjacent categories
     * especially so.
     */
    enum class Toggle(
        val key: String,
        val default: Boolean,
        @StringRes val labelRes: Int,
        @StringRes val captionRes: Int
    ) {
        NEW_MATCHES(
            "notif_new_matches", true,
            R.string.notif_new_matches, R.string.notif_new_matches_caption
        ),
        MESSAGES(
            "notif_messages", true,
            R.string.notif_messages, R.string.notif_messages_caption
        ),
        LIKES(
            "notif_likes", false,
            R.string.notif_likes, R.string.notif_likes_caption
        ),
        APP_UPDATES(
            "notif_app_updates", false,
            R.string.notif_app_updates, R.string.notif_app_updates_caption
        ),
        PROMOTIONS(
            "notif_promotions", false,
            R.string.notif_promotions, R.string.notif_promotions_caption
        )
    }

    fun isEnabled(context: Context, toggle: Toggle): Boolean =
        prefs(context).getBoolean(toggle.key, toggle.default)

    fun setEnabled(context: Context, toggle: Toggle, enabled: Boolean) {
        prefs(context).edit().putBoolean(toggle.key, enabled).apply()
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
