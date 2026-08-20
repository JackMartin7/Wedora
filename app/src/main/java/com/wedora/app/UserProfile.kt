package com.wedora.app

import android.content.Context
import androidx.annotation.StringRes
import com.google.firebase.firestore.DocumentSnapshot
import java.util.Date

/**
 * Formats an "age • city, country" line, or returns null if any part is
 * missing so the caller can hide the view entirely rather than render a
 * half-empty string like "18 years old • , ".
 *
 * Shared by ProfileActivity (own profile) and the Home feed cards (other
 * users), which use different format strings but identical null handling.
 */
fun formatAgeLocation(
    context: Context,
    @StringRes formatRes: Int,
    age: Int?,
    city: String?,
    country: String?
): String? {
    if (age == null || city.isNullOrBlank() || country.isNullOrBlank()) return null
    return context.getString(formatRes, age, city, country)
}

/**
 * A user's Firestore `users/{uid}` document.
 *
 * Field names live here as constants rather than as string literals scattered
 * across SignUpActivity / LoginActivity / HomeActivity / the ProfileStep
 * screens / ProfileActivity, so a rename can't silently desync one call site.
 *
 * The document is built up field by field: sign-up writes none of it, and each
 * setup step merges its own answer. Any of these can therefore be absent, and
 * which ones are missing is exactly what routes a user to the step that asks.
 */
data class UserProfile(
    val displayName: String?,
    val email: String?,
    val gender: String?,
    val interestedIn: String?,
    val age: Int?,
    val city: String?,
    val country: String?,
    /**
     * Coordinates of the detected location, for geographic distance. Present
     * only when the city was auto-detected — a manually typed city has no
     * coordinates, so both are null and every distance involving this user
     * fails open (they're never excluded for being un-locatable).
     */
    val latitude: Double?,
    val longitude: Double?,
    /**
     * Free text the user writes about themselves, capped at 150 characters by
     * the editor. Null for anyone who hasn't written one — it is optional, so
     * it is optional and no setup step asks for it.
     */
    val bio: String?,
    /**
     * When true, only mutually-matched users may send this person messages.
     * Enforced in firestore.rules, not just here. Absent reads as false — the
     * setting is opt-in, and applying it to someone who never turned it on
     * would silently mute their existing conversations.
     */
    val onlyMatchesCanMessage: Boolean,
    /**
     * Marital status — one of [MarriageIntent.statusOptions]. Null until step 3
     * has been answered, which is what sends a user there.
     */
    val myStatus: String?,
    /**
     * What this user is looking for. The available options depend on their
     * gender (see [MarriageIntent.lookingForOptions]), and the stored value is
     * the display string itself, so anything reading it can render it as-is.
     */
    val lookingFor: String?,
    /**
     * [Interest.firestoreValue] ids the user picked on Sign Up or Edit
     * Profile. Optional — empty for anyone who skipped it or predates this
     * field, same as [bio]. Not currently used to narrow the Home/Explore
     * feed (see FilterActivity's own Interests section).
     */
    val interests: List<String>,
    /**
     * When this profile became visible in the feed — written once, at the
     * details step (see ProfileStep4DetailsActivity), not at sign-up, since
     * that's the point there's enough on the profile to show anyone.
     *
     * Drives "newest first" ordering in the feed (see Feed.kt's
     * withRecencyPriority / withNewSignupPriority). Null on accounts that
     * predate the field, which sort as oldest — the safe reading.
     */
    val createdAt: Date?,
    /**
     * When this user was last seen — updated on every app foreground/background
     * transition (see PresenceTracker). Null for anyone who predates presence or
     * whose server timestamp hasn't resolved yet; consumers show nothing then.
     */
    val lastSeen: Date?,
    /**
     * Whether this account has Premium. Absent/false is free — the default
     * for every account, since there is no purchase flow yet.
     *
     * There is currently no in-app way to become Premium. Grant it by hand:
     * Firebase Console → Firestore → users/{uid} → set isPremium to the
     * boolean `true`. firestore.rules blocks a user from setting this on
     * their own document (see isPremiumUnchanged there), so this is the only
     * way it's ever set — Console/Admin SDK writes aren't subject to rules.
     */
    val isPremium: Boolean,
    /**
     * How many likes this profile has received. Zero on any account that
     * predates the counter.
     *
     * Floored at zero in [from] rather than at each call site. Without a
     * backfill the stored value can legitimately go negative — an unlike of
     * a like that was never counted decrements from zero — and
     * FieldValue.increment cannot clamp server-side. Doing it at the single
     * parse point means no screen can forget.
     */
    val likesReceivedCount: Int,
    /** How many likes this user has given on [likesGivenDate]. Free tier only. */
    val likesGivenToday: Int,
    /** "yyyy-MM-dd", device-local calendar day the count above is for. */
    val likesGivenDate: String?,
    /**
     * How many messages this user has sent on [messagesSentDate]. Free tier
     * only — an independent quota from the like limit above, not a shared
     * pool; messaging 10 people and liking 10 people are separate allowances.
     */
    val messagesSentToday: Int,
    /** "yyyy-MM-dd", device-local calendar day the count above is for. */
    val messagesSentDate: String?,
    /** Rewarded-ad bonuses on top of the flat free-tier caps — see [FIELD_BONUS_LIKES_TODAY]. */
    val bonusLikesToday: Int,
    val bonusLikesDate: String?,
    val bonusMessagesToday: Int,
    val bonusMessagesDate: String?,
    /**
     * The hosted URL of this user's uploaded photo (see PhotoUploadService),
     * for OTHER users' devices to load — the local file [LocalProfilePrefs]
     * points at only exists on this account's own device. Null until the
     * first successful upload, on accounts that predate this field, or if an
     * upload never succeeded; every reader treats that as "no photo" and
     * falls back to the neutral placeholder rather than erroring.
     */
    val photoUrl: String?,
    /**
     * Set by an admin via AdminReportDetailActivity's Ban action — see
     * firestore.rules' admin-only exception on this field. Absent/false for
     * every normal account. [AuthRouting.resolveSignedInDestination] checks
     * this on every sign-in as a secondary safety net (the primary
     * enforcement is the disableUserAccount Cloud Function disabling the
     * Auth account itself) and signs the user straight back out if it's
     * somehow true despite that.
     */
    val isBanned: Boolean,
    /** Why, shown to the banned user; null unless [isBanned] is true. */
    val banReason: String?
) {

    /** "18 years old • Islamabad, Pakistan", or null if incomplete. */
    fun ageLocationLine(context: Context): String? =
        formatAgeLocation(context, R.string.profile_age_location_format, age, city, country)

    companion object {
        const val COLLECTION = "users"

        const val FIELD_DISPLAY_NAME = "displayName"
        const val FIELD_EMAIL = "email"
        const val FIELD_GENDER = "gender"
        const val FIELD_INTERESTED_IN = "interestedIn"
        const val FIELD_CREATED_AT = "createdAt"
        const val FIELD_AGE = "age"
        const val FIELD_CITY = "city"
        const val FIELD_COUNTRY = "country"
        const val FIELD_LATITUDE = "latitude"
        const val FIELD_LONGITUDE = "longitude"
        const val FIELD_BIO = "bio"
        const val FIELD_ONLY_MATCHES_CAN_MESSAGE = "onlyMatchesCanMessage"
        const val FIELD_MY_STATUS = "myStatus"
        const val FIELD_LOOKING_FOR = "lookingFor"
        const val FIELD_INTERESTS = "interests"
        const val FIELD_LAST_SEEN = "lastSeen"
        const val FIELD_IS_PREMIUM = "isPremium"
        /**
         * Likes this profile has RECEIVED. Maintained solely by the
         * onMatchWritten Cloud Function; firestore.rules blocks clients from
         * writing it (see likesReceivedCountUnchanged there). Not backfilled,
         * so it counts activity from that function's deploy onward.
         */
        const val FIELD_LIKES_RECEIVED_COUNT = "likesReceivedCount"
        const val FIELD_LIKES_GIVEN_TODAY = "likesGivenToday"
        const val FIELD_LIKES_GIVEN_DATE = "likesGivenDate"
        const val FIELD_MESSAGES_SENT_TODAY = "messagesSentToday"
        const val FIELD_MESSAGES_SENT_DATE = "messagesSentDate"

        /**
         * Extra daily allowance earned by watching a rewarded ad, on top of
         * the free tier's flat caps — see RewardedAds, LikeLimit.kt and
         * MessageLimit.kt.
         *
         * Kept as separate fields rather than just letting the client push
         * likesGivenToday past 10, because firestore.rules caps that value
         * directly: the bonus has to be visible to the rules for them to
         * widen the ceiling by exactly the amount earned. Each carries its
         * own date and resets the same way the base counters do.
         */
        const val FIELD_BONUS_LIKES_TODAY = "bonusLikesToday"
        const val FIELD_BONUS_LIKES_DATE = "bonusLikesDate"
        const val FIELD_BONUS_MESSAGES_TODAY = "bonusMessagesToday"
        const val FIELD_BONUS_MESSAGES_DATE = "bonusMessagesDate"

        /**
         * Ceilings on rewarded bonuses per day, mirrored in firestore.rules.
         *
         * Bounded because the rules can't verify an ad was actually watched
         * — same documented trust boundary as the daily-limit date itself.
         * The cap turns "a modified client grants itself unlimited likes"
         * into "a modified client gets at most double", which is the same
         * shape of gap the rest of this file's limits already accept.
         *
         * One constant per quota, not one shared value: each is set to its
         * own base allowance (10 likes, 5 messages) so that "at most double"
         * holds for both. A single shared 10 against the lower message base
         * would quietly make the ad path worth 2x the free allowance on
         * messages alone.
         */
        const val MAX_DAILY_BONUS_LIKES = 10
        const val MAX_DAILY_BONUS_MESSAGES = 5

        /**
         * When this user last earned a rewarded bonus, as a SERVER
         * timestamp — the cooldown in firestore.rules compares it against
         * request.time, so a client-supplied value would defeat the point.
         * See [RewardedCooldownPrefs] for the local mirror that drives the
         * countdown UI, and RewardedAds.grantBonus for the write.
         *
         * One field for both quotas: the cooldown is global, rate-limiting
         * ad views rather than either quota individually.
         */
        const val FIELD_LAST_REWARDED_AD_AT = "lastRewardedAdAt"
        const val FIELD_PHOTO_URL = "photoUrl"
        /**
         * This device's current FCM registration token — write-only from the
         * app's own perspective (nothing here reads it back), like
         * [FIELD_CREATED_AT]. send_notification.php reads it server-side to
         * address a push at this user. Written on every confirmed sign-in
         * (see AuthRouting.registerFcmToken) and whenever it rotates (see
         * WedoraFirebaseMessagingService.onNewToken).
         */
        const val FIELD_FCM_TOKEN = "fcmToken"
        const val FIELD_IS_BANNED = "isBanned"
        const val FIELD_BAN_REASON = "banReason"

        /**
         * Reference-only fields from the last Play Billing purchase that set
         * [FIELD_IS_PREMIUM] — see BillingManager. Nothing in the app reads
         * these back; they exist for support/debugging (e.g. confirming
         * which plan a user is on, or looking a purchase up in the Play
         * Console) rather than as inputs to any decision.
         */
        const val FIELD_PURCHASE_TOKEN = "purchaseToken"
        const val FIELD_PRODUCT_ID = "productId"

        /** Character cap on [bio], enforced by the editor's input filter too. */
        const val MAX_BIO_LENGTH = 150

        /**
         * Reads a profile out of a snapshot. Safe on a snapshot for a document
         * that doesn't exist — every field simply comes back null, which the
         * routing gate correctly reads as "no steps answered yet".
         */
        fun from(snapshot: DocumentSnapshot): UserProfile = UserProfile(
            displayName = snapshot.getString(FIELD_DISPLAY_NAME),
            email = snapshot.getString(FIELD_EMAIL),
            gender = snapshot.getString(FIELD_GENDER),
            interestedIn = snapshot.getString(FIELD_INTERESTED_IN),
            // Firestore stores whole numbers as Long; narrow to Int for the UI.
            age = snapshot.getLong(FIELD_AGE)?.toInt(),
            city = snapshot.getString(FIELD_CITY),
            country = snapshot.getString(FIELD_COUNTRY),
            latitude = snapshot.getDouble(FIELD_LATITUDE),
            longitude = snapshot.getDouble(FIELD_LONGITUDE),
            bio = snapshot.getString(FIELD_BIO),
            onlyMatchesCanMessage =
                snapshot.getBoolean(FIELD_ONLY_MATCHES_CAN_MESSAGE) ?: false,
            myStatus = snapshot.getString(FIELD_MY_STATUS),
            lookingFor = snapshot.getString(FIELD_LOOKING_FOR),
            interests = (snapshot.get(FIELD_INTERESTS) as? List<*>)
                ?.filterIsInstance<String>() ?: emptyList(),
            createdAt = snapshot.getTimestamp(FIELD_CREATED_AT)?.toDate(),
            // Null while a just-written serverTimestamp is still pending.
            lastSeen = snapshot.getTimestamp(FIELD_LAST_SEEN)?.toDate(),
            isPremium = snapshot.getBoolean(FIELD_IS_PREMIUM) ?: false,
            likesReceivedCount = (snapshot.getLong(FIELD_LIKES_RECEIVED_COUNT) ?: 0L).toInt().coerceAtLeast(0),
            likesGivenToday = snapshot.getLong(FIELD_LIKES_GIVEN_TODAY)?.toInt() ?: 0,
            likesGivenDate = snapshot.getString(FIELD_LIKES_GIVEN_DATE),
            messagesSentToday = snapshot.getLong(FIELD_MESSAGES_SENT_TODAY)?.toInt() ?: 0,
            messagesSentDate = snapshot.getString(FIELD_MESSAGES_SENT_DATE),
            bonusLikesToday = snapshot.getLong(FIELD_BONUS_LIKES_TODAY)?.toInt() ?: 0,
            bonusLikesDate = snapshot.getString(FIELD_BONUS_LIKES_DATE),
            bonusMessagesToday = snapshot.getLong(FIELD_BONUS_MESSAGES_TODAY)?.toInt() ?: 0,
            bonusMessagesDate = snapshot.getString(FIELD_BONUS_MESSAGES_DATE),
            photoUrl = snapshot.getString(FIELD_PHOTO_URL),
            isBanned = snapshot.getBoolean(FIELD_IS_BANNED) ?: false,
            banReason = snapshot.getString(FIELD_BAN_REASON)
        )
    }
}
