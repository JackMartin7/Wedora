package com.wedora.app

import android.util.Log
import com.google.android.gms.tasks.Tasks
import com.google.firebase.Timestamp
import com.google.firebase.firestore.FieldPath
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.QuerySnapshot

private const val TAG = "WedoraLikes"

/** Firestore caps whereIn values, so profile lookups go out in chunks. */
private const val WHERE_IN_CHUNK = 10

/**
 * A like the current user has received, resolved to the liker's profile.
 * Shared by the Notifications list and the Likes grid, which read the same
 * data and differ only in how they display it.
 */
data class ReceivedLike(
    val matchId: String,
    val likerUserId: String,
    val likerName: String,
    val profile: UserProfile,
    val createdAt: Timestamp?
)

/**
 * Loads the likes [selfUid] has received — matches someone else initiated —
 * each paired with the liker's profile, newest first.
 *
 * `likedBy != me` is filtered in code rather than in the query: pairing an
 * inequality with the array-contains would need a composite index, and matches
 * predating the likedBy field have no value to compare against.
 *
 * [onResult] delivers the display list plus the ids of every still-unseen
 * received like. A like whose liker profile is missing is dropped from the
 * display list but still reported in unseenMatchIds — so [markLikesSeen] can
 * clear a badge count the user could otherwise never act on.
 */
fun loadReceivedLikes(
    firestore: FirebaseFirestore,
    selfUid: String,
    onResult: (likes: List<ReceivedLike>, unseenMatchIds: List<String>) -> Unit,
    onError: () -> Unit
) {
    firestore.collection(Match.COLLECTION)
        .whereArrayContains(Match.FIELD_USERS, selfUid)
        .get()
        .addOnSuccessListener { snapshot ->
            val received = snapshot.documents
                .mapNotNull { Match.from(it) }
                .filter { it.isLikeFor(selfUid) }
                .sortedByDescending { it.createdAt?.toDate()?.time ?: Long.MAX_VALUE }

            val unseenIds = received.filter { it.isUnseenLikeFor(selfUid) }.map { it.id }

            if (received.isEmpty()) {
                onResult(emptyList(), unseenIds)
                return@addOnSuccessListener
            }

            val likerUids = received.mapNotNull { it.likedBy }.distinct()
            val profileTasks = likerUids.chunked(WHERE_IN_CHUNK).map { chunk ->
                firestore.collection(UserProfile.COLLECTION)
                    .whereIn(FieldPath.documentId(), chunk)
                    .get()
            }

            Tasks.whenAllSuccess<QuerySnapshot>(profileTasks)
                .addOnSuccessListener { snapshots ->
                    val profilesByUid = snapshots
                        .flatMap { it.documents }
                        .mapNotNull { doc ->
                            val profile = UserProfile.from(doc)
                            profile.displayName
                                ?.takeIf { it.isNotBlank() }
                                ?.let { doc.id to profile }
                        }
                        .toMap()

                    val likes = received.mapNotNull { match ->
                        val likerUid = match.likedBy ?: return@mapNotNull null
                        val profile = profilesByUid[likerUid] ?: return@mapNotNull null
                        val name = profile.displayName?.takeIf { it.isNotBlank() }
                            ?: return@mapNotNull null
                        ReceivedLike(
                            matchId = match.id,
                            likerUserId = likerUid,
                            likerName = name,
                            profile = profile,
                            createdAt = match.createdAt
                        )
                    }
                    onResult(likes, unseenIds)
                }
                .addOnFailureListener { e ->
                    Log.w(TAG, "Failed to load liker profiles", e)
                    onError()
                }
        }
        .addOnFailureListener { e ->
            Log.w(TAG, "Failed to load received likes", e)
            onError()
        }
}

/**
 * Flips seenByRecipient to true on the given matches in one batched write,
 * clearing the Home badge. Non-fatal on failure — the list is on screen either
 * way, the badge just clears on a later visit.
 *
 * Firestore caps a batch at 500 writes; a user with that many unseen likes in
 * one sitting is well beyond this stage.
 */
fun markLikesSeen(firestore: FirebaseFirestore, matchIds: List<String>) {
    if (matchIds.isEmpty()) return

    val batch = firestore.batch()
    matchIds.forEach { id ->
        val ref = firestore.collection(Match.COLLECTION).document(id)
        batch.update(ref, Match.FIELD_SEEN_BY_RECIPIENT, true)
    }
    batch.commit().addOnFailureListener { e ->
        Log.w(TAG, "Failed to mark likes seen", e)
    }
}
