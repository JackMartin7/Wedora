package com.wedora.app

import android.content.Context
import android.util.Log
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FirebaseFirestore

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
        // Filled in later by withDistanceFrom once the viewer's own coordinates
        // are known; a card mapped in isolation has no distance yet.
        distanceKm = null,
        bio = profile.bio.orEmpty(),
        age = profile.age,
        city = profile.city,
        country = profile.country,
        latitude = profile.latitude,
        longitude = profile.longitude,
        gender = profile.gender,
        myStatus = profile.myStatus,
        lookingFor = profile.lookingFor,
        lastSeen = profile.lastSeen
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
 * Relationship type is not applied — there is no relationshipType field to
 * compare. It's stored in FilterPrefs, and the filter screen says so.
 *
 * Distance IS applied now, via [matchesDistanceFilter], using the viewer's
 * coordinates ([myLat]/[myLon]) — but it fails open when either side has none,
 * so it narrows the feed without ever excluding people for being un-locatable.
 */
fun matchesActiveFilters(
    context: Context,
    card: MatchCard,
    myLat: Double?,
    myLon: Double?
): Boolean {
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

    if (!matchesDistanceFilter(card, myLat, myLon, FilterPrefs.getDistanceKm(context))) return false

    return true
}

/**
 * Whether [card] is within [maxKm] of the viewer at ([myLat], [myLon]).
 *
 * Fails open: if either the viewer or the other user has no coordinates there's
 * no distance to test, so the card is kept. Excluding people merely because a
 * coordinate is missing would quietly empty the feed for anyone who typed their
 * city by hand — the opposite of what a distance filter is for.
 */
fun matchesDistanceFilter(
    card: MatchCard,
    myLat: Double?,
    myLon: Double?,
    maxKm: Int
): Boolean {
    if (myLat == null || myLon == null || card.latitude == null || card.longitude == null) {
        return true
    }
    return DistanceUtils.distanceKm(myLat, myLon, card.latitude, card.longitude) <= maxKm
}

/**
 * Loads the discovery feed as distance-sorted cards — opposite gender, minus
 * everyone blocked/passed/liked, passing the active filters, closest first
 * (cards with no distance sort last).
 *
 * Shared by Explore and NearbyList so "who's nearby" has a single definition.
 * [onResult] gets an empty list for a guest, a signed-out user, an own profile
 * that hasn't set who they're interested in, or a genuinely empty feed — the
 * caller decides how to present emptiness. A read failure also yields an empty
 * list rather than an error path, matching how the feed degrades elsewhere.
 */
fun loadDiscoveryFeed(
    context: Context,
    firestore: FirebaseFirestore,
    onResult: (List<MatchCard>) -> Unit
) {
    val uid = FirebaseAuth.getInstance().currentUser
        ?.takeUnless { GuestPrefs.isGuest(context) }?.uid
    if (uid == null) {
        onResult(emptyList())
        return
    }

    firestore.collection(UserProfile.COLLECTION).document(uid).get()
        .addOnSuccessListener { selfDoc ->
            val self = UserProfile.from(selfDoc)
            val interestedIn = self.interestedIn
            if (interestedIn.isNullOrBlank()) {
                onResult(emptyList())
                return@addOnSuccessListener
            }

            loadFeedExclusions(firestore, uid) { excluded ->
                firestore.collection(UserProfile.COLLECTION)
                    .whereEqualTo(UserProfile.FIELD_GENDER, interestedIn)
                    .get()
                    .addOnSuccessListener { snapshot ->
                        val cards = snapshot.documents
                            .filter { it.id != uid && it.id !in excluded }
                            .mapNotNull { it.toMatchCard()?.withDistanceFrom(self.latitude, self.longitude) }
                            .filter { matchesActiveFilters(context, it, self.latitude, self.longitude) }
                            // Closest first; un-locatable cards (null distance)
                            // sort to the end rather than jumping to the front.
                            .sortedWith(compareBy(nullsLast()) { it.distanceKm })
                        onResult(cards)
                    }
                    .addOnFailureListener { e ->
                        Log.w("WedoraFeed", "Discovery feed query failed", e)
                        onResult(emptyList())
                    }
            }
        }
        .addOnFailureListener { e ->
            Log.w("WedoraFeed", "Failed to read own profile for discovery feed", e)
            onResult(emptyList())
        }
}
