package com.wedora.app

import android.util.Log
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.crashlytics.FirebaseCrashlytics
import java.util.concurrent.ConcurrentHashMap

/**
 * Crashlytics glue: the signed-in user identifier, and the handful of
 * non-fatal reports worth raising from otherwise-silent failure paths.
 *
 * Deliberately thin. Uncaught crashes need nothing from this object — the
 * SDK ships a ContentProvider in its own manifest, so it initializes and
 * installs its Thread.UncaughtExceptionHandler before any Activity starts.
 * Everything here is the *extra* signal on top of that.
 *
 * Attached once from [WedoraApplication.onCreate], mirroring how
 * [PremiumStatus] and [MatchNotificationWatcher] attach their own
 * AuthStateListeners.
 */
object CrashReporting {

    private const val TAG = "WedoraCrash"

    /**
     * Crashlytics, or null when it isn't available — never a throw.
     *
     * Resolved per call rather than held behind a `by lazy`. The lazy version
     * shipped a NullPointerException("FirebaseCrashlytics component is not
     * present") from [authListener] on real devices: getInstance() throws
     * when FirebaseApp.get() can't resolve the component, and this object's
     * attach() is the first Firebase call in WedoraApplication.onCreate, so
     * the immediate auth-state callback can land while the component graph
     * is still being built.
     *
     * The deeper problem wasn't the timing, though — it was that a `by lazy`
     * put a throwing call behind an innocuous property read, so all eleven
     * call sites silently inherited the risk of taking the process down.
     * Three of those sites are inside error handlers (see
     * AccountSettingsActivity's delete-account reporting), where a throw
     * would replace a user-visible error message with a crash.
     *
     * Telemetry is best-effort by definition: a report that can't be filed
     * is worth strictly less than the session it would have interrupted.
     * Losing one is the correct outcome; crashing is not.
     *
     * Not cached even on success. SynchronizedLazyImpl doesn't cache
     * exceptions, so the old version would have recovered on a later call
     * anyway; resolving each time keeps that recovery without the trap, and
     * getInstance() is a map lookup on an already-built component graph.
     */
    private fun crashlyticsOrNull(): FirebaseCrashlytics? = try {
        FirebaseCrashlytics.getInstance()
    } catch (e: Exception) {
        // NullPointerException in the observed case, but IllegalStateException
        // is also reachable here if the default FirebaseApp isn't initialized.
        // Log only — routing this through record() would recurse straight
        // back into the thing that just failed.
        Log.w(TAG, "Crashlytics unavailable; dropping this report", e)
        null
    }

    /**
     * Keys already reported this process, for [recordOnce]. A set rather
     * than a counter — the question these answer is "does this happen at
     * all", and the first occurrence carries that.
     */
    private val reportedOnce = ConcurrentHashMap.newKeySet<String>()

    /**
     * [FirebaseAuth.realUid], not `currentUser?.uid`: a guest signs in
     * anonymously, and that session's throwaway id would correlate nothing
     * across launches while still putting an identifier in the dashboard.
     * Guests therefore report crashes with no identifier attached — the
     * uncaught-exception handler is global and entirely independent of this.
     *
     * An empty string is Crashlytics' documented way to unset the id, which
     * is what a sign-out or a guest session should leave behind.
     */
    private val authListener = FirebaseAuth.AuthStateListener { auth ->
        // The site that actually crashed. It runs from a Firebase-posted
        // runnable during startup, so it is both the earliest and the least
        // controllable caller in here.
        crashlyticsOrNull()?.setUserId(auth.realUid.orEmpty())
    }

    fun attach() {
        FirebaseAuth.getInstance().addAuthStateListener(authListener)
    }

    /**
     * Reports a non-fatal. Also logs locally: Crashlytics batches uploads to
     * the next foreground/background transition, so logcat stays the only
     * way to see one during a hands-on debugging pass.
     *
     * [where] is attached to this report specifically (Crashlytics' log()
     * accumulates into the next event), not emitted as a standalone
     * breadcrumb — nothing in the app logs breadcrumbs on its own.
     */
    fun record(throwable: Throwable, where: String) {
        // Logged first, and unconditionally: if Crashlytics can't be reached
        // this line is the only remaining trace of the failure.
        Log.w(TAG, "Non-fatal at $where", throwable)

        val crashlytics = crashlyticsOrNull() ?: return
        crashlytics.log(where)
        crashlytics.recordException(throwable)
    }

    /**
     * [record], but at most once per [key] per process.
     *
     * For failures that occur inside a loop over a Firestore snapshot, where
     * one systematically malformed collection would otherwise raise a report
     * per document and bury everything else in the dashboard. The first
     * occurrence is the signal; the rest are the same finding repeated.
     */
    fun recordOnce(key: String, throwable: Throwable, where: String) {
        if (!reportedOnce.add(key)) return
        record(throwable, where)
    }
}
