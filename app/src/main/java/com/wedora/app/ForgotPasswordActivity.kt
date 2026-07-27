package com.wedora.app

import android.os.Bundle
import android.util.Log
import android.util.Patterns
import android.widget.Toast
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthInvalidCredentialsException
import com.google.firebase.auth.FirebaseAuthInvalidUserException
import com.google.firebase.FirebaseNetworkException
import com.wedora.app.databinding.ActivityForgotPasswordBinding

/**
 * Sends a Firebase password-reset email.
 *
 * There is no follow-up "new password" screen by design — the emailed link
 * opens Firebase's own hosted reset page, so the app's involvement ends once
 * the email is sent.
 */
class ForgotPasswordActivity : WedoraBaseActivity() {

    private companion object {
        const val TAG = "WedoraAuth"
    }

    private lateinit var binding: ActivityForgotPasswordBinding
    private val auth: FirebaseAuth by lazy { FirebaseAuth.getInstance() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityForgotPasswordBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applyEdgeInsets(binding.root, applyIme = true)

        binding.btnBack.setOnClickListener { onBackPressedDispatcher.onBackPressed() }
        binding.btnSendResetLink.setOnClickListener { attemptSendResetLink() }
        binding.btnSendResetLink.addPressScale()
    }

    private fun attemptSendResetLink() {
        val email = binding.etEmail.text.toString().trim()

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

        setLoading(true)
        auth.sendPasswordResetEmail(email)
            .addOnCompleteListener(this) { task ->
                if (task.isSuccessful) {
                    Toast.makeText(this, R.string.reset_link_sent, Toast.LENGTH_LONG).show()
                    // We were launched from Login, so finishing returns there.
                    finish()
                } else {
                    setLoading(false)
                    Log.w(TAG, "Failed to send password reset email", task.exception)
                    Toast.makeText(this, resetErrorMessage(task.exception), Toast.LENGTH_LONG).show()
                }
            }
    }

    private fun resetErrorMessage(e: Exception?): String {
        val resId = when (e) {
            is FirebaseAuthInvalidUserException -> R.string.error_no_account
            // Firebase rejects a malformed address here too, even though the
            // field was already validated — e.g. an address form Patterns
            // accepts but Firebase doesn't.
            is FirebaseAuthInvalidCredentialsException -> R.string.error_invalid_email
            is FirebaseNetworkException -> R.string.error_network
            else -> R.string.error_generic_reset
        }
        return getString(resId)
    }

    private fun setLoading(loading: Boolean) {
        binding.btnSendResetLink.isEnabled = !loading
        binding.btnSendResetLink.setText(
            if (loading) R.string.btn_sending_reset_link else R.string.btn_send_reset_link
        )
    }
}
