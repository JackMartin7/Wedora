package com.wedora.app

import android.content.Context
import android.content.Intent
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner

/**
 * Shows the full-screen "It's mutual" moment when a match becomes mutual.
 *
 * Attached once from Application.onCreate, like the watchers it reads from. It
 * observes MatchNotificationWatcher rather than opening a listener of its own,
 * so it costs no reads: that listener is already live for the whole process.
 *
 * WHY THIS EXISTS AT ALL: mutual like is now the gate for messaging, not a
 * notification-copy distinction. The moment the second like lands is the moment
 * a conversation becomes possible, and that is worth marking rather than
 * leaving the user to notice a composer quietly unlocking.
 *
 * Two guards keep it from firing wrongly, and both matter:
 *
 *  - The FIRST emit after attach only seeds [knownMutual]. On a cold start the
 *    watcher replays every existing match at once, and without seeding, every
 *    mutual match the user has ever had would look newly mutual and queue a
 *    celebration.
 *
 *  - [MatchCelebrationPrefs] records which matches have been celebrated, so a
 *    reinstall or a process restart cannot replay one. Seeding alone is not
 *    enough: a match that turns mutual while the app is closed would otherwise
 *    celebrate on the next cold start, which is the right moment, but would do
 *    so again on every subsequent one.
 *
 * Only fires while the app is in the FOREGROUND. If the match completes while
 * the user is away, the Cloud Function's push carries the same copy, and the
 * celebration appears the next time they open the app.
 */
object MatchCelebration : DefaultLifecycleObserver {

    private var appContext: Context? = null
    private var seeded = false
    private var isForeground = false

    /** Match ids already known to be mutual, so only NEW ones celebrate. */
    private val knownMutual = mutableSetOf<String>()

    /** Queued while backgrounded, shown on the next foreground. */
    private var pending: Match? = null

    private val onMatches: (List<Match>, String?) -> Unit = { matches, selfUid ->
        if (selfUid == null) {
            // Signed out: forget everything rather than celebrating the next
            // account's matches against this one's memory.
            seeded = false
            knownMutual.clear()
            pending = null
        } else {
            val mutual = matches.filter { it.isMutual() }
            if (!seeded) {
                knownMutual.addAll(mutual.map { it.id })
                seeded = true
            } else {
                val ctx = appContext
                val fresh = mutual.firstOrNull {
                    it.id !in knownMutual && ctx != null &&
                        !MatchCelebrationPrefs.wasCelebrated(ctx, it.id)
                }
                knownMutual.addAll(mutual.map { it.id })
                if (fresh != null) {
                    pending = fresh
                    if (isForeground) showPending()
                }
            }
        }
    }

    fun attach(context: Context) {
        appContext = context.applicationContext
        ProcessLifecycleOwner.get().lifecycle.addObserver(this)
        MatchNotificationWatcher.addObserver(onMatches)
    }

    override fun onStart(owner: LifecycleOwner) {
        isForeground = true
        showPending()
    }

    override fun onStop(owner: LifecycleOwner) {
        isForeground = false
    }

    private fun showPending() {
        val ctx = appContext ?: return
        val match = pending ?: return
        val selfUid = com.google.firebase.auth.FirebaseAuth.getInstance().realUid ?: return
        val otherUid = match.otherUserId(selfUid) ?: return

        // Marked before launching, not after: if the Activity is killed on the
        // way up, the alternative is celebrating the same match on every
        // foreground until it happens to survive.
        MatchCelebrationPrefs.markCelebrated(ctx, match.id)
        pending = null

        ctx.startActivity(
            MatchCelebrationActivity.intent(ctx, otherUid)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }
}

/** Which matches have already had their celebration shown. */
object MatchCelebrationPrefs {
    private const val PREFS = "wedora_prefs"
    private const val KEY = "celebrated_matches"

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun wasCelebrated(context: Context, matchId: String): Boolean =
        prefs(context).getStringSet(KEY, emptySet())?.contains(matchId) == true

    fun markCelebrated(context: Context, matchId: String) {
        val current = prefs(context).getStringSet(KEY, emptySet()).orEmpty()
        prefs(context).edit().putStringSet(KEY, current + matchId).apply()
    }
}
