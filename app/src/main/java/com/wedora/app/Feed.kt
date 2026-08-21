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
 */
fun DocumentSnapshot.toMatchCard(): MatchCard? {
    val profile = UserProfile.from(this)
    val name = profile.displayName?.takeIf { it.isNotBlank() } ?: return null
    return MatchCard(
        id = id,
        name = name,
        role = "",
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
        likesReceivedCount = profile.likesReceivedCount,
        interests = profile.interests,
        myStatus = profile.myStatus,
        lookingFor = profile.lookingFor,
        lastSeen = profile.lastSeen,
        createdAt = profile.createdAt,
        isPremium = profile.isPremium,
        photoUrl = profile.photoUrl
    )
}

/**
 * Premium accounts sort before non-premium ones. A stable sort — Kotlin's
 * sortedByDescending guarantees it — so applying this *after* whatever
 * ordering a feed already has (distance in the discovery feed, Firestore's
 * returned order in Home's swipe stack) only regroups by tier; the existing
 * order survives untouched within each tier, which is what "premium sorts
 * first, otherwise unchanged" means in practice.
 */
/**
 * The minimum received likes to qualify for Trending.
 *
 * 1, not 0. Measured against production, 64% of feed-eligible profiles have at
 * least one like and the median is 3, so this excludes a real minority rather
 * than gutting the section - but a "Trending" strip led by people nobody has
 * liked would be a lie.
 */
const val TRENDING_MIN_LIKES = 1

/**
 * The same candidate pool, ordered for Trending: most-liked first.
 *
 * Deliberately a re-sort of what [loadDiscoveryFeed] already returned rather
 * than its own query. That costs zero extra reads, and more importantly it is
 * the correct set - a global "most liked" query would surface people outside
 * the viewer's gender preference, people they blocked, and people they already
 * passed or liked. Trending is therefore scoped to who this viewer could
 * actually match with, including their own distance filter.
 *
 * Ties break by distance for free: sortedByDescending is stable and the pool
 * arrives distance-sorted, so equal counts keep nearest-first order. Same
 * property [withPremiumPriority] relies on.
 */
fun List<MatchCard>.asTrending(): List<MatchCard> =
    filter { it.likesReceivedCount >= TRENDING_MIN_LIKES }
        .sortedByDescending { it.likesReceivedCount }

fun List<MatchCard>.withPremiumPriority(): List<MatchCard> =
    sortedByDescending { it.isPremium }

/**
 * Newest profiles first — a full recency sort, for Home's swipe deck.
 *
 * Home has no other meaningful ordering to protect: a plain equality query
 * returns documents in document-ID order, which is arbitrary and, worse,
 * identical on every load — the reason the same faces kept coming up. Cards
 * with no [MatchCard.createdAt] (accounts predating the field) sort last, the
 * safe reading of "unknown age".
 *
 * Stable, like [withPremiumPriority], so applying it *before* that one
 * leaves Premium on top with recency ordering within each tier.
 */
fun List<MatchCard>.withRecencyPriority(): List<MatchCard> =
    sortedByDescending { it.createdAt?.time ?: Long.MIN_VALUE }

/**
 * Profiles inside [MatchCard.NEW_SIGNUP_WINDOW_MS] to the front — Explore's
 * visibility band.
 *
 * A band rather than a full recency sort because Explore is organised
 * spatially: a strict newest-first would put someone 5,000 km away who
 * joined yesterday above someone 2 km away, inverting what a proximity sort
 * is for. Banding caps that to a bounded window, and within the band
 * distance still decides.
 *
 * Evaluated against a single [nowMs] so every card in one pass is judged
 * against the same instant — a comparator that called
 * System.currentTimeMillis() per element could, in principle, disagree with
 * itself mid-sort.
 */
fun List<MatchCard>.withNewSignupPriority(
    nowMs: Long = System.currentTimeMillis()
): List<MatchCard> = sortedByDescending { it.isNewSignup(nowMs) }

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
 * Interests match on ANY: one in common is enough. A profile with none is
 * excluded while the filter is active, on the same reasoning as status — see
 * the comment at the check itself.
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

    // ANY, not ALL: one interest in common qualifies. Empty means the filter
    // is off, so nobody is narrowed until the user actually picks something -
    // and empty is both the default and what Reset restores.
    //
    // A profile with NO interests is excluded once the filter is on, matching
    // the status/looking-for rule above rather than distance's fail-open.
    // Distance fails open for a specific reason - coordinates are legitimately
    // missing for anyone who typed their city by hand - and there is no
    // equivalent here: an empty interests list means the optional step was
    // skipped, which is the same "no way to know whether it would have
    // qualified" case the status rule already covers.
    val wantedInterests = FilterPrefs.getInterestsFilter(context)
    if (wantedInterests.isNotEmpty() && card.interests.none { it in wantedInterests }) return false

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
 *
 * No special-casing for the slider's "Worldwide" position needed: [maxKm] at
 * that point is FilterPrefs.MAX_DISTANCE_KM, which is deliberately set above
 * the greatest possible distance between two points on Earth (see that
 * constant's own doc comment) — so `<=` below is already true for every real
 * coordinate pair once the slider is at max, with no extra branch required.
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
 * Below this many candidates, a tier is considered too thin to show on its
 * own and [buildFeedCards] widens to the next one — a small user base would
 * otherwise hit a hard empty state almost immediately.
 *
 * Also the bar a cached pool has to clear before it's served instead of
 * re-querying: see the bypass in [queryDiscoveryFeed].
 */
const val FALLBACK_MIN_RESULTS = 3

/** Every user document that maps to a usable card, before any exclusion or
 *  filter — the shape [FeedCache] stores and [buildFeedCards] consumes. */
fun List<DocumentSnapshot>.toFeedPool(): List<MatchCard> = mapNotNull { it.toMatchCard() }

/**
 * The widest set a tier could ever draw from — everyone but blocked and
 * matched, before the active filters run.
 *
 * Lets a caller tell "nobody at all" apart from "nobody once your filters
 * applied", which HomeActivity uses to decide whether pointing at the
 * filters is useful advice.
 */
fun feedCandidatePool(
    pool: List<MatchCard>,
    selfUid: String?,
    exclusions: FeedExclusions
): List<MatchCard> {
    val permanent = exclusions.blocked + exclusions.matched
    return pool.filter { it.id != selfUid && it.id !in permanent }
}

/**
 * The candidate list for [pool], widening the exclusion set until it
 * yields something worth showing.
 *
 * Three tiers, each strictly wider than the last:
 *
 *  1. blocked + matched + passed + liked — fresh, never-seen people only.
 *  2. …reintroducing passed — people they said no to.
 *  3. …reintroducing liked too — people they said yes to who never matched back.
 *
 * Blocked and matched are excluded at every tier and there is no path here
 * that reintroduces either: a block is a safety decision a fallback must
 * never undo, and a matched user already has a chat thread, so re-dealing
 * them into the deck is noise rather than discovery.
 *
 * The count that decides each tier is the FINAL, post-filter card list, not
 * the raw surviving documents. That distinction is the whole point: filters
 * (and matchesActiveFilters' hard exclusion of profiles with no age) run
 * *after* exclusions, so counting documents would let a tier look healthy
 * while rendering empty — the feed would go blank with the fallback never
 * firing.
 *
 * Costs no extra reads: every tier re-filters the one pool already in
 * memory, whether that came from a query or from [FeedCache].
 *
 * Distance is recomputed here rather than taken from [pool], so a cached
 * pool still measures against the viewer's current coordinates.
 *
 * Returned unsorted. Explore sorts by distance and Home keeps the query's own
 * order (see each call site) — a deliberate difference, so ordering stays
 * with the caller rather than being imposed here.
 */
fun buildFeedCards(
    context: Context,
    pool: List<MatchCard>,
    selfUid: String?,
    exclusions: FeedExclusions,
    myLat: Double?,
    myLon: Double?
): List<MatchCard> {
    val permanent = exclusions.blocked + exclusions.matched
    val tiers = listOf(
        permanent + exclusions.passed + exclusions.liked,
        permanent + exclusions.liked,
        permanent
    )

    var widest = emptyList<MatchCard>()
    for (excluded in tiers) {
        val cards = pool
            .filter { it.id != selfUid && it.id !in excluded }
            .map { it.withDistanceFrom(myLat, myLon) }
            .filter { matchesActiveFilters(context, it, myLat, myLon) }
        if (cards.size >= FALLBACK_MIN_RESULTS) return cards
        // Tiers only ever widen, so the last one computed is also the largest.
        widest = cards
    }
    return widest
}

/**
 * Loads the discovery feed as distance-sorted cards — opposite gender, minus
 * everyone blocked and already matched (and, pool permitting, passed/liked
 * too — see [buildFeedCards]), passing the active filters, closest first
 * (cards with no distance sort last).
 *
 * Shared by Explore and NearbyList so "who's nearby" has a single definition.
 * [onResult] gets an empty list for a signed-out user, an own profile that
 * hasn't set who they're interested in, or a genuinely empty feed — the
 * caller decides how to present emptiness. A read failure also yields an
 * empty list rather than an error path, matching how the feed degrades
 * elsewhere. A guest is no longer one of those empty cases (see the branch
 * below) — they have no self document to read, so they skip straight to the
 * query using [GuestPrefs.guestInterestedIn] instead of a signed-in
 * `interestedIn` field, with no self id or exclusion set to filter by and no
 * location to sort or filter distance by.
 */
fun loadDiscoveryFeed(
    context: Context,
    firestore: FirebaseFirestore,
    onResult: (List<MatchCard>) -> Unit
) {
    if (GuestPrefs.isGuest(context)) {
        queryDiscoveryFeed(
            context, firestore,
            interestedIn = GuestPrefs.guestInterestedIn(context),
            selfUid = null,
            exclusions = FeedExclusions.EMPTY,
            myLat = null,
            myLon = null,
            onResult = onResult
        )
        return
    }

    val uid = FirebaseAuth.getInstance().currentUser?.uid
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

            loadFeedExclusions(firestore, uid) { exclusions ->
                queryDiscoveryFeed(
                    context, firestore, interestedIn, uid, exclusions, self.latitude, self.longitude, onResult
                )
            }
        }
        .addOnFailureListener { e ->
            Log.w("WedoraFeed", "Failed to read own profile for discovery feed", e)
            onResult(emptyList())
        }
}

/**
 * [interestedIn] null queries every gender (the guest fallback for a
 * somehow-unset GuestPrefs value); [selfUid] null excludes nobody, since
 * `it.id != null` is trivially true for every real document id — same
 * reasoning as HomeActivity.queryMatches' own nullable pair.
 */
private fun queryDiscoveryFeed(
    context: Context,
    firestore: FirebaseFirestore,
    interestedIn: String?,
    selfUid: String?,
    exclusions: FeedExclusions,
    myLat: Double?,
    myLon: Double?,
    onResult: (List<MatchCard>) -> Unit
) {
    // Cached pool first, for signed-in users only — selfUid is null for a
    // guest, which is exactly the case FeedCache deliberately never stores.
    //
    // The size check is the bypass that keeps this cache from making the
    // very problem it's here to help with worse: a thin or empty result
    // means this user has run out of people, and serving that from cache
    // would strand them on a stale pool instead of picking up whoever has
    // signed up since. An exhausted feed always re-queries.
    val cachedPool = selfUid?.let { FeedCache.load(context, it, interestedIn) }
    if (cachedPool != null) {
        val cards = buildFeedCards(context, cachedPool, selfUid, exclusions, myLat, myLon)
            .sortedForDiscovery()
        if (cards.size >= FALLBACK_MIN_RESULTS) {
            onResult(cards)
            return
        }
    }

    val baseQuery = firestore.collection(UserProfile.COLLECTION)
    val query = if (interestedIn.isNullOrBlank()) {
        baseQuery
    } else {
        baseQuery.whereEqualTo(UserProfile.FIELD_GENDER, interestedIn)
    }

    query
        .get()
        .addOnSuccessListener { snapshot ->
            // Cached pre-filter, so a later filter change re-narrows this
            // locally rather than paying for the query again.
            val pool = snapshot.documents.toFeedPool()
            selfUid?.let { FeedCache.save(context, it, interestedIn, pool) }
            onResult(
                buildFeedCards(context, pool, selfUid, exclusions, myLat, myLon)
                    .sortedForDiscovery()
            )
        }
        .addOnFailureListener { e ->
            Log.w("WedoraFeed", "Discovery feed query failed", e)
            onResult(emptyList())
        }
}

/**
 * Explore's ordering, built by stable sorts applied in reverse priority —
 * the same layering [withPremiumPriority] documents, so each pass regroups
 * without disturbing the order established inside each group.
 *
 * Reading the result outermost-first: Premium accounts, then profiles inside
 * the new-signup window, then closest first — with un-locatable cards (null
 * distance) at the end of their group rather than jumping to the front.
 *
 * Premium stays above recency deliberately: "Priority in discovery feed" is
 * an advertised paid perk (R.array.premium_features), so recency ranking
 * above it would quietly withdraw something users paid for.
 */
private fun List<MatchCard>.sortedForDiscovery(): List<MatchCard> =
    sortedWith(compareBy(nullsLast<Double>()) { it.distanceKm })
        .withNewSignupPriority()
        .withPremiumPriority()
