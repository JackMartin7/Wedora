package com.wedora.app

import com.google.android.gms.tasks.Task
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Free-tier likes per calendar day. Premium is unlimited — see [UserProfile.isPremium]. */
private const val FREE_DAILY_LIKE_LIMIT = 10

/** "yyyy-MM-dd" for the device's local calendar day — see [UserProfile.likesGivenDate]. */
private fun todayDateString(): String =
    SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())

/** What happened when [likeUserRespectingDailyLimit] was asked to record a like. */
sealed class LikeAttempt {
    /** The write is in flight; attach UI handling to [task] as usual. */
    data class Started(val task: Task<Void>) : LikeAttempt()

    /** Free tier, already at [FREE_DAILY_LIKE_LIMIT] for today — nothing was written. */
    object DailyLimitReached : LikeAttempt()
}

/**
 * Records a like, enforcing the free-tier daily cap first.
 *
 * Reads the liker's own profile before writing — unlike [createMatchDocument],
 * which is deliberately a blind write (see its doc comment on why a pre-read
 * there would weaken the match-read rule). That constraint doesn't apply to a
 * user's own profile: `users/{uid}` is already fully readable by any signed-in
 * user, so a pre-read here costs nothing rule-wise, and enforcing the limit
 * without one isn't possible at all.
 *
 * Premium skips the check entirely. For a free account, the count and date
 * live together so a stale date (yesterday's, or absent on a new account)
 * always reads as zero for today rather than carrying over. Match creation
 * and the incremented count are one batch, so a like is never recorded
 * without the corresponding count moving, or vice versa.
 *
 * Client-side only. See firestore.rules for how far the limit is enforced
 * server-side, and its documented gap.
 */
fun likeUserRespectingDailyLimit(
    firestore: FirebaseFirestore,
    selfUid: String,
    otherUid: String,
    onResult: (LikeAttempt) -> Unit
) {
    val selfDoc = firestore.collection(UserProfile.COLLECTION).document(selfUid)
    selfDoc.get()
        .addOnSuccessListener { snapshot ->
            val profile = UserProfile.from(snapshot)
            if (profile.isPremium) {
                onResult(LikeAttempt.Started(createMatchDocument(firestore, selfUid, otherUid)))
                return@addOnSuccessListener
            }

            val today = todayDateString()
            val countSoFar = if (profile.likesGivenDate == today) profile.likesGivenToday else 0

            if (countSoFar >= FREE_DAILY_LIKE_LIMIT) {
                onResult(LikeAttempt.DailyLimitReached)
                return@addOnSuccessListener
            }

            val batch = firestore.batch()
            batch.set(
                selfDoc,
                mapOf(
                    UserProfile.FIELD_LIKES_GIVEN_TODAY to countSoFar + 1,
                    UserProfile.FIELD_LIKES_GIVEN_DATE to today
                ),
                SetOptions.merge()
            )
            batch.set(
                firestore.collection(Match.COLLECTION).document(Match.idFor(selfUid, otherUid)),
                matchDataFor(selfUid, otherUid),
                SetOptions.merge()
            )
            onResult(LikeAttempt.Started(batch.commit()))
        }
        .addOnFailureListener {
            // Can't confirm the limit — fail open rather than blocking a like
            // over a transient read error. Unlimited like-attempt failures are
            // still handled by whatever the caller attaches to the task.
            onResult(LikeAttempt.Started(createMatchDocument(firestore, selfUid, otherUid)))
        }
}
