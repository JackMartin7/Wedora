package com.wedora.app

import android.view.View
import android.widget.TextView

/**
 * Step 2: gender and who they're interested in.
 *
 * Both matter beyond display: `gender` is what other users' feeds query
 * against, and `interestedIn` is what this user's own feed queries. Without
 * the pair the feed has nothing to match on, which is why neither is optional.
 */
class ProfileStep2GenderActivity : ProfileStepActivity() {

    private lateinit var genderControl: SegmentedControl
    private lateinit var interestedInControl: SegmentedControl

    override val stepNumber = 2
    override val titleRes = R.string.step2_title
    override val subtitleRes = R.string.step2_subtitle
    override val contentLayoutRes = R.layout.view_step_gender

    override fun bindContent(content: View) {
        genderControl = SegmentedControl(
            listOf(
                content.findViewById<TextView>(R.id.tvGenderMale) to Gender.MALE,
                content.findViewById<TextView>(R.id.tvGenderFemale) to Gender.FEMALE,
                content.findViewById<TextView>(R.id.tvGenderOther) to Gender.OTHER
            ),
            onSelected = { updateContinueEnabled() }
        )
        interestedInControl = SegmentedControl(
            listOf(
                content.findViewById<TextView>(R.id.tvInterestedMale) to Gender.MALE,
                content.findViewById<TextView>(R.id.tvInterestedFemale) to Gender.FEMALE,
                content.findViewById<TextView>(R.id.tvInterestedOther) to Gender.OTHER
            ),
            onSelected = { updateContinueEnabled() }
        )
    }

    override fun onExistingProfile(profile: UserProfile) {
        genderFrom(profile.gender)?.let { genderControl.select(it) }
        genderFrom(profile.interestedIn)?.let { interestedInControl.select(it) }
    }

    private fun genderFrom(value: String?): Gender? =
        Gender.values().firstOrNull { it.firestoreValue == value }

    override fun isStepValid(): Boolean =
        genderControl.selected != null && interestedInControl.selected != null

    override fun stepUpdates(): Map<String, Any?> = mapOf(
        UserProfile.FIELD_GENDER to genderControl.selected?.firestoreValue,
        UserProfile.FIELD_INTERESTED_IN to interestedInControl.selected?.firestoreValue
    )

    override fun nextStep(): Class<*> = ProfileStep3StatusActivity::class.java
}
