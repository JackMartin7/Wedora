package com.wedora.app

import android.util.Base64
import android.util.Log
import java.io.DataOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.util.concurrent.Executors

/**
 * Sends a push notification through our own Hostinger endpoint, which looks
 * up the recipient's `fcmToken` (see [UserProfile.FIELD_FCM_TOKEN]) and
 * relays to FCM server-side using its own service-account credentials — the
 * app itself never talks to FCM's send API directly, the same division of
 * responsibility [PhotoUploadService] has with the photo host.
 *
 * Fire-and-forget: no caller awaits or reacts to the result, matching every
 * other "nice to have, not correctness-critical" write in this app
 * (presence, profile-view recording, ...). A push that never arrives just
 * means the recipient finds out the next time they open the app instead of
 * immediately — never a data-loss or broken-state situation, and every
 * trigger site already writes the real Firestore data (the match/message/
 * like itself) before calling this.
 *
 * [senderUid] — always the currently signed-in user, i.e. whoever's action
 * triggered this push, never [recipientUid] — is what lets the receiving
 * device's own WedoraFirebaseMessagingService derive a matchId
 * (`Match.idFor(selfUid, senderUid)`) and build the right tap destination,
 * without the server needing to compute or forward one.
 *
 * TODO: NotificationPrefs are device-local; syncing them to Firestore would
 * let the server respect per-user preferences before even sending. For now
 * every trigger always sends, and the *receiving* device is what applies
 * NotificationPrefs before showing anything — see
 * [AppNotifications.notifyFromPush].
 */
object PushNotificationSender {

    private const val TAG = "WedoraPush"

    private const val ENDPOINT = "https://tuberstec.com/wedora/send_notification.php"

    /**
     * The endpoint's shared secret (send_notification.php's SECRET_KEY),
     * Base64-encoded so it isn't a bare grep hit for the literal string —
     * NOT real security (anyone who decompiles the APK has it in seconds
     * either way); actual access control has to live server-side. Same
     * pattern as PhotoUploadService's own upload key.
     */
    private const val ENCODED_KEY = "V2Vkb3JhcGFzc3dvcmRfeHl6Nzg5YWJjMTIz"

    const val TYPE_MATCH = "match"
    const val TYPE_MESSAGE = "message"
    const val TYPE_LIKE = "like"

    private const val CONNECT_TIMEOUT_MS = 15_000
    private const val READ_TIMEOUT_MS = 15_000

    private const val BOUNDARY = "----WedoraPushBoundary"
    private const val LINE_END = "\r\n"
    private const val TWO_HYPHENS = "--"

    /** One background thread is plenty — pushes are small, infrequent, fire-and-forget. */
    private val executor = Executors.newSingleThreadExecutor()

    private fun sendKey(): String =
        String(Base64.decode(ENCODED_KEY, Base64.NO_WRAP), Charsets.UTF_8)

    /**
     * POSTs multipart/form-data (key, recipientUid, title, body, dataType,
     * senderUid) to [ENDPOINT]. send_notification.php's own FCM data
     * payload back to the device must use exactly the keys
     * WedoraFirebaseMessagingService.onMessageReceived reads: type,
     * senderUid, title, body.
     */
    fun send(recipientUid: String, title: String, body: String, type: String, senderUid: String) {
        executor.execute {
            var connection: HttpURLConnection? = null
            try {
                connection = (URL(ENDPOINT).openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    doOutput = true
                    doInput = true
                    useCaches = false
                    connectTimeout = CONNECT_TIMEOUT_MS
                    readTimeout = READ_TIMEOUT_MS
                    setRequestProperty("Connection", "Keep-Alive")
                    setRequestProperty("Content-Type", "multipart/form-data; boundary=$BOUNDARY")
                    // See PhotoUploadService's identical header for why this
                    // matters: some hosting WAFs block Java's default
                    // User-Agent outright.
                    setRequestProperty("User-Agent", "Mozilla/5.0 (Android) WedoraApp")
                }

                DataOutputStream(connection.outputStream).use { out ->
                    writeField(out, "key", sendKey())
                    writeField(out, "recipientUid", recipientUid)
                    writeField(out, "title", title)
                    writeField(out, "body", body)
                    writeField(out, "dataType", type)
                    writeField(out, "senderUid", senderUid)
                    out.writeBytes("$TWO_HYPHENS$BOUNDARY$TWO_HYPHENS$LINE_END")
                }

                val responseCode = connection.responseCode
                if (responseCode !in 200..299) {
                    Log.w(TAG, "Push send failed: HTTP $responseCode (type=$type, recipient=$recipientUid)")
                }
            } catch (e: SocketTimeoutException) {
                Log.w(TAG, "Push send timed out (type=$type, recipient=$recipientUid)", e)
            } catch (e: IOException) {
                Log.w(TAG, "Push send failed (type=$type, recipient=$recipientUid)", e)
            } finally {
                connection?.disconnect()
            }
        }
    }

    private fun writeField(out: DataOutputStream, name: String, value: String) {
        out.writeBytes("$TWO_HYPHENS$BOUNDARY$LINE_END")
        out.writeBytes("Content-Disposition: form-data; name=\"$name\"$LINE_END")
        out.writeBytes(LINE_END)
        out.writeBytes(value)
        out.writeBytes(LINE_END)
    }
}
