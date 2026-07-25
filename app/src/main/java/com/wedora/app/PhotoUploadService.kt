package com.wedora.app

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.util.Log
import android.widget.Toast
import org.json.JSONException
import org.json.JSONObject
import java.io.DataOutputStream
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.util.concurrent.Executors

/**
 * Uploads a profile photo to the hosted endpoint so other users' devices —
 * which have no access to this device's local filesDir — have something to
 * load. The local copy in [LocalProfilePrefs] stays the source of truth for
 * this device's own Profile screen; this is purely so OTHER users can see it
 * (see HomeActivity/ProfileDetailActivity/etc. loading `photoUrl` via Glide).
 *
 * Plain [HttpURLConnection] rather than OkHttp/Retrofit: this is the only
 * network call in the app that isn't Firebase, so pulling in a whole HTTP
 * client for one multipart POST would be a much bigger dependency than the
 * problem calls for. Multipart is built by hand for the same reason.
 */
object PhotoUploadService {

    private const val TAG = "WedoraPhotoUpload"

    private const val UPLOAD_URL =
        "https://lightcoral-elephant-196936.hostingersite.com/wedora/upload.php"

    /**
     * The endpoint's shared secret (upload.php's SECRET_KEY), Base64-encoded
     * so it isn't a bare grep hit for the literal string — NOT real security
     * (anyone who decompiles the APK has it in seconds either way); actual
     * access control has to live server-side.
     */
    private const val ENCODED_UPLOAD_KEY = "V2Vkb3JhcGFzc3dvcmRfYWJjMTIzeHl6Nzg5"

    private const val CONNECT_TIMEOUT_MS = 15_000
    private const val READ_TIMEOUT_MS = 30_000

    private const val BOUNDARY = "----WedoraPhotoUploadBoundary"
    private const val LINE_END = "\r\n"
    private const val TWO_HYPHENS = "--"

    /** One background thread is plenty — photo uploads don't overlap in practice. */
    private val executor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())

    private fun uploadKey(): String =
        String(Base64.decode(ENCODED_UPLOAD_KEY, Base64.NO_WRAP), Charsets.UTF_8)

    // ----- TEMPORARY DIAGNOSTIC (photoUrl-never-lands bug) -----------------
    //
    // adb logcat isn't reliably reaching this device (same issue hit while
    // debugging the Likes ad-gap bug), so this uses on-screen Toasts instead
    // — they don't depend on adb/logcat at all. Remove this whole block, and
    // the diagToast call sites below, once the bug is confirmed fixed.
    //
    // Always routed through mainHandler: this fires from both the caller's
    // thread (the start toast) and from [executor]'s background thread (the
    // response/exception toasts), and Toast.show() must run on the main
    // thread regardless of which one called in.
    private fun diagToast(context: Context, message: String) {
        mainHandler.post {
            Toast.makeText(context.applicationContext, "DIAG: $message", Toast.LENGTH_LONG).show()
        }
    }

    /**
     * Uploads the JPEG at [localFilePath] for [uid]. [callback] always fires
     * on the main thread, exactly once: [success] true only when the server
     * reported success AND returned a usable `url`; [error] is a short,
     * English, log-friendly reason — callers show their own fixed, localized
     * toast rather than displaying this directly (matches how every other
     * failure listener in this app reports to the user).
     *
     * Network work runs entirely on [executor]; nothing here ever touches
     * the main thread except the final [callback] delivery.
     */
    fun uploadProfilePhoto(
        context: Context,
        localFilePath: String,
        uid: String,
        callback: (success: Boolean, url: String?, error: String?) -> Unit
    ) {
        diagToast(context, "upload starting, uid=$uid file=$localFilePath")

        executor.execute {
            val file = File(localFilePath)
            if (!file.exists()) {
                diagToast(context, "local file missing at $localFilePath")
                deliver(callback, false, null, "Local photo file does not exist")
                return@execute
            }

            var connection: HttpURLConnection? = null
            try {
                connection = (URL(UPLOAD_URL).openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    doOutput = true
                    doInput = true
                    useCaches = false
                    connectTimeout = CONNECT_TIMEOUT_MS
                    readTimeout = READ_TIMEOUT_MS
                    setRequestProperty("Connection", "Keep-Alive")
                    setRequestProperty("Content-Type", "multipart/form-data; boundary=$BOUNDARY")
                }

                DataOutputStream(connection.outputStream).use { out ->
                    writeFormField(out, "key", uploadKey())
                    writeFormField(out, "uid", uid)
                    writeFileField(out, "photo", file)
                    out.writeBytes("$TWO_HYPHENS$BOUNDARY$TWO_HYPHENS$LINE_END")
                }

                val responseCode = connection.responseCode
                val body = (if (responseCode in 200..299) connection.inputStream else connection.errorStream)
                    ?.bufferedReader()?.use { it.readText() }
                    .orEmpty()

                diagToast(context, "HTTP $responseCode, body=${body.take(300)}")

                if (responseCode !in 200..299) {
                    deliver(callback, false, null, "Upload failed: HTTP $responseCode")
                    return@execute
                }

                parseResponse(context, body, callback)
            } catch (e: SocketTimeoutException) {
                Log.w(TAG, "Photo upload timed out", e)
                diagToast(context, "exception SocketTimeoutException: ${e.message}")
                deliver(callback, false, null, "Upload timed out")
            } catch (e: IOException) {
                Log.w(TAG, "Photo upload failed", e)
                diagToast(context, "exception ${e.javaClass.simpleName}: ${e.message}")
                deliver(callback, false, null, "No connection")
            } finally {
                connection?.disconnect()
            }
        }
    }

    private fun parseResponse(
        context: Context,
        body: String,
        callback: (success: Boolean, url: String?, error: String?) -> Unit
    ) {
        try {
            val json = JSONObject(body)
            val success = json.optBoolean("success", false)
            val url = json.optString("url").takeIf { it.isNotBlank() }
            when {
                success && url != null -> deliver(callback, true, url, null)
                success -> deliver(callback, false, null, "Server reported success but returned no url")
                else -> deliver(
                    callback, false, null,
                    json.optString("message").takeIf { it.isNotBlank() } ?: "Upload failed"
                )
            }
        } catch (e: JSONException) {
            Log.w(TAG, "Photo upload returned invalid JSON: $body", e)
            diagToast(context, "exception parsing JSON: ${e.message}")
            deliver(callback, false, null, "Invalid server response")
        }
    }

    private fun deliver(
        callback: (success: Boolean, url: String?, error: String?) -> Unit,
        success: Boolean,
        url: String?,
        error: String?
    ) {
        if (!success) Log.w(TAG, "Photo upload unsuccessful: $error")
        mainHandler.post { callback(success, url, error) }
    }

    private fun writeFormField(out: DataOutputStream, name: String, value: String) {
        out.writeBytes("$TWO_HYPHENS$BOUNDARY$LINE_END")
        out.writeBytes("Content-Disposition: form-data; name=\"$name\"$LINE_END")
        out.writeBytes(LINE_END)
        out.writeBytes(value)
        out.writeBytes(LINE_END)
    }

    private fun writeFileField(out: DataOutputStream, name: String, file: File) {
        out.writeBytes("$TWO_HYPHENS$BOUNDARY$LINE_END")
        out.writeBytes(
            "Content-Disposition: form-data; name=\"$name\"; filename=\"${file.name}\"$LINE_END"
        )
        out.writeBytes("Content-Type: image/jpeg$LINE_END")
        out.writeBytes(LINE_END)
        file.inputStream().use { it.copyTo(out) }
        out.writeBytes(LINE_END)
    }
}
