package com.wedora.app

import android.content.Context
import androidx.annotation.StringRes
import com.google.firebase.firestore.DocumentSnapshot

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
 * across SignUpActivity / LoginActivity / HomeActivity / CompleteProfileActivity
 * / ProfileActivity, so a rename can't silently desync one call site.
 *
 * [age], [city] and [country] are filled in by CompleteProfileActivity after
 * first verified login — they are absent for accounts created before that step
 * existed, which is exactly what the completion gate keys off.
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
     * Free text the user writes about themselves, capped at 150 characters by
     * the editor. Null for anyone who hasn't written one — it is optional, so
     * it deliberately plays no part in [isComplete].
     */
    val bio: String?,
    /**
     * When true, only mutually-matched users may send this person messages.
     * Enforced in firestore.rules, not just here. Absent reads as false — the
     * setting is opt-in, and applying it to someone who never turned it on
     * would silently mute their existing conversations.
     */
    val onlyMatchesCanMessage: Boolean
) {

    /** True once the Complete Profile step has been satisfied. */
    val isComplete: Boolean
        get() = age != null && !city.isNullOrBlank() && !country.isNullOrBlank()

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
        const val FIELD_BIO = "bio"
        const val FIELD_ONLY_MATCHES_CAN_MESSAGE = "onlyMatchesCanMessage"

        /** Character cap on [bio], enforced by the editor's input filter too. */
        const val MAX_BIO_LENGTH = 150

        /**
         * Reads a profile out of a snapshot. Safe on a snapshot for a document
         * that doesn't exist — every field simply comes back null, which
         * [isComplete] correctly reports as incomplete.
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
            bio = snapshot.getString(FIELD_BIO),
            onlyMatchesCanMessage =
                snapshot.getBoolean(FIELD_ONLY_MATCHES_CAN_MESSAGE) ?: false
        )
    }
}
