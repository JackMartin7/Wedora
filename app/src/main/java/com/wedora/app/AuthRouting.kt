package com.wedora.app

import android.util.Log
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions

private const val TAG = "WedoraAuth"

/**
 * Decides where a signed-in, email-verified user belongs: Home if their
 * profile is complete, CompleteProfile if age/city/country are still missing.
 *
 * Shared by [SplashActivity], which restores a persisted session on launch,
 * and [LoginActivity], which handles a fresh sign-in — so the completeness
 * gate can't drift between the two ways into the app.
 *
 * A failed read fails *open* to Home rather than stranding an authenticated
 * user. The gate runs again on the next launch (via Splash), so a transient
 * failure self-corrects.
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
            onResolved(
                if (profile.isComplete) HomeActivity::class.java
                else CompleteProfileActivity::class.java
            )
        }
        .addOnFailureListener { e ->
            Log.w(TAG, "Couldn't read profile for completeness gate; continuing to Home", e)
            onResolved(HomeActivity::class.java)
        }
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
