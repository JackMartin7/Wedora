package com.wedora.app

import android.net.Uri
import android.util.Log
import com.google.firebase.storage.FirebaseStorage
import java.io.File

private const val TAG = "WedoraPhotoUpload"

/**
 * Uploads a profile photo to Firebase Storage so other users' devices —
 * which have no access to this device's local filesDir — have something to
 * load. The local copy in [LocalProfilePrefs] stays the source of truth for
 * this device's own Profile screen; this is purely so OTHER users can see it
 * (see HomeActivity/ProfileDetailActivity/etc. loading `photoUrl` via Glide).
 *
 * Previously uploaded to a custom Hostinger-hosted PHP endpoint
 * (upload.php); replaced with Firebase Storage after that host proved
 * unreliable — connections to it started intermittently timing out/refusing
 * outright, breaking photo loading app-wide with no code-side fix possible,
 * since the app was never the problem. Storage rides on the same Firebase
 * project (Blaze plan) already backing Auth/Firestore, so there's no
 * separate host to go down independently of the rest of the app.
 *
 * One fixed object path per user (`profile_photos/{uid}/photo.jpg`, see
 * storage.rules for the matching owner-write rule) — same "overwrite in
 * place" shape [LocalProfilePrefs] already uses for the local copy, so a
 * re-upload replaces the previous photo rather than accumulating orphaned
 * files. Firebase's own download URL for a given path doesn't change when
 * the object is overwritten (same token), which would otherwise mean Glide
 * keeps serving whatever it cached under that exact URL string after a
 * re-upload — a `v=<timestamp>` query param is appended before the URL is
 * stored to force a fresh load each time, the same role the old Hostinger
 * endpoint's own `?v=` param served.
 */
object PhotoUploadService {

    /**
     * Uploads the JPEG at [localFilePath] for [uid]. [callback] always fires
     * on the main thread, exactly once: [success] true only when both the
     * upload and the subsequent download-URL fetch succeeded; [error] is a
     * short, English, log-friendly reason — callers show their own fixed,
     * localized toast rather than displaying this directly (matches how
     * every other failure listener in this app reports to the user).
     *
     * Firebase's Task listeners already run on the main thread by default
     * (no executor given), so unlike the old HttpURLConnection-based
     * implementation this needs no explicit background thread or main-thread
     * hop of its own.
     */
    fun uploadProfilePhoto(
        localFilePath: String,
        uid: String,
        callback: (success: Boolean, url: String?, error: String?) -> Unit
    ) {
        val file = File(localFilePath)
        if (!file.exists()) {
            deliver(callback, false, null, "Local photo file does not exist")
            return
        }

        val photoRef = FirebaseStorage.getInstance().reference
            .child("profile_photos/$uid/photo.jpg")

        photoRef.putFile(Uri.fromFile(file))
            .addOnSuccessListener {
                photoRef.downloadUrl
                    .addOnSuccessListener { uri ->
                        val cacheBustedUrl = "$uri&v=${System.currentTimeMillis()}"
                        deliver(callback, true, cacheBustedUrl, null)
                    }
                    .addOnFailureListener { e ->
                        Log.w(TAG, "Uploaded photo but failed to fetch its download URL", e)
                        deliver(callback, false, null, "Upload succeeded but URL fetch failed")
                    }
            }
            .addOnFailureListener { e ->
                Log.w(TAG, "Photo upload failed", e)
                deliver(callback, false, null, "Upload failed")
            }
    }

    private fun deliver(
        callback: (success: Boolean, url: String?, error: String?) -> Unit,
        success: Boolean,
        url: String?,
        error: String?
    ) {
        if (!success) Log.w(TAG, "Photo upload unsuccessful: $error")
        callback(success, url, error)
    }
}
