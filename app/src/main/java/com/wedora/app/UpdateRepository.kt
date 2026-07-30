package com.wedora.app

import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.google.android.play.core.appupdate.AppUpdateInfo
import com.google.android.play.core.appupdate.AppUpdateManager
import com.google.android.play.core.appupdate.AppUpdateManagerFactory
import com.google.android.play.core.appupdate.AppUpdateOptions
import com.google.android.play.core.install.InstallStateUpdatedListener
import com.google.android.play.core.install.model.AppUpdateType
import com.google.android.play.core.install.model.InstallStatus
import com.google.android.play.core.install.model.UpdateAvailability

/**
 * The one place that talks to Play Core.
 *
 * Every surface — nudge sheet, download dialog, ready snackbar, blocking
 * screen, failure dialog, App Version row, and the profile-avatar badge —
 * renders from [state] and calls the methods here. No screen constructs its
 * own AppUpdateManager, which is what keeps six surfaces from disagreeing
 * about whether an update exists.
 *
 * Exposed as a plain observable rather than a StateFlow so this stays usable
 * from the app's View-based Activities without dragging a coroutine scope into
 * each one; the app has no other Flow consumers to be consistent with.
 *
 * Play Core caveat worth knowing before testing: on a build that Play did not
 * install (a locally-signed debug APK, or a non-GMS device) getAppUpdateInfo()
 * fails or reports UPDATE_NOT_AVAILABLE regardless of what is actually live.
 * That is not a bug to work around — see [UpdateDebug] for how the surfaces
 * are exercised instead, and note the spec's own rule: never fall back to
 * linking a raw APK.
 */
object UpdateRepository {

    private const val TAG = "WedoraUpdate"

    /** No byte event for this long and the dialog swaps to a "still downloading" label. */
    const val STALL_TIMEOUT_MS = 8_000L

    /** Priority at or above which an update becomes a blocking, non-dismissible gate. */
    private const val PRIORITY_IMMEDIATE = 5

    /** Priority at or above which a dismissible sheet is earned. */
    private const val PRIORITY_SHEET = 4

    /** Days of staleness that earn a sheet even at a low priority. */
    private const val STALENESS_SHEET_DAYS = 5

    /** Retry backoff, per the spec: immediately, then 30 s, then 2 min. */
    private val RETRY_BACKOFF_MS = longArrayOf(0L, 30_000L, 120_000L)

    fun interface Observer {
        fun onUpdateState(state: UpdateState)
    }

    private lateinit var appContext: Context
    private var manager: AppUpdateManager? = null
    private val handler = Handler(Looper.getMainLooper())
    private val observers = mutableSetOf<Observer>()

    /** Last AppUpdateInfo, kept so a surface can start a flow without re-querying. */
    private var cachedInfo: AppUpdateInfo? = null

    private var downloadStartedAt = 0L
    private var stallCheck: Runnable? = null

    var state: UpdateState = UpdateState.Idle
        private set(value) {
            field = value
            observers.toList().forEach { it.onUpdateState(value) }
        }

    /**
     * True once a flexible flow has been started this session, so the download
     * dialog can distinguish "Play is downloading because the user asked" from
     * a download Play resumed on its own in the background.
     */
    var userStartedFlow = false
        private set

    private val installListener = InstallStateUpdatedListener { install ->
        val versionCode = cachedInfo?.availableVersionCode() ?: 0
        when (install.installStatus()) {
            InstallStatus.DOWNLOADING -> {
                if (downloadStartedAt == 0L) {
                    downloadStartedAt = System.currentTimeMillis()
                    UpdateAnalytics.downloadStart(
                        appContext, versionCode, install.totalBytesToDownload()
                    )
                }
                armStallCheck(install.bytesDownloaded())
                state = UpdateState.Downloading(
                    versionCode = versionCode,
                    bytesDownloaded = install.bytesDownloaded(),
                    totalBytes = install.totalBytesToDownload()
                )
            }

            InstallStatus.DOWNLOADED -> {
                cancelStallCheck()
                UpdatePrefs.clearRetries(appContext)
                if (downloadStartedAt != 0L) {
                    UpdateAnalytics.downloadComplete(
                        versionCode,
                        System.currentTimeMillis() - downloadStartedAt,
                        install.totalBytesToDownload()
                    )
                    downloadStartedAt = 0L
                }
                state = UpdateState.Downloaded(versionCode)
            }

            // FAILED, CANCELED and INSTALL_FAILED share one visual; only the
            // error code distinguishes them, and that goes to analytics.
            InstallStatus.FAILED, InstallStatus.CANCELED -> {
                cancelStallCheck()
                val pct = (state as? UpdateState.Downloading)?.percent ?: 0
                UpdatePrefs.recordRetry(appContext)
                UpdateAnalytics.failed(appContext, versionCode, install.installErrorCode(), pct)
                val retries = UpdatePrefs.retryCount(appContext)
                state = if (retries >= UpdatePrefs.MAX_RETRIES) {
                    // Budget spent: stop interrupting and let the App Version
                    // row carry it from here.
                    UpdateAnalytics.abandoned(versionCode, retries)
                    availableFrom(cachedInfo, forcePath = UpdatePath.PASSIVE_ROW)
                } else {
                    UpdateState.Failed(
                        versionCode = versionCode,
                        installErrorCode = install.installErrorCode(),
                        percentAtFailure = pct,
                        retryCount = retries,
                        blocking = isBlocking(cachedInfo)
                    )
                }
                downloadStartedAt = 0L
            }

            else -> Unit
        }
    }

    /** Call once from [WedoraApplication.onCreate]. */
    fun attach(context: Context) {
        appContext = context.applicationContext
        manager = runCatching { AppUpdateManagerFactory.create(appContext) }
            .onFailure {
                // A device with no Play Store at all — the factory itself can
                // throw. Everything below no-ops from here.
                Log.w(TAG, "Play Core unavailable on this device", it)
                UpdateAnalytics.checkUnavailable("no_play_core")
            }
            .getOrNull()
        manager?.registerListener(installListener)
        reportInstallIfUpgraded()
    }

    fun addObserver(observer: Observer) {
        observers += observer
        observer.onUpdateState(state)
    }

    fun removeObserver(observer: Observer) {
        observers -= observer
    }

    /**
     * Queries Play unless a completed check is still inside the 4-hour cache.
     * [force] bypasses the cache — used by the App Version row's long-press,
     * which exists so support can get a definite answer on demand.
     */
    fun check(force: Boolean = false) {
        val mgr = manager ?: run {
            state = UpdateState.UpToDate(reachable = false)
            return
        }
        if (!force && !UpdatePrefs.checkCacheExpired(appContext) && state != UpdateState.Idle) {
            return
        }

        mgr.appUpdateInfo
            .addOnSuccessListener { info ->
                UpdatePrefs.recordCheck(appContext)
                cachedInfo = info
                onInfo(info)
            }
            .addOnFailureListener { error ->
                // Sideloaded build, no Play account, or offline. Suppress every
                // surface — never surface a download route Play didn't give us.
                Log.w(TAG, "getAppUpdateInfo failed", error)
                UpdateAnalytics.checkUnavailable("info_failed")
                state = UpdateState.UpToDate(reachable = false)
            }
    }

    private fun onInfo(info: AppUpdateInfo) {
        // A flow already in progress outranks a fresh decision — the user is
        // mid-update and must not be dropped back to a nudge.
        if (info.updateAvailability() == UpdateAvailability.DEVELOPER_TRIGGERED_UPDATE_IN_PROGRESS) {
            state = if (info.installStatus() == InstallStatus.DOWNLOADED) {
                UpdateState.Downloaded(info.availableVersionCode())
            } else {
                UpdateState.Downloading(
                    versionCode = info.availableVersionCode(),
                    bytesDownloaded = info.bytesDownloaded(),
                    totalBytes = info.totalBytesToDownload()
                )
            }
            return
        }

        if (info.updateAvailability() != UpdateAvailability.UPDATE_AVAILABLE) {
            state = UpdateState.UpToDate()
            return
        }

        val available = availableFrom(info)
        state = available
        if (available is UpdateState.Available) {
            UpdateAnalytics.availableUpdate(
                availableVersion = available.versionCode,
                currentVersion = currentVersionCode(),
                priority = available.priority,
                stalenessDays = available.stalenessDays,
                type = if (available.path == UpdatePath.IMMEDIATE_BLOCKING) {
                    UpdateAnalytics.TYPE_IMMEDIATE
                } else {
                    UpdateAnalytics.TYPE_FLEXIBLE
                }
            )
        }
    }

    private fun availableFrom(info: AppUpdateInfo?, forcePath: UpdatePath? = null): UpdateState {
        if (info == null) return UpdateState.UpToDate()
        val priority = info.updatePriority()
        val staleness = info.clientVersionStalenessDays() ?: 0
        return UpdateState.Available(
            versionCode = info.availableVersionCode(),
            totalBytes = info.totalBytesToDownload(),
            priority = priority,
            stalenessDays = staleness,
            path = forcePath ?: pathFor(info.availableVersionCode(), priority, staleness)
        )
    }

    /**
     * The spec's decision table, in one place.
     *
     * The nag budget is applied HERE rather than at the sheet: a spent budget
     * demotes an update to the passive row, so "we already asked three times"
     * produces the same state everywhere instead of a sheet that decides not
     * to show itself and leaves other surfaces thinking a sheet is up.
     *
     * The Remote Config floor is checked before Play's priority so a security
     * issue found after publish can still force an update — Play's priority is
     * fixed at release time and cannot be raised afterwards.
     */
    fun pathFor(versionCode: Int, priority: Int, stalenessDays: Int): UpdatePath {
        val minSupported = UpdateCopy.minSupportedVersion()
        if (minSupported > 0 && currentVersionCode() < minSupported) {
            return UpdatePath.IMMEDIATE_BLOCKING
        }
        if (priority >= PRIORITY_IMMEDIATE) return UpdatePath.IMMEDIATE_BLOCKING

        val earnsSheet = priority >= PRIORITY_SHEET || stalenessDays >= STALENESS_SHEET_DAYS
        if (!earnsSheet) return UpdatePath.PASSIVE_ROW

        // Interruption is allowed only if the budget has room; otherwise the
        // update is real but demoted to the row.
        return if (UpdatePrefs.mayPrompt(appContext, versionCode)) {
            UpdatePath.FLEXIBLE_SHEET
        } else {
            UpdatePath.PASSIVE_ROW
        }
    }

    private fun isBlocking(info: AppUpdateInfo?): Boolean =
        (availableFrom(info) as? UpdateState.Available)?.path == UpdatePath.IMMEDIATE_BLOCKING

    // ----- Starting flows -------------------------------------------------

    /**
     * Starts the flexible (background-download) flow. Play renders its own
     * confirmation; our sheet is the invitation, not the consent dialog.
     */
    fun startFlexible(activity: Activity, surface: String) {
        val mgr = manager ?: return
        val info = cachedInfo ?: return
        userStartedFlow = true
        UpdateAnalytics.accepted(
            info.availableVersionCode(), UpdateAnalytics.TYPE_FLEXIBLE, surface
        )
        runCatching {
            mgr.startUpdateFlowForResult(
                info,
                activity,
                AppUpdateOptions.newBuilder(AppUpdateType.FLEXIBLE).build(),
                REQUEST_CODE
            )
        }.onFailure { Log.w(TAG, "startUpdateFlowForResult(FLEXIBLE) failed", it) }
    }

    /** Starts the blocking flow; after this Play owns the whole screen. */
    fun startImmediate(activity: Activity, surface: String = UpdateAnalytics.SURFACE_BLOCKING) {
        val mgr = manager ?: return
        val info = cachedInfo ?: return
        userStartedFlow = true
        UpdateAnalytics.accepted(
            info.availableVersionCode(), UpdateAnalytics.TYPE_IMMEDIATE, surface
        )
        runCatching {
            mgr.startUpdateFlowForResult(
                info,
                activity,
                AppUpdateOptions.newBuilder(AppUpdateType.IMMEDIATE).build(),
                REQUEST_CODE
            )
        }.onFailure { Log.w(TAG, "startUpdateFlowForResult(IMMEDIATE) failed", it) }
    }

    /** Hands off to Play's install + relaunch. Save in-flight drafts first. */
    fun completeUpdate() {
        manager?.completeUpdate()
    }

    /** Delay before the next retry is allowed, per the spec's backoff ladder. */
    fun retryDelayMs(): Long =
        RETRY_BACKOFF_MS[UpdatePrefs.retryCount(appContext).coerceIn(0, RETRY_BACKOFF_MS.lastIndex)]

    fun onFlowCancelled(resultCode: Int, immediate: Boolean) {
        UpdateAnalytics.flowCancelled(
            if (immediate) UpdateAnalytics.TYPE_IMMEDIATE else UpdateAnalytics.TYPE_FLEXIBLE,
            resultCode
        )
    }

    fun onDismissed(versionCode: Int, method: String) {
        UpdatePrefs.recordDismissed(appContext, versionCode)
        UpdateAnalytics.dismissed(
            versionCode, UpdatePrefs.promptCount(appContext, versionCode), method
        )
        // Demote rather than clear: the update still exists, it just loses the
        // right to interrupt again for now.
        state = availableFrom(cachedInfo, forcePath = UpdatePath.PASSIVE_ROW)
    }

    // ----- Version helpers ------------------------------------------------

    /**
     * Read from PackageManager rather than BuildConfig — this module has
     * buildConfig generation off (AGP 8 default), and PackageManager is the
     * value actually installed either way.
     */
    fun currentVersionCode(): Int = runCatching {
        val pkg = appContext.packageManager.getPackageInfo(appContext.packageName, 0)
        @Suppress("DEPRECATION")
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
            pkg.longVersionCode.toInt()
        } else {
            pkg.versionCode
        }
    }.getOrDefault(0)

    fun currentVersionName(): String = runCatching {
        appContext.packageManager.getPackageInfo(appContext.packageName, 0).versionName
    }.getOrNull() ?: ""

    /**
     * Play never says "you were just updated", so the only way to detect it is
     * to compare a stored watermark on each launch.
     */
    private fun reportInstallIfUpgraded() {
        val current = currentVersionCode()
        val previous = UpdatePrefs.lastRunVersion(appContext)
        if (previous != 0 && previous < current) {
            UpdateAnalytics.installed(previous, current, UpdateAnalytics.TYPE_FLEXIBLE)
        }
        if (previous != current) UpdatePrefs.recordRunVersion(appContext, current)
    }

    // ----- Stall detection ------------------------------------------------

    /**
     * A download that stops producing byte events looks identical to a slow one
     * on a percentage alone. After [STALL_TIMEOUT_MS] the state is re-emitted
     * with `stalled = true` so the dialog can say so, without changing the
     * progress value itself (which would be inventing data).
     */
    private fun armStallCheck(bytes: Long) {
        cancelStallCheck()
        val runnable = Runnable {
            val current = state
            if (current is UpdateState.Downloading && current.bytesDownloaded == bytes) {
                state = current.copy(stalled = true)
            }
        }
        stallCheck = runnable
        handler.postDelayed(runnable, STALL_TIMEOUT_MS)
    }

    private fun cancelStallCheck() {
        stallCheck?.let(handler::removeCallbacks)
        stallCheck = null
    }

    /** Test seam for [UpdateDebug]; never called in production code. */
    fun setStateForPreview(preview: UpdateState) {
        state = preview
    }

    const val REQUEST_CODE = 4711
}
