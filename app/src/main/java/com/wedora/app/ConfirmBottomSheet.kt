package com.wedora.app

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import com.wedora.app.databinding.SheetConfirmBinding

/**
 * A confirm-or-cancel bottom sheet: icon disc, title, subtitle, an accent
 * primary button and a transparent Cancel. Shared by Log Out, Delete Account
 * and Block confirm — each subclass supplies the copy and what the primary
 * button does.
 */
abstract class ConfirmBottomSheet : WedoraBottomSheetDialog() {

    private var binding: SheetConfirmBinding? = null

    @get:DrawableRes protected abstract val iconRes: Int
    @get:StringRes protected abstract val titleRes: Int
    @get:StringRes protected abstract val subtitleRes: Int
    @get:StringRes protected abstract val primaryLabelRes: Int

    /** "Cancel" fits every existing sheet; override only where the copy differs. */
    @get:StringRes protected open val secondaryLabelRes: Int = R.string.action_cancel

    /**
     * An optional middle action between primary and secondary. Null — the
     * default — leaves that button GONE, so every existing two-button sheet
     * is unaffected. Supplying it also requires [onTertiary].
     */
    @get:StringRes protected open val tertiaryLabelRes: Int? = null

    /** Runs when the middle button is tapped; the sheet dismisses afterwards. */
    protected open fun onTertiary() = Unit

    /**
     * Optional leading icons for the three buttons. Null — the default —
     * leaves a button text-only, so every existing sheet (Log Out, Delete
     * Account, Block, guest limit, rate app) is untouched; only the
     * daily-limit sheet opts in.
     *
     * Whatever fillColor the drawable ships with is overridden at bind time
     * with the button's own text colour, so one icon works on both the
     * accent-filled primary (white text) and the outlined tertiary (accent
     * text), and follows the light/dark theme without a second asset.
     */
    @get:DrawableRes protected open val primaryIconRes: Int? = null
    @get:DrawableRes protected open val secondaryIconRes: Int? = null
    @get:DrawableRes protected open val tertiaryIconRes: Int? = null

    /** Runs when the primary button is tapped; the sheet dismisses afterwards
     *  unless [dismissImmediately] is false, in which case [onPrimary] is
     *  responsible for calling [dismiss] once its async work resolves. */
    protected abstract fun onPrimary()

    /**
     * True (the default) dismisses right after [onPrimary] fires, matching
     * every prior use of this base (Log Out, Delete Account confirm — both
     * synchronous or handled by an overlay elsewhere). A subclass whose
     * [onPrimary] itself issues the async write — e.g. Block — sets this to
     * false so the sheet stays open, busy, until the write resolves, rather
     * than dismissing with no feedback that anything happened.
     */
    protected open val dismissImmediately: Boolean = true

    /**
     * Overrides the middle button's label and enabled state after bind —
     * for a subclass that needs it to change while the sheet is open, which
     * so far means the daily-limit sheet's rewarded-ad countdown.
     *
     * Passing null for [label] restores [tertiaryLabelRes], so a caller
     * re-enabling the button doesn't have to remember its own copy.
     *
     * No-ops once the view is gone, so a ticker that fires one last time
     * between onDestroyView and its own cancellation can't crash.
     */
    protected fun setTertiaryState(enabled: Boolean, label: CharSequence? = null) {
        val b = binding ?: return
        b.btnSheetTertiary.isEnabled = enabled
        // AppCompatButton has no disabled state in these pill backgrounds
        // (they're plain drawables, not state lists), so the dimming has to
        // be explicit or a disabled button would look tappable.
        b.btnSheetTertiary.alpha = if (enabled) 1f else 0.5f
        if (label != null) {
            b.btnSheetTertiary.text = label
        } else {
            tertiaryLabelRes?.let { b.btnSheetTertiary.setText(it) }
        }
    }

    /** Swaps the primary button to a busy label and disables both buttons. */
    protected fun setBusy(busy: Boolean, @StringRes busyLabelRes: Int? = null) {
        val b = binding ?: return
        b.btnSheetPrimary.isEnabled = !busy
        b.btnSheetSecondary.isEnabled = !busy
        isCancelable = !busy
        if (busy && busyLabelRes != null) {
            b.btnSheetPrimary.setText(busyLabelRes)
        } else if (!busy) {
            b.btnSheetPrimary.setText(primaryLabelRes)
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = SheetConfirmBinding.inflate(inflater, container, false).also { binding = it }.root

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val b = binding ?: return
        b.ivSheetIcon.setImageResource(iconRes)
        b.tvSheetTitle.setText(titleRes)
        b.tvSheetSubtitle.setText(subtitleRes)
        b.btnSheetPrimary.setText(primaryLabelRes)
        b.btnSheetSecondary.setText(secondaryLabelRes)

        b.btnSheetPrimary.setLeadingIcon(primaryIconRes)
        b.btnSheetSecondary.setLeadingIcon(secondaryIconRes)

        b.btnSheetPrimary.addPressScale()
        b.btnSheetPrimary.setOnClickListener {
            onPrimary()
            if (dismissImmediately) dismiss()
        }

        val tertiary = tertiaryLabelRes
        if (tertiary == null) {
            b.btnSheetTertiary.visibility = View.GONE
        } else {
            b.btnSheetTertiary.visibility = View.VISIBLE
            b.btnSheetTertiary.setText(tertiary)
            b.btnSheetTertiary.setLeadingIcon(tertiaryIconRes)
            b.btnSheetTertiary.addPressScale()
            b.btnSheetTertiary.setOnClickListener {
                onTertiary()
                dismiss()
            }
        }

        b.btnSheetSecondary.setOnClickListener { dismiss() }

        springIn(view)
    }

    override fun onDestroyView() {
        binding = null
        super.onDestroyView()
    }
}

/**
 * Puts [iconRes] at the start of the button, tinted to the button's current
 * text colour, or clears any icon when null.
 *
 * .mutate() first: drawables loaded from resources share one constant state,
 * so tinting without it would recolour every other use of the same icon in
 * the process. Bounds come from the drawable's own intrinsic size, which for
 * these vectors is the 24dp they're authored at.
 *
 * These are AppCompatButtons, not MaterialButtons (the sheet's pill
 * backgrounds are set through android:background, which MaterialButton
 * ignores), so there's no app:icon/iconGravity here — a compound drawable is
 * the equivalent. On a match_parent button that sits the icon at the leading
 * edge with the label still centred, which is the intended look.
 */
private fun android.widget.Button.setLeadingIcon(@DrawableRes iconRes: Int?) {
    if (iconRes == null) {
        setCompoundDrawablesRelativeWithIntrinsicBounds(0, 0, 0, 0)
        return
    }
    val icon = androidx.core.content.ContextCompat.getDrawable(context, iconRes)?.mutate()
    icon?.setTint(currentTextColor)
    setCompoundDrawablesRelativeWithIntrinsicBounds(icon, null, null, null)
    compoundDrawablePadding =
        resources.getDimensionPixelSize(R.dimen.sheet_button_icon_padding)
}
