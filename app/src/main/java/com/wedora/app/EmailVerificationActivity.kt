package com.wedora.app

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.CountDownTimer
import android.widget.Toast
import androidx.activity.addCallback
import androidx.appcompat.app.AppCompatActivity
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
class EmailVerificationActivity : AppCompatActivity() {

    companion object {
        private const val EXTRA_EMAIL = "extra_email"

        /** How long the Resend link stays disabled after a send, in seconds. */
        private const val RESEND_COOLDOWN_SECONDS = 60L

        fun intent(context: Context, email: String): Intent =
            Intent(context, EmailVerificationActivity::class.java)
                .putExtra(EXTRA_EMAIL, email)
    }

    private lateinit var binding: ActivityEmailVerificationBinding
    private val auth: FirebaseAuth by lazy { FirebaseAuth.getInstance() }

    private var resendTimer: CountDownTimer? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityEmailVerificationBinding.inflate(layoutInflater)
        setContentView(binding.root)

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
        resendTimer?.cancel()
        super.onDestroy()
    }
}
