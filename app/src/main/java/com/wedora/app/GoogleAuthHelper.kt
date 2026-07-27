package com.wedora.app

import android.app.Activity
import android.content.Intent
import android.util.Log
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInClient
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.ApiException
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.GoogleAuthProvider

private const val TAG = "WedoraGoogleAuth"

/**
 * Shared "Continue with Google" wiring for LoginActivity and SignUpActivity.
 * Both screens offer the same entry point into the same account system, so
 * the client setup, the launcher, and what happens with the result are
 * identical regardless of which one the user tapped it from — only what a
 * caller does once [onSignedIn] fires differs (Login shows a "Welcome back"
 * toast where Sign Up wouldn't), so that part stays with each Activity.
 *
 * Must be constructed during [WedoraBaseActivity.onCreate], before the
 * Activity reaches STARTED — same constraint as any other
 * registerForActivityResult call, and the reason this isn't built lazily
 * on first tap of the button.
 *
 * Deliberately does not write anything to the Firestore profile doc, and
 * does not check additionalUserInfo?.isNewUser to decide whether to. A new
 * account with no doc at all already routes to ProfileStep1NameActivity via
 * the normal resolveSignedInDestination gate, the same as any other fresh
 * sign-up — and that screen already pre-fills its name field from
 * auth.currentUser.displayName, which Firebase itself populates from the
 * Google account automatically on this kind of sign-in, no extra write
 * needed. Pre-filling Firestore's displayName directly (an earlier version
 * of this class did) would have made nextSetupStepFor treat the name as
 * already answered and skip Step 1 outright — silently accepting whatever
 * Google's account name happens to be for a profile the user is meant to
 * consciously choose. Letting Step 1 run normally, just with a suggested
 * value already typed in, is the fix: confirm or edit, not accept blind.
 * ProfileStep5PhotoActivity applies the identical treatment to
 * auth.currentUser.photoUrl for the same reason.
 */
class GoogleAuthHelper(
    private val activity: WedoraBaseActivity,
    private val onSignedIn: (uid: String) -> Unit,
    private val onCancelled: () -> Unit,
    private val onError: () -> Unit
) {
    private val auth: FirebaseAuth by lazy { FirebaseAuth.getInstance() }

    private val googleSignInClient: GoogleSignInClient by lazy {
        val options = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestIdToken(activity.getString(R.string.default_web_client_id))
            .requestEmail()
            .build()
        GoogleSignIn.getClient(activity, options)
    }

    private val launcher: ActivityResultLauncher<Intent> = activity.registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        // Backing out of the account chooser is a normal dismissal
        // (RESULT_CANCELED), not a failure — same as backing out of any
        // other sheet, it just returns to the screen as if nothing happened.
        if (result.resultCode != Activity.RESULT_OK) {
            onCancelled()
            return@registerForActivityResult
        }

        try {
            val account = GoogleSignIn.getSignedInAccountFromIntent(result.data)
                .getResult(ApiException::class.java)
            val idToken = account.idToken
            if (idToken == null) {
                Log.w(TAG, "Google account returned no ID token")
                onError()
                return@registerForActivityResult
            }
            signInToFirebase(idToken)
        } catch (e: ApiException) {
            Log.w(TAG, "Google sign-in failed", e)
            onError()
        }
    }

    fun launch() {
        launcher.launch(googleSignInClient.signInIntent)
    }

    private fun signInToFirebase(idToken: String) {
        val credential = GoogleAuthProvider.getCredential(idToken, null)
        auth.signInWithCredential(credential)
            .addOnSuccessListener { result ->
                val user = result.user
                if (user == null) {
                    Log.w(TAG, "signInWithCredential succeeded with no user")
                    onError()
                    return@addOnSuccessListener
                }

                // A Google account skips email verification entirely — Google
                // already verified it, and FirebaseUser.isEmailVerified is
                // already true for a Google-provider sign-in without any
                // extra call here; this is not re-checked or re-gated on
                // anywhere in the routing this hands off to.
                GuestPrefs.clearGuest(activity)
                onSignedIn(user.uid)
            }
            .addOnFailureListener { e ->
                Log.w(TAG, "Firebase credential sign-in failed", e)
                onError()
            }
    }
}
