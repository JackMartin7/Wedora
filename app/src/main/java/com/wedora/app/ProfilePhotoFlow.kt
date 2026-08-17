// CropImageContract and CropImageContractOptions are marked deprecated in
// android-image-cropper 4.6.0, but that version ships no replacement — the
// deprecation targets the contract's built-in gallery/camera chooser, which
// this code explicitly turns off (see launchCrop) in favour of the app's own
// picker. Suppressed at file scope rather than left as four repeating
// warnings; revisit if a later release introduces the successor API.
@file:Suppress("DEPRECATION")

package com.wedora.app

import android.net.Uri
import android.util.Log
import androidx.appcompat.app.AppCompatActivity
import com.canhub.cropper.CropImageContract
import com.canhub.cropper.CropImageContractOptions
import com.canhub.cropper.CropImageOptions
import com.canhub.cropper.CropImageView
import java.io.File

private const val TAG = "WedoraPhotoFlow"

/**
 * Drives one profile-photo pick from the picker's Uri to a finished file:
 * normalise -> face check -> crop UI -> face re-check -> hand back.
 *
 * Constructed as an Activity field, because it registers an
 * ActivityResultLauncher and that has to happen before the Activity reaches
 * STARTED — the same reason the existing pickImageLauncher fields are
 * initialised where they are.
 *
 * [onReady] gets a file that is already 1024px-max, JPEG q85 and upright, so
 * callers do exactly what they did before with their raw copy: point
 * LocalProfilePrefs at it, preview it, upload it.
 *
 * [onRejected] gets a string resource to show. It's a resource rather than a
 * message so the caller keeps deciding how to surface it, matching how every
 * other failure path in this app reports.
 *
 * [onBusyChanged] brackets the off-main-thread work — decoding a 12MP image
 * and running the detector is a second or two on a mid-range phone, and the
 * picker returning to a screen that then does nothing visible reads as a
 * failure.
 */
class ProfilePhotoFlow(
    private val activity: AppCompatActivity,
    private val onReady: (File) -> Unit,
    private val onRejected: (Int) -> Unit,
    private val onBusyChanged: (Boolean) -> Unit = {}
) {

    /** The upright intermediate the crop UI works on, kept until the crop returns. */
    private var workingFile: File? = null

    /** Where [onReady]'s file should end up — supplied per pick by the caller. */
    private var destination: File? = null

    private val cropLauncher =
        activity.registerForActivityResult(CropImageContract()) { result ->
            onCropFinished(result)
        }

    /**
     * Entry point. [source] is the picker's Uri; [destination] is where the
     * finished JPEG should be written (the live UID-keyed file during
     * sign-up, a staging file in Edit Profile).
     */
    fun start(source: Uri, destination: File) {
        this.destination = destination
        onBusyChanged(true)

        val working = File(activity.cacheDir, WORKING_FILENAME)
        workingFile = working

        ProfilePhotoPipeline.prepare(activity, source, working) { prepared ->
            if (!activity.isUsableForFlow()) return@prepare
            if (!prepared) {
                finish(R.string.error_photo_processing_failed)
                return@prepare
            }
            checkFaceThenCrop(working)
        }
    }

    private fun checkFaceThenCrop(working: File) {
        ProfilePhotoPipeline.detectFace(activity, Uri.fromFile(working)) { check ->
            if (!activity.isUsableForFlow()) return@detectFace

            when (check) {
                is ProfilePhotoPipeline.FaceCheck.None -> {
                    // The hard block. Nothing is written and no crop opens —
                    // the user goes straight back to picking another image.
                    finish(R.string.error_photo_no_face)
                }

                is ProfilePhotoPipeline.FaceCheck.Unavailable -> {
                    // Fail open: the detector never ran, so there is no
                    // judgement to act on. Straight to the crop UI with no
                    // face-based suggestion, and the user is told the check
                    // was skipped rather than being silently let through.
                    Log.w(TAG, "Face check unavailable; continuing without it")
                    onRejected(R.string.error_photo_check_unavailable)
                    launchCrop(working, suggestion = null)
                }

                is ProfilePhotoPipeline.FaceCheck.Found -> {
                    val (width, height) = ProfilePhotoPipeline.imageSize(working)
                    val suggestion = if (width > 0 && height > 0) {
                        ProfilePhotoPipeline.suggestedCropWindow(check.box, width, height)
                    } else {
                        null
                    }
                    launchCrop(working, suggestion)
                }
            }
        }
    }

    private fun launchCrop(working: File, suggestion: android.graphics.Rect?) {
        onBusyChanged(false)

        val options = CropImageOptions().apply {
            // The source is passed in the contract options, so the library's
            // own gallery/camera chooser must stay out of the way.
            imageSourceIncludeGallery = false
            imageSourceIncludeCamera = false

            cropShape = CropImageView.CropShape.RECTANGLE
            fixAspectRatio = true
            aspectRatioX = 1
            aspectRatioY = 1
            guidelines = CropImageView.Guidelines.ON_TOUCH

            // The library titles its confirm action "Crop", which reads as an
            // operation rather than as the way out of this screen. Both of
            // these render in the ActionBar that Theme.Wedora.Crop supplies —
            // without that theme override they would have nowhere to go.
            activityTitle = activity.getString(R.string.crop_photo_title)
            cropMenuCropButtonTitle = activity.getString(R.string.crop_photo_confirm)

            // Pre-positioned on the detected face. Safe to hand over in the
            // image's own coordinates because the file has already been made
            // upright — see ProfilePhotoPipeline.normalizeToUpright for why
            // that matters more than it looks.
            suggestion?.let { initialCropWindowRectangle = it }

            outputCompressFormat = android.graphics.Bitmap.CompressFormat.JPEG
            outputCompressQuality = ProfilePhotoPipeline.OUTPUT_QUALITY
            outputRequestWidth = ProfilePhotoPipeline.OUTPUT_DIMENSION
            outputRequestHeight = ProfilePhotoPipeline.OUTPUT_DIMENSION
            // RESIZE_INSIDE is a ceiling, not a target: a crop smaller than
            // 1024 is left alone rather than upscaled into false detail.
            outputRequestSizeOptions = CropImageView.RequestSizeOptions.RESIZE_INSIDE
        }

        cropLauncher.launch(CropImageContractOptions(Uri.fromFile(working), options))
    }

    private fun onCropFinished(result: CropImageView.CropResult) {
        if (!activity.isUsableForFlow()) return

        // Cancelled: silent. The user backing out of the crop screen is a
        // decision, not an error, and their previous photo is untouched.
        if (!result.isSuccessful) {
            result.error?.let { Log.w(TAG, "Crop failed", it) }
            cleanUp()
            return
        }

        val cropped = result.uriContent
        val target = destination
        if (cropped == null || target == null) {
            finish(R.string.error_photo_processing_failed)
            return
        }

        onBusyChanged(true)

        // Re-check the crop, not just the original. Detection ran on the full
        // image, so without this a user could frame the crop on a wall and
        // still get through the block.
        //
        // Straight on the crop output, with no normalise step in between:
        // the cropper read an already-upright source so its output is upright
        // too, and re-encoding it here would put a second round of JPEG loss
        // on top of the q85 it was just written at, for nothing.
        ProfilePhotoPipeline.detectFace(activity, cropped) { check ->
            if (!activity.isUsableForFlow()) return@detectFace

            if (check is ProfilePhotoPipeline.FaceCheck.None) {
                finish(R.string.error_photo_no_face_in_crop)
                return@detectFace
            }
            // Found, or Unavailable — the same fail-open reasoning as the
            // first check. No second toast for Unavailable here: the user
            // was already told when it happened the first time.
            deliver(cropped, target)
        }
    }

    /**
     * Copies the cropper's own output onto [target] and hands it to the
     * caller — bytes as written by the cropper, never re-encoded.
     *
     * The copy is needed because the crop output lands in cacheDir, which the
     * system may reclaim at any time; everything downstream
     * (LocalProfilePrefs, the upload) expects a file that stays put.
     */
    private fun deliver(cropped: Uri, target: File) {
        val copied = try {
            target.parentFile?.mkdirs()
            // The elvis here binds to `use`'s value, not to the stream —
            // safe only because copyTo returns a non-null Long, so the whole
            // expression is null exactly when openInputStream was. Reading
            // this shape as "null stream" is wrong whenever the lambda can
            // itself return null; see ProfilePhotoPipeline.normalizeToUpright.
            activity.contentResolver.openInputStream(cropped)?.use { input ->
                target.outputStream().use { output -> input.copyTo(output) }
            } ?: error("openInputStream returned null for the crop output")
            true
        } catch (e: Exception) {
            Log.w(TAG, "Failed to write the finished photo to ${target.absolutePath}", e)
            false
        }

        cleanUp()
        onBusyChanged(false)

        if (copied) onReady(target) else onRejected(R.string.error_photo_copy_failed)
    }

    /** Reports [messageRes], clears state, and drops the busy indicator. */
    private fun finish(messageRes: Int) {
        cleanUp()
        onBusyChanged(false)
        onRejected(messageRes)
    }

    private fun cleanUp() {
        workingFile?.delete()
        workingFile = null
        destination = null
    }

    /**
     * Every callback here can land after the user has left: the crop screen
     * is a separate Activity, and the work before it runs on a background
     * thread.
     */
    private fun AppCompatActivity.isUsableForFlow(): Boolean = !isFinishing && !isDestroyed

    private companion object {
        const val WORKING_FILENAME = "profile_photo_working.jpg"
    }
}
