package com.wedora.app

import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.text.SpannableString
import android.text.TextPaint
import android.text.method.LinkMovementMethod
import android.text.style.ClickableSpan
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.text.method.HideReturnsTransformationMethod
import android.text.method.PasswordTransformationMethod
import android.util.Patterns
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.auth.FirebaseAuthUserCollisionException
import com.google.firebase.auth.FirebaseAuthWeakPasswordException
import com.google.firebase.auth.FirebaseAuthInvalidCredentialsException
import com.google.firebase.FirebaseNetworkException
import com.wedora.app.databinding.ActivitySignupBinding

/**
 * Creates the account, and nothing else: email and password.
 *
 * Everything a profile needs — name, gender, intent, age, location, photo —
 * is collected afterwards by the ProfileStep screens, one question per screen.
 * This deliberately writes no Firestore document: the steps build it up with
 * set(merge) as each answer arrives, which is also what lets the routing gate
 * send a returning user back to whichever step they left off at.
 */
class SignUpActivity : WedoraBaseActivity() {

    private lateinit var binding: ActivitySignupBinding
    private val auth: FirebaseAuth by lazy { FirebaseAuth.getInstance() }
    private var isPasswordVisible = false

    /**
     * Opening Terms from the agreement link shows its "I Agree" button; tapping
     * it returns here and ticks the checkbox, so agreeing on that screen and
     * agreeing via the box are the same action rather than two competing ones.
     */
    private val termsLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) binding.cbAgreeTerms.isChecked = true
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySignupBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applyEdgeInsets(binding.root)

        setUpLoginPrompt()
        setUpAgreementLinks()

        binding.btnBack.setOnClickListener { onBackPressedDispatcher.onBackPressed() }
        binding.btnTogglePassword.setOnClickListener { togglePasswordVisibility() }
        binding.tvLoginPrompt.setOnClickListener { goToLogin() }

        binding.cbAgreeTerms.setOnCheckedChangeListener { _, _ -> updateSignUpEnabled() }
        val watcher = SimpleTextWatcher { updateSignUpEnabled() }
        binding.etEmail.addTextChangedListener(watcher)
        binding.etPassword.addTextChangedListener(watcher)
        updateSignUpEnabled()

        binding.btnSignUp.setOnClickListener { attemptSignUp() }
        binding.btnSignUp.addPressScale()
    }

    /**
     * Sign Up is enabled only once the agreement is accepted and both fields
     * have something in them. The full email/password validation still runs on
     * tap — this gate is about the obviously-incomplete case, so the button
     * doesn't invite a tap that can only fail.
     */
    private fun updateSignUpEnabled() {
        binding.btnSignUp.isEnabled =
            binding.cbAgreeTerms.isChecked &&
            binding.etEmail.text.isNotBlank() &&
            binding.etPassword.text.isNotEmpty()
    }

    /**
     * Navigates to Login rather than popping the back stack. Going back only
     * lands on Login when Sign Up was opened from it — a guest who arrives
     * here via a gated tab would otherwise be sent back to Home with no route
     * to signing in at all. CLEAR_TOP reuses the existing Login instance when
     * there is one instead of stacking a second.
     */
    private fun goToLogin() {
        startActivity(
            Intent(this, LoginActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
        )
        finish()
    }

    private fun attemptSignUp() {
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
        auth.createUserWithEmailAndPassword(email, password)
            .addOnCompleteListener(this) { task ->
                val user = auth.currentUser
                if (task.isSuccessful && user != null) {
                    sendVerificationEmail(user)
                } else {
                    setLoading(false)
                    Toast.makeText(this, signUpErrorMessage(task.exception), Toast.LENGTH_LONG).show()
                }
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
     * Hand off to the verification screen. The user must verify their email
     * before they can get in, so we deliberately do not drop them into the app —
     * which is also why the profile steps start after the first verified login
     * rather than here.
     *
     * Unlike before, the account is NOT signed out here: the verification screen
     * offers a Resend, which needs a current user. That screen signs out when
     * the user leaves for Login, and an unverified session can't reach the app
     * regardless (Splash and Login both re-check verification).
     */
    private fun finishSignUp() {
        setLoading(false)
        // The user now has a real account, so they are no longer a guest.
        GuestPrefs.clearGuest(this)
        val email = auth.currentUser?.email
            ?: binding.etEmail.text.toString().trim()
        startActivity(EmailVerificationActivity.intent(this, email))
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
        binding.btnSignUp.setText(if (loading) R.string.btn_creating_account else R.string.btn_signup)
        // Off the loading path, re-apply the checkbox gate rather than blanket
        // re-enabling — otherwise a failed attempt would leave Sign Up tappable
        // even if the box were somehow unticked.
        if (loading) binding.btnSignUp.isEnabled = false else updateSignUpEnabled()
    }

    /**
     * Builds the "…Terms of Service and Privacy Policy" line with the two
     * document names as tappable, accent-coloured links, assembled from parts
     * so the spans land on the right ranges without counting characters by
     * hand.
     */
    private fun setUpAgreementLinks() {
        val prefix = getString(R.string.signup_agreement_prefix)
        val terms = getString(R.string.signup_agreement_terms)
        val and = getString(R.string.signup_agreement_and)
        val privacy = getString(R.string.signup_agreement_privacy)
        val full = prefix + terms + and + privacy

        val spannable = SpannableString(full)
        val accent = ContextCompat.getColor(this, R.color.wedora_accent)

        fun linkSpan(onClick: () -> Unit) = object : ClickableSpan() {
            override fun onClick(widget: View) = onClick()
            override fun updateDrawState(ds: TextPaint) {
                ds.color = accent
                ds.isUnderlineText = false
            }
        }

        val termsStart = prefix.length
        spannable.setSpan(
            linkSpan { termsLauncher.launch(TermsOfServiceActivity.intent(this, fromSignup = true)) },
            termsStart, termsStart + terms.length,
            SpannableString.SPAN_EXCLUSIVE_EXCLUSIVE
        )

        val privacyStart = prefix.length + terms.length + and.length
        spannable.setSpan(
            linkSpan { startActivity(Intent(this, PrivacyPolicyActivity::class.java)) },
            privacyStart, privacyStart + privacy.length,
            SpannableString.SPAN_EXCLUSIVE_EXCLUSIVE
        )

        binding.tvAgreementLinks.text = spannable
        // Without this the ClickableSpans render but don't receive taps.
        binding.tvAgreementLinks.movementMethod = LinkMovementMethod.getInstance()
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
