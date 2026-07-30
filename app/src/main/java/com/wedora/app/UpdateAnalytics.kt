package com.wedora.app

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Bundle
import android.util.Log
import com.google.firebase.analytics.FirebaseAnalytics

/**
 * Every GA4 event in the update spec, in one object.
 *
 * Centralised deliberately: the funnel the spec asks to watch
 * (update_available -> update_prompt_view -> update_accepted ->
 * update_download_complete -> update_installed) is only readable if the event
 * names and parameter keys are identical across all six surfaces, and that is
 * far easier to guarantee from one file than from six call sites.
 *
 * Also mirrors each event to logcat under [TAG]. That is not redundant with
 * GA4 — Firebase's DebugView needs a device flag and has minutes of latency,
 * which makes it useless for confirming ordering during a hands-on pass.
 */
object UpdateAnalytics {

    private const val TAG = "WedoraUpdate"

    // Param keys. Reused across events, so a rename can't desync two surfaces.
    private const val P_AVAILABLE_VERSION = "available_version"
    private const val P_CURRENT_VERSION = "current_version"
    private const val P_MINIMUM_VERSION = "minimum_version"
    private const val P_PREVIOUS_VERSION = "previous_version"
    private const val P_NEW_VERSION = "new_version"
    private const val P_UPDATE_PRIORITY = "update_priority"
    private const val P_STALENESS_DAYS = "staleness_days"
    private const val P_UPDATE_TYPE = "update_type"
    private const val P_SURFACE = "surface"
    private const val P_PROMPT_COUNT = "prompt_count"
    private const val P_METHOD = "method"
    private const val P_TOTAL_BYTES = "total_bytes"
    private const val P_CONNECTION_TYPE = "connection_type"
    private const val P_PROGRESS_PCT = "progress_pct"
    private const val P_DURATION_MS = "duration_ms"
    private const val P_TIME_TO_RESTART_MS = "time_to_restart_ms"
    private const val P_INSTALL_ERROR_CODE = "install_error_code"
    private const val P_PROGRESS_PCT_AT_FAILURE = "progress_pct_at_failure"
    private const val P_RETRY_COUNT = "retry_count"
    private const val P_RESULT_CODE = "result_code"
    private const val P_REASON_CODE = "reason_code"

    const val TYPE_FLEXIBLE = "flexible"
    const val TYPE_IMMEDIATE = "immediate"

    const val SURFACE_BOTTOM_SHEET = "bottom_sheet"
    const val SURFACE_SNACKBAR = "snackbar"
    const val SURFACE_SETTINGS_ROW = "settings_row"
    const val SURFACE_BLOCKING = "blocking_screen"

    const val METHOD_LATER = "later"
    const val METHOD_SCRIM = "scrim"
    const val METHOD_DRAG = "drag"

    private var analytics: FirebaseAnalytics? = null

    fun attach(context: Context) {
        analytics = FirebaseAnalytics.getInstance(context.applicationContext)
    }

    // ----- 01 flexible nudge ---------------------------------------------

    fun availableUpdate(
        availableVersion: Int,
        currentVersion: Int,
        priority: Int,
        stalenessDays: Int,
        type: String
    ) = log("update_available") {
        putLong(P_AVAILABLE_VERSION, availableVersion.toLong())
        putLong(P_CURRENT_VERSION, currentVersion.toLong())
        putLong(P_UPDATE_PRIORITY, priority.toLong())
        putLong(P_STALENESS_DAYS, stalenessDays.toLong())
        putString(P_UPDATE_TYPE, type)
    }

    fun promptView(surface: String, availableVersion: Int, promptCount: Int) =
        log("update_prompt_view") {
            putString(P_SURFACE, surface)
            putLong(P_AVAILABLE_VERSION, availableVersion.toLong())
            putLong(P_PROMPT_COUNT, promptCount.toLong())
        }

    /** The conversion metric — every "start the update" tap, from any surface. */
    fun accepted(availableVersion: Int, type: String, surface: String) =
        log("update_accepted") {
            putLong(P_AVAILABLE_VERSION, availableVersion.toLong())
            putString(P_UPDATE_TYPE, type)
            putString(P_SURFACE, surface)
        }

    /** Watch prompt_count >= 3 here: that is nag fatigue, not indecision. */
    fun dismissed(availableVersion: Int, promptCount: Int, method: String) =
        log("update_dismissed") {
            putLong(P_AVAILABLE_VERSION, availableVersion.toLong())
            putLong(P_PROMPT_COUNT, promptCount.toLong())
            putString(P_METHOD, method)
        }

    // ----- 02 downloading -------------------------------------------------

    fun downloadStart(context: Context, availableVersion: Int, totalBytes: Long) =
        log("update_download_start") {
            putLong(P_AVAILABLE_VERSION, availableVersion.toLong())
            putLong(P_TOTAL_BYTES, totalBytes)
            putString(P_CONNECTION_TYPE, connectionType(context))
        }

    fun backgrounded(availableVersion: Int, progressPct: Int) =
        log("update_backgrounded") {
            putLong(P_AVAILABLE_VERSION, availableVersion.toLong())
            putLong(P_PROGRESS_PCT, progressPct.toLong())
        }

    fun downloadComplete(availableVersion: Int, durationMs: Long, totalBytes: Long) =
        log("update_download_complete") {
            putLong(P_AVAILABLE_VERSION, availableVersion.toLong())
            putLong(P_DURATION_MS, durationMs)
            putLong(P_TOTAL_BYTES, totalBytes)
        }

    // ----- 03 ready to install -------------------------------------------

    fun readyView(availableVersion: Int, surface: String = SURFACE_SNACKBAR) =
        log("update_ready_view") {
            putLong(P_AVAILABLE_VERSION, availableVersion.toLong())
            putString(P_SURFACE, surface)
        }

    /** Measures how long users sit on an already-downloaded update. */
    fun installStart(availableVersion: Int, timeToRestartMs: Long) =
        log("update_install_start") {
            putLong(P_AVAILABLE_VERSION, availableVersion.toLong())
            putLong(P_TIME_TO_RESTART_MS, timeToRestartMs)
        }

    fun installed(previousVersion: Int, newVersion: Int, type: String) =
        log("update_installed") {
            putLong(P_PREVIOUS_VERSION, previousVersion.toLong())
            putLong(P_NEW_VERSION, newVersion.toLong())
            putString(P_UPDATE_TYPE, type)
        }

    // ----- 04 immediate ---------------------------------------------------

    /** Volume here is a churn-risk signal, not a health metric. */
    fun requiredView(currentVersion: Int, minimumVersion: Int, reasonCode: String) =
        log("update_required_view") {
            putLong(P_CURRENT_VERSION, currentVersion.toLong())
            putLong(P_MINIMUM_VERSION, minimumVersion.toLong())
            putString(P_REASON_CODE, reasonCode)
        }

    /** These users are stuck on a build that can't run — segment and monitor. */
    fun flowCancelled(type: String, resultCode: Int) =
        log("update_flow_cancelled") {
            putString(P_UPDATE_TYPE, type)
            putLong(P_RESULT_CODE, resultCode.toLong())
        }

    // ----- 05 failed ------------------------------------------------------

    fun failed(
        context: Context,
        availableVersion: Int,
        installErrorCode: Int,
        progressPctAtFailure: Int
    ) = log("update_failed") {
        putLong(P_AVAILABLE_VERSION, availableVersion.toLong())
        putLong(P_INSTALL_ERROR_CODE, installErrorCode.toLong())
        putLong(P_PROGRESS_PCT_AT_FAILURE, progressPctAtFailure.toLong())
        putString(P_CONNECTION_TYPE, connectionType(context))
    }

    fun retry(availableVersion: Int, retryCount: Int) = log("update_retry") {
        putLong(P_AVAILABLE_VERSION, availableVersion.toLong())
        putLong(P_RETRY_COUNT, retryCount.toLong())
    }

    fun abandoned(availableVersion: Int, retryCount: Int) = log("update_abandoned") {
        putLong(P_AVAILABLE_VERSION, availableVersion.toLong())
        putLong(P_RETRY_COUNT, retryCount.toLong())
    }

    // ----- Check couldn't run --------------------------------------------

    /**
     * Sideloaded build, no Play Services, or the check threw. Logged so the
     * install base that CANNOT be reached by an update prompt is measurable —
     * without it those users are indistinguishable from up-to-date ones.
     */
    fun checkUnavailable(reason: String) = log("update_check_unavailable") {
        putString(P_REASON_CODE, reason)
    }

    // ----- Plumbing -------------------------------------------------------

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

    /** "wifi" | "cellular" | "other" | "none" — enough to split retry rates by. */
    private fun connectionType(context: Context): String {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return "none"
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return "none"
        return when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular"
            else -> "other"
        }
    }
}
