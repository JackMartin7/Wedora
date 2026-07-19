package com.wedora.app

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.appcompat.app.AppCompatActivity

class SplashActivity : AppCompatActivity() {

    companion object {
        private const val SPLASH_DELAY_MS = 1800L
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_splash)

        Handler(Looper.getMainLooper()).postDelayed({
            val next = if (OnboardingPrefs.isOnboardingComplete(this)) {
                LoginActivity::class.java
            } else {
                OnboardingActivity::class.java
            }
            startActivity(Intent(this, next))
            finish()
        }, SPLASH_DELAY_MS)
    }
}
