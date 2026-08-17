package com.wedora.app

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.widget.Toast
import androidx.annotation.LayoutRes
import androidx.annotation.StringRes
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import com.wedora.app.databinding.ActivityProfileStepBinding

/**
 * Shared behaviour for the profile-setup steps.
 *
 * Each step asks one thing, writes its own answer, and hands on to the next.
 * The chrome — progress, back arrow, title, Continue — lives here so five
 * screens can't drift apart, and each subclass supplies only its fields and
 * what they mean.
 *
 * Writing per step rather than once at the end is what makes the flow
 * resumable: a user who closes the app after step 2 has two answers stored,
 * and the routing gate can drop them back at step 3 instead of starting over.
 * It also means every write is a set(merge) onto whatever is already there.
 */
abstract class ProfileStepActivity : WedoraBaseActivity() {

    protected companion object {
        const val TAG = "WedoraProfileStep"

        /** Denominator for the progress indicator and the "Step n of N" label. */
        const val TOTAL_STEPS = 7
    }

    protected lateinit var binding: ActivityProfileStepBinding
    protected val auth: FirebaseAuth by lazy { FirebaseAuth.getInstance() }
    protected val firestore: FirebaseFirestore by lazy { FirebaseFirestore.getInstance() }

    /** The signed-in user. Every step needs it; the flow can't run without one. */
    protected lateinit var uid: String

    protected abstract val stepNumber: Int

    /** [OnboardingAnalytics] step_name — one of its STEP_NAME_* constants. */
    protected abstract val stepId: String

    @get:StringRes
    protected abstract val titleRes: Int

    @get:StringRes
    protected abstract val subtitleRes: Int

    @get:LayoutRes
    protected abstract val contentLayoutRes: Int

    /** Wire up the step's own views once they're inflated. */
    protected abstract fun bindContent(content: View)

    /** Whether Continue should be enabled — the step's required fields. */
    protected abstract fun isStepValid(): Boolean

    /** Fields this step contributes, merged onto the user document. */
    protected abstract fun stepUpdates(): Map<String, Any?>

    /** Where Continue goes. */
    protected abstract fun nextStep(): Class<*>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityProfileStepBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applyEdgeInsets(binding.root)

        val currentUid = auth.currentUser?.uid
        if (currentUid == null) {
            Toast.makeText(this, R.string.error_generic_login, Toast.LENGTH_LONG).show()
            finish()
            return
        }
        uid = currentUid

        binding.tvStepTitle.setText(titleRes)
        binding.tvStepSubtitle.setText(subtitleRes)
        binding.tvStepCount.text =
            getString(R.string.step_counter_format, stepNumber, TOTAL_STEPS)
        binding.progressStep.max = TOTAL_STEPS
        binding.progressStep.setProgressCompat(stepNumber, false)

        // Step 1 has nothing to go back to — the account already exists and
        // Sign Up is finished, so an arrow there would either dead-end or
        // suggest the account could be undone.
        binding.btnBack.visibility =
            if (stepNumber > 1) View.VISIBLE else View.INVISIBLE
        binding.btnBack.setOnClickListener { finish() }

        val content = LayoutInflater.from(this)
            .inflate(contentLayoutRes, binding.stepContent, true)
        bindContent(content)

        binding.btnContinue.setOnClickListener { saveAndContinue() }
        binding.btnContinue.addPressScale()
        updateContinueEnabled()

        loadExistingProfile()

        OnboardingAnalytics.stepView(stepNumber, stepId)
    }

    /**
     * Reads whatever is already stored and hands it to the step.
     *
     * Two reasons every step does this rather than only the ones that need it:
     * the back arrow means a user can return to a step they've answered, and
     * seeing it blank would suggest the answer was lost. Step 3 additionally
     * *needs* the stored gender before it can build its options at all.
     *
     * A failed read is silent — the step simply starts empty, which is the
     * same state a first-time user sees, and nothing is lost because the write
     * is a merge.
     */
    private fun loadExistingProfile() {
        binding.stepContent.visibility = View.INVISIBLE
        binding.progressStepLoad.visibility = View.VISIBLE
        firestore.collection(UserProfile.COLLECTION).document(uid).get()
            .addOnSuccessListener { snapshot ->
                onExistingProfile(UserProfile.from(snapshot))
                updateContinueEnabled()
                revealStepContent()
            }
            .addOnFailureListener { e ->
                Log.i(TAG, "No existing profile to prefill step $stepNumber", e)
                revealStepContent()
            }
    }

    private fun revealStepContent() {
        binding.progressStepLoad.visibility = View.GONE
        binding.stepContent.visibility = View.VISIBLE
    }

    /** Pre-fill from what's already stored. Default: nothing to restore. */
    protected open fun onExistingProfile(profile: UserProfile) = Unit

    /** Subclasses call this whenever an answer changes. */
    protected fun updateContinueEnabled() {
        binding.btnContinue.isEnabled = isStepValid()
    }

    /**
     * Merges this step's answers, then moves on. A failed write keeps the user
     * on the step with their answers intact rather than advancing past data
     * that didn't save.
     */
    private fun saveAndContinue() {
        if (!isStepValid()) return
        if (!onContinueRequested()) return

        val updates = stepUpdates()
        if (updates.isEmpty()) {
            onStepCompleteAnalytics()
            onStepSaved()
            return
        }

        setSaving(true)
        firestore.collection(UserProfile.COLLECTION).document(uid)
            .set(updates, SetOptions.merge())
            .addOnSuccessListener {
                onStepCompleteAnalytics()
                onStepSaved()
            }
            .addOnFailureListener { e ->
                logFirestoreWriteFailure(TAG, "Failed to save profile step $stepNumber", e)
                setSaving(false)
                Toast.makeText(this, R.string.error_profile_update_failed, Toast.LENGTH_LONG)
                    .show()
            }
    }

    private fun onStepCompleteAnalytics() {
        OnboardingAnalytics.stepComplete(stepNumber, stepId, analyticsItemsSelected())
    }

    /**
     * Extra context for [OnboardingAnalytics.stepComplete] — currently only
     * overridden by the Interests step, whose selection count is otherwise
     * invisible to this generic completion hook.
     */
    protected open fun analyticsItemsSelected(): Int? = null

    /**
     * Last check before the write, for rules a step wants to *explain* rather
     * than enforce by disabling Continue — the 18+ age floor being the case
     * that matters. Returning false leaves the user on the step, having shown
     * them why.
     */
    protected open fun onContinueRequested(): Boolean = true

    /** Hook for a step with more to do after its write — step 1 syncs Auth. */
    protected open fun onStepSaved() {
        goToNextStep()
    }

    protected fun goToNextStep() {
        startActivity(Intent(this, nextStep()))
        finish()
    }

    protected fun setSaving(saving: Boolean) {
        binding.btnContinue.isEnabled = !saving
        binding.btnContinue.setText(if (saving) R.string.btn_saving else R.string.btn_continue)
        if (!saving) updateContinueEnabled()
    }

    /**
     * See [OnboardingAnalytics.stepAbandon] for what this signal does and
     * doesn't catch. `isFinishing` is false for backgrounding/leaving the
     * app and true for every in-flow transition (Continue, Skip, the back
     * arrow all call finish() before or as part of triggering this), so this
     * fires only for the former. `isChangingConfigurations` excludes a
     * rotation, which stops and recreates this same Activity rather than
     * abandoning it.
     */
    override fun onStop() {
        super.onStop()
        if (!isFinishing && !isChangingConfigurations) {
            OnboardingAnalytics.stepAbandon(stepNumber, stepId)
        }
    }
}
