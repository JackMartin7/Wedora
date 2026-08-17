package com.wedora.app

import android.view.View
import com.google.android.material.chip.ChipGroup

/**
 * Step 3: marital status and what they're looking for.
 *
 * Both option lists are gender-aware — a man is a widower and looks for a
 * wife; everyone else is widowed and looks for a marriage — so they can't be
 * built until the gender saved in step 2 has been read back. Until it arrives
 * the groups show the non-male lists, which still contain every shared option,
 * rather than sitting empty.
 */
class ProfileStep3StatusActivity : ProfileStepActivity() {

    private lateinit var chipsMyStatus: ChipGroup
    private lateinit var chipsLookingFor: ChipGroup

    override val stepNumber = 3
    override val stepId = OnboardingAnalytics.STEP_NAME_STATUS
    override val titleRes = R.string.step3_title
    override val subtitleRes = R.string.step3_subtitle
    override val contentLayoutRes = R.layout.view_step_status

    override fun bindContent(content: View) {
        chipsMyStatus = content.findViewById(R.id.chipsMyStatus)
        chipsLookingFor = content.findViewById(R.id.chipsLookingFor)
        showOptions(gender = null, myStatus = null, lookingFor = null)
    }

    override fun onExistingProfile(profile: UserProfile) {
        showOptions(profile.gender, profile.myStatus, profile.lookingFor)
    }

    /**
     * setOptions filters the restored selection against the new list, so a
     * stored answer that doesn't belong to this gender's options simply
     * doesn't come back — the user picks again from what now applies.
     */
    private fun showOptions(gender: String?, myStatus: String?, lookingFor: String?) {
        chipsMyStatus.setOptions(
            options = MarriageIntent.statusOptions(gender),
            selected = listOfNotNull(myStatus),
            onChanged = { updateContinueEnabled() }
        )
        chipsLookingFor.setOptions(
            options = MarriageIntent.lookingForOptions(gender),
            selected = listOfNotNull(lookingFor),
            onChanged = { updateContinueEnabled() }
        )
    }

    override fun isStepValid(): Boolean =
        chipsMyStatus.selectedOption() != null && chipsLookingFor.selectedOption() != null

    override fun stepUpdates(): Map<String, Any?> = mapOf(
        UserProfile.FIELD_MY_STATUS to chipsMyStatus.selectedOption(),
        UserProfile.FIELD_LOOKING_FOR to chipsLookingFor.selectedOption()
    )

    override fun nextStep(): Class<*> = ProfileStepPermissionsActivity::class.java
}
