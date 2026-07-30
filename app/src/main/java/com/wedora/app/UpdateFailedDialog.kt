package com.wedora.app

import android.app.Dialog
import android.os.Bundle
import android.view.HapticFeedbackConstants
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import androidx.fragment.app.DialogFragment
import com.wedora.app.databinding.DialogUpdateFailedBinding

/**
 * 05 · The download failed, and can be resumed.
 *
 * FAILED, CANCELED and INSTALL_FAILED all land here behind one visual; only the
 * error code distinguishes them and that goes to analytics, not to the user.
 *
 * The emotional work is done by a single shake — never a loop, never red, never
 * alarm iconography. A recoverable network hiccup that looks like a crash makes
 * users stop retrying, which is the opposite of what this dialog is for.
 */
class UpdateFailedDialog : DialogFragment() {

    private var binding: DialogUpdateFailedBinding? = null

    /**
     * Bare dialog; content comes from [onCreateView] — see
     * UpdateDownloadDialog's own note on why setContentView(view) is wrong here
     * (it discards the layout's params and clamps the card).
     */
    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog =
        Dialog(requireContext(), R.style.WedoraUpdateDialog)

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = DialogUpdateFailedBinding.inflate(inflater, container, false)
        .also { binding = it }.root

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val b = binding ?: return

        val failed = UpdateRepository.state as? UpdateState.Failed

        b.tvBody.text = if (failed != null && failed.percentAtFailure > 0) {
            getString(R.string.update_failed_body, failed.percentAtFailure)
        } else {
            // Claiming a percentage we don't have would undercut the one
            // sentence that makes retrying feel worthwhile.
            getString(R.string.update_failed_body_no_progress)
        }

        // Under a forced update Retry is the only action — there is nowhere to
        // defer to.
        b.btnLater.visibility = if (failed?.blocking == true) View.GONE else View.VISIBLE

        b.btnRetry.addPressScale()
        b.btnRetry.setOnClickListener {
            UpdateAnalytics.retry(
                failed?.versionCode ?: 0,
                UpdatePrefs.retryCount(requireContext())
            )
            // Play resumes from the bytes it retained rather than starting over.
            UpdateRepository.startFlexible(requireActivity(), UpdateAnalytics.SURFACE_BOTTOM_SHEET)
            dismiss()
        }
        b.btnLater.setOnClickListener {
            UpdateAnalytics.abandoned(
                failed?.versionCode ?: 0,
                UpdatePrefs.retryCount(requireContext())
            )
            dismiss()
        }

        // Tap-outside dismisses — except on the forced path, where there is
        // nothing behind this to return to. The window is full-screen, so this
        // replaces setCanceledOnTouchOutside.
        val blocking = failed?.blocking == true
        isCancelable = !blocking
        if (!blocking) b.dialogScrim.setOnClickListener { dismiss() }
    }

    override fun onStart() {
        super.onStart()
        // Full-screen so the centred card keeps its declared width — see
        // UpdateDownloadDialog for the full reasoning.
        dialog?.window?.setLayout(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT
        )
        playTimeline()
    }

    private fun playTimeline() {
        val b = binding ?: return

        if (Motion.reducedMotion(b.root)) {
            b.ivIcon.alpha = 1f
            b.ivIcon.scaleX = 1f
            b.ivIcon.scaleY = 1f
            b.btnRetry.alpha = 1f
            b.btnLater.alpha = 1f
            // The spec swaps the shake for a haptic under reduced motion, so
            // the failure is still *felt* when it can't be seen.
            b.root.performHapticFeedback(HapticFeedbackConstants.REJECT)
            return
        }

        Motion.popIn(b.ivIcon, durationMs = 450, delayMs = 160)
        Motion.riseUp(b.btnRetry, durationMs = 400, delayMs = 760, fromYDp = 18f)
        // "Not now" fades rather than rises, and lands last: a visible exit
        // that isn't competing with Retry for attention.
        b.btnLater.alpha = 0f
        b.btnLater.animate().alpha(1f).setStartDelay(860).setDuration(400).start()

        // One shake, once, synced with a single REJECT haptic.
        b.root.postDelayed({
            binding?.root?.let { root ->
                Motion.shake(root)
                root.performHapticFeedback(HapticFeedbackConstants.REJECT)
            }
        }, 420)
    }

    override fun onDestroyView() {
        binding = null
        super.onDestroyView()
    }

    companion object {
        const val TAG = "update_failed"
    }
}
