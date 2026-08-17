package com.wedora.app

import android.util.Log
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import com.google.firebase.messaging.FirebaseMessaging

private const val TAG = "WedoraAuth"

/** What [resolveSignedInDestination] decided. */
sealed class SignedInRouting {
    /** Proceed to [destination] as normal. */
    data class Allowed(val destination: Class<*>) : SignedInRouting()

    /**
     * This account is banned (see [UserProfile.isBanned]) — already signed
     * out by the time this is delivered. The caller's job is just to show
     * why and land on Login; there's no destination to proceed to.
     */
    object Banned : SignedInRouting()
}

/**
 * Decides where a signed-in, email-verified user belongs: Home if their
 * profile is finished, otherwise the first setup step they haven't answered
 * — or [SignedInRouting.Banned] if an admin has banned this account.
 *
 * Resuming at the missing step is what makes the progressive flow work in both
 * directions. A new user walks it front to back; someone who quit halfway
 * returns to exactly where they stopped; and an account created before these
 * fields existed is routed to whichever one it lacks, without a migration.
 *
 * Shared by [SplashActivity], which restores a persisted session on launch,
 * and [LoginActivity], which handles a fresh sign-in — so the gate can't drift
 * between the two ways into the app. Neither calls it until the email is
 * verified.
 *
 * The ban check runs first, before anything else here: a banned account
 * gets no setup-step routing, no email sync, no FCM token registration —
 * just signed out immediately. This is a *secondary* safety net; the
 * primary enforcement is the disableUserAccount Cloud Function disabling
 * the Auth account itself (see AdminReportDetailActivity), which stops a
 * banned user from ever reaching this point at all in the normal case.
 * This check exists for when that call failed, didn't run yet, or the
 * account was banned by some other path — it can't rely on the ban having
 * already been enforced upstream.
 *
 * A failed read fails *open* to Home rather than stranding an authenticated
 * user behind a step they can't get past. The gate runs again on the next
 * launch, so a transient failure self-corrects.
 */
fun resolveSignedInDestination(
    firestore: FirebaseFirestore,
    uid: String,
    onResolved: (SignedInRouting) -> Unit
) {
    firestore.collection(UserProfile.COLLECTION).document(uid).get()
        .addOnSuccessListener { snapshot ->
            val profile = UserProfile.from(snapshot)
            if (profile.isBanned) {
                Log.i(TAG, "Signing out banned account $uid")
                FirebaseAuth.getInstance().signOut()
                onResolved(SignedInRouting.Banned)
                return@addOnSuccessListener
            }
            syncEmailIfChanged(firestore, uid, snapshot, profile)
            registerFcmToken(firestore, uid)
            onResolved(SignedInRouting.Allowed(nextSetupStepFor(profile)))
        }
        .addOnFailureListener { e ->
            Log.w(TAG, "Couldn't read profile for the setup gate; continuing to Home", e)
            onResolved(SignedInRouting.Allowed(HomeActivity::class.java))
        }
}

/**
 * Refreshes `users/{uid}.fcmToken` on every confirmed sign-in — not just
 * once on first grant — since a token can rotate at any time (reinstall,
 * app data clear, Play Services update, ...) and a stale one silently drops
 * every push to this device until something overwrites it.
 * [WedoraFirebaseMessagingService.onNewToken] covers a rotation that
 * happens while already signed in; this covers the token this device
 * already held becoming attached to this account in the first place. Runs
 * unconditionally on both callers of [resolveSignedInDestination] — a fresh
 * sign-in (LoginActivity) and a restored session (SplashActivity) — which
 * together are the closest thing this app has to "on every login." Failure
 * is logged only: a push is a nice-to-have, not worth blocking sign-in over.
 */
private fun registerFcmToken(firestore: FirebaseFirestore, uid: String) {
    FirebaseMessaging.getInstance().token
        .addOnSuccessListener { token ->
            firestore.collection(UserProfile.COLLECTION).document(uid)
                .set(mapOf(UserProfile.FIELD_FCM_TOKEN to token), SetOptions.merge())
                .addOnFailureListener { e -> Log.w(TAG, "Failed to save FCM token", e) }
        }
        .addOnFailureListener { e -> Log.w(TAG, "Failed to fetch FCM token", e) }
}

/**
 * The first setup step [profile] hasn't answered, or Home if it has answered
 * them all.
 *
 * Checked in step order so a partially-filled profile lands on its earliest
 * gap rather than its latest — a user missing both gender and age should be
 * asked for gender first, the same order a new account sees.
 *
 * Three steps are deliberately absent. The photo (ProfileStep5PhotoActivity)
 * is optional and device-local, so gating on it would send anyone who
 * skipped it back to the same screen on every launch, with no way to satisfy
 * a check that has nothing to read. Interests (ProfileStep6InterestsActivity)
 * is optional for the same reason — an empty list means either "skipped" or
 * "chose none," which are indistinguishable and both valid, so there's
 * nothing here that would ever resolve. The permissions primer
 * (ProfileStepPermissionsActivity) writes nothing to the profile at all —
 * there's no field to check even if it were the returning-user experience
 * this gate is for, and it isn't: it belongs only to a fresh signup passing
 * through the steps in order, not to someone resuming a partially-completed
 * one. A returning user with an age on file goes straight past all three.
 */
private fun nextSetupStepFor(profile: UserProfile): Class<*> = when {
    profile.displayName.isNullOrBlank() -> ProfileStep1NameActivity::class.java
    profile.gender.isNullOrBlank() -> ProfileStep2GenderActivity::class.java
    profile.myStatus.isNullOrBlank() -> ProfileStep3StatusActivity::class.java
    profile.age == null -> ProfileStep4DetailsActivity::class.java
    else -> HomeActivity::class.java
}

/**
 * Brings `users/{uid}.email` back in line with the Auth account's email.
 *
 * AccountSettings changes an email with verifyBeforeUpdateEmail, which only
 * takes effect when the user clicks the link in the new inbox — possibly on
 * another device, possibly days later. There is no client callback for that,
 * so the Firestore copy is stale from the moment the change completes until
 * something re-syncs it. This is that something.
 *
 * It lives here rather than in [LoginActivity] because sessions persist: after
 * verifying, the user is far more likely to relaunch into a session restored
 * by [SplashActivity] than to sign in again, and a login-only sync would
 * simply never run for them. Both entry points come through here, and the
 * snapshot is already in hand, so this costs no extra read.
 *
 * Skipped unless the document exists and already carries an age. The users
 * update rule requires the post-write document to hold a valid 18+ age, so a
 * merge onto an ageless document is denied — writing anyway would guarantee a
 * PERMISSION_DENIED on every launch for those accounts. They are routed to
 * Complete Profile regardless, and the sync lands on the launch after that.
 *
 * Failure is logged only: an out-of-date email field is not worth blocking
 * someone's sign-in over, and the next launch tries again.
 */
private fun syncEmailIfChanged(
    firestore: FirebaseFirestore,
    uid: String,
    snapshot: DocumentSnapshot,
    profile: UserProfile
) {
    if (!snapshot.exists() || profile.age == null) return

    val authEmail = FirebaseAuth.getInstance().currentUser?.email?.takeIf { it.isNotBlank() }
        ?: return
    if (authEmail.equals(profile.email, ignoreCase = true)) return

    Log.i(TAG, "Auth email differs from the stored profile email; re-syncing")
    firestore.collection(UserProfile.COLLECTION).document(uid)
        .set(mapOf(UserProfile.FIELD_EMAIL to authEmail), SetOptions.merge())
        .addOnFailureListener { e -> Log.w(TAG, "Failed to re-sync profile email", e) }
}
