package com.wedora.app

import android.content.Intent
import android.os.Bundle
import android.text.SpannableString
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.graphics.Typeface
import android.text.method.HideReturnsTransformationMethod
import android.text.method.PasswordTransformationMethod
import android.util.Patterns
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthInvalidCredentialsException
import com.google.firebase.auth.FirebaseAuthInvalidUserException
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.FirebaseNetworkException
import com.wedora.app.databinding.ActivityLoginBinding

class LoginActivity : WedoraBaseActivity(), EmailNotVerifiedBottomSheet.Host {

    private lateinit var binding: ActivityLoginBinding
    private val auth: FirebaseAuth by lazy { FirebaseAuth.getInstance() }
    private val firestore: FirebaseFirestore by lazy { FirebaseFirestore.getInstance() }
    private var isPasswordVisible = false

    private lateinit var googleAuthHelper: GoogleAuthHelper

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLoginBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applyEdgeInsets(binding.root, applyIme = true)

        googleAuthHelper = GoogleAuthHelper(
            activity = this,
            onSignedIn = { uid ->
                // Guest state and routing are exactly what a successful
                // email/password sign-in already does below (onSignInSuccess)
                // — this joins that same path rather than duplicating it.
                // The overlay stays up through routeAfterSignIn's Firestore
                // check; goTo()/the Banned branch clear it.
                routeAfterSignIn(uid)
            },
            onCancelled = {
                binding.btnGoogle.isEnabled = true
                setGoogleLoading(false)
            },
            onError = {
                binding.btnGoogle.isEnabled = true
                setGoogleLoading(false)
                Toast.makeText(this, R.string.error_google_sign_in_failed, Toast.LENGTH_LONG).show()
            }
        )
        binding.btnGoogle.setOnClickListener {
            binding.btnGoogle.isEnabled = false
            // Covers the whole chain — intent launch, token exchange, Firebase
            // sign-in, Firestore profile-completeness check, navigate — not
            // just the initial tap, since that chain can take a few seconds.
            setGoogleLoading(true)
            googleAuthHelper.launch()
        }

        setUpSignUpPrompt()

        binding.btnBack.setOnClickListener { onBackPressedDispatcher.onBackPressed() }

        binding.btnTogglePassword.setOnClickListener { togglePasswordVisibility() }

        binding.tvSignUpPrompt.setOnClickListener {
            startActivity(Intent(this, SignUpActivity::class.java))
        }

        binding.tvForgotPassword.setOnClickListener {
            startActivity(Intent(this, ForgotPasswordActivity::class.java))
        }

        binding.btnLogin.setOnClickListener { attemptLogin() }
        binding.btnLogin.addPressScale()

        val watcher = SimpleTextWatcher { updateLoginEnabled() }
        binding.etEmail.addTextChangedListener(watcher)
        binding.etPassword.addTextChangedListener(watcher)
        updateLoginEnabled()

        binding.tvContinueAsGuest.setOnClickListener {
            continueAsGuest(this, auth, binding.tvContinueAsGuest)
        }

        playEntranceTimeline()
    }

    /**
     * Brand mark, headline, subline and the card rise in on a light stagger —
     * the same rise-up/decelerate language the splash and onboarding screens
     * already use (see Motion.riseUp), just applied to this screen's own
     * elements. Motion.reducedMotion is checked inside riseUp itself, so
     * there's nothing extra to guard here.
     */
    private fun playEntranceTimeline() {
        Motion.riseUp(binding.ivAuthMark, durationMs = 500, delayMs = 0, fromYDp = 16f)
        Motion.riseUp(binding.tvTitle, durationMs = 500, delayMs = 60, fromYDp = 16f)
        Motion.riseUp(binding.tvSubtitle, durationMs = 500, delayMs = 100, fromYDp = 16f)
        Motion.riseUp(binding.authCard, durationMs = 550, delayMs = 160, fromYDp = 16f)
    }

    /**
     * Login is enabled only once both fields have something in them. The full
     * email/password validation still runs on tap — this gate just keeps the
     * button from inviting a tap that can only fail on an empty form.
     */
    private fun updateLoginEnabled() {
        binding.btnLogin.isEnabled =
            binding.etEmail.text.isNotBlank() &&
            binding.etPassword.text.isNotEmpty()
    }

    private fun attemptLogin() {
        val email = binding.etEmail.text.toString().trim()
        val password = binding.etPassword.text.toString()

        if (email.isEmpty()) {
            binding.etEmail.error = getString(R.string.error_email_required)
            binding.etEmail.requestFocus()
            return
        }
        if (!Patterns.EMAIL_ADDRESS.matcher(email).matches()) {
            binding.etEmail.error = getString(R.string.error_invalid_email)
            binding.etEmail.requestFocus()
            return
        }
        if (password.isEmpty()) {
            binding.etPassword.error = getString(R.string.error_password_required)
            binding.etPassword.requestFocus()
            return
        }

        setLoading(true)
        auth.signInWithEmailAndPassword(email, password)
            .addOnCompleteListener(this) { task ->
                if (task.isSuccessful) {
                    // Stays in the loading state until routeAfterSignIn navigates,
                    // so the profile-completeness read can't be double-tapped.
                    onSignInSuccess()
                } else {
                    setLoading(false)
                    Toast.makeText(this, loginErrorMessage(task.exception), Toast.LENGTH_LONG).show()
                }
            }
    }

    private fun onSignInSuccess() {
        val user = auth.currentUser
        if (user == null) {
            setLoading(false)
            Toast.makeText(this, R.string.error_generic_login, Toast.LENGTH_LONG).show()
            return
        }

        if (user.isEmailVerified) {
            // Signing into a real account ends any prior guest session.
            // Without this the flag would survive, and the guest gates on Home
            // would keep bouncing a genuinely signed-in user to Sign Up.
            GuestPrefs.clearGuest(this)
            Toast.makeText(this, R.string.login_success, Toast.LENGTH_SHORT).show()
            routeAfterSignIn(user.uid)
        } else {
            setLoading(false)
            promptEmailVerification()
        }
    }

    /**
     * Sends the user to the first profile step they haven't answered, or
     * straight to Home once they've answered them all.
     *
     * An account with no Firestore document at all — one created by Sign Up,
     * which writes none — reads as missing every field, so it lands on step 1.
     * The same is true of accounts predating these fields, which is what lets
     * them through the new flow without a migration.
     *
     * Shares [resolveSignedInDestination] with SplashActivity, which applies
     * the same gate when it restores a persisted session — since sessions
     * persist, a signed-in user no longer passes back through this screen on
     * every launch, so the gate has to live somewhere both entry points reach.
     */
    private fun routeAfterSignIn(uid: String) {
        resolveSignedInDestination(firestore, uid) { routing ->
            when (routing) {
                is SignedInRouting.Allowed -> goTo(routing.destination)
                // Already signed out by resolveSignedInDestination itself;
                // nothing to navigate to since Login is where this already
                // is — just stop the spinner and say why.
                SignedInRouting.Banned -> {
                    setLoading(false)
                    setGoogleLoading(false)
                    Toast.makeText(this, R.string.error_account_suspended, Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    /**
     * Signing in hands the app over rather than pushing a screen, so it fades
     * instead of sliding — the user can't come back through Login.
     */
    private fun goTo(destination: Class<*>) {
        setLoading(false)
        setGoogleLoading(false)
        startActivity(Intent(this, destination))
        finish()
        applyHandoffTransition()
    }

    /**
     * The account exists but the address is unverified. We keep the user signed in
     * (signing out would make [FirebaseUser.sendEmailVerification] unavailable) but
     * deliberately do not navigate onwards — the sheet offers a resend and a
     * re-check instead.
     */
    private fun promptEmailVerification() {
        EmailNotVerifiedBottomSheet().show(supportFragmentManager, "email_not_verified")
    }

    /** Resend from the sheet. The sheet owns the cooldown; this just sends. */
    override fun onResendVerificationRequested() {
        val user = auth.currentUser ?: return
        user.sendEmailVerification().addOnCompleteListener(this) { task ->
            val msg =
                if (task.isSuccessful) R.string.verify_resent_toast
                else R.string.error_verification_send
            Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
        }
    }

    /**
     * "I've Verified, Try Again": reload the user so the freshly-verified state
     * is reflected, then continue exactly as a normal sign-in would. Still
     * unverified is a normal outcome, not an error — say so and leave them here.
     */
    override fun onVerifiedRetryRequested() {
        val user = auth.currentUser ?: return
        setLoading(true)
        user.reload().addOnCompleteListener(this) {
            if (auth.currentUser?.isEmailVerified == true) {
                onSignInSuccess()
            } else {
                setLoading(false)
                Toast.makeText(this, R.string.verify_sheet_still_unverified, Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun loginErrorMessage(e: Exception?): String {
        val resId = when (e) {
            is FirebaseAuthInvalidCredentialsException -> R.string.error_wrong_password
            is FirebaseAuthInvalidUserException -> R.string.error_no_account
            is FirebaseNetworkException -> R.string.error_network
            else -> R.string.error_generic_login
        }
        return getString(resId)
    }

    private fun setLoading(loading: Boolean) {
        binding.btnLogin.setText(if (loading) R.string.btn_logging_in else R.string.btn_login)
        // Off the loading path, re-apply the empty-field gate rather than blanket
        // re-enabling, so a failed attempt doesn't leave Login tappable on a
        // form the user has since cleared.
        if (loading) binding.btnLogin.isEnabled = false else updateLoginEnabled()
    }

    /** Full-chain overlay for Google Sign-In — see layout_auth_loading_overlay.xml. */
    private fun setGoogleLoading(loading: Boolean) {
        binding.authLoading.authLoadingOverlay.visibility =
            if (loading) android.view.View.VISIBLE else android.view.View.GONE
    }

    private fun togglePasswordVisibility() {
        isPasswordVisible = !isPasswordVisible
        if (isPasswordVisible) {
            binding.etPassword.transformationMethod = HideReturnsTransformationMethod.getInstance()
        } else {
            binding.etPassword.transformationMethod = PasswordTransformationMethod.getInstance()
        }
        binding.etPassword.setSelection(binding.etPassword.text?.length ?: 0)
    }

    private fun setUpSignUpPrompt() {
        val prompt = getString(R.string.no_account_prompt)
        val link = getString(R.string.sign_up_link)
        val full = prompt + link

        val spannable = SpannableString(full)
        val linkColor = ContextCompat.getColor(this, R.color.wedora_accent)

        spannable.setSpan(
            ForegroundColorSpan(linkColor),
            prompt.length,
            full.length,
            SpannableString.SPAN_EXCLUSIVE_EXCLUSIVE
        )
        spannable.setSpan(
            StyleSpan(Typeface.BOLD),
            prompt.length,
            full.length,
            SpannableString.SPAN_EXCLUSIVE_EXCLUSIVE
        )

        binding.tvSignUpPrompt.text = spannable
    }
}
