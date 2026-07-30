package com.wedora.app

import android.animation.Animator
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.os.Bundle
import android.text.format.Formatter
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import com.wedora.app.databinding.ActivityUpdateRequiredBinding

/**
 * 04 · The blocking, non-dismissible update gate.
 *
 * Reached only from [UpdatePath.IMMEDIATE_BLOCKING] — Play priority 5, or a
 * Remote Config `minSupportedVersion` above the installed build.
 *
 * This screen is the pre-roll while the immediate flow is starting, and the
 * fallback the user lands back on if they cancel out of Play's own blocking UI.
 * It is deliberately its own Activity rather than a dialog: there must be no
 * app behind it to interact with, and no dialog-dismiss gesture to find.
 *
 * There is no exit. Back is consumed, there is no up affordance, and the only
 * control is the update itself. That is a real cost — forced updates drive
 * uninstalls — which is exactly why the copy states a concrete reason and shows
 * the version delta rather than just demanding compliance.
 */
class UpdateRequiredActivity : WedoraBaseActivity(), UpdateRepository.Observer, UpdateFlowHost {

    /**
     * A decline here does not release the gate — the user lands back on this
     * screen, which is the one place re-prompting is legitimate because the
     * app genuinely cannot run on this build.
     */
    override val updateFlowLauncher = registerForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result -> UpdateRepository.onFlowResult(result.resultCode) }

    // The coral gradient sits behind both system bars in either theme, so this
    // never follows @bool/wedora_light_status_bar the way normal screens do —
    // same reasoning as SplashActivity.
    override val isLightSystemBars = false

    private lateinit var binding: ActivityUpdateRequiredBinding
    private var haloLoop: Animator? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityUpdateRequiredBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applyEdgeInsets(binding.root)

        // No system-gesture escape either: consuming the event here covers the
        // predictive-back gesture as well as the button.
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = Unit
        })

        render()

        binding.btnUpdate.addPressScale()
        binding.btnUpdate.setOnClickListener {
            if (isOffline()) {
                // Offline gets the same screen with a retry, never a bypass.
                render()
                return@setOnClickListener
            }
            UpdateRepository.startImmediate(this)
        }

        UpdateAnalytics.requiredView(
            currentVersion = UpdateRepository.currentVersionCode(),
            minimumVersion = UpdateCopy.minSupportedVersion(),
            reasonCode = UpdateCopy.forcedReasonCode()
        )

        playTimeline()
    }

    override fun onStart() {
        super.onStart()
        UpdateRepository.addObserver(this)
    }

    override fun onResume() {
        super.onResume()
        // Actually re-enters the flow when Play reports one already underway —
        // querying alone would leave the user parked on a screen they cannot
        // dismiss. Falls back to a normal refresh when nothing is in progress.
        UpdateRepository.resumeImmediateIfInProgress(this)
    }

    override fun onStop() {
        UpdateRepository.removeObserver(this)
        super.onStop()
    }

    override fun onUpdateState(state: UpdateState) {
        when (state) {
            // The gate has been satisfied — either the build is now current or
            // the requirement was lifted server-side. Let the user back in.
            is UpdateState.UpToDate -> if (state.reachable) finish()
            is UpdateState.Available ->
                if (state.path != UpdatePath.IMMEDIATE_BLOCKING) finish() else render()
            else -> Unit
        }
    }

    private fun render() {
        val available = UpdateRepository.state as? UpdateState.Available
        val offline = isOffline()

        binding.tvReason.text = if (offline) {
            getString(R.string.update_required_offline)
        } else {
            UpdateCopy.forcedReason(this)
        }

        binding.btnUpdate.text = when {
            offline -> getString(R.string.update_required_retry)
            available != null && available.totalBytes > 0 -> getString(
                R.string.update_required_cta,
                Formatter.formatShortFileSize(this, available.totalBytes)
            )
            // No size yet (the check hasn't resolved): show the action without
            // inventing a figure.
            else -> getString(R.string.update_required_cta_plain)
        }

        binding.tvFootnote.visibility = if (offline) android.view.View.GONE else android.view.View.VISIBLE

        // The pill only makes sense as name -> name. Play exposes the target's
        // versionCode but never its name, so without a Remote Config mapping
        // the pill is hidden entirely rather than showing "1.1.2 -> 47", which
        // pairs a version name against a build number and reads as a glitch.
        val targetName = available?.versionCode?.let(UpdateCopy::targetVersionName)
        binding.tvVersionFrom.text = UpdateRepository.currentVersionName()
        binding.tvVersionTo.text = targetName.orEmpty()
        binding.versionPill.visibility =
            if (targetName == null) android.view.View.GONE else android.view.View.VISIBLE
    }

    /** Timeline from the spec. Notably, there is no exit motion to define. */
    private fun playTimeline() {
        if (Motion.reducedMotion(binding.root)) {
            listOf(binding.tvTitle, binding.tvReason, binding.versionPill, binding.ctaGroup)
                .forEach { it.alpha = 1f }
            return
        }

        // Lock drops in with an overshoot: authority, not error.
        binding.shieldFrame.scaleX = 0.7f
        binding.shieldFrame.scaleY = 0.7f
        binding.shieldFrame.alpha = 0f
        binding.shieldFrame.translationY = -14f * resources.displayMetrics.density
        binding.shieldFrame.animate()
            .scaleX(1f).scaleY(1f).alpha(1f).translationY(0f)
            .setDuration(600).setStartDelay(100)
            .setInterpolator(Motion.BOUNCE)
            .start()

        haloLoop = Motion.floatLoop(binding.halo, durationMs = 4000, delayMs = 900, amplitudeDp = 8f)

        Motion.riseUp(binding.tvTitle, durationMs = 550, delayMs = 420, fromYDp = 18f)
        Motion.riseUp(binding.tvReason, durationMs = 550, delayMs = 520, fromYDp = 18f)
        Motion.riseUp(binding.versionPill, durationMs = 500, delayMs = 620, fromYDp = 18f)
        Motion.riseUp(binding.ctaGroup, durationMs = 550, delayMs = 720, fromYDp = 18f)
    }

    private fun isOffline(): Boolean {
        val cm = getSystemService(ConnectivityManager::class.java) ?: return false
        return cm.activeNetwork == null
    }

    override fun onDestroy() {
        haloLoop?.cancel()
        haloLoop = null
        super.onDestroy()
    }

    companion object {
        fun intent(context: Context): Intent =
            Intent(context, UpdateRequiredActivity::class.java).apply {
                // Nothing may sit behind or return to this screen while the
                // requirement stands.
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            }
    }
}
