package com.wedora.app

/**
 * Everything the six update surfaces render from — see [UpdateRepository],
 * which is the only thing that ever produces one of these.
 *
 * Deliberately carries raw byte counts rather than a pre-computed percentage:
 * the download dialog shows both a percentage AND a megabyte figure, and the
 * megabyte figure is specifically what makes a stalled download legible (a
 * percentage that hasn't moved could just be a slow repaint).
 */
sealed class UpdateState {

    /** Nothing checked yet this session. No surface shows. */
    object Idle : UpdateState()

    /**
     * Play says this build is current, OR the check couldn't run at all
     * (sideloaded build, no Play Services, network failure). Both collapse to
     * the same thing on screen: the App Version row reads "up to date" and
     * nothing else appears. [reachable] separates them for the Settings row's
     * own copy and for analytics — never to unlock some alternate download
     * route, which is why there is no "unavailable" state with its own UI.
     */
    data class UpToDate(val reachable: Boolean = true) : UpdateState()

    /** An update exists and has not started downloading. */
    data class Available(
        val versionCode: Int,
        val totalBytes: Long,
        val priority: Int,
        val stalenessDays: Int,
        val path: UpdatePath
    ) : UpdateState()

    data class Downloading(
        val versionCode: Int,
        val bytesDownloaded: Long,
        val totalBytes: Long,
        /** True once no byte event has arrived for [UpdateRepository.STALL_TIMEOUT_MS]. */
        val stalled: Boolean = false
    ) : UpdateState() {
        /** 0..100, floored. Zero when Play hasn't reported a total yet. */
        val percent: Int
            get() = if (totalBytes <= 0L) 0 else ((bytesDownloaded * 100) / totalBytes).toInt()
    }

    /** Bytes are on disk; the install needs a restart. */
    data class Downloaded(val versionCode: Int) : UpdateState()

    /**
     * FAILED, CANCELED and INSTALL_FAILED all land here — the spec puts one
     * visual behind all three and keeps the distinction only in
     * [installErrorCode], which goes to analytics.
     */
    data class Failed(
        val versionCode: Int,
        val installErrorCode: Int,
        val percentAtFailure: Int,
        val retryCount: Int,
        /** Under a forced update the failure dialog drops its "Not now" exit. */
        val blocking: Boolean
    ) : UpdateState()
}

/**
 * Which surface an [UpdateState.Available] earns, decided once in
 * [UpdateRepository.pathFor] from Play's own priority and staleness rather
 * than by each screen guessing.
 */
enum class UpdatePath {
    /** No update, or the check couldn't run. Nothing shown anywhere. */
    SILENT,

    /**
     * Priority 0–3: routine release. Only the App Version row tints — a
     * bug-fix build does not earn an interruption.
     */
    PASSIVE_ROW,

    /** Priority 4, or staleness >= 5 days: the dismissible bottom sheet. */
    FLEXIBLE_SHEET,

    /** Priority 5: security / breaking API. Blocking, no dismiss. */
    IMMEDIATE_BLOCKING
}
