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
 * NEW_MATCHES, MESSAGES and LIKES are read live by
 * [MatchNotificationWatcher] before every local notification it considers
 * showing — these are process-local only, since there's no push delivery yet,
 * so they can only ever fire while the app itself is running (see that
 * class's doc for what that does and doesn't cover). APP_UPDATES and
 * PROMOTIONS have no trigger source at all yet, local or server-side — see
 * the TODO in [NotificationsSettingsActivity].
 *
 * The toggles are an enum rather than five pairs of getters and setters, so
 * the screen can build itself from the list and adding a category is a
 * one-line change in one place.
 */
object NotificationPrefs {

    private const val PREFS_NAME = "wedora_prefs"

    /**
     * Every category defaults to on, so a new user sees the app's full
     * behaviour and turns off what they don't want.
     *
     * A default only ever applies where no value has been stored, so changing
     * one can't move a switch a user has already set — [isEnabled] falls back
     * to it solely when the key is absent. Anyone who has opened this screen
     * keeps exactly what they chose.
     *
     * Note that PROMOTIONS defaulting to on makes marketing opt-out rather
     * than opt-in, which some jurisdictions treat differently from the rest of
     * these. Worth a look before launch; nothing sends yet, so it isn't live.
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
            "notif_likes", true,
            R.string.notif_likes, R.string.notif_likes_caption
        ),
        APP_UPDATES(
            "notif_app_updates", true,
            R.string.notif_app_updates, R.string.notif_app_updates_caption
        ),
        PROMOTIONS(
            "notif_promotions", true,
            R.string.notif_promotions, R.string.notif_promotions_caption
        )
    }

    fun isEnabled(context: Context, toggle: Toggle): Boolean =
        prefs(context).getBoolean(toggle.key, toggle.default)

    fun setEnabled(context: Context, toggle: Toggle, enabled: Boolean) {
        prefs(context).edit().putBoolean(toggle.key, enabled).apply()
    }

    private const val KEY_ASKED_SYSTEM_PERMISSION = "notif_asked_system_permission"

    /**
     * Whether the OS POST_NOTIFICATIONS prompt has already been shown once.
     * Separate from the category toggles above, which are this app's own
     * preferences UI — this tracks the one-time system dialog instead, so
     * HomeActivity doesn't re-trigger it (a no-op after the first decision
     * anyway, but there's no reason to keep calling it).
     */
    fun hasAskedSystemPermission(context: Context): Boolean =
        prefs(context).getBoolean(KEY_ASKED_SYSTEM_PERMISSION, false)

    fun markAskedSystemPermission(context: Context) {
        prefs(context).edit().putBoolean(KEY_ASKED_SYSTEM_PERMISSION, true).apply()
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
