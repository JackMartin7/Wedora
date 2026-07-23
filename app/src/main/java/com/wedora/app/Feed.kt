package com.wedora.app

import android.content.Context
import com.google.firebase.firestore.DocumentSnapshot

/**
 * Shared feed helpers: how a user document becomes a swipeable/browsable card,
 * and whether that card survives the current filters.
 *
 * Both the Home swipe stack and the Explore "Discover" grid draw from the same
 * pool — opposite gender, minus everyone excluded — and must agree on who
 * belongs in it, so the mapping and the filter live here rather than being
 * copied into each screen where they could drift.
 */

/**
 * Maps a user document to a feed [MatchCard], or null when it has no usable
 * display name — an unnamed profile has nothing to show and is dropped rather
 * than rendered blank.
 *
 * Photos aren't backed for other users yet, so both image slots take the
 * neutral placeholder; the caller shows name, age/location and marriage intent.
 */
fun DocumentSnapshot.toMatchCard(): MatchCard? {
    val profile = UserProfile.from(this)
    val name = profile.displayName?.takeIf { it.isNotBlank() } ?: return null
    return MatchCard(
        id = id,
        name = name,
        role = "",
        avatarRes = R.drawable.ic_avatar_placeholder,
        photoRes = R.drawable.ic_avatar_placeholder,
        distanceKm = null,
        bio = profile.bio.orEmpty(),
        age = profile.age,
        city = profile.city,
        country = profile.country,
        gender = profile.gender,
        myStatus = profile.myStatus,
        lookingFor = profile.lookingFor
    )
}

/**
 * Whether [card] passes the user's active filters.
 *
 * Client-side, consistent with the self and block filters — pairing more
 * conditions onto the gender query would need composite indexes, and an
 * inequality on age would silently drop every document that has no age rather
 * than letting us decide.
 *
 * A card with no age is excluded, deliberately: an age filter that keeps
 * unknown ages isn't filtering by age. That's the one case where "handle
 * missing gracefully" and "respect what the user asked for" disagree, and the
 * user's request wins.
 *
 * Relationship type and distance are not applied — there is no relationshipType
 * field to compare and no coordinates to measure between. They're stored in
 * FilterPrefs, and the filter screen says so.
 */
fun matchesActiveFilters(context: Context, card: MatchCard): Boolean {
    val age = card.age ?: return false
    if (age < FilterPrefs.getAgeMin(context) || age > FilterPrefs.getAgeMax(context)) return false

    // Empty means "don't narrow" — there is no UI setting this yet, so it stays
    // empty and the gender query alone decides.
    val genders = FilterPrefs.getInterestedIn(context)
    if (genders.isNotEmpty() && card.gender !in genders) return false

    // Null means every option is ticked, i.e. no narrowing — so a profile
    // missing the field is kept. Once a filter IS active it's excluded, because
    // there's no way to know whether it would have qualified and showing it
    // anyway would ignore what the user asked for.
    FilterPrefs.getMyStatusFilter(context)?.let { allowed ->
        if (card.myStatus !in allowed) return false
    }
    FilterPrefs.getLookingForFilter(context, MarriageIntent.ALL_LOOKING_FOR)?.let { allowed ->
        if (card.lookingFor !in allowed) return false
    }

    return true
}
