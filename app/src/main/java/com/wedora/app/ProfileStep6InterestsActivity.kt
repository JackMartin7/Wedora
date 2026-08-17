package com.wedora.app

import android.content.Intent
import android.os.Bundle
import android.view.View
import com.google.android.material.chip.ChipGroup
import com.google.firebase.auth.EmailAuthProvider
import com.google.firebase.auth.GoogleAuthProvider

/**
 * Step 7 (final): hobbies/interests. Added after Photo rather than inserted
 * earlier and renumbering the steps above it — see the string resources'
 * own "visual number only" notes for why that convention holds here too.
 *
 * Optional, same as Photo — but unlike Photo (where Continue is unconditional
 * so there's no way to tell "skipped" from "kept nothing") this has its own
 * Skip control, added specifically so onboarding_step_skip can fire here
 * directly rather than only being inferable from a zero-selection Continue.
 * Both Continue and Skip end the setup flow, same as Photo used to.
 */
class ProfileStep6InterestsActivity : ProfileStepActivity() {

    private lateinit var chipsInterests: ChipGroup

    override val stepNumber = 7
    override val stepId = OnboardingAnalytics.STEP_NAME_INTERESTS
    override val titleRes = R.string.step6_title
    override val subtitleRes = R.string.step6_subtitle
    override val contentLayoutRes = R.layout.view_step_interests

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Guard: the base finishes early when there's no signed-in user, and
        // the views below would not have been bound.
        if (isFinishing) return

        binding.tvStepSkip.visibility = View.VISIBLE
        binding.tvStepSkip.setOnClickListener {
            OnboardingAnalytics.stepSkip(stepNumber, stepId)
            finishSetup()
        }
    }

    override fun bindContent(content: View) {
        chipsInterests = content.findViewById(R.id.chipsInterests)
        chipsInterests.setInterestOptions(selected = emptyList())
    }

    override fun onExistingProfile(profile: UserProfile) {
        chipsInterests.setInterestOptions(selected = profile.interests)
    }

    /** Always valid — picking any interests is optional. */
    override fun isStepValid(): Boolean = true

    override fun stepUpdates(): Map<String, Any?> = mapOf(
        UserProfile.FIELD_INTERESTS to chipsInterests.selectedInterestIds()
    )

    override fun analyticsItemsSelected(): Int = chipsInterests.selectedInterestIds().size

    override fun nextStep(): Class<*> = HomeActivity::class.java

    override fun onStepSaved() = finishSetup()

    /**
     * Ends the setup flow rather than stacking Home on top of seven steps
     * that should no longer be reachable by back — same flags Photo used to
     * apply for the same reason before this step existed. Shared by both
     * Continue ([onStepSaved]) and Skip, so sign_up — the funnel's single
     * terminal event — fires exactly once regardless of which one actually
     * ended the flow.
     */
    private fun finishSetup() {
        OnboardingAnalytics.signUp(analyticsSignUpMethod())
        startActivity(
            Intent(this, HomeActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        )
        finish()
    }

    /**
     * There's no sign-up-method state threaded through the seven onboarding
     * steps to carry here, so this reads it back off the account itself —
     * same [FirebaseUser.providerData] check AccountSettingsActivity already
     * uses to tell a password account from a Google one.
     */
    private fun analyticsSignUpMethod(): String {
        val providerIds = auth.currentUser?.providerData?.map { it.providerId }.orEmpty()
        return when {
            providerIds.contains(GoogleAuthProvider.PROVIDER_ID) -> OnboardingAnalytics.METHOD_GOOGLE
            providerIds.contains(EmailAuthProvider.PROVIDER_ID) -> OnboardingAnalytics.METHOD_EMAIL
            else -> OnboardingAnalytics.METHOD_UNKNOWN
        }
    }
}
