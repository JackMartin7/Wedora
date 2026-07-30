package com.wedora.app

import android.app.Dialog
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Bundle
import android.text.format.Formatter
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import androidx.fragment.app.DialogFragment
import com.wedora.app.databinding.DialogUpdateDownloadBinding

/**
 * 02 · Download progress.
 *
 * Renders [UpdateState.Downloading] straight from [UpdateRepository] — it owns
 * no progress state of its own, so dismissing and reopening it always shows the
 * true position rather than restarting a local animation.
 *
 * "Continue in background" dismisses this dialog and nothing else; the download
 * keeps running under Play and the hairline bar on Home takes over as the
 * remaining indicator.
 */
class UpdateDownloadDialog : DialogFragment(), UpdateRepository.Observer {

    private var binding: DialogUpdateDownloadBinding? = null

    /** Latched so the metered warning appears once rather than on every update. */
    private var meteredShown = false

    /**
     * Bare dialog; the content comes from [onCreateView].
     *
     * Deliberately NOT Dialog.setContentView(view): that discards the inflated
     * root's own LayoutParams and substitutes MATCH_PARENT, which force-fitted
     * this card to the window (213dp instead of its 272dp) and made the
     * LinearLayout clamp its children — squeezing the ring and cutting the
     * bottom button in half. Going through onCreateView lets DialogFragment add
     * the view with the params the layout actually declares.
     */
    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog =
        Dialog(requireContext(), R.style.WedoraUpdateDialog)

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = DialogUpdateDownloadBinding.inflate(inflater, container, false)
        .also { binding = it }.root

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val b = binding ?: return

        b.btnBackground.setOnClickListener { continueInBackground() }
        // The window is full-screen now, so there is no "outside" for
        // setCanceledOnTouchOutside to detect — the scrim carries it instead.
        // Back and tap-outside both mean "continue in background", matching the
        // button rather than silently doing nothing.
        b.dialogScrim.setOnClickListener { continueInBackground() }
    }

    private fun continueInBackground() {
        (UpdateRepository.state as? UpdateState.Downloading)?.let {
            UpdateAnalytics.backgrounded(it.versionCode, it.percent)
        }
        dismiss()
    }

    override fun onStart() {
        super.onStart()
        // Full-screen so the centred card is free to take its declared 272dp
        // instead of being clamped to the platform's dialog width.
        dialog?.window?.setLayout(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT
        )
        UpdateRepository.addObserver(this)
    }

    override fun onStop() {
        UpdateRepository.removeObserver(this)
        super.onStop()
    }

    override fun onUpdateState(state: UpdateState) {
        val b = binding ?: return
        when (state) {
            is UpdateState.Downloading -> render(b, state)
            // Terminal states are hosted elsewhere (snackbar / failure dialog);
            // this dialog's job is over the moment downloading stops.
            is UpdateState.Downloaded, is UpdateState.Failed -> dismissAllowingStateLoss()
            else -> Unit
        }
    }

    private fun render(b: DialogUpdateDownloadBinding, state: UpdateState.Downloading) {
        b.ring.setProgress(
            if (state.totalBytes > 0) state.bytesDownloaded.toFloat() / state.totalBytes else 0f
        )
        b.tvPercent.text = getString(R.string.update_downloading_percent, state.percent)
        b.tvBytes.text = getString(
            R.string.update_downloading_bytes,
            Formatter.formatShortFileSize(requireContext(), state.bytesDownloaded),
            Formatter.formatShortFileSize(requireContext(), state.totalBytes)
        )

        // Swap the reassurance line for an honest one once Play has stopped
        // reporting bytes. The progress figures themselves are left alone —
        // moving them would be inventing data.
        b.tvBody.setText(
            if (state.stalled) R.string.update_downloading_stalled
            else R.string.update_downloading_body
        )

        if (!meteredShown && isMetered() && state.totalBytes > 0) {
            meteredShown = true
            b.tvMetered.text = getString(
                R.string.update_downloading_metered,
                Formatter.formatShortFileSize(requireContext(), state.totalBytes)
            )
            b.tvMetered.visibility = View.VISIBLE
        }
    }

    private fun isMetered(): Boolean {
        val cm = requireContext().getSystemService(ConnectivityManager::class.java) ?: return false
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
    }

    override fun onDestroyView() {
        binding = null
        super.onDestroyView()
    }

    companion object {
        const val TAG = "update_download"
    }
}
