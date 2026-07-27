package com.wedora.app

import android.content.Intent
import android.os.Bundle
import com.wedora.app.databinding.ActivityGuestGenderPromptBinding

/**
 * One-time gate between "Continue as Guest" and Home: which gender the guest
 * is, and who they're interested in — the same pair ProfileStep2GenderActivity
 * collects for a real account, just written to [GuestPrefs] instead of
 * Firestore, since a guest has no document to write to.
 *
 * Skipped entirely on a later launch once both are already stored — see
 * [LoginActivity.continueAsGuest], which is the only caller and does that
 * check before ever starting this Activity.
 */
class GuestGenderPromptActivity : WedoraBaseActivity() {

    private lateinit var binding: ActivityGuestGenderPromptBinding
    private lateinit var genderControl: SegmentedControl
    private lateinit var interestedInControl: SegmentedControl

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityGuestGenderPromptBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val content = binding.guestGenderContent
        genderControl = SegmentedControl(
            listOf(
                content.tvGenderMale to Gender.MALE,
                content.tvGenderFemale to Gender.FEMALE,
                content.tvGenderOther to Gender.OTHER
            ),
            onSelected = ::updateContinueEnabled
        )
        interestedInControl = SegmentedControl(
            listOf(
                content.tvInterestedMale to Gender.MALE,
                content.tvInterestedFemale to Gender.FEMALE,
                content.tvInterestedOther to Gender.OTHER
            ),
            onSelected = ::updateContinueEnabled
        )

        binding.btnGuestGenderContinue.setOnClickListener { continueToHome() }
        binding.btnGuestGenderContinue.addPressScale()
        updateContinueEnabled()
    }

    private fun updateContinueEnabled() {
        binding.btnGuestGenderContinue.isEnabled =
            genderControl.selected != null && interestedInControl.selected != null
    }

    private fun continueToHome() {
        val gender = genderControl.selected ?: return
        val interestedIn = interestedInControl.selected ?: return
        GuestPrefs.setGuestGenderPreferences(this, gender.firestoreValue, interestedIn.firestoreValue)
        startActivity(Intent(this, HomeActivity::class.java))
        finish()
    }
}
