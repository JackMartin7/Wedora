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
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.snackbar.Snackbar
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthInvalidCredentialsException
import com.google.firebase.auth.FirebaseAuthInvalidUserException
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.FirebaseNetworkException
import com.wedora.app.databinding.ActivityLoginBinding

class LoginActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLoginBinding
    private val auth: FirebaseAuth by lazy { FirebaseAuth.getInstance() }
    private val firestore: FirebaseFirestore by lazy { FirebaseFirestore.getInstance() }
    private var isPasswordVisible = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLoginBinding.inflate(layoutInflater)
        setContentView(binding.root)

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

        binding.tvContinueAsGuest.setOnClickListener { continueAsGuest() }

        binding.btnGoogle.setOnClickListener { /* TODO: Google sign-in */ }
        binding.btnFacebook.setOnClickListener { /* TODO: Facebook sign-in */ }
    }

    /** Enter the app without an account. Guests get a read-only feed. */
    private fun continueAsGuest() {
        GuestPrefs.setGuest(this)
        startActivity(Intent(this, HomeActivity::class.java))
        finish()
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
     * Sends the user to Complete Profile if their Firestore doc is missing
     * age/city/country, otherwise straight to Home.
     *
     * Accounts predating the Firestore user-doc feature have no document at
     * all; [UserProfile.from] reports those as incomplete, which correctly
     * routes them through the completion step.
     *
     * Shares [resolveSignedInDestination] with SplashActivity, which applies
     * the same gate when it restores a persisted session — since sessions
     * persist, a signed-in user no longer passes back through this screen on
     * every launch, so the gate has to live somewhere both entry points reach.
     */
    private fun routeAfterSignIn(uid: String) {
        resolveSignedInDestination(firestore, uid) { goTo(it) }
    }

    private fun goTo(destination: Class<*>) {
        setLoading(false)
        startActivity(Intent(this, destination))
        finish()
    }

    /**
     * The account exists but the address is unverified. We keep the user signed in
     * (signing out would make [FirebaseUser.sendEmailVerification] unavailable) but
     * deliberately do not navigate onwards.
     */
    private fun promptEmailVerification() {
        Snackbar.make(binding.root, R.string.error_email_not_verified, Snackbar.LENGTH_INDEFINITE)
            .setAction(R.string.action_resend_verification) { resendVerificationEmail() }
            .setActionTextColor(ContextCompat.getColor(this, R.color.wedora_accent))
            .show()
    }

    private fun resendVerificationEmail() {
        val user = auth.currentUser ?: return
        user.sendEmailVerification().addOnCompleteListener(this) { task ->
            val msg =
                if (task.isSuccessful) R.string.verification_email_sent
                else R.string.error_verification_send
            Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
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
        binding.btnLogin.isEnabled = !loading
        binding.btnLogin.setText(if (loading) R.string.btn_logging_in else R.string.btn_login)
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
