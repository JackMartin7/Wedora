package com.wedora.app

import android.util.Log
import com.google.android.gms.tasks.Tasks
import com.google.firebase.Timestamp
import com.google.firebase.firestore.FieldPath
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.QuerySnapshot
import java.util.Date

private const val TAG = "WedoraMatches"

/** Firestore caps whereIn values, so profile lookups go out in chunks. */
private const val WHERE_IN_CHUNK = 10

/**
 * One of [selfUid]'s matches, resolved to the other participant's profile.
 * Shared by Match History (which adds its own date formatting) and the Likes
 * tab's "Users Matched" strip, which read the same data and differ only in
 * how they display it — the same relationship [ReceivedLike] has to the
 * Likes grid and Notifications list.
 */
data class MatchedUser(
    val matchId: String,
    val otherUserId: String,
    val name: String,
    val lastSeen: Date?,
    val createdAt: Timestamp?,
    val photoUrl: String?,
    /** Null when either this user or the match has no coordinates on file. */
    val distanceBadge: String?
)

/**
 * Loads every match [selfUid] is part of, newest first, each paired with the
 * other participant's profile.
 *
 * Sorted client-side rather than with orderBy: pairing an orderBy on
 * createdAt with the array-contains would require a composite index, and the
 * per-user match set is small enough to sort in memory.
 *
 * A match whose profile is missing or unnamed is dropped rather than
 * delivered as a blank entry.
 *
 * [myLat]/[myLon] are this user's own coordinates, for each [MatchedUser]'s
 * [MatchedUser.distanceBadge] — null from a caller with none to give, which
 * simply leaves every badge null too.
 */
fun loadMatchedUsers(
    firestore: FirebaseFirestore,
    selfUid: String,
    myLat: Double? = null,
    myLon: Double? = null,
    onResult: (List<MatchedUser>) -> Unit,
    onError: () -> Unit
) {
    firestore.collection(Match.COLLECTION)
        .whereArrayContains(Match.FIELD_USERS, selfUid)
        .get()
        .addOnSuccessListener { snapshot ->
            val matches = snapshot.documents
                .mapNotNull { Match.from(it) }
                .sortedByDescending { it.createdAt?.toDate()?.time ?: Long.MAX_VALUE }

            val otherUids = matches.mapNotNull { it.otherUserId(selfUid) }.distinct()
            if (otherUids.isEmpty()) {
                onResult(emptyList())
                return@addOnSuccessListener
            }

            val tasks = otherUids.chunked(WHERE_IN_CHUNK).map { chunk ->
                firestore.collection(UserProfile.COLLECTION)
                    .whereIn(FieldPath.documentId(), chunk)
                    .get()
            }

            Tasks.whenAllSuccess<QuerySnapshot>(tasks)
                .addOnSuccessListener { snapshots ->
                    val profilesByUid = snapshots.flatMap { it.documents }
                        .associate { it.id to UserProfile.from(it) }

                    val users = matches.mapNotNull { match ->
                        val otherUid = match.otherUserId(selfUid) ?: return@mapNotNull null
                        val profile = profilesByUid[otherUid] ?: return@mapNotNull null
                        val name = profile.displayName?.takeIf { it.isNotBlank() }
                            ?: return@mapNotNull null
                        MatchedUser(
                            matchId = match.id,
                            otherUserId = otherUid,
                            name = name,
                            lastSeen = profile.lastSeen,
                            createdAt = match.createdAt,
                            photoUrl = profile.photoUrl,
                            distanceBadge = distanceBadgeBetween(myLat, myLon, profile.latitude, profile.longitude)
                        )
                    }
                    onResult(users)
                }
                .addOnFailureListener { e ->
                    Log.w(TAG, "Failed to load matched profiles", e)
                    onError()
                }
        }
        .addOnFailureListener { e ->
            Log.w(TAG, "Failed to load matches", e)
            onError()
        }
}
