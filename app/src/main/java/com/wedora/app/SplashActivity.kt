package com.wedora.app

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.appcompat.app.AppCompatActivity
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore

/**
 * Decides where to send the user on launch.
 *
 * Firebase Auth persists sessions locally by default, so a signed-in user is
 * still signed in after the process dies — this screen is what actually acts
 * on that, skipping Login entirely. Logging out (from Profile) is the only
 * thing that sends a real account back to Login.
 *
 * The destination is resolved in parallel with the branding delay rather than
 * after it, so the profile lookup usually costs nothing in wall-clock time.
 */
class SplashActivity : AppCompatActivity() {

    private companion object {
        const val SPLASH_DELAY_MS = 1800L

        /**
         * Backstop so a lookup that never settles can't strand the user on the
         * splash screen. Firestore's offline cache normally makes the read
         * resolve or fail quickly, so this should not fire in practice.
         */
        const val RESOLVE_TIMEOUT_MS = 6000L
    }

    private val firestore: FirebaseFirestore by lazy { FirebaseFirestore.getInstance() }
    private val handler = Handler(Looper.getMainLooper())

    private var minimumDelayElapsed = false
    private var destination: Class<*>? = null
    private var navigated = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_splash)

        handler.postDelayed({
            minimumDelayElapsed = true
            navigateIfReady()
        }, SPLASH_DELAY_MS)

        handler.postDelayed({
            if (destination == null) {
                minimumDelayElapsed = true
                setDestination(HomeActivity::class.java)
            }
        }, RESOLVE_TIMEOUT_MS)

        resolveDestination()
    }

    private fun resolveDestination() {
        val user = FirebaseAuth.getInstance().currentUser

        when {
            // A persisted session goes straight into the app. Unverified
            // accounts deliberately fall through to Login, which is where the
            // "verify your email" prompt and its resend action live.
            user != null && user.isEmailVerified ->
                resolveSignedInDestination(firestore, user.uid) { setDestination(it) }

            // A guest who reopened the app stays a guest — no bounce to Login.
            GuestPrefs.isGuest(this) ->
                setDestination(HomeActivity::class.java)

            !OnboardingPrefs.isOnboardingComplete(this) ->
                setDestination(OnboardingActivity::class.java)

            else ->
                setDestination(LoginActivity::class.java)
        }
    }

    private fun setDestination(next: Class<*>) {
        destination = next
        navigateIfReady()
    }

    /** Navigates once both the branding delay and the lookup have finished. */
    private fun navigateIfReady() {
        val next = destination ?: return
        if (!minimumDelayElapsed || navigated) return

        navigated = true
        startActivity(Intent(this, next))
        finish()
        applyHandoffTransition()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }
}
