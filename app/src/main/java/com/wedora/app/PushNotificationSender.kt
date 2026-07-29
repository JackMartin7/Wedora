package com.wedora.app

import android.util.Base64
import android.util.Log
import java.io.DataOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.util.ArrayDeque
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
     * either way); actual access control has to live server-side.
     *
     * Every send was failing with HTTP 403 {"success":false,"message":
     * "Invalid or missing key"} before this value was corrected — decoded
     * to "Wedorapassword_xyz789abc123" (capital W), one character off from
     * the PHP script's actual `define('SECRET_KEY', 'wedorapassword_...')`
     * (lowercase w). A case-sensitive string compare on the server rejected
     * every single call regardless of type or recipient, which is exactly
     * the "deterministic, not a burst" pattern that gave it away — a rate
     * limit or abuse filter would only catch some calls, not all of them.
     */
    private const val ENCODED_KEY = "d2Vkb3JhcGFzc3dvcmRfeHl6Nzg5YWJjMTIz"

    const val TYPE_MATCH = "match"
    const val TYPE_MESSAGE = "message"
    const val TYPE_LIKE = "like"

    private const val CONNECT_TIMEOUT_MS = 15_000
    private const val READ_TIMEOUT_MS = 15_000

    private const val BOUNDARY = "----WedoraPushBoundary"
    private const val LINE_END = "\r\n"
    private const val TWO_HYPHENS = "--"

    /**
     * Same logical event never sent twice within this window — a safety net
     * against a double-tap slipping past a caller's own UI debounce, or any
     * future call site that isn't as carefully single-fire as the existing
     * ones. Keyed on what actually distinguishes one push from another:
     * recipient, sender, category, and (for a message, where the same two
     * people can legitimately send many pushes back to back) the text
     * itself — a like or match has no equivalent per-event content, so
     * those key on recipient+sender+type alone, which is already specific
     * enough given [likeUserRespectingDailyLimit]/[sendLikeOrMatchPush]
     * only ever fire once per actual state change.
     */
    private const val DEDUPE_WINDOW_MS = 10_000L

    /**
     * Blunt, global safety net independent of what's actually causing a
     * flood — caps total sends regardless of cause, so a bug anywhere
     * (including one not yet discovered) can't hammer the endpoint into a
     * real rate-limit or abuse block the way a genuine burst eventually
     * would. 20 sends/minute is far above anything a real user's own
     * like/message rate could produce by hand.
     */
    private const val RATE_LIMIT_WINDOW_MS = 60_000L
    private const val RATE_LIMIT_MAX_SENDS = 20

    /** One background thread is plenty — pushes are small, infrequent, fire-and-forget. */
    private val executor = Executors.newSingleThreadExecutor()

    // Both maps/deques below are only ever touched from inside executor's
    // single thread, so — like everything else that's serialized onto one
    // background executor in this app — they need no separate lock.
    private val recentSendTimestamps = mutableMapOf<String, Long>()
    private val recentSendTimes = ArrayDeque<Long>()

    private fun sendKey(): String =
        String(Base64.decode(ENCODED_KEY, Base64.NO_WRAP), Charsets.UTF_8)

    /**
     * POSTs multipart/form-data (key, recipientUid, title, body, dataType,
     * senderUid) to [ENDPOINT]. send_notification.php's own FCM data
     * payload back to the device must use exactly the keys
     * WedoraFirebaseMessagingService.onMessageReceived reads: type,
     * senderUid, title, body.
     *
     * Already async by construction — every call, including the dedupe/
     * rate-limit bookkeeping below, runs on [executor]'s background thread,
     * never the caller's. Skips the network call entirely (logged, not
     * thrown — this stays fire-and-forget either way) if it's a duplicate
     * of a very recent identical send, or if the global rate limit is
     * currently exceeded.
     */
    fun send(recipientUid: String, title: String, body: String, type: String, senderUid: String) {
        executor.execute {
            val now = System.currentTimeMillis()

            val dedupeKey = "$type:$senderUid:$recipientUid:${body.hashCode()}"
            val lastSentAt = recentSendTimestamps[dedupeKey]
            if (lastSentAt != null && now - lastSentAt < DEDUPE_WINDOW_MS) {
                Log.d(TAG, "Skipping duplicate push within dedupe window (type=$type, recipient=$recipientUid)")
                return@execute
            }

            while (recentSendTimes.isNotEmpty() && now - recentSendTimes.peek() > RATE_LIMIT_WINDOW_MS) {
                recentSendTimes.poll()
            }
            if (recentSendTimes.size >= RATE_LIMIT_MAX_SENDS) {
                Log.w(TAG, "Skipping push: rate limit reached (type=$type, recipient=$recipientUid)")
                return@execute
            }

            recentSendTimestamps[dedupeKey] = now
            recentSendTimes.add(now)

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
                    val errorBody = connection.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
                    Log.w(
                        TAG,
                        "Push send failed: HTTP $responseCode body=\"$errorBody\" " +
                            "(type=$type, recipient=$recipientUid)"
                    )
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
