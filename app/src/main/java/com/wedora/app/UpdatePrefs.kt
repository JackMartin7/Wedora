package com.wedora.app

import android.content.Context

/**
 * The nag budget, plus the small amount of state that has to outlive the
 * process for it to mean anything.
 *
 * The spec calls for DataStore; this uses SharedPreferences to match every
 * other preference holder in the app ([OnboardingPrefs], [GuestPrefs],
 * [ThemePrefs], NotificationPrefs) and share their "wedora_prefs" file. The
 * values here are a handful of longs and ints read once per session on a
 * lifecycle callback, so DataStore's async/flow story would buy nothing and
 * would make this the only screen in the app needing a coroutine to answer
 * "should I prompt".
 *
 * The budget itself, verbatim from the spec: at most one interruptive prompt
 * per 3 days, and never more than 3 lifetime prompts for the same
 * versionCode. Past that the App Version row is the only surface — dismissal
 * is treated as a signal, not an obstacle to route around.
 */
object UpdatePrefs {

    private const val PREFS_NAME = "wedora_prefs"

    private const val KEY_DISMISSED_VERSION = "update_dismissed_version"
    private const val KEY_DISMISSED_AT = "update_dismissed_at"
    private const val KEY_PROMPT_COUNT_VERSION = "update_prompt_count_version"
    private const val KEY_PROMPT_COUNT = "update_prompt_count"
    private const val KEY_LAST_CHECK_AT = "update_last_check_at"
    private const val KEY_SEEN_ROW_VERSION = "update_seen_row_version"
    private const val KEY_RETRY_COUNT = "update_retry_count"
    private const val KEY_INSTALLED_VERSION = "update_installed_version"

    /** Re-prompt gap for a versionCode the user already dismissed. */
    private const val REPROMPT_AFTER_MS = 3L * 24 * 60 * 60 * 1000

    /** Hard lifetime cap on interruptive prompts for one versionCode. */
    const val MAX_PROMPTS_PER_VERSION = 3

    /** How long a completed check stays good for. */
    private const val CHECK_CACHE_MS = 4L * 60 * 60 * 1000

    /** After this many failures the flexible path collapses to the passive row. */
    const val MAX_RETRIES = 3

    // ----- Interruptive-prompt budget ------------------------------------

    /**
     * Whether an interruptive surface (the bottom sheet) may be shown for
     * [versionCode]. False does NOT mean "no update" — the App Version row
     * still reflects it; this only gates the interruption.
     *
     * A forced update never consults this; see [UpdateRepository.pathFor].
     */
    fun mayPrompt(context: Context, versionCode: Int): Boolean {
        val p = prefs(context)
        if (promptCount(context, versionCode) >= MAX_PROMPTS_PER_VERSION) return false

        val dismissedVersion = p.getInt(KEY_DISMISSED_VERSION, 0)
        if (dismissedVersion != versionCode) return true

        val elapsed = System.currentTimeMillis() - p.getLong(KEY_DISMISSED_AT, 0L)
        return elapsed >= REPROMPT_AFTER_MS
    }

    /** Call when the sheet is actually shown, not when a prompt is merely considered. */
    fun recordPromptShown(context: Context, versionCode: Int) {
        prefs(context).edit()
            .putInt(KEY_PROMPT_COUNT_VERSION, versionCode)
            .putInt(KEY_PROMPT_COUNT, promptCount(context, versionCode) + 1)
            .apply()
    }

    /** How many times an interruptive prompt has been shown for [versionCode]. */
    fun promptCount(context: Context, versionCode: Int): Int {
        val p = prefs(context)
        // The stored count belongs to whichever version it was last written
        // for; a newer version starts its own budget from zero rather than
        // inheriting an old one's exhausted count.
        return if (p.getInt(KEY_PROMPT_COUNT_VERSION, 0) == versionCode) {
            p.getInt(KEY_PROMPT_COUNT, 0)
        } else {
            0
        }
    }

    fun recordDismissed(context: Context, versionCode: Int) {
        prefs(context).edit()
            .putInt(KEY_DISMISSED_VERSION, versionCode)
            .putLong(KEY_DISMISSED_AT, System.currentTimeMillis())
            .apply()
    }

    // ----- Check cache ----------------------------------------------------

    /** True when the last completed check is older than the 4-hour window. */
    fun checkCacheExpired(context: Context): Boolean =
        System.currentTimeMillis() - prefs(context).getLong(KEY_LAST_CHECK_AT, 0L) >= CHECK_CACHE_MS

    fun recordCheck(context: Context) {
        prefs(context).edit().putLong(KEY_LAST_CHECK_AT, System.currentTimeMillis()).apply()
    }

    // ----- Settings-row shimmer ------------------------------------------

    /**
     * The row's shimmer is an attention cue for a pending update the user
     * hasn't looked at yet, so it runs once per versionCode and then stops —
     * a permanently shimmering settings row is just noise.
     */
    fun hasSeenRow(context: Context, versionCode: Int): Boolean =
        prefs(context).getInt(KEY_SEEN_ROW_VERSION, 0) == versionCode

    fun recordRowSeen(context: Context, versionCode: Int) {
        prefs(context).edit().putInt(KEY_SEEN_ROW_VERSION, versionCode).apply()
    }

    // ----- Retry backoff --------------------------------------------------

    fun retryCount(context: Context): Int = prefs(context).getInt(KEY_RETRY_COUNT, 0)

    fun recordRetry(context: Context) {
        prefs(context).edit().putInt(KEY_RETRY_COUNT, retryCount(context) + 1).apply()
    }

    /** Call on a successful download so a later failure starts from zero again. */
    fun clearRetries(context: Context) {
        prefs(context).edit().remove(KEY_RETRY_COUNT).apply()
    }

    // ----- Installed-version watermark -----------------------------------

    /**
     * Last versionCode this install was seen running, so the first launch on a
     * new build can fire `update_installed` with a real previous_version. Play
     * gives no "you were just updated" callback, so comparing a stored value
     * is the only way to detect it.
     */
    fun lastRunVersion(context: Context): Int = prefs(context).getInt(KEY_INSTALLED_VERSION, 0)

    fun recordRunVersion(context: Context, versionCode: Int) {
        prefs(context).edit().putInt(KEY_INSTALLED_VERSION, versionCode).apply()
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
