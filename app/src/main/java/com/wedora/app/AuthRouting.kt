package com.wedora.app

import android.util.Log
import com.google.firebase.firestore.FirebaseFirestore

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
            onResolved(
                if (UserProfile.from(snapshot).isComplete) HomeActivity::class.java
                else CompleteProfileActivity::class.java
            )
        }
        .addOnFailureListener { e ->
            Log.w(TAG, "Couldn't read profile for completeness gate; continuing to Home", e)
            onResolved(HomeActivity::class.java)
        }
}
