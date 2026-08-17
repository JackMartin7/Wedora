package com.wedora.app

import android.content.Context

/**
 * When to ask the user to rate the app, and the state that has to outlive
 * the process for that question to mean anything.
 *
 * Same shape and same storage as [UpdatePrefs]' nag budget — a re-prompt gap
 * plus a hard lifetime cap, in the shared "wedora_prefs" file — for the same
 * reason it gives: this is a handful of ints and longs read on a single
 * callback, so DataStore's async story would buy nothing here.
 *
 * **Device-level, deliberately not keyed by UID.** A Play rating belongs to
 * the device and its Play account, not to a Wedora account, so keying this
 * per-user would re-prompt someone who signed into a second account on a
 * phone they had already rated from. A reinstall resets it, which is
 * acceptable and arguably right — that user is effectively new again.
 *
 * Counts messages locally rather than reusing [UserProfile.messagesSentToday]:
 * that field is a *daily* quota counter that resets every calendar day, so it
 * can't answer "3rd message ever", and it's only written for free-tier users
 * (premium sends skip the user-document write entirely — see MessageLimit.kt).
 */
object RatePromptPrefs {

    /**
     * What earned the ask. Only ever changes the *eligibility* clause in
     * [shouldPrompt] — every gate before it is shared, which is what stops
     * three triggers stacking into three sheets.
     */
    enum class Trigger { THIRD_MESSAGE, NEW_MATCH, PREMIUM_PURCHASE }

    private const val PREFS_NAME = "wedora_prefs"

    private const val KEY_MESSAGES_SENT = "rate_messages_sent"
    private const val KEY_PROMPT_COUNT = "rate_prompt_count"
    private const val KEY_LAST_PROMPT_AT = "rate_last_prompt_at"
    private const val KEY_MESSAGES_AT_LAST_PROMPT = "rate_messages_at_last_prompt"
    private const val KEY_SETTLED = "rate_settled"
    private const val KEY_MATCH_ARMED = "rate_match_armed"

    /**
     * Whether a prompt has already been shown in this process.
     *
     * In memory on purpose: "one prompt per session" is exactly a
     * process-lifetime question, and the persisted [KEY_PROMPT_COUNT] plus
     * the re-ask cooldown already bound things across launches. This is the
     * single field that makes three independent triggers unable to stack —
     * they all set it through [recordPromptShown] and all read it through
     * [shouldPrompt].
     */
    @Volatile
    private var shownThisProcess = false

    /** Messages sent before the first ask — the "3rd message ever" milestone. */
    private const val FIRST_PROMPT_AT_MESSAGES = 3

    /**
     * A re-ask needs BOTH of these since the last prompt, not either.
     *
     * Messages alone would re-ask a chatty user hours after they dismissed;
     * elapsed time alone would re-ask someone who has largely stopped using
     * the app, at a random app open — the least receptive moment there is.
     * Together they land the re-ask on an engaged user who has had a real
     * break from it.
     */
    private const val REPROMPT_AFTER_MESSAGES = 10
    private const val REPROMPT_AFTER_MS = 7L * 24 * 60 * 60 * 1000

    /**
     * Hard lifetime cap: the first ask plus one re-ask, then never again.
     *
     * Lower than [UpdatePrefs.MAX_PROMPTS_PER_VERSION]'s 3 on purpose — an
     * update prompt can carry a security or compatibility fix, a rating
     * request carries nothing the user needs.
     */
    private const val MAX_PROMPTS = 2

    /** Counts one confirmed send. Returns the new lifetime total. */
    fun recordMessageSent(context: Context): Int {
        val p = prefs(context)
        val next = p.getInt(KEY_MESSAGES_SENT, 0) + 1
        p.edit().putInt(KEY_MESSAGES_SENT, next).apply()
        return next
    }

    /**
     * Arms the new-match trigger. Called from [MatchNotificationWatcher],
     * which detects the mutual transition app-wide but has no Activity to
     * show anything on — [HomeActivity] consumes this on its next onStart.
     *
     * Persisted rather than in-memory because the watcher and that consuming
     * Activity can be separated by a process death (a match landing via push
     * while the app isn't running is the normal case, not the edge case).
     *
     * Armed on *any* mutual match, not just the first: the shared cap in
     * [shouldPrompt] is what bounds spam, so there's no reason to also burn
     * this trigger permanently the first time it happens to fire while the
     * budget is on cooldown.
     */
    fun armMatchTrigger(context: Context) {
        prefs(context).edit().putBoolean(KEY_MATCH_ARMED, true).apply()
    }

    /**
     * Whether the prompt may be shown right now for [trigger].
     *
     * Everything before the `when` is shared by all three triggers, which is
     * the whole design: a trigger can only ever *satisfy the last clause*,
     * never grant itself an extra ask. [settle] wins over all of it.
     */
    fun shouldPrompt(context: Context, trigger: Trigger): Boolean {
        val p = prefs(context)

        // ---- Shared gates ----
        if (p.getBoolean(KEY_SETTLED, false)) return false
        if (shownThisProcess) return false

        val promptCount = p.getInt(KEY_PROMPT_COUNT, 0)
        if (promptCount >= MAX_PROMPTS) return false

        val messages = p.getInt(KEY_MESSAGES_SENT, 0)
        if (promptCount > 0) {
            val elapsed = System.currentTimeMillis() - p.getLong(KEY_LAST_PROMPT_AT, 0L)
            if (elapsed < REPROMPT_AFTER_MS) return false
        }

        // ---- Per-trigger eligibility ----
        return when (trigger) {
            Trigger.THIRD_MESSAGE -> {
                if (promptCount == 0) {
                    messages >= FIRST_PROMPT_AT_MESSAGES
                } else {
                    // A re-ask driven by messages needs fresh activity on top
                    // of the shared cooldown; a re-ask driven by a match or a
                    // purchase doesn't, because the event itself is the
                    // activity.
                    messages - p.getInt(KEY_MESSAGES_AT_LAST_PROMPT, 0) >= REPROMPT_AFTER_MESSAGES
                }
            }
            Trigger.NEW_MATCH -> p.getBoolean(KEY_MATCH_ARMED, false)
            // Rare by nature and always a high point — the shared cap above
            // is the only throttle it needs.
            Trigger.PREMIUM_PURCHASE -> true
        }
    }

    /**
     * Records that the prompt was put on screen. Called when it's shown, not
     * when it's answered — a sheet swiped away without touching either button
     * is still an ask the user has now seen, and should start the re-ask
     * clock exactly like "Maybe Later" does.
     */
    fun recordPromptShown(context: Context) {
        shownThisProcess = true
        val p = prefs(context)
        p.edit()
            .putInt(KEY_PROMPT_COUNT, p.getInt(KEY_PROMPT_COUNT, 0) + 1)
            .putLong(KEY_LAST_PROMPT_AT, System.currentTimeMillis())
            .putInt(KEY_MESSAGES_AT_LAST_PROMPT, p.getInt(KEY_MESSAGES_SENT, 0))
            // Disarmed whether or not the match is what triggered this ask:
            // the moment has passed either way, and leaving it armed would
            // let a months-old match fire the next prompt out of nowhere.
            .putBoolean(KEY_MATCH_ARMED, false)
            .apply()
    }

    /**
     * Ends it permanently. Set the moment "Rate Now" is tapped, not when the
     * store returns — nothing reports back whether a rating was actually
     * left, and re-asking someone who already went to the listing is worse
     * than missing a single rating.
     */
    fun settle(context: Context) {
        prefs(context).edit().putBoolean(KEY_SETTLED, true).apply()
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
