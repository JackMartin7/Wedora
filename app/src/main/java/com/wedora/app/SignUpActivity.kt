package com.wedora.app

import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.text.SpannableString
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.text.method.HideReturnsTransformationMethod
import android.text.method.PasswordTransformationMethod
import android.util.Log
import android.util.Patterns
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.bumptech.glide.Glide
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.auth.FirebaseAuthUserCollisionException
import com.google.firebase.auth.FirebaseAuthWeakPasswordException
import com.google.firebase.auth.FirebaseAuthInvalidCredentialsException
import com.google.firebase.auth.UserProfileChangeRequest
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.FirebaseNetworkException
import com.wedora.app.databinding.ActivitySignupBinding
import java.io.File
import java.io.IOException

class SignUpActivity : AppCompatActivity() {

    private companion object {
        const val TAG = "WedoraAuth"
        const val STAGED_PHOTO_FILENAME = "profile_photos/staging_signup.jpg"
    }

    private lateinit var binding: ActivitySignupBinding
    private val auth: FirebaseAuth by lazy { FirebaseAuth.getInstance() }
    private val firestore: FirebaseFirestore by lazy { FirebaseFirestore.getInstance() }
    private var isPasswordVisible = false

    private lateinit var genderControl: SegmentedControl
    private lateinit var interestedInControl: SegmentedControl

    /** Set once a photo has been picked and copied into internal storage. */
    private var stagedPhotoFile: File? = null

    private val pickImageLauncher =
        registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
            if (uri != null) copyPickedImageToStaging(uri)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySignupBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setUpLoginPrompt()
        setUpSelectors()

        binding.btnBack.setOnClickListener { onBackPressedDispatcher.onBackPressed() }

        binding.btnTogglePassword.setOnClickListener { togglePasswordVisibility() }

        binding.tvLoginPrompt.setOnClickListener { onBackPressedDispatcher.onBackPressed() }

        binding.rowAddPhoto.setOnClickListener { pickImageLauncher.launch("image/*") }

        binding.btnSignUp.setOnClickListener { attemptSignUp() }

        binding.btnGoogle.setOnClickListener { /* TODO: Google sign-up */ }
        binding.btnFacebook.setOnClickListener { /* TODO: Facebook sign-up */ }
    }

    private fun setUpSelectors() {
        genderControl = SegmentedControl(
            listOf(
                binding.tvGenderMale to Gender.MALE,
                binding.tvGenderFemale to Gender.FEMALE,
                binding.tvGenderOther to Gender.OTHER
            )
        )
        interestedInControl = SegmentedControl(
            listOf(
                binding.tvInterestedMale to Gender.MALE,
                binding.tvInterestedFemale to Gender.FEMALE,
                binding.tvInterestedOther to Gender.OTHER
            )
        )
    }

    /**
     * Copies the picked image into internal storage immediately, rather than
     * holding onto the picker's Uri — that grant is not guaranteed to remain
     * valid for as long as the sign-up form stays open.
     */
    private fun copyPickedImageToStaging(uri: Uri) {
        try {
            val staging = File(filesDir, STAGED_PHOTO_FILENAME)
            staging.parentFile?.mkdirs()
            contentResolver.openInputStream(uri)?.use { input ->
                staging.outputStream().use { output -> input.copyTo(output) }
            } ?: throw IOException("openInputStream returned null")

            stagedPhotoFile = staging
            Glide.with(this).load(staging).circleCrop().into(binding.ivPhotoPreview)
            binding.tvAddPhoto.setText(R.string.change_photo)
        } catch (e: IOException) {
            Log.w(TAG, "Failed to copy picked profile photo", e)
            Toast.makeText(this, R.string.error_photo_copy_failed, Toast.LENGTH_SHORT).show()
        }
    }

    private fun attemptSignUp() {
        val username = binding.etUsername.text.toString().trim()
        val email = binding.etEmail.text.toString().trim()
        val password = binding.etPassword.text.toString()
        val gender = genderControl.selected
        val interestedIn = interestedInControl.selected

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
        if (gender == null) {
            Toast.makeText(this, R.string.error_gender_required, Toast.LENGTH_SHORT).show()
            return
        }
        if (interestedIn == null) {
            Toast.makeText(this, R.string.error_interested_in_required, Toast.LENGTH_SHORT).show()
            return
        }

        setLoading(true)
        auth.createUserWithEmailAndPassword(email, password)
            .addOnCompleteListener(this) { task ->
                if (task.isSuccessful) {
                    setUserDisplayName(username, gender, interestedIn)
                } else {
                    setLoading(false)
                    Toast.makeText(this, signUpErrorMessage(task.exception), Toast.LENGTH_LONG).show()
                }
            }
    }

    private fun setUserDisplayName(username: String, gender: Gender, interestedIn: Gender) {
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
            // and write the Firestore profile document.
            writeUserDocument(user, username, gender, interestedIn)
        }
    }

    /**
     * The photo is deliberately NOT included here — it stays device-local
     * (see [finalizeLocalPhoto]), never uploaded or written to Firestore.
     */
    private fun writeUserDocument(user: FirebaseUser, username: String, gender: Gender, interestedIn: Gender) {
        val userDoc = hashMapOf(
            UserProfile.FIELD_DISPLAY_NAME to username,
            UserProfile.FIELD_EMAIL to user.email,
            UserProfile.FIELD_GENDER to gender.firestoreValue,
            UserProfile.FIELD_INTERESTED_IN to interestedIn.firestoreValue,
            UserProfile.FIELD_CREATED_AT to FieldValue.serverTimestamp()
        )
        firestore.collection(UserProfile.COLLECTION).document(user.uid).set(userDoc)
            .addOnCompleteListener(this) { task ->
                if (!task.isSuccessful) {
                    Log.w(TAG, "Failed to write user profile document", task.exception)
                    Toast.makeText(this, R.string.error_profile_save_failed, Toast.LENGTH_LONG).show()
                }
                finalizeLocalPhoto(user)
                sendVerificationEmail(user)
            }
    }

    /** Moves the staged photo to its final UID-keyed name and records the path. */
    private fun finalizeLocalPhoto(user: FirebaseUser) {
        val staging = stagedPhotoFile ?: return
        val finalFile = File(filesDir, "profile_photos/${user.uid}.jpg")
        finalFile.parentFile?.mkdirs()
        if (staging.renameTo(finalFile)) {
            LocalProfilePrefs.setPhotoPath(this, user.uid, finalFile.absolutePath)
        } else {
            Log.w(TAG, "Failed to finalize staged profile photo")
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
        // The user now has a real account, so they are no longer a guest.
        GuestPrefs.clearGuest(this)
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
