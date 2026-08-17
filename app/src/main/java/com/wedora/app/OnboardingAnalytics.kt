package com.wedora.app

import android.content.Context
import android.os.Bundle
import android.util.Log
import com.google.firebase.analytics.FirebaseAnalytics

/**
 * The onboarding funnel's GA4 events, in one object — same reasoning as
 * [UpdateAnalytics]: the funnel (view -> complete/skip per step, ending in
 * sign_up) is only readable if step_number/step_name are identical
 * across every step's call site, which is far easier to guarantee from one
 * file than from seven.
 *
 * step_number/step_name are the same pair on every event here, matching
 * [ProfileStepActivity.stepNumber]/[ProfileStepActivity.stepId] exactly, so a
 * Funnel report can be built directly off step_number without relabeling
 * anything.
 */
object OnboardingAnalytics {

    private const val TAG = "WedoraOnboarding"

    // Param keys. Reused across events, so a rename can't desync two surfaces.
    private const val P_STEP_NUMBER = "step_number"
    private const val P_STEP_NAME = "step_name"
    private const val P_ITEMS_SELECTED = "items_selected"
    /** GA4's own recommended param name for the standard sign_up event. */
    private const val P_METHOD = "method"

    const val METHOD_GOOGLE = "google"
    const val METHOD_EMAIL = "email"
    const val METHOD_UNKNOWN = "unknown"

    const val STEP_NAME_NAME = "name"
    const val STEP_NAME_GENDER = "gender"
    const val STEP_NAME_STATUS = "status"
    const val STEP_NAME_PERMISSIONS = "permissions"
    const val STEP_NAME_DETAILS = "details"
    const val STEP_NAME_PHOTO = "photo"
    const val STEP_NAME_INTERESTS = "interests"

    private var analytics: FirebaseAnalytics? = null

    fun attach(context: Context) {
        analytics = FirebaseAnalytics.getInstance(context.applicationContext)
    }

    /** Fired once per step load, from [ProfileStepActivity.onCreate]. */
    fun stepView(stepNumber: Int, stepName: String) = log("onboarding_step_view") {
        putLong(P_STEP_NUMBER, stepNumber.toLong())
        putString(P_STEP_NAME, stepName)
    }

    /**
     * Fired from every successful Continue, generically from
     * [ProfileStepActivity.saveAndContinue] — a step whose Skip bypasses that
     * (Photo, Interests) never reaches here for a skip, only for an actual
     * Continue tap. [itemsSelected] is optional and currently only passed by
     * the Interests step, via [ProfileStepActivity.analyticsItemsSelected].
     */
    fun stepComplete(stepNumber: Int, stepName: String, itemsSelected: Int? = null) =
        log("onboarding_step_complete") {
            putLong(P_STEP_NUMBER, stepNumber.toLong())
            putString(P_STEP_NAME, stepName)
            if (itemsSelected != null) putLong(P_ITEMS_SELECTED, itemsSelected.toLong())
        }

    /** Only Photo and Interests have a dedicated Skip control to fire this from. */
    fun stepSkip(stepNumber: Int, stepName: String) = log("onboarding_step_skip") {
        putLong(P_STEP_NUMBER, stepNumber.toLong())
        putString(P_STEP_NAME, stepName)
    }

    /**
     * Fired from [ProfileStepActivity.onStop] when the activity is stopping
     * without finishing and without a config change — the closest reliable
     * signal Android offers for "left this step without completing it"
     * (backgrounding, switching apps, swiping away from Recents). It does
     * NOT catch a hard process kill (OS low-memory reap, Force Stop) — there
     * is no callback for that at all, app-wide. A user who backgrounds and
     * later returns to finish the same step fires this again on the next
     * backgrounding, so treat this as a signal to corroborate against the
     * step_view step-over-step drop-off, not a precise abandon count on its
     * own.
     */
    fun stepAbandon(stepNumber: Int, stepName: String) = log("onboarding_step_abandon") {
        putLong(P_STEP_NUMBER, stepNumber.toLong())
        putString(P_STEP_NAME, stepName)
    }

    /**
     * The funnel's single terminal event — fired exactly once, from whichever
     * of Interests' Continue or Skip actually ends the flow.
     *
     * GA4's standard recommended sign_up event (name and `method` param both
     * exactly as GA4 defines them) rather than a custom name, so this feeds
     * GA4's built-in sign-up reports directly instead of only a custom
     * Funnel report. [method] is one of the METHOD_* constants — see
     * [ProfileStep6InterestsActivity]'s own call site for how it's derived
     * from the signed-in account's auth provider.
     */
    fun signUp(method: String) = log("sign_up") {
        putString(P_METHOD, method)
    }

    private inline fun log(name: String, params: Bundle.() -> Unit) {
        val bundle = Bundle().apply(params)
        // Bundle.get(String) is deprecated with no non-reflective replacement
        // that works for a heterogeneous debug dump like this — the typed
        // getters would need the value's type known per key, which is exactly
        // what this loop doesn't have.
        @Suppress("DEPRECATION")
        val dump = bundle.keySet().joinToString { "$it=${bundle.get(it)}" }
        Log.d(TAG, "$name $dump")
        analytics?.logEvent(name, bundle)
    }
}
