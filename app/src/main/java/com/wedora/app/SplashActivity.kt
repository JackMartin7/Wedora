package com.wedora.app

import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.widget.Toast
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.wedora.app.databinding.ActivitySplashBinding

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
class SplashActivity : WedoraBaseActivity() {

    // The brand gradient (bg_splash_gradient) sits behind both system bars
    // regardless of light/dark theme, so this never follows
    // @bool/wedora_light_status_bar the way every other screen does.
    override val isLightSystemBars = false

    private companion object {
        /**
         * Matches the motion spec's own "3-second master timeline" — the
         * progress bar (0.1s delay + 2.9s fill) is timed to finish right at
         * this cutoff, so shortening this would visibly truncate it.
         */
        const val SPLASH_DELAY_MS = 3000L

        /**
         * Backstop so a lookup that never settles can't strand the user on the
         * splash screen. Firestore's offline cache normally makes the read
         * resolve or fail quickly, so this should not fire in practice.
         */
        const val RESOLVE_TIMEOUT_MS = 6000L
    }

    private lateinit var binding: ActivitySplashBinding
    private val firestore: FirebaseFirestore by lazy { FirebaseFirestore.getInstance() }
    private val handler = Handler(Looper.getMainLooper())

    private var minimumDelayElapsed = false
    private var destination: Class<*>? = null
    private var navigated = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySplashBinding.inflate(layoutInflater)
        setContentView(binding.root)

        playSplashTimeline()

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

    /**
     * The full timed sequence from the splash motion spec — every duration
     * and delay below is taken directly from that spec's timing table, not
     * guessed. See [Motion] for what each named animation actually does.
     */
    private fun playSplashTimeline() {
        // Reduced motion has to short-circuit the WHOLE timeline, not rely on
        // each animator individually no-opping: the sequence below pre-sets
        // hidden states (alpha 0 / scale 0 / width 0) and depends on its
        // animators to reveal them, so with animators disabled the splash
        // renders as a completely blank gradient — confirmed on-device before
        // this guard existed.
        if (Motion.reducedMotion(binding.root)) {
            showSplashAtRest()
            return
        }

        // wd-bg-drift: scale 1.06 -> 1.0 over the full 3s.
        binding.bgDrift.scaleX = 1.06f
        binding.bgDrift.scaleY = 1.06f
        binding.bgDrift.animate()
            .scaleX(1f).scaleY(1f)
            .setDuration(3000).setStartDelay(0)
            .setInterpolator(Motion.SMOOTH_DECEL)
            .start()

        // wd-ring x2, staggered.
        Motion.pulse(binding.ring1, 0.6f, 2.4f, 0.55f, 0f, durationMs = 1100, delayMs = 420)
        Motion.pulse(binding.ring2, 0.6f, 2.4f, 0.55f, 0f, durationMs = 1100, delayMs = 620)

        // wd-heart-pop: scale+rotate+fade in one bounce, approximating the
        // spec's 5-keyframe overshoot with a single spring curve (see
        // Motion's own doc comment on why that's equivalent in practice).
        binding.heartLogo.apply {
            alpha = 0f; scaleX = 0f; scaleY = 0f; rotation = -18f
        }
        binding.heartLogo.animate()
            .alpha(1f).scaleX(1f).scaleY(1f).rotation(0f)
            .setDuration(620).setStartDelay(0)
            .setInterpolator(Motion.BOUNCE)
            .withEndAction { playHeartBeat() }
            .start()

        // wd-write: wordmark typewriter reveal. wordmarkText's natural width
        // is measured off the layout pass (wordmarkClip starts at width 0, so
        // an in-layout measure would come back 0) and then PINNED onto the
        // TextView, so the shrinking clip never re-wraps its text — see the
        // layout's own comment on that.
        binding.wordmarkText.measure(
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )
        val wordmarkWidthPx = binding.wordmarkText.measuredWidth
        binding.wordmarkText.layoutParams = binding.wordmarkText.layoutParams.apply {
            width = wordmarkWidthPx
        }
        Motion.stepRevealWidth(
            binding.wordmarkClip, wordmarkWidthPx,
            durationMs = 1000, delayMs = 550, steps = 14
        )

        blinkCaret()

        // wd-fade-up (tagline) / plain ease-out (loading dots).
        Motion.riseUp(binding.tagline, durationMs = 600, delayMs = 1550, fromYDp = 14f)
        Motion.riseUp(
            binding.loadingDots, durationMs = 500, delayMs = 2100, fromYDp = 14f,
            interpolator = DecelerateInterpolator()
        )

        // wd-bar: progress fill 0 -> track width.
        binding.progressFill.post {
            val trackWidthPx = (binding.progressFill.parent as View).width
            Motion.revealWidth(binding.progressFill, trackWidthPx, durationMs = 2900, delayMs = 100, interpolator = Motion.STANDARD)
        }
    }

    /**
     * The splash exactly as the timeline would leave it once finished, applied
     * in one shot for the reduced-motion path: same composition, no movement.
     * The two pulse rings stay at alpha 0 — they only ever exist mid-ripple,
     * so their "at rest" state is genuinely invisible.
     */
    private fun showSplashAtRest() {
        binding.bgDrift.scaleX = 1f
        binding.bgDrift.scaleY = 1f
        binding.ring1.alpha = 0f
        binding.ring2.alpha = 0f
        binding.heartLogo.apply {
            alpha = 1f; scaleX = 1f; scaleY = 1f; rotation = 0f
        }
        binding.caret.alpha = 0f
        binding.tagline.apply { alpha = 1f; translationY = 0f }
        binding.loadingDots.apply { alpha = 1f; translationY = 0f }

        binding.wordmarkText.measure(
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )
        val wordmarkWidthPx = binding.wordmarkText.measuredWidth
        binding.wordmarkText.layoutParams = binding.wordmarkText.layoutParams.apply {
            width = wordmarkWidthPx
        }
        binding.wordmarkClip.layoutParams = binding.wordmarkClip.layoutParams.apply {
            width = wordmarkWidthPx
        }

        binding.progressFill.post {
            val trackWidthPx = (binding.progressFill.parent as View).width
            binding.progressFill.layoutParams = binding.progressFill.layoutParams.apply {
                width = trackWidthPx
            }
        }
    }

    /** wd-heart-beat: two gentle heartbeats after the pop-in lands, starting 0.9s into the timeline. */
    private fun playHeartBeat() {
        if (Motion.reducedMotion(binding.heartLogo)) return
        val pvhX = PropertyValuesHolder.ofFloat(View.SCALE_X, 1f, 1.12f, 1f)
        val pvhY = PropertyValuesHolder.ofFloat(View.SCALE_Y, 1f, 1.12f, 1f)
        ObjectAnimator.ofPropertyValuesHolder(binding.heartLogo, pvhX, pvhY).apply {
            duration = 1100
            // Delay is relative to the timeline start (0.9s), not to when
            // the pop-in's own 0.62s duration already finished.
            startDelay = (900 - 620).coerceAtLeast(0).toLong()
            interpolator = AccelerateDecelerateInterpolator()
            repeatCount = 1 // 1 repeat + the initial play = 2 iterations total.
            start()
        }
    }

    /**
     * wd-caret: a blinking `|` — implemented as a discrete on/off toggle loop
     * (matching the spec's `steps(1,end)` instant-flip interpolator, which a
     * smooth ObjectAnimator fade can't reproduce) rather than an animator.
     * Blinks 4 full cycles (8 toggles) then hides for good, matching
     * wd-caret-out's fade-to-0 right after the blinking ends.
     */
    private fun blinkCaret() {
        if (Motion.reducedMotion(binding.caret)) return
        val blinkIntervalMs = 210L
        var blinkCount = 0
        val totalToggles = 8
        lateinit var toggle: () -> Unit
        toggle = {
            binding.caret.alpha = if (binding.caret.alpha == 0f) 1f else 0f
            blinkCount++
            if (blinkCount < totalToggles) {
                handler.postDelayed(toggle, blinkIntervalMs)
            } else {
                binding.caret.alpha = 0f
            }
        }
        handler.postDelayed({
            binding.caret.alpha = 1f
            handler.postDelayed(toggle, blinkIntervalMs)
        }, 550)
    }

    private fun resolveDestination() {
        val user = FirebaseAuth.getInstance().currentUser

        when {
            // A persisted session goes straight into the app. Unverified
            // accounts deliberately fall through to Login, which is where the
            // "verify your email" prompt and its resend action live.
            //
            // !user.isAnonymous is explicit rather than relied-on-implicitly:
            // an anonymous guest session's isEmailVerified is also always
            // false (there's no email to verify), so this condition would
            // already exclude guests without it — but leaving that
            // incidental would mean a guest silently starting to route
            // through the real-account profile-completion steps the moment
            // Firebase ever changed that default. Guests are routed by the
            // GuestPrefs branch below instead.
            user != null && !user.isAnonymous && user.isEmailVerified ->
                resolveSignedInDestination(firestore, user.uid) { routing ->
                    when (routing) {
                        is SignedInRouting.Allowed -> setDestination(routing.destination)
                        SignedInRouting.Banned -> {
                            Toast.makeText(
                                this, R.string.error_account_suspended, Toast.LENGTH_LONG
                            ).show()
                            setDestination(LoginActivity::class.java)
                        }
                    }
                }

            // A guest who reopened the app stays a guest — no bounce to Login.
            // Their anonymous Firebase session (see LoginActivity.continueAsGuest)
            // already persisted across the restart same as a real one would;
            // this is what actually routes them, not the branch above.
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
