package com.wedora.app

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.core.content.getSystemService

/**
 * The app's local-notification categories. Created once on process start;
 * channel creation is a no-op on an already-existing channel, so calling this
 * on every launch is safe and cheap.
 *
 * Importance is set per channel rather than per notification: messages are
 * HIGH (a heads-up alert — waiting on a reply is time-sensitive), matches,
 * likes and profile views are DEFAULT (worth a sound/badge but not an
 * interruption).
 */
object NotificationChannels {

    const val MATCHES = "channel_matches"
    const val MESSAGES = "channel_messages"
    const val LIKES = "channel_likes"
    const val PROFILE_VIEWS = "channel_profile_views"

    fun createAll(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService<NotificationManager>() ?: return

        manager.createNotificationChannel(
            NotificationChannel(
                MATCHES,
                context.getString(R.string.notif_channel_matches_name),
                NotificationManager.IMPORTANCE_DEFAULT
            )
        )
        manager.createNotificationChannel(
            NotificationChannel(
                MESSAGES,
                context.getString(R.string.notif_channel_messages_name),
                NotificationManager.IMPORTANCE_HIGH
            )
        )
        manager.createNotificationChannel(
            NotificationChannel(
                LIKES,
                context.getString(R.string.notif_channel_likes_name),
                NotificationManager.IMPORTANCE_DEFAULT
            )
        )
        manager.createNotificationChannel(
            NotificationChannel(
                PROFILE_VIEWS,
                context.getString(R.string.notif_channel_profile_views_name),
                NotificationManager.IMPORTANCE_DEFAULT
            )
        )
    }
}
