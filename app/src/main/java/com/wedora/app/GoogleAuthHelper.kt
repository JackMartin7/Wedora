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
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions

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
 * [onSignedIn] receives the signed-in UID and whether this was a new
 * account — a new account has already had its Firestore profile prefilled
 * (see [prefillProfile]) with whatever Google supplied by the time this
 * fires, so the caller's job is just to route, via the same
 * [resolveSignedInDestination] gate both Login's own email/password path
 * and SplashActivity already use. Routing through that shared gate rather
 * than a special "new user" destination is what makes the prefill actually
 * matter: nextSetupStepFor reads the same displayName field this just
 * wrote, so a Google account that supplied one skips
 * ProfileStep1NameActivity automatically, with no separate "skip step 1"
 * branch needed here.
 */
class GoogleAuthHelper(
    private val activity: WedoraBaseActivity,
    private val onSignedIn: (uid: String) -> Unit,
    private val onCancelled: () -> Unit,
    private val onError: () -> Unit
) {
    private val auth: FirebaseAuth by lazy { FirebaseAuth.getInstance() }
    private val firestore: FirebaseFirestore by lazy { FirebaseFirestore.getInstance() }

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
            signInToFirebase(idToken, account.displayName, account.photoUrl?.toString())
        } catch (e: ApiException) {
            Log.w(TAG, "Google sign-in failed", e)
            onError()
        }
    }

    fun launch() {
        launcher.launch(googleSignInClient.signInIntent)
    }

    private fun signInToFirebase(idToken: String, displayName: String?, photoUrl: String?) {
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

                if (result.additionalUserInfo?.isNewUser == true) {
                    prefillProfile(user.uid, displayName, photoUrl) {
                        onSignedIn(user.uid)
                    }
                } else {
                    onSignedIn(user.uid)
                }
            }
            .addOnFailureListener { e ->
                Log.w(TAG, "Firebase credential sign-in failed", e)
                onError()
            }
    }

    /**
     * Seeds displayName/photoUrl from the Google account onto the
     * brand-new profile doc, so ProfileStep1NameActivity — reached via the
     * same [resolveSignedInDestination] gate the caller uses once this
     * finishes — sees a name already on file and is skipped, the same as a
     * returning user who already answered it. set(merge) onto a document
     * that doesn't exist yet still succeeds (Firestore treats it as a
     * create) and matches every other step-write in this flow.
     *
     * Only writes the fields Google actually supplied — either can be
     * null/blank depending on the account's own privacy settings, and an
     * absent field here just means Step 1 asks for a name normally, not a
     * write of an empty string that would need to be distinguished from
     * "not answered yet" everywhere else that checks it.
     *
     * Google's photo URL is stored directly as photoUrl rather than
     * downloaded and re-uploaded through the Hostinger flow
     * (PhotoUploadService): it's already a stable, permanently-hosted HTTPS
     * URL Google itself serves, so re-hosting it would just be a slower path
     * to a string with the same shape and no benefit — that extra hop only
     * pays for itself for a device-local file that has no URL yet, which
     * this isn't.
     */
    private fun prefillProfile(
        uid: String,
        displayName: String?,
        photoUrl: String?,
        onDone: () -> Unit
    ) {
        val fields = buildMap<String, Any> {
            if (!displayName.isNullOrBlank()) put(UserProfile.FIELD_DISPLAY_NAME, displayName)
            if (!photoUrl.isNullOrBlank()) put(UserProfile.FIELD_PHOTO_URL, photoUrl)
            auth.currentUser?.email?.let { put(UserProfile.FIELD_EMAIL, it) }
        }
        if (fields.isEmpty()) {
            onDone()
            return
        }

        firestore.collection(UserProfile.COLLECTION).document(uid)
            .set(fields, SetOptions.merge())
            .addOnCompleteListener {
                if (!it.isSuccessful) {
                    Log.w(TAG, "Failed to prefill profile from Google account", it.exception)
                }
                // Not worth failing the sign-in over — the missing fields
                // just get asked for normally on whichever step reads them.
                onDone()
            }
    }
}
