package com.wedora.app

import android.util.Log
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage

/**
 * Receives FCM token refreshes and incoming push messages.
 *
 * send_notification.php always sends a data-only payload (no "notification"
 * block) so [onMessageReceived] is the single place a push becomes a shown
 * system notification on every app state (foreground, backgrounded, or
 * killed but not force-stopped) — a "notification" payload would instead let
 * the OS auto-display it while backgrounded, bypassing NotificationPrefs and
 * the shared notification-ID dedup [AppNotifications.notifyFromPush] relies
 * on. The data payload's keys are documented on [PushNotificationSender.send].
 */
class WedoraFirebaseMessagingService : FirebaseMessagingService() {

    private companion object {
        const val TAG = "WedoraFcm"

        const val DATA_TYPE = "type"
        const val DATA_SENDER_UID = "senderUid"
        const val DATA_TITLE = "title"
        const val DATA_BODY = "body"
    }

    /**
     * Fires whenever this device's FCM registration token is issued or
     * rotates — not just once ever, so a rotation (reinstall, app data
     * clear, Play Services update, ...) doesn't silently leave
     * `users/{uid}.fcmToken` stale and every push to this device dropped.
     * Skipped for a guest (anonymous session) or a signed-out app: there's
     * no real account to attach the token to yet — the next confirmed
     * sign-in registers it instead, via AuthRouting.registerFcmToken.
     */
    override fun onNewToken(token: String) {
        super.onNewToken(token)

        val uid = FirebaseAuth.getInstance().currentUser
            ?.takeUnless { GuestPrefs.isGuest(applicationContext) }?.uid
        if (uid == null) {
            Log.d(TAG, "New FCM token, but no signed-in user to save it against yet")
            return
        }

        FirebaseFirestore.getInstance().collection(UserProfile.COLLECTION).document(uid)
            .set(mapOf(UserProfile.FIELD_FCM_TOKEN to token), SetOptions.merge())
            .addOnFailureListener { e -> Log.w(TAG, "Failed to save refreshed FCM token", e) }
    }

    /**
     * [DATA_SENDER_UID] is the device that triggered this push (the liker,
     * matcher, or message sender) — always someone other than whoever's
     * signed in *here*, which is what lets a local matchId be rebuilt
     * without the server needing to compute or forward one:
     * `Match.idFor(selfUid, senderUid)` is the same deterministic,
     * order-independent id every other match lookup in this app uses.
     *
     * Silently dropped (no crash, no notification) if any required field is
     * missing, or if nobody is signed in on this device right now — the
     * only way that second case should happen is a push arriving in the
     * brief window after sign-out but before this device's stale token is
     * ever cleaned up server-side.
     */
    override fun onMessageReceived(message: RemoteMessage) {
        super.onMessageReceived(message)

        val data = message.data
        val type = data[DATA_TYPE] ?: return
        val senderUid = data[DATA_SENDER_UID] ?: return
        val title = data[DATA_TITLE].orEmpty()
        val body = data[DATA_BODY].orEmpty()

        val selfUid = FirebaseAuth.getInstance().currentUser?.uid ?: return
        val matchId = Match.idFor(selfUid, senderUid)

        AppNotifications.notifyFromPush(applicationContext, type, matchId, senderUid, title, body)
    }
}
