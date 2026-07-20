package com.wedora.app

import android.app.Application

class WedoraApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        // Apply the saved dark-mode preference before any Activity is created,
        // so the very first screen renders in the right theme.
        ThemePrefs.applyStoredMode(this)
    }
}
