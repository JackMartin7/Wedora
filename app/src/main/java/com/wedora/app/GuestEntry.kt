package com.wedora.app

import android.content.Intent
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.firebase.auth.FirebaseAuth

private const val TAG = "WedoraGuestEntry"

/**
 * Enter the app without a real account — shared by LoginActivity's and
 * SignUpActivity's "Continue as Guest" links, the only two entry points.
 *
 * Guests still need a Firebase Auth session — every Firestore read rule
 * requires `request.auth != null`, so without one the guest feed would fail
 * every query with PERMISSION_DENIED — so this signs in anonymously first.
 * GuestPrefs stays the source of truth for "is this a guest" everywhere in
 * the app; the anonymous session exists only so Firestore's own rules let a
 * guest's reads through, not as a second identity system. See
 * FirebaseAuth.realUid for how the rest of the app keeps an anonymous
 * session from being treated as a genuine one.
 *
 * A returning guest — same session, or the app reopened without logging
 * out — already has an anonymous session FirebaseAuth persists locally, so
 * there's no need to sign in again; and already has both GuestPrefs values
 * from a previous run through GuestGenderPromptActivity, so there's nothing
 * left to ask either — skip straight to Home rather than showing the same
 * prompt twice.
 *
 * [guestButton] is disabled for the duration of the anonymous sign-in (and
 * re-enabled on failure) so a slow network can't turn one tap into several
 * concurrent sign-in attempts.
 */
fun continueAsGuest(activity: AppCompatActivity, auth: FirebaseAuth, guestButton: View) {
    val existing = auth.currentUser
    if (existing != null && existing.isAnonymous) {
        proceedAsGuest(activity)
        return
    }

    guestButton.isEnabled = false
    auth.signInAnonymously()
        .addOnSuccessListener { proceedAsGuest(activity) }
        .addOnFailureListener { e ->
            guestButton.isEnabled = true
            Log.w(TAG, "Anonymous sign-in failed", e)
            Toast.makeText(activity, R.string.error_generic_login, Toast.LENGTH_LONG).show()
        }
}

private fun proceedAsGuest(activity: AppCompatActivity) {
    GuestPrefs.setGuest(activity)
    val alreadyAnswered =
        GuestPrefs.guestGender(activity) != null && GuestPrefs.guestInterestedIn(activity) != null
    val destination =
        if (alreadyAnswered) HomeActivity::class.java else GuestGenderPromptActivity::class.java
    activity.startActivity(Intent(activity, destination))
    activity.finish()
}
