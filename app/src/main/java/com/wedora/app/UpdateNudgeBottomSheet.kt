package com.wedora.app

import android.animation.Animator
import android.content.DialogInterface
import android.os.Bundle
import android.text.format.Formatter
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityEvent
import com.wedora.app.databinding.ItemUpdateNoteBinding
import com.wedora.app.databinding.SheetUpdateNudgeBinding

/**
 * 01 · The flexible update nudge.
 *
 * Home stays fully rendered behind the framework's dim so this reads as an
 * interruption over the app, not a new screen — which is also why it is a
 * modal sheet rather than an Activity.
 *
 * The arrow's slow upward loop is the only sustained motion here: it is the
 * one affordance that has to be understood without reading, so it keeps
 * asking after everything else has settled.
 *
 * Dismissal is a first-class outcome. `Later` is a real, low-emphasis exit,
 * and scrim-tap and drag are both honoured — the nag budget in [UpdatePrefs]
 * is what stops this becoming a nag, not hiding the way out.
 */
class UpdateNudgeBottomSheet : WedoraBottomSheetDialog() {

    private var binding: SheetUpdateNudgeBinding? = null
    private var arrowLoop: Animator? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = SheetUpdateNudgeBinding.inflate(inflater, container, false)
        .also { binding = it }.root

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val b = binding ?: return
        val available = UpdateRepository.state as? UpdateState.Available ?: run {
            // State moved on while the sheet was being shown (a background
            // download completing, say). Nothing to nudge about any more.
            dismissAllowingStateLoss()
            return
        }

        // Size always comes from AppUpdateInfo, never a hard-coded figure.
        val size = Formatter.formatShortFileSize(requireContext(), available.totalBytes)
        // Play gives only the target's versionCode, so its NAME has to come
        // from Remote Config. Absent that, show the size alone rather than
        // labelling the download with the version already installed.
        val targetName = UpdateCopy.targetVersionName(available.versionCode)
        b.tvSubtitle.text = if (targetName != null) {
            getString(R.string.update_nudge_subtitle, targetName, size)
        } else {
            getString(R.string.update_nudge_subtitle_size_only, size)
        }

        buildNotes(b, available.versionCode)

        b.btnUpdate.addPressScale()
        b.btnUpdate.setOnClickListener {
            UpdateRepository.startFlexible(requireActivity(), UpdateAnalytics.SURFACE_BOTTOM_SHEET)
            dismiss()
        }
        b.btnLater.setOnClickListener {
            UpdateRepository.onDismissed(available.versionCode, UpdateAnalytics.METHOD_LATER)
            dismiss()
        }

        UpdatePrefs.recordPromptShown(requireContext(), available.versionCode)
        UpdateAnalytics.promptView(
            UpdateAnalytics.SURFACE_BOTTOM_SHEET,
            available.versionCode,
            UpdatePrefs.promptCount(requireContext(), available.versionCode)
        )

        playTimeline(b)

        // Modal a11y pane: focus lands on the title, so a screen reader reads
        // what this is before the actions. The arrow is decorative and is
        // marked importantForAccessibility="no" in the layout.
        b.tvTitle.sendAccessibilityEvent(AccessibilityEvent.TYPE_VIEW_FOCUSED)
    }

    private fun buildNotes(b: SheetUpdateNudgeBinding, versionCode: Int) {
        val notes = UpdateCopy.releaseNotes(requireContext(), versionCode)
        val inflater = LayoutInflater.from(requireContext())
        b.notesContainer.removeAllViews()
        notes.forEach { note ->
            ItemUpdateNoteBinding.inflate(inflater, b.notesContainer, true).tvNote.text = note
        }
    }

    /**
     * The spec's timeline, minus the scrim and sheet-rise which the framework
     * and [WedoraBottomSheetDialog.springIn] already provide — reimplementing
     * those here would fight the dialog's own animation.
     *
     * Offsets are shifted down from the spec's absolute times (which are
     * measured from when the scrim starts) to be relative to this view being
     * created, i.e. after the sheet has already begun rising.
     */
    private fun playTimeline(b: SheetUpdateNudgeBinding) {
        if (Motion.reducedMotion(b.root)) {
            // Per the spec: cross-fade only, no loops. Everything lands visible
            // in one step rather than each animator no-opping independently —
            // the splash taught us the difference.
            listOf(b.iconDisc, b.tvTitle, b.tvSubtitle, b.notesContainer, b.btnUpdate, b.btnLater)
                .forEach { it.alpha = 1f }
            b.iconDisc.scaleX = 1f
            b.iconDisc.scaleY = 1f
            return
        }

        Motion.popIn(b.iconDisc, durationMs = 500, delayMs = 240)
        Motion.riseUp(b.tvTitle, durationMs = 450, delayMs = 300, fromYDp = 18f)
        Motion.riseUp(b.tvSubtitle, durationMs = 450, delayMs = 300, fromYDp = 18f)
        Motion.riseUp(b.notesContainer, durationMs = 450, delayMs = 400, fromYDp = 18f)
        Motion.riseUp(b.btnUpdate, durationMs = 450, delayMs = 500, fromYDp = 18f)
        Motion.riseUp(b.btnLater, durationMs = 450, delayMs = 500, fromYDp = 18f)

        // The one sustained motion: +9 -> -11 px with a fade at each end.
        arrowLoop = Motion.arrowLoop(b.ivArrow, durationMs = 1900, delayMs = 700)
    }

    /**
     * Catches scrim-tap and drag-away, which never route through a button.
     * Both count as a dismissal for the budget.
     *
     * Only cancellation lands here — a programmatic [dismiss] (the Update and
     * Later paths) does not — so this cannot double-log a dismissal those
     * paths already recorded.
     */
    override fun onCancel(dialog: DialogInterface) {
        (UpdateRepository.state as? UpdateState.Available)?.let {
            UpdateRepository.onDismissed(it.versionCode, UpdateAnalytics.METHOD_SCRIM)
        }
        super.onCancel(dialog)
    }

    override fun onDestroyView() {
        arrowLoop?.cancel()
        arrowLoop = null
        binding = null
        super.onDestroyView()
    }

    companion object {
        const val TAG = "update_nudge"
    }
}
