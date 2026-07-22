package com.wedora.app

import android.content.Context

/**
 * The option lists behind `myStatus` and `lookingFor`, in one place so the
 * sign-up form, the completion gate, the profile editor and the feed filter
 * can't drift apart on what the valid answers are.
 *
 * The stored value is the display string itself ("Divorced", "Second Wife"),
 * which is what lets every display site render it without a lookup. The
 * trade-off is that these strings are not localisable — translating them would
 * rewrite stored data — so they stay in code rather than in strings.xml, where
 * a translator would reasonably assume they were safe to change.
 */
object MarriageIntent {

    const val STATUS_SINGLE = "Single"
    const val STATUS_MARRIED = "Married"
    const val STATUS_DIVORCED = "Divorced"
    const val STATUS_WIDOWED = "Widowed"

    val STATUS_OPTIONS = listOf(
        STATUS_SINGLE, STATUS_MARRIED, STATUS_DIVORCED, STATUS_WIDOWED
    )

    private val LOOKING_FOR_MALE = listOf("First Wife", "Second Wife", "Third Wife", "Any")
    private val LOOKING_FOR_OTHER = listOf("First Marriage", "Second Marriage", "Any")

    /**
     * What this user can be looking for, given their gender.
     *
     * A null or unrecognised gender falls back to the non-male list rather than
     * to an empty one: an account that somehow has no gender still needs
     * answerable options, and "Any" is present in both lists either way.
     */
    fun lookingForOptions(gender: String?): List<String> =
        if (gender == Gender.MALE.firestoreValue) LOOKING_FOR_MALE else LOOKING_FOR_OTHER

    /** Every option across both genders — used by the filter's default set. */
    val ALL_LOOKING_FOR: List<String> =
        (LOOKING_FOR_MALE + LOOKING_FOR_OTHER).distinct()

    /**
     * "Divorced • Looking for Second Wife", or just one half when only one
     * field is set, or null when neither is — so callers hide the row rather
     * than render an empty badge. Accounts predating these fields land here.
     */
    fun summaryLine(context: Context, myStatus: String?, lookingFor: String?): String? {
        val status = myStatus?.takeIf { it.isNotBlank() }
        val looking = lookingFor?.takeIf { it.isNotBlank() }
            ?.let { context.getString(R.string.looking_for_format, it) }

        return when {
            status != null && looking != null ->
                context.getString(R.string.marriage_intent_format, status, looking)
            else -> status ?: looking
        }
    }
}
