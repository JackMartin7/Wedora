package com.wedora.app

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import com.wedora.app.databinding.ActivityLegalDocumentBinding

/**
 * The Terms of Service, as scrolling text.
 *
 * Reached two ways: from the sign-up screen, where it shows an "I Agree"
 * button that returns RESULT_OK so the checkbox can tick itself; and from
 * Profile settings, where it's read-only and the button is hidden. The
 * [EXTRA_FROM_SIGNUP] flag is what tells the two apart.
 */
class TermsOfServiceActivity : WedoraBaseActivity() {

    companion object {
        private const val EXTRA_FROM_SIGNUP = "extra_from_signup"

        /** Sign-up opens it with the agree button; settings without. */
        fun intent(context: Context, fromSignup: Boolean): Intent =
            Intent(context, TermsOfServiceActivity::class.java)
                .putExtra(EXTRA_FROM_SIGNUP, fromSignup)
    }

    private lateinit var binding: ActivityLegalDocumentBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLegalDocumentBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applyEdgeInsets(binding.root)

        binding.tvTitle.setText(R.string.terms_title)
        binding.tvBody.setText(R.string.terms_body)
        binding.btnBack.setOnClickListener { finish() }

        if (intent.getBooleanExtra(EXTRA_FROM_SIGNUP, false)) {
            binding.btnAgree.visibility = View.VISIBLE
            binding.btnAgree.addPressScale()
            binding.btnAgree.setOnClickListener {
                setResult(RESULT_OK)
                finish()
            }
        }
    }
}
