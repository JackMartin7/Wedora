package com.wedora.app

import android.util.Log
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions

private const val TAG = "WedoraAuth"

/**
 * Decides where a signed-in, email-verified user belongs: Home if their
 * profile is finished, otherwise the first setup step they haven't answered.
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
 * A failed read fails *open* to Home rather than stranding an authenticated
 * user behind a step they can't get past. The gate runs again on the next
 * launch, so a transient failure self-corrects.
 */
fun resolveSignedInDestination(
    firestore: FirebaseFirestore,
    uid: String,
    onResolved: (Class<*>) -> Unit
) {
    firestore.collection(UserProfile.COLLECTION).document(uid).get()
        .addOnSuccessListener { snapshot ->
            val profile = UserProfile.from(snapshot)
            syncEmailIfChanged(firestore, uid, snapshot, profile)
            onResolved(nextSetupStepFor(profile))
        }
        .addOnFailureListener { e ->
            Log.w(TAG, "Couldn't read profile for the setup gate; continuing to Home", e)
            onResolved(HomeActivity::class.java)
        }
}

/**
 * The first setup step [profile] hasn't answered, or Home if it has answered
 * them all.
 *
 * Checked in step order so a partially-filled profile lands on its earliest
 * gap rather than its latest — a user missing both gender and age should be
 * asked for gender first, the same order a new account sees.
 *
 * Two steps are deliberately absent. The photo (ProfileStep5PhotoActivity) is
 * optional and device-local, so gating on it would send anyone who skipped it
 * back to the same screen on every launch, with no way to satisfy a check
 * that has nothing to read. The permissions primer
 * (ProfileStepPermissionsActivity) writes nothing to the profile at all —
 * there's no field to check even if it were the returning-user experience
 * this gate is for, and it isn't: it belongs only to a fresh signup passing
 * through the steps in order, not to someone resuming a partially-completed
 * one. A returning user with an age on file goes straight past it.
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
