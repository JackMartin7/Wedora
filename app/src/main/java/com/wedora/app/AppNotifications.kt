package com.wedora.app

import android.Manifest
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat

/**
 * Builds and shows this app's local-notification categories.
 *
 * Every function here re-reads [NotificationPrefs] at call time rather than
 * once when [MatchNotificationWatcher] starts — so switching a toggle off in
 * Settings takes effect on the very next event, with no restart needed. The
 * cost is one SharedPreferences read per event, which is negligible next to
 * the Firestore round trip that triggered it.
 */
object AppNotifications {

    private const val TAG = "WedoraNotify"

    /**
     * Distinct id ranges per category so a match/like/message about the same
     * matchId can't silently overwrite one another's notification — while a
     * second event of the *same* category for the same match still correctly
     * replaces the first rather than stacking duplicates.
     */
    private const val ID_OFFSET_MATCH = 0
    private const val ID_OFFSET_LIKE = 1
    private const val ID_OFFSET_MESSAGE = 2
    private const val ID_OFFSET_PROFILE_VIEW = 3

    fun notifyNewMatch(context: Context, matchId: String, otherUid: String, otherName: String) {
        if (!NotificationPrefs.isEnabled(context, NotificationPrefs.Toggle.NEW_MATCHES)) {
            Log.d(TAG, "Skipping new-match notification for $matchId: category disabled in Settings")
            return
        }

        val intent = ProfileDetailActivity.intent(context, otherUid)
        show(
            context = context,
            channelId = NotificationChannels.MATCHES,
            notificationId = idFor(matchId, ID_OFFSET_MATCH),
            title = context.getString(R.string.notif_new_match_title),
            text = context.getString(R.string.notif_new_match_body, otherName),
            contentIntent = intent
        )
    }

    /** Deliberately vague — who liked isn't named, matching the Likes tab's blurred teaser. */
    fun notifyNewLike(context: Context, matchId: String) {
        if (!NotificationPrefs.isEnabled(context, NotificationPrefs.Toggle.LIKES)) {
            Log.d(TAG, "Skipping new-like notification for $matchId: category disabled in Settings")
            return
        }

        show(
            context = context,
            channelId = NotificationChannels.LIKES,
            notificationId = idFor(matchId, ID_OFFSET_LIKE),
            title = context.getString(R.string.notif_new_like_title),
            text = context.getString(R.string.notif_new_like_body),
            contentIntent = Intent(context, LikesActivity::class.java)
        )
    }

    fun notifyNewMessage(
        context: Context,
        matchId: String,
        otherUid: String,
        senderName: String,
        messageText: String
    ) {
        if (!NotificationPrefs.isEnabled(context, NotificationPrefs.Toggle.MESSAGES)) {
            Log.d(TAG, "Skipping new-message notification for $matchId: category disabled in Settings")
            return
        }

        val intent = ChatThreadActivity.intent(context, otherUid, senderName)
        show(
            context = context,
            channelId = NotificationChannels.MESSAGES,
            notificationId = idFor(matchId, ID_OFFSET_MESSAGE),
            title = senderName,
            text = messageText,
            contentIntent = intent
        )
    }

    /**
     * Deliberately vague — who viewed isn't named, matching notifyNewLike's
     * own reasoning: it drives someone to open Profile Viewers to find out,
     * rather than settling their curiosity from the notification shade.
     */
    fun notifyProfileView(context: Context, viewerUid: String) {
        if (!NotificationPrefs.isEnabled(context, NotificationPrefs.Toggle.PROFILE_VIEWS)) {
            Log.d(TAG, "Skipping profile-view notification for $viewerUid: category disabled in Settings")
            return
        }

        show(
            context = context,
            channelId = NotificationChannels.PROFILE_VIEWS,
            notificationId = idFor(viewerUid, ID_OFFSET_PROFILE_VIEW),
            title = context.getString(R.string.notif_profile_view_title),
            text = context.getString(R.string.notif_profile_view_body),
            contentIntent = Intent(context, ProfileViewersActivity::class.java)
        )
    }

    /**
     * Shows a notification for an FCM push (see
     * [WedoraFirebaseMessagingService.onMessageReceived]), using its title/
     * body verbatim rather than re-deriving them the way notifyNewMatch/
     * notifyNewLike/notifyNewMessage do for a *locally observed* event — a
     * push already carries exactly what the sending device's
     * [PushNotificationSender] call decided to say.
     *
     * [type] is one of [PushNotificationSender.TYPE_MATCH]/
     * [PushNotificationSender.TYPE_LIKE]/[PushNotificationSender.TYPE_MESSAGE]
     * and picks the channel/toggle/tap-destination, mirroring the three
     * local-notification functions above; an unrecognized type is dropped
     * rather than guessed at.
     *
     * Reuses the exact same [idFor] scheme those local paths use, so a push
     * and MatchNotificationWatcher's own live detection for the *same*
     * matchId/category collapse into one notification — the second call
     * just replaces the first via [NotificationManagerCompat.notify] rather
     * than stacking a duplicate — instead of the user seeing both.
     */
    fun notifyFromPush(
        context: Context,
        type: String,
        matchId: String,
        otherUid: String,
        title: String,
        body: String
    ) {
        when (type) {
            PushNotificationSender.TYPE_MATCH -> {
                if (!NotificationPrefs.isEnabled(context, NotificationPrefs.Toggle.NEW_MATCHES)) {
                    Log.d(TAG, "Skipping push match notification for $matchId: category disabled in Settings")
                    return
                }
                show(
                    context, NotificationChannels.MATCHES, idFor(matchId, ID_OFFSET_MATCH),
                    title, body, ProfileDetailActivity.intent(context, otherUid)
                )
            }
            PushNotificationSender.TYPE_LIKE -> {
                if (!NotificationPrefs.isEnabled(context, NotificationPrefs.Toggle.LIKES)) {
                    Log.d(TAG, "Skipping push like notification for $matchId: category disabled in Settings")
                    return
                }
                show(
                    context, NotificationChannels.LIKES, idFor(matchId, ID_OFFSET_LIKE),
                    title, body, Intent(context, LikesActivity::class.java)
                )
            }
            PushNotificationSender.TYPE_MESSAGE -> {
                if (!NotificationPrefs.isEnabled(context, NotificationPrefs.Toggle.MESSAGES)) {
                    Log.d(TAG, "Skipping push message notification for $matchId: category disabled in Settings")
                    return
                }
                // title doubles as the sender's display name here — that's
                // what PushNotificationSender.send's message call site
                // passes as title, matching notifyNewMessage's own shape.
                show(
                    context, NotificationChannels.MESSAGES, idFor(matchId, ID_OFFSET_MESSAGE),
                    title, body, ChatThreadActivity.intent(context, otherUid, title)
                )
            }
            else -> Log.w(TAG, "Ignoring push with unrecognized type \"$type\"")
        }
    }

    private fun idFor(matchId: String, offset: Int): Int =
        matchId.hashCode() * 4 + offset

    /**
     * FLAG_ACTIVITY_NEW_TASK because the PendingIntent fires from outside any
     * Activity context. No parent-stack synthesis (TaskStackBuilder) — this
     * app doesn't declare parentActivityName anywhere, so it wouldn't produce
     * a real back stack, just an extra layer of indirection over what
     * PendingIntent.getActivity already does directly.
     *
     * Two independent gates, both checked and logged separately: the runtime
     * POST_NOTIFICATIONS permission (API 33+ only — there's no such grant
     * below it) and NotificationManagerCompat.areNotificationsEnabled(),
     * which is the *only* signal on pre-33 devices (no runtime permission
     * exists there at all) and also catches a 33+ user who disabled
     * notifications for the app from system Settings after granting the
     * permission.
     */
    private fun show(
        context: Context,
        channelId: String,
        notificationId: Int,
        title: String,
        text: String,
        contentIntent: Intent
    ) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ActivityCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
        ) {
            Log.d(TAG, "Not posting to $channelId: POST_NOTIFICATIONS not granted")
            return
        }
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) {
            Log.d(TAG, "Not posting to $channelId: notifications disabled for the app in system Settings")
            return
        }

        contentIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val pendingIntent = PendingIntent.getActivity(
            context,
            notificationId,
            contentIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(R.drawable.ic_notification_bell)
            .setContentTitle(title)
            .setContentText(text)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()

        Log.d(TAG, "Posting to $channelId: \"$title\" / \"$text\"")
        NotificationManagerCompat.from(context).notify(notificationId, notification)
    }
}
