package com.wedora.app

import android.util.Log
import com.google.android.gms.tasks.Tasks
import com.google.firebase.Timestamp
import com.google.firebase.firestore.FieldPath
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.QuerySnapshot
import com.google.firebase.firestore.SetOptions
import java.util.Date

private const val TAG = "WedoraProfileViews"

/** Firestore caps whereIn values, so profile lookups go out in chunks. */
private const val WHERE_IN_CHUNK = 10

// internal, not private: ProfileViewNotificationWatcher needs the same path.
internal const val PROFILE_VIEWS_COLLECTION = "profileViews"
internal const val PROFILE_VIEWS_SUBCOLLECTION_VIEWERS = "viewers"
private const val FIELD_VIEWED_AT = "viewedAt"

/** Someone who viewed the current user's profile, resolved to their name and presence. */
data class ProfileViewer(
    val viewerUid: String,
    val name: String,
    val lastSeen: Date?,
    val viewedAt: Timestamp?,
    val photoUrl: String?,
    /** Null when either this user or the viewer has no coordinates on file. */
    val distanceBadge: String?
)

/**
 * Records that [viewerUid] viewed [viewedUid]'s profile — one document per
 * unique viewer, `viewedAt` refreshed on every visit rather than accumulating
 * a history, so a repeat viewer just moves back to the top of the owner's
 * list instead of appearing twice.
 *
 * Skips the write entirely for a self-view: [viewedUid] finding themselves in
 * their own "who viewed me" list would be meaningless noise, not a sign
 * anyone is interested.
 *
 * Fire-and-forget, same as presence — a missed view isn't worth surfacing an
 * error for.
 */
fun recordProfileView(firestore: FirebaseFirestore, viewedUid: String, viewerUid: String) {
    if (viewedUid == viewerUid) return

    firestore.collection(PROFILE_VIEWS_COLLECTION).document(viewedUid)
        .collection(PROFILE_VIEWS_SUBCOLLECTION_VIEWERS).document(viewerUid)
        .set(mapOf(FIELD_VIEWED_AT to FieldValue.serverTimestamp()), SetOptions.merge())
        .addOnFailureListener { e -> Log.w(TAG, "Failed to record profile view", e) }
}

/**
 * Loads everyone who has viewed [selfUid]'s profile, newest view first, each
 * resolved to their display name and presence.
 *
 * Sorted client-side rather than via orderBy, consistent with how this app
 * already reads similarly-shaped small collections (Match History, the Likes
 * tab) — the whereIn profile lookup doesn't preserve the original query's
 * order, so the sort has to happen after merging in profile data regardless.
 *
 * A viewer whose profile is missing or unnamed is dropped rather than shown
 * as a blank row.
 *
 * [myLat]/[myLon] are this user's own coordinates, for each [ProfileViewer]'s
 * [ProfileViewer.distanceBadge] — null from a caller with none to give,
 * which simply leaves every badge null too.
 */
fun loadProfileViewers(
    firestore: FirebaseFirestore,
    selfUid: String,
    myLat: Double? = null,
    myLon: Double? = null,
    onResult: (List<ProfileViewer>) -> Unit,
    onError: () -> Unit
) {
    firestore.collection(PROFILE_VIEWS_COLLECTION).document(selfUid)
        .collection(PROFILE_VIEWS_SUBCOLLECTION_VIEWERS)
        .get()
        .addOnSuccessListener { snapshot ->
            val viewedAtByUid = snapshot.documents.associate { it.id to it.getTimestamp(FIELD_VIEWED_AT) }
            val viewerUids = viewedAtByUid.keys.toList()
            if (viewerUids.isEmpty()) {
                onResult(emptyList())
                return@addOnSuccessListener
            }

            val tasks = viewerUids.chunked(WHERE_IN_CHUNK).map { chunk ->
                firestore.collection(UserProfile.COLLECTION)
                    .whereIn(FieldPath.documentId(), chunk)
                    .get()
            }

            Tasks.whenAllSuccess<QuerySnapshot>(tasks)
                .addOnSuccessListener { snapshots ->
                    val profilesByUid = snapshots.flatMap { it.documents }
                        .associate { it.id to UserProfile.from(it) }

                    val viewers = viewerUids.mapNotNull { uid ->
                        val profile = profilesByUid[uid] ?: return@mapNotNull null
                        val name = profile.displayName?.takeIf { it.isNotBlank() }
                            ?: return@mapNotNull null
                        ProfileViewer(
                            viewerUid = uid,
                            name = name,
                            lastSeen = profile.lastSeen,
                            viewedAt = viewedAtByUid[uid],
                            photoUrl = profile.photoUrl,
                            distanceBadge = distanceBadgeBetween(myLat, myLon, profile.latitude, profile.longitude)
                        )
                    }.sortedByDescending { it.viewedAt?.toDate()?.time ?: 0L }

                    onResult(viewers)
                }
                .addOnFailureListener { e ->
                    Log.w(TAG, "Failed to load viewer profiles", e)
                    onError()
                }
        }
        .addOnFailureListener { e ->
            Log.w(TAG, "Failed to load profile viewers", e)
            onError()
        }
}
