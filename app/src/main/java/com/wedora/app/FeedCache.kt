package com.wedora.app

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.util.Date

/**
 * On-device cache of the raw discovery candidate pool, so returning to
 * Discover/Explore doesn't re-run the (per-document billed) feed query every
 * time.
 *
 * Caches the pool **pre-filter and pre-exclusion**: the stored cards are
 * everything the gender query returned, before exclusions or
 * [matchesActiveFilters] narrow them. That's what lets a filter change
 * re-narrow locally with no refetch — and it's why exclusions are
 * deliberately NOT cached alongside. Those change on every like and pass, so
 * they're re-read on every load (three small queries) rather than risking
 * re-showing someone the user just dismissed.
 *
 * It's also why there is no per-user prune here, and why a like or pass
 * leaves this cache untouched. Narrowing is entirely the exclusion sets'
 * job, re-read fresh every time; dropping someone from the pool when they
 * were passed would delete them permanently and defeat [buildFeedCards]'
 * whole point, which is reintroducing exactly those people once the feed
 * runs dry.
 *
 * Keyed by UID like [LocalProfilePrefs] and [LikedProfilesCache], in the
 * shared `wedora_prefs` file, so [clearAllWedoraData] wipes it on account
 * deletion for free.
 *
 * Serialized with org.json — part of the Android framework, so this needs no
 * new dependency. SharedPreferences is not really built for payloads this
 * size (the whole file is read into memory on first access and rewritten on
 * every commit), which is what [MAX_CACHED_CARDS] is there to bound.
 *
 * Guests are never cached: [applyGuestProfileViewLimit] spends their daily
 * allowance against whatever pool is loaded, and serving that from a cache
 * would make the count drift.
 */
object FeedCache {

    private const val TAG = "WedoraFeedCache"
    private const val PREFS_NAME = "wedora_prefs"
    private const val KEY_PREFIX = "feed_cache_"

    /**
     * How long a cached pool stays usable.
     *
     * Short on purpose. The thing that rescues an exhausted feed is new
     * people signing up, and every minute of cache is a minute those stay
     * invisible — so this trades some read savings for discovery staying
     * live. [isFresh] is only ever consulted when the caller already has a
     * healthy feed anyway (see the bypass in loadDiscoveryFeed).
     */
    private const val TTL_MS = 15 * 60 * 1000L

    /** Bounds the SharedPreferences payload — see this object's own note. */
    private const val MAX_CACHED_CARDS = 300

    /**
     * Bumped whenever the serialized card shape changes, so entries written
     * by an older build are treated as a miss instead of deserializing with
     * the new fields silently null.
     *
     * v2 added createdAt — without this, every cached profile would look
     * like it had no signup date for up to a full TTL after the update,
     * sorting the entire pool as "oldest" and making the newest-first
     * ordering look broken exactly when a user first sees it.
     */
    // 3: added likesReceivedCount. A v2 payload would deserialize it as 0
    //    via optInt's default, which is silently wrong rather than merely
    //    stale — a real count would read as zero until the pool refreshed.
    //    Bumping discards those payloads instead.
    private const val SCHEMA_VERSION = 3

    private const val FIELD_VERSION = "version"
    private const val FIELD_SAVED_AT = "savedAt"
    private const val FIELD_INTERESTED_IN = "interestedIn"
    private const val FIELD_CARDS = "cards"

    /**
     * The cached pool for [uid], or null when there's nothing usable —
     * absent, expired, corrupt, or stored against a different
     * [interestedIn] (changing who you're shown invalidates the pool
     * outright, since the underlying query itself differs).
     */
    fun load(context: Context, uid: String, interestedIn: String?): List<MatchCard>? {
        val raw = prefs(context).getString(KEY_PREFIX + uid, null) ?: return null
        return try {
            val root = JSONObject(raw)
            if (root.optInt(FIELD_VERSION) != SCHEMA_VERSION) return null
            if (root.optString(FIELD_INTERESTED_IN).orEmptyNull() != interestedIn) return null
            if (!isFresh(root.optLong(FIELD_SAVED_AT))) return null

            val array = root.optJSONArray(FIELD_CARDS) ?: return null
            (0 until array.length()).mapNotNull { array.optJSONObject(it)?.toMatchCard() }
        } catch (e: JSONException) {
            // A corrupt entry is not worth surfacing to the user — it's a
            // miss, and the next save overwrites it. Still reported: this
            // should essentially never fire, so a spike means the serializer
            // above is writing something it can't read back.
            Log.w(TAG, "Discarding unreadable feed cache", e)
            CrashReporting.record(e, "FeedCache.load could not parse a cached pool")
            null
        }
    }

    fun save(context: Context, uid: String, interestedIn: String?, cards: List<MatchCard>) {
        val array = JSONArray()
        cards.take(MAX_CACHED_CARDS).forEach { array.put(it.toJson()) }

        val root = JSONObject()
            .put(FIELD_VERSION, SCHEMA_VERSION)
            .put(FIELD_SAVED_AT, System.currentTimeMillis())
            .put(FIELD_INTERESTED_IN, interestedIn ?: JSONObject.NULL)
            .put(FIELD_CARDS, array)

        prefs(context).edit().putString(KEY_PREFIX + uid, root.toString()).apply()
    }

    fun clear(context: Context, uid: String) {
        prefs(context).edit().remove(KEY_PREFIX + uid).apply()
    }

    private fun isFresh(savedAt: Long): Boolean =
        savedAt > 0L && System.currentTimeMillis() - savedAt < TTL_MS

    private fun String.orEmptyNull(): String? = takeIf { it.isNotEmpty() }

    private fun MatchCard.toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("name", name)
        .put("bio", bio)
        .put("age", age ?: JSONObject.NULL)
        .put("city", city ?: JSONObject.NULL)
        .put("country", country ?: JSONObject.NULL)
        .put("latitude", latitude ?: JSONObject.NULL)
        .put("longitude", longitude ?: JSONObject.NULL)
        .put("gender", gender ?: JSONObject.NULL)
        .put("likesReceivedCount", likesReceivedCount)
        .put("myStatus", myStatus ?: JSONObject.NULL)
        .put("lookingFor", lookingFor ?: JSONObject.NULL)
        .put("lastSeen", lastSeen?.time ?: JSONObject.NULL)
        .put("createdAt", createdAt?.time ?: JSONObject.NULL)
        .put("isPremium", isPremium)
        .put("photoUrl", photoUrl ?: JSONObject.NULL)

    /**
     * `role` and `distanceKm` are deliberately not stored: role has no
     * backing Firestore field (see [MatchCard]), and distance is recomputed
     * per load via withDistanceFrom against the viewer's current
     * coordinates, which may have moved since the pool was cached.
     */
    private fun JSONObject.toMatchCard(): MatchCard? {
        val id = optString("id").orEmptyNull() ?: return null
        val name = optString("name").orEmptyNull() ?: return null
        return MatchCard(
            id = id,
            name = name,
            role = "",
            distanceKm = null,
            bio = optString("bio"),
            age = if (isNull("age")) null else optInt("age"),
            city = optString("city").orEmptyNull(),
            country = optString("country").orEmptyNull(),
            latitude = if (isNull("latitude")) null else optDouble("latitude"),
            longitude = if (isNull("longitude")) null else optDouble("longitude"),
            gender = optString("gender").orEmptyNull(),
            likesReceivedCount = optInt("likesReceivedCount"),
            myStatus = optString("myStatus").orEmptyNull(),
            lookingFor = optString("lookingFor").orEmptyNull(),
            lastSeen = if (isNull("lastSeen")) null else Date(optLong("lastSeen")),
            createdAt = if (isNull("createdAt")) null else Date(optLong("createdAt")),
            isPremium = optBoolean("isPremium"),
            photoUrl = optString("photoUrl").orEmptyNull()
        )
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
