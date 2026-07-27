package com.wedora.app

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.CountDownTimer
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.animation.OvershootInterpolator
import android.widget.Toast
import androidx.activity.addCallback
import com.google.firebase.auth.FirebaseAuth
import com.wedora.app.databinding.ActivityEmailVerificationBinding

// TODO: Replace Firebase default email sender with custom domain SMTP when available — this will fix spam classification permanently

/**
 * Post-sign-up screen telling the user to verify their email, with an explicit
 * nudge toward the spam folder (where these messages frequently land) and a
 * numbered walkthrough.
 *
 * The account stays signed in while this screen is up — deliberately — because
 * [FirebaseUser.sendEmailVerification] needs a current user for the Resend
 * action. That isn't a way into the app: an unverified session is stopped by
 * both SplashActivity (which routes only verified users onward) and
 * LoginActivity (which re-checks on every sign-in). Leaving for Login signs the
 * account out to restore a clean state.
 */
class EmailVerificationActivity : WedoraBaseActivity() {

    companion object {
        private const val EXTRA_EMAIL = "extra_email"

        /** How long the Resend link stays disabled after a send, in seconds. */
        private const val RESEND_COOLDOWN_SECONDS = 60L

        /** How often to re-check verification while the screen is in front. */
        private const val POLL_INTERVAL_MS = 3000L

        /** Beat on the success tick before navigating, so it's actually seen. */
        private const val SUCCESS_DELAY_MS = 1500L

        fun intent(context: Context, email: String): Intent =
            Intent(context, EmailVerificationActivity::class.java)
                .putExtra(EXTRA_EMAIL, email)
    }

    private lateinit var binding: ActivityEmailVerificationBinding
    private val auth: FirebaseAuth by lazy { FirebaseAuth.getInstance() }

    private var resendTimer: CountDownTimer? = null

    private val handler = Handler(Looper.getMainLooper())

    /** True only while the screen is resumed and should keep polling. */
    private var polling = false

    /** Latches once verification is detected, so the handoff runs exactly once. */
    private var verified = false

    private val checkVerificationRunnable = Runnable { checkVerification() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityEmailVerificationBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applyEdgeInsets(binding.root)

        val email = intent.getStringExtra(EXTRA_EMAIL).orEmpty()
        binding.tvSubtitle.text = getString(R.string.verify_subtitle, email)

        binding.btnOpenEmail.addPressScale()
        binding.btnOpenEmail.setOnClickListener { openEmailApp() }
        binding.tvResend.setOnClickListener { resendVerificationEmail() }
        binding.tvBackToLogin.setOnClickListener { backToLogin() }

        // Back should go to Login (and sign out) rather than dropping onto
        // whatever is under this screen while still signed in.
        onBackPressedDispatcher.addCallback(this) { backToLogin() }
    }

    // ----- Automatic verification polling ----------------------------------

    /**
     * Polls only while the screen is in front — started here rather than in
     * onCreate so it also picks up straight away when the user returns from
     * their email app having just clicked the link.
     */
    override fun onResume() {
        super.onResume()
        if (verified) return
        polling = true
        handler.postDelayed(checkVerificationRunnable, POLL_INTERVAL_MS)
    }

    /** No point burning battery reloading the token while backgrounded. */
    override fun onPause() {
        polling = false
        handler.removeCallbacks(checkVerificationRunnable)
        super.onPause()
    }

    /**
     * One poll: reload the user so the local token reflects a verification that
     * happened server-side, then either hand off or schedule the next check.
     * The completion can land after onPause (or after success), so it re-checks
     * [polling]/[verified] before doing anything.
     */
    private fun checkVerification() {
        val user = auth.currentUser ?: return
        user.reload().addOnCompleteListener {
            if (verified || !polling) return@addOnCompleteListener
            if (auth.currentUser?.isEmailVerified == true) {
                onEmailVerified()
            } else {
                handler.postDelayed(checkVerificationRunnable, POLL_INTERVAL_MS)
            }
        }
    }

    /**
     * Detected verification: stop polling, swap the spinner for the tick, say
     * so, and hand off to Login after a short beat. No sign-out here — the
     * account is now verified, and Login re-checks on sign-in regardless.
     */
    private fun onEmailVerified() {
        verified = true
        polling = false
        handler.removeCallbacks(checkVerificationRunnable)

        binding.progressWaiting.visibility = View.GONE
        binding.tvWaiting.setText(R.string.verify_success)
        binding.ivVerified.apply {
            visibility = View.VISIBLE
            scaleX = 0f
            scaleY = 0f
            animate()
                .scaleX(1f)
                .scaleY(1f)
                .setInterpolator(OvershootInterpolator())
                .setDuration(300)
                .start()
        }

        Toast.makeText(this, R.string.verify_success, Toast.LENGTH_SHORT).show()
        handler.postDelayed({ goToLoginVerified() }, SUCCESS_DELAY_MS)
    }

    private fun goToLoginVerified() {
        startActivity(
            Intent(this, LoginActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        finish()
    }

    /**
     * Jumps straight to the user's email app via the standard email category,
     * so they don't have to leave and find it themselves. Not every device has
     * a handler, hence the fallback.
     */
    private fun openEmailApp() {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_APP_EMAIL)
        try {
            startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, R.string.verify_no_email_app, Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * Resends the verification email and immediately starts the cooldown — the
     * countdown begins on tap, not on success, so a slow network can't be used
     * to fire several in a row.
     */
    private fun resendVerificationEmail() {
        val user = auth.currentUser ?: return
        startResendCooldown()
        user.sendEmailVerification().addOnCompleteListener(this) { task ->
            val msg =
                if (task.isSuccessful) R.string.verify_resent_toast
                else R.string.error_verification_send
            Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
        }
    }

    private fun startResendCooldown() {
        binding.tvResend.isEnabled = false
        resendTimer?.cancel()
        resendTimer = object : CountDownTimer(RESEND_COOLDOWN_SECONDS * 1000, 1000) {
            override fun onTick(millisUntilFinished: Long) {
                val seconds = (millisUntilFinished / 1000).toInt()
                binding.tvResend.text = getString(R.string.verify_resend_countdown, seconds)
            }

            override fun onFinish() {
                binding.tvResend.setText(R.string.verify_resend)
                binding.tvResend.isEnabled = true
            }
        }.start()
    }

    private fun backToLogin() {
        // Clean slate: the unverified session shouldn't linger once the user
        // heads to Login, where they'll sign in fresh.
        auth.signOut()
        startActivity(
            Intent(this, LoginActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
        )
        finish()
    }

    override fun onDestroy() {
        // removeCallbacksAndMessages(null) also clears the pending success
        // handoff posted as a lambda, which isn't the named runnable.
        handler.removeCallbacksAndMessages(null)
        resendTimer?.cancel()
        super.onDestroy()
    }
}
