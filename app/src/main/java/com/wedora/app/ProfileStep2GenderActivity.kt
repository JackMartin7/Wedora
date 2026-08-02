package com.wedora.app

import android.view.View
import android.widget.TextView

/**
 * Step 2: gender only. `interestedIn` is no longer a separate choice — it's
 * always the opposite of whatever gender is picked here (see [Gender.opposite]),
 * auto-derived and written alongside it rather than shown as its own control.
 */
class ProfileStep2GenderActivity : ProfileStepActivity() {

    private lateinit var genderControl: SegmentedControl

    override val stepNumber = 2
    override val titleRes = R.string.step2_title
    override val subtitleRes = R.string.step2_subtitle
    override val contentLayoutRes = R.layout.view_step_gender

    override fun bindContent(content: View) {
        genderControl = SegmentedControl(
            listOf(
                content.findViewById<TextView>(R.id.tvGenderMale) to Gender.MALE,
                content.findViewById<TextView>(R.id.tvGenderFemale) to Gender.FEMALE
            ),
            onSelected = { updateContinueEnabled() }
        )
    }

    override fun onExistingProfile(profile: UserProfile) {
        genderFrom(profile.gender)?.let { genderControl.select(it) }
    }

    private fun genderFrom(value: String?): Gender? =
        Gender.values().firstOrNull { it.firestoreValue == value }

    override fun isStepValid(): Boolean = genderControl.selected != null

    override fun stepUpdates(): Map<String, Any?> {
        val gender = genderControl.selected ?: return emptyMap()
        return mapOf(
            UserProfile.FIELD_GENDER to gender.firestoreValue,
            UserProfile.FIELD_INTERESTED_IN to gender.opposite.firestoreValue
        )
    }

    override fun nextStep(): Class<*> = ProfileStep3StatusActivity::class.java
}
