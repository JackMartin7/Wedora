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
import com.google.firebase.FirebaseNetworkException
import com.wedora.app.databinding.ActivityLoginBinding

class LoginActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLoginBinding
    private val auth: FirebaseAuth by lazy { FirebaseAuth.getInstance() }
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
            // TODO: navigate to Forgot Password screen
            Toast.makeText(this, "Forgot Password tapped", Toast.LENGTH_SHORT).show()
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
                setLoading(false)
                if (task.isSuccessful) {
                    onSignInSuccess()
                } else {
                    Toast.makeText(this, loginErrorMessage(task.exception), Toast.LENGTH_LONG).show()
                }
            }
    }

    private fun onSignInSuccess() {
        val user = auth.currentUser
        if (user == null) {
            Toast.makeText(this, R.string.error_generic_login, Toast.LENGTH_LONG).show()
            return
        }

        if (user.isEmailVerified) {
            Toast.makeText(this, R.string.login_success, Toast.LENGTH_SHORT).show()
            startActivity(Intent(this, HomeActivity::class.java))
            finish()
        } else {
            promptEmailVerification()
        }
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
