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

    /** Runs when the primary button is tapped; the sheet dismisses afterwards. */
    protected abstract fun onPrimary()

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

        b.btnSheetPrimary.addPressScale()
        b.btnSheetPrimary.setOnClickListener {
            onPrimary()
            dismiss()
        }
        b.btnSheetSecondary.setOnClickListener { dismiss() }

        springIn(view)
    }

    override fun onDestroyView() {
        binding = null
        super.onDestroyView()
    }
}
