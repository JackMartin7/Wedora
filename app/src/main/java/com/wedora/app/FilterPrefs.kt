package com.wedora.app

import android.content.Context

/**
 * Feed filter settings, stored in the shared `wedora_prefs` file alongside
 * [OnboardingPrefs], [ThemePrefs], [GuestPrefs], [LocalProfilePrefs] and
 * [NotificationPrefs] — so account deletion clears them via clearAllWedoraData.
 *
 * Age, status, looking-for and distance all narrow the feed — see Feed.kt's
 * matchesDistanceFilter for how distance is applied (it fails open whenever
 * either side lacks coordinates, same as every other distance calculation in
 * this app).
 */
object FilterPrefs {

    private const val PREFS_NAME = "wedora_prefs"

    private const val KEY_INTERESTED_IN = "filter_interested_in"
    private const val KEY_AGE_MIN = "filter_age_min"
    private const val KEY_AGE_MAX = "filter_age_max"
    private const val KEY_DISTANCE_KM = "filter_distance_km"
    private const val KEY_MY_STATUS = "filter_my_status"
    private const val KEY_LOOKING_FOR = "filter_looking_for"

    const val DEFAULT_AGE_MIN = 18
    const val DEFAULT_AGE_MAX = 40

    /** The app's hard floor — the same 18+ policy the security rules enforce. */
    const val AGE_FLOOR = 18
    const val AGE_CEILING = 80

    const val DEFAULT_DISTANCE_KM = 50
    const val MIN_DISTANCE_KM = 1

    /**
     * The slider's top end, standing in for "worldwide" (see FilterActivity's
     * showDistanceLabel, which swaps the label to that word once the value
     * gets within a couple of km of this). Deliberately set above ~20,015 km
     * — the greatest possible great-circle distance between two points on
     * Earth (half the circumference at DistanceUtils.EARTH_RADIUS_KM) — so
     * matchesDistanceFilter's `<= maxKm` check can never exclude a real pair
     * of coordinates once the slider is all the way up, whatever they are.
     */
    const val MAX_DISTANCE_KM = 20_020

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

    fun getDistanceKm(context: Context): Int =
        prefs(context).getInt(KEY_DISTANCE_KM, DEFAULT_DISTANCE_KM)

    fun setDistanceKm(context: Context, km: Int) {
        prefs(context).edit().putInt(KEY_DISTANCE_KM, km).apply()
    }

    /**
     * True when anything differs from the defaults — drives the dot on the
     * Home filter icon.
     */
    fun hasActiveFilters(context: Context): Boolean =
        getAgeMin(context) != DEFAULT_AGE_MIN ||
            getAgeMax(context) != DEFAULT_AGE_MAX ||
            getDistanceKm(context) != DEFAULT_DISTANCE_KM ||
            getInterestedIn(context).isNotEmpty() ||
            getMyStatusFilter(context) != null ||
            getLookingForFilter(context, MarriageIntent.ALL_LOOKING_FOR) != null

    /**
     * Statuses to include. An unset filter — and one with every option ticked
     * — both mean "don't narrow", which is why the getters return null rather
     * than a full set: the feed can then skip the check entirely instead of
     * testing membership against everything.
     */
    fun getMyStatusFilter(context: Context): Set<String>? =
        activeSubsetOf(
            prefs(context).getStringSet(KEY_MY_STATUS, null),
            MarriageIntent.ALL_STATUS
        )

    fun setMyStatusFilter(context: Context, statuses: Set<String>) {
        prefs(context).edit().putStringSet(KEY_MY_STATUS, statuses).apply()
    }

    /**
     * Raw stored set, for the filter screen to restore its chips from. Absent
     * means every option, which is how a first visit shows all chips ticked.
     */
    fun getRawMyStatusFilter(context: Context): Set<String> =
        prefs(context).getStringSet(KEY_MY_STATUS, null) ?: MarriageIntent.ALL_STATUS.toSet()

    fun getLookingForFilter(context: Context, options: List<String>): Set<String>? =
        activeSubsetOf(prefs(context).getStringSet(KEY_LOOKING_FOR, null), options)

    fun setLookingForFilter(context: Context, values: Set<String>) {
        prefs(context).edit().putStringSet(KEY_LOOKING_FOR, values).apply()
    }

    /** Raw stored set, defaulting to every option for the given gender. */
    fun getRawLookingForFilter(context: Context, options: List<String>): Set<String> =
        prefs(context).getStringSet(KEY_LOOKING_FOR, null) ?: options.toSet()

    /**
     * Null when [stored] is absent or covers every option — i.e. when it isn't
     * actually narrowing anything.
     */
    private fun activeSubsetOf(stored: Set<String>?, options: List<String>): Set<String>? {
        if (stored == null) return null
        return if (stored.containsAll(options)) null else stored
    }

    /**
     * Drops a stored Looking For filter, leaving it at its default of "no
     * filter".
     *
     * Called when the user changes their gender or who they're interested in,
     * because either can change the wording of the options: a filter holding
     * "Second Wife" would keep narrowing the feed while the filter screen — now
     * showing the marriage-worded options — offers no way to see or clear it.
     *
     * Status goes with it, since "Widower" and "Widowed" split the same way.
     */
    fun clearIntentFilters(context: Context) {
        prefs(context).edit()
            .remove(KEY_LOOKING_FOR)
            .remove(KEY_MY_STATUS)
            .apply()
    }

    /**
     * Drops only the Looking For filter. Used when the filter screen finds a
     * stored value that doesn't fit the option list the current gender
     * produces — a stale male-worded pick for what is now a female-candidate
     * list — so it can't keep narrowing the feed by something invisible.
     */
    fun clearLookingForFilter(context: Context) {
        prefs(context).edit().remove(KEY_LOOKING_FOR).apply()
    }

    /** Restores every filter to its default. */
    fun reset(context: Context) {
        prefs(context).edit()
            .remove(KEY_INTERESTED_IN)
            .remove(KEY_AGE_MIN)
            .remove(KEY_AGE_MAX)
            .remove(KEY_DISTANCE_KM)
            .remove(KEY_MY_STATUS)
            .remove(KEY_LOOKING_FOR)
            .apply()
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
