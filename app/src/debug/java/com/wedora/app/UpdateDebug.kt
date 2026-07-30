package com.wedora.app

import android.app.AlertDialog
import android.os.Handler
import android.os.Looper
import androidx.fragment.app.FragmentActivity

/**
 * Debug-only previewer for the six update surfaces.
 *
 * Exists because Play Core cannot be exercised on a locally-signed build: on a
 * build Play did not install, getAppUpdateInfo() reports nothing available no
 * matter what is live, so none of the six surfaces would ever appear on a dev
 * device. Play Console internal app sharing is the only way to see the real
 * flow — this is how the layout and motion get reviewed before that.
 *
 * Drives the surfaces by pushing fake states through
 * [UpdateRepository.setStateForPreview], so each screen renders through exactly
 * the same code path production uses. Nothing here mocks the views themselves.
 *
 * Reached by long-pressing the App Version row in Profile. Absent from release
 * builds entirely — see the release-variant stub of this file.
 */
object UpdateDebug {

    const val ENABLED = true

    private const val FAKE_VERSION = 99
    private const val FAKE_TOTAL_BYTES = 18L * 1024 * 1024

    private val handler = Handler(Looper.getMainLooper())
    private var downloadTicker: Runnable? = null

    fun showPicker(activity: FragmentActivity) {
        val options = arrayOf(
            "01 · Flexible nudge (sheet)",
            "02 · Downloading (ring)",
            "03 · Ready to install (snackbar)",
            "04 · Immediate / required (blocking)",
            "05 · Failed / retry",
            "06 · Settings row — pending",
            "06 · Settings row — up to date",
            "Reset to Idle"
        )

        AlertDialog.Builder(activity)
            .setTitle("Update states (debug)")
            .setItems(options) { _, which ->
                stopDownloadTicker()
                when (which) {
                    0 -> showNudge(activity)
                    1 -> showDownloading(activity)
                    2 -> showReady(activity)
                    3 -> showRequired(activity)
                    4 -> showFailed(activity)
                    5 -> showRowPending(activity)
                    6 -> UpdateRepository.setStateForPreview(UpdateState.UpToDate())
                    7 -> UpdateRepository.setStateForPreview(UpdateState.Idle)
                }
            }
            .show()
    }

    private fun available(path: UpdatePath) = UpdateState.Available(
        versionCode = FAKE_VERSION,
        totalBytes = FAKE_TOTAL_BYTES,
        priority = if (path == UpdatePath.IMMEDIATE_BLOCKING) 5 else 4,
        stalenessDays = 6,
        path = path
    )

    private fun showNudge(activity: FragmentActivity) {
        UpdateRepository.setStateForPreview(available(UpdatePath.FLEXIBLE_SHEET))
        UpdateNudgeBottomSheet().show(activity.supportFragmentManager, UpdateNudgeBottomSheet.TAG)
    }

    /**
     * Ticks fake bytes so the ring, percentage and MB counter all animate the
     * way they would against real byte events — including the 8-second stall
     * label if left running past the end.
     */
    private fun showDownloading(activity: FragmentActivity) {
        UpdateDownloadDialog().show(activity.supportFragmentManager, UpdateDownloadDialog.TAG)

        var bytes = 0L
        val step = FAKE_TOTAL_BYTES / 25
        val ticker = object : Runnable {
            override fun run() {
                bytes = (bytes + step).coerceAtMost(FAKE_TOTAL_BYTES)
                UpdateRepository.setStateForPreview(
                    UpdateState.Downloading(FAKE_VERSION, bytes, FAKE_TOTAL_BYTES)
                )
                if (bytes < FAKE_TOTAL_BYTES) handler.postDelayed(this, 120)
            }
        }
        downloadTicker = ticker
        handler.postDelayed(ticker, 300)
    }

    private fun showReady(activity: FragmentActivity) {
        UpdateRepository.setStateForPreview(UpdateState.Downloaded(FAKE_VERSION))
        UpdateReadySnackbar.show(activity, FAKE_VERSION)
    }

    private fun showRequired(activity: FragmentActivity) {
        UpdateRepository.setStateForPreview(available(UpdatePath.IMMEDIATE_BLOCKING))
        activity.startActivity(UpdateRequiredActivity.intent(activity))
    }

    private fun showFailed(activity: FragmentActivity) {
        UpdateRepository.setStateForPreview(
            UpdateState.Failed(
                versionCode = FAKE_VERSION,
                installErrorCode = -100,
                percentAtFailure = 41,
                retryCount = 1,
                blocking = false
            )
        )
        UpdateFailedDialog().show(activity.supportFragmentManager, UpdateFailedDialog.TAG)
    }

    /**
     * Clears the "already seen" watermark first, so the shimmer actually runs
     * again rather than being suppressed by a previous preview.
     */
    private fun showRowPending(activity: FragmentActivity) {
        UpdatePrefs.recordRowSeen(activity, 0)
        UpdateRepository.setStateForPreview(available(UpdatePath.PASSIVE_ROW))
    }

    private fun stopDownloadTicker() {
        downloadTicker?.let(handler::removeCallbacks)
        downloadTicker = null
    }
}
