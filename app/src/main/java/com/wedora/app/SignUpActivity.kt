package com.wedora.app

import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.text.SpannableString
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.text.method.HideReturnsTransformationMethod
import android.text.method.PasswordTransformationMethod
import android.util.Patterns
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.auth.FirebaseAuthUserCollisionException
import com.google.firebase.auth.FirebaseAuthWeakPasswordException
import com.google.firebase.auth.FirebaseAuthInvalidCredentialsException
import com.google.firebase.auth.UserProfileChangeRequest
import com.google.firebase.FirebaseNetworkException
import com.wedora.app.databinding.ActivitySignupBinding

class SignUpActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySignupBinding
    private val auth: FirebaseAuth by lazy { FirebaseAuth.getInstance() }
    private var isPasswordVisible = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySignupBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setUpLoginPrompt()

        binding.btnBack.setOnClickListener { onBackPressedDispatcher.onBackPressed() }

        binding.btnTogglePassword.setOnClickListener { togglePasswordVisibility() }

        binding.tvLoginPrompt.setOnClickListener { onBackPressedDispatcher.onBackPressed() }

        binding.btnSignUp.setOnClickListener { attemptSignUp() }

        binding.btnGoogle.setOnClickListener { /* TODO: Google sign-up */ }
        binding.btnFacebook.setOnClickListener { /* TODO: Facebook sign-up */ }
    }

    private fun attemptSignUp() {
        val username = binding.etUsername.text.toString().trim()
        val email = binding.etEmail.text.toString().trim()
        val password = binding.etPassword.text.toString()

        if (username.isEmpty()) {
            binding.etUsername.error = getString(R.string.error_username_required)
            binding.etUsername.requestFocus()
            return
        }
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
        auth.createUserWithEmailAndPassword(email, password)
            .addOnCompleteListener(this) { task ->
                if (task.isSuccessful) {
                    setUserDisplayName(username)
                } else {
                    setLoading(false)
                    Toast.makeText(this, signUpErrorMessage(task.exception), Toast.LENGTH_LONG).show()
                }
            }
    }

    private fun setUserDisplayName(username: String) {
        val user = auth.currentUser
        if (user == null) {
            finishSignUp()
            return
        }

        val request = UserProfileChangeRequest.Builder()
            .setDisplayName(username)
            .build()
        user.updateProfile(request).addOnCompleteListener(this) {
            // Whether or not the display name stuck, the account exists — carry on
            // and send the verification email.
            sendVerificationEmail(user)
        }
    }

    private fun sendVerificationEmail(user: FirebaseUser) {
        user.sendEmailVerification().addOnCompleteListener(this) { task ->
            if (!task.isSuccessful) {
                Toast.makeText(this, R.string.error_verification_send, Toast.LENGTH_LONG).show()
            }
            finishSignUp()
        }
    }

    /**
     * Sign the new account out and hand off to Login. The user must verify their
     * email before they can get in, so we deliberately do not drop them straight
     * into the app.
     */
    private fun finishSignUp() {
        setLoading(false)
        auth.signOut()
        Toast.makeText(this, R.string.signup_success_verify, Toast.LENGTH_LONG).show()
        startActivity(
            Intent(this, LoginActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
        )
        finish()
    }

    private fun signUpErrorMessage(e: Exception?): String {
        val resId = when (e) {
            is FirebaseAuthWeakPasswordException -> R.string.error_weak_password
            is FirebaseAuthUserCollisionException -> R.string.error_email_in_use
            is FirebaseAuthInvalidCredentialsException -> R.string.error_invalid_email
            is FirebaseNetworkException -> R.string.error_network
            else -> R.string.error_generic_signup
        }
        return getString(resId)
    }

    private fun setLoading(loading: Boolean) {
        binding.btnSignUp.isEnabled = !loading
        binding.btnSignUp.setText(if (loading) R.string.btn_creating_account else R.string.btn_signup)
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

    private fun setUpLoginPrompt() {
        val prompt = getString(R.string.has_account_prompt)
        val link = getString(R.string.login_link)
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

        binding.tvLoginPrompt.text = spannable
    }
}
