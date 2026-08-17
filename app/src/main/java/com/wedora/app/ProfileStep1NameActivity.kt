package com.wedora.app

import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.view.View
import android.widget.EditText
import com.google.firebase.auth.UserProfileChangeRequest

/**
 * Step 1: the display name.
 *
 * This is the write that creates the user document. Sign Up deliberately
 * writes nothing, so until this step lands there is no `users/{uid}` at all —
 * which is exactly what the routing gate keys off to send someone here.
 */
class ProfileStep1NameActivity : ProfileStepActivity() {

    private lateinit var etName: EditText

    override val stepNumber = 1
    override val stepId = OnboardingAnalytics.STEP_NAME_NAME
    override val titleRes = R.string.step1_title
    override val subtitleRes = R.string.step1_subtitle
    override val contentLayoutRes = R.layout.view_step_name

    override fun bindContent(content: View) {
        etName = content.findViewById(R.id.etName)

        // Pre-filled if they've been here before, so someone who came back to
        // change it isn't retyping from scratch.
        etName.setText(auth.currentUser?.displayName.orEmpty())

        etName.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) = Unit
            override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) = Unit
            override fun afterTextChanged(s: Editable?) = updateContinueEnabled()
        })
    }

    private fun enteredName(): String = etName.text.toString().trim()

    override fun isStepValid(): Boolean = enteredName().isNotEmpty()

    /**
     * Email comes from the Auth account rather than being asked for again —
     * it's already verified by this point, and a second copy the user could
     * mistype would just be a field that disagrees with the one they log in
     * with.
     */
    override fun stepUpdates(): Map<String, Any?> = mapOf(
        UserProfile.FIELD_DISPLAY_NAME to enteredName(),
        UserProfile.FIELD_EMAIL to auth.currentUser?.email
    )

    override fun nextStep(): Class<*> = ProfileStep2GenderActivity::class.java

    /**
     * Mirrors the name onto the Auth profile after the Firestore write, since
     * Home and Profile read the greeting from there. A failure is logged but
     * doesn't block the step — Firestore already has the name, which is the
     * copy that matters.
     */
    override fun onStepSaved() {
        val user = auth.currentUser
        if (user == null) {
            goToNextStep()
            return
        }

        user.updateProfile(
            UserProfileChangeRequest.Builder().setDisplayName(enteredName()).build()
        ).addOnCompleteListener {
            if (!it.isSuccessful) {
                Log.w(TAG, "Saved name but failed to update Auth displayName", it.exception)
            }
            goToNextStep()
        }
    }
}
