package com.wedora.app

import android.app.Application
import androidx.lifecycle.ProcessLifecycleOwner

class WedoraApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        // Apply the saved dark-mode preference before any Activity is created,
        // so the very first screen renders in the right theme.
        ThemePrefs.applyStoredMode(this)

        // App-level foreground/background presence. Registered once here so it
        // tracks the whole process rather than any single activity.
        ProcessLifecycleOwner.get().lifecycle.addObserver(PresenceTracker)
    }
}
