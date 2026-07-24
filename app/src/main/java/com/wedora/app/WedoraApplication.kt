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

        // Local notifications for matches/messages/likes. Channels must exist
        // before the first notification is ever posted; the watcher attaches
        // to whichever user is (or becomes) signed in and runs for the life of
        // the process, independent of which Activity — if any — is in front.
        NotificationChannels.createAll(this)
        MatchNotificationWatcher.attach(this)

        // Session-wide cache of isPremium so upgrade-prompt UI across the app
        // doesn't each run its own Firestore read to decide whether to show.
        PremiumStatus.attach()
    }
}
