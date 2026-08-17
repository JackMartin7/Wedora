package com.wedora.app

import android.content.Context

/**
 * On-device record of who this account has already liked, so a re-like of a
 * profile that resurfaces (see Feed.kt's exclusion-set fallback) can skip
 * Firestore entirely — no [matchExistsQuery] read, no refresh write, no
 * daily-limit check — rather than every re-like still costing a round trip.
 *
 * Keyed by Firebase Auth UID (not a single flat set), same reasoning as
 * [LocalProfilePrefs]: the right cache applies if more than one account signs
 * in on this device. Shares the same SharedPreferences file as
 * [LocalProfilePrefs]/[OnboardingPrefs]/[ThemePrefs]/[GuestPrefs], so
 * [clearAllWedoraData] wipes it on account deletion for free.
 *
 * This is a fast-path cache, not a source of truth — it can be behind
 * Firestore for a moment (a fresh install, a new device, right after
 * onStart before the first snapshot lands) or ahead of it if a write fails,
 * but nothing here trusts it for correctness: a cache miss always falls
 * through to the real Firestore-verified path (see [likeUserRespectingDailyLimit]
 * in LikeLimit.kt), so being behind only ever costs a redundant round trip,
 * never a wrongly-skipped write or a stolen daily-limit slot. Kept in sync by
 * HomeActivity's own real-time match listener — see [HomeActivity.observeMatches] —
 * which already rebuilds the liked set from Firestore on every session start,
 * so a reinstall or new device repopulates this the same way rather than
 * needing a separate one-time sync.
 */
object LikedProfilesCache {

    private const val PREFS_NAME = "wedora_prefs"
    private const val KEY_PREFIX_LIKED_IDS = "liked_profile_ids_"

    fun isLiked(context: Context, uid: String, otherUid: String): Boolean =
        otherUid in likedIds(context, uid)

    fun add(context: Context, uid: String, otherUid: String) {
        val updated = likedIds(context, uid) + otherUid
        prefs(context).edit().putStringSet(KEY_PREFIX_LIKED_IDS + uid, updated).apply()
    }

    fun remove(context: Context, uid: String, otherUid: String) {
        val updated = likedIds(context, uid) - otherUid
        prefs(context).edit().putStringSet(KEY_PREFIX_LIKED_IDS + uid, updated).apply()
    }

    private fun likedIds(context: Context, uid: String): Set<String> =
        prefs(context).getStringSet(KEY_PREFIX_LIKED_IDS + uid, emptySet()) ?: emptySet()

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
