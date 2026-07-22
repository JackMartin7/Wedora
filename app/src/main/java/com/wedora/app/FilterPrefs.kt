package com.wedora.app

import android.content.Context

/**
 * Feed filter settings, stored in the shared `wedora_prefs` file alongside
 * [OnboardingPrefs], [ThemePrefs], [GuestPrefs], [LocalProfilePrefs] and
 * [NotificationPrefs] — so account deletion clears them via clearAllWedoraData.
 *
 * Only age and interestedIn actually narrow the feed today. Relationship type
 * and distance are stored and restored so the screen remembers what the user
 * chose, but nothing reads them when querying: there is no relationshipType
 * field on user documents, and no distance to compute without coordinates on
 * both sides. Both are marked at their accessors.
 */
object FilterPrefs {

    private const val PREFS_NAME = "wedora_prefs"

    private const val KEY_INTERESTED_IN = "filter_interested_in"
    private const val KEY_AGE_MIN = "filter_age_min"
    private const val KEY_AGE_MAX = "filter_age_max"
    private const val KEY_RELATIONSHIP_TYPE = "filter_relationship_type"
    private const val KEY_DISTANCE_KM = "filter_distance_km"

    const val DEFAULT_AGE_MIN = 18
    const val DEFAULT_AGE_MAX = 40

    /** The app's hard floor — the same 18+ policy the security rules enforce. */
    const val AGE_FLOOR = 18
    const val AGE_CEILING = 80

    const val DEFAULT_DISTANCE_KM = 50
    const val MIN_DISTANCE_KM = 1
    const val MAX_DISTANCE_KM = 100

    const val RELATIONSHIP_SERIOUS = "serious"
    const val RELATIONSHIP_CASUAL = "casual"
    const val RELATIONSHIP_FRIENDSHIP = "friendship"
    const val RELATIONSHIP_OPEN = "open"
    const val DEFAULT_RELATIONSHIP = RELATIONSHIP_OPEN

    /**
     * Genders to show. Empty — the default — means "don't narrow", leaving the
     * feed's existing behaviour of showing whoever matches the user's own
     * interestedIn. There is no UI for this yet, so it stays empty in practice.
     */
    fun getInterestedIn(context: Context): Set<String> =
        prefs(context).getStringSet(KEY_INTERESTED_IN, emptySet()) ?: emptySet()

    fun setInterestedIn(context: Context, genders: Set<String>) {
        prefs(context).edit().putStringSet(KEY_INTERESTED_IN, genders).apply()
    }

    fun getAgeMin(context: Context): Int =
        prefs(context).getInt(KEY_AGE_MIN, DEFAULT_AGE_MIN)

    fun getAgeMax(context: Context): Int =
        prefs(context).getInt(KEY_AGE_MAX, DEFAULT_AGE_MAX)

    fun setAgeRange(context: Context, min: Int, max: Int) {
        prefs(context).edit()
            .putInt(KEY_AGE_MIN, min)
            .putInt(KEY_AGE_MAX, max)
            .apply()
    }

    /**
     * UI-only. User documents have no relationshipType field, so this narrows
     * nothing — it's stored so the screen remembers the choice.
     */
    fun getRelationshipType(context: Context): String =
        prefs(context).getString(KEY_RELATIONSHIP_TYPE, DEFAULT_RELATIONSHIP)
            ?: DEFAULT_RELATIONSHIP

    fun setRelationshipType(context: Context, type: String) {
        prefs(context).edit().putString(KEY_RELATIONSHIP_TYPE, type).apply()
    }

    /**
     * UI-only.
     * TODO: wire to real distance calculation when Maps is implemented
     */
    fun getDistanceKm(context: Context): Int =
        prefs(context).getInt(KEY_DISTANCE_KM, DEFAULT_DISTANCE_KM)

    fun setDistanceKm(context: Context, km: Int) {
        prefs(context).edit().putInt(KEY_DISTANCE_KM, km).apply()
    }

    /**
     * True when anything differs from the defaults — drives the dot on the
     * Home filter icon.
     *
     * Deliberately counts the stored-but-inert filters too. The user chose
     * them and the screen shows them as chosen, so reporting "no filters
     * active" would contradict what they can see.
     */
    fun hasActiveFilters(context: Context): Boolean =
        getAgeMin(context) != DEFAULT_AGE_MIN ||
            getAgeMax(context) != DEFAULT_AGE_MAX ||
            getRelationshipType(context) != DEFAULT_RELATIONSHIP ||
            getDistanceKm(context) != DEFAULT_DISTANCE_KM ||
            getInterestedIn(context).isNotEmpty()

    /** Restores every filter to its default. Backs the Reset action. */
    fun reset(context: Context) {
        prefs(context).edit()
            .remove(KEY_INTERESTED_IN)
            .remove(KEY_AGE_MIN)
            .remove(KEY_AGE_MAX)
            .remove(KEY_RELATIONSHIP_TYPE)
            .remove(KEY_DISTANCE_KM)
            .apply()
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
