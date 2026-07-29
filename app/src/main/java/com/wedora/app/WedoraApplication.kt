package com.wedora.app

import android.app.Application
import androidx.lifecycle.ProcessLifecycleOwner
import com.google.android.gms.ads.MobileAds

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
        // Premium-only: attaches its own inner isPremium check before ever
        // listening for views, so a free user never pays for that listener.
        ProfileViewNotificationWatcher.attach(this)

        // Session-wide cache of isPremium so upgrade-prompt UI across the app
        // doesn't each run its own Firestore read to decide whether to show.
        PremiumStatus.attach()

        // Play Billing connection + entitlement sync — see BillingManager's
        // own doc comment for why this has to run from cold start, not
        // lazily from PaymentSubscriptionActivity.
        BillingManager.attach(this)

        // Native ads in Home's swipe stack (free users only). Init is
        // fire-and-forget — the SDK queues ad requests made before it
        // finishes, so nothing has to wait on the completion callback.
        MobileAds.initialize(this)
    }
}
