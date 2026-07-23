package com.wedora.app

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.wedora.app.databinding.ActivityLegalDocumentBinding

/**
 * The Privacy Policy, as scrolling text. Informational only — no agreement
 * button, however it's reached, so it reuses the shared layout with the button
 * left at its default GONE.
 */
class PrivacyPolicyActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLegalDocumentBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLegalDocumentBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.tvTitle.setText(R.string.privacy_title)
        binding.tvBody.setText(R.string.privacy_body)
        binding.btnBack.setOnClickListener { finish() }
    }
}
