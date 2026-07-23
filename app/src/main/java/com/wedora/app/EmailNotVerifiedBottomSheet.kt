package com.wedora.app

import android.os.Bundle
import android.os.CountDownTimer
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import com.wedora.app.databinding.SheetEmailNotVerifiedBinding

/**
 * "Email Not Verified" sheet, shown on Login when the account exists but its
 * address isn't verified.
 *
 * The actual resend and re-check live on the [Host] (it holds the auth session
 * and the navigation); the sheet owns only the resend cooldown so the button
 * can't be hammered.
 */
class EmailNotVerifiedBottomSheet : WedoraBottomSheetDialog() {

    /** Implemented by the screen that shows this sheet (LoginActivity). */
    interface Host {
        /** Send the verification email again. The sheet handles the cooldown UI. */
        fun onResendVerificationRequested()

        /** Re-check verification and continue if it's now verified. */
        fun onVerifiedRetryRequested()
    }

    private var binding: SheetEmailNotVerifiedBinding? = null
    private var resendTimer: CountDownTimer? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = SheetEmailNotVerifiedBinding.inflate(inflater, container, false)
        .also { binding = it }.root

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val b = binding ?: return

        b.btnResend.addPressScale()
        b.btnResend.setOnClickListener {
            startResendCooldown()
            (activity as? Host)?.onResendVerificationRequested()
        }
        b.btnTryAgain.addPressScale()
        b.btnTryAgain.setOnClickListener {
            (activity as? Host)?.onVerifiedRetryRequested()
            dismiss()
        }
        b.btnCancel.setOnClickListener { dismiss() }

        springIn(view)
    }

    private fun startResendCooldown() {
        val b = binding ?: return
        b.btnResend.isEnabled = false
        resendTimer?.cancel()
        resendTimer = object : CountDownTimer(RESEND_COOLDOWN_SECONDS * 1000, 1000) {
            override fun onTick(millisUntilFinished: Long) {
                val seconds = (millisUntilFinished / 1000).toInt()
                binding?.btnResend?.text =
                    getString(R.string.verify_resend_countdown, seconds)
            }

            override fun onFinish() {
                binding?.btnResend?.apply {
                    setText(R.string.verify_resend)
                    isEnabled = true
                }
            }
        }.start()
    }

    override fun onDestroyView() {
        resendTimer?.cancel()
        resendTimer = null
        binding = null
        super.onDestroyView()
    }

    private companion object {
        const val RESEND_COOLDOWN_SECONDS = 60L
    }
}
