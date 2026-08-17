package com.wedora.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.graphics.Rect
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.exifinterface.media.ExifInterface
import com.google.mlkit.common.MlKitException
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import java.io.File
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

private const val TAG = "WedoraPhotoPipeline"

/**
 * Everything that happens to a profile photo between the picker handing back
 * a Uri and a file being ready to save and upload: EXIF normalisation, the
 * face check, and the resize/re-encode.
 *
 * Shared by both upload paths — sign-up ([ProfileStep5PhotoActivity]) and
 * [EditProfileActivity] — which previously each did a raw
 * `input.copyTo(output)` of whatever the picker returned. That meant a 3-8MB
 * camera JPEG went to Firebase Storage untouched, and was re-downloaded at
 * full size on every feed card, Explore tile and profile open. Egress, not
 * storage, was the real cost of that.
 */
object ProfilePhotoPipeline {

    /**
     * Final upload dimensions. 1024, not the 800 first considered, because
     * ProfileDetail now shows the photo up to 400dp wide — 1200 physical px
     * on a 3x display — and 800 would visibly soften there. Still roughly a
     * 30-50x reduction against the multi-MB originals this replaces.
     *
     * RESIZE_INSIDE semantics: this is a ceiling, never an upscale. A small
     * source (a Google account photo is often 96-512px) is left at its own
     * size rather than being blown up.
     */
    const val OUTPUT_DIMENSION = 1024
    const val OUTPUT_QUALITY = 85

    /**
     * Ceiling for the intermediate upright copy. Big enough that the crop UI
     * still has real detail to work with and small faces stay detectable,
     * small enough that a 12MP source doesn't decode into an OOM.
     */
    private const val WORK_MAX_DIMENSION = 2048
    private const val WORK_QUALITY = 90

    /**
     * How much bigger than the detected face the suggested crop should be.
     * A face box is tight to the features — chin to brow, ear to ear — and
     * cropping to it produces an unsettling headshot with no hair or
     * shoulders. 2.2x is roughly a conventional portrait framing.
     */
    private const val FACE_CROP_EXPANSION = 2.2f

    /** Minimum face size as a fraction of the image, passed to ML Kit, so a
     *  bystander in the background doesn't satisfy the requirement. */
    private const val MIN_FACE_SIZE = 0.15f

    /** Outcome of one face check. */
    sealed class FaceCheck {
        /** A usable face, its box in the upright image's coordinate space. */
        data class Found(val box: Rect) : FaceCheck()

        /** Detection ran and found nothing — the hard block. */
        object None : FaceCheck()

        /**
         * Detection could not run at all: the Play services model isn't
         * downloaded yet, or this device has no Play services.
         *
         * Deliberately distinct from [None], and callers fail OPEN on it.
         * "No face in this photo" is a judgement about the photo; "we
         * couldn't look" is not, and turning the second into a block would
         * permanently lock some users out of ever setting a photo.
         */
        data class Unavailable(val cause: Exception?) : FaceCheck()
    }

    private val mainHandler = Handler(Looper.getMainLooper())

    /**
     * Decodes [source], applies its EXIF orientation, and writes an upright
     * JPEG with no orientation tag to [destination].
     *
     * **This is the step that makes everything downstream safe.** BitmapFactory
     * ignores the EXIF orientation tag entirely, so a photo taken in portrait
     * on a phone camera decodes sideways. Two separate things would break
     * without this:
     *
     *  - the obvious one — the saved photo comes out rotated;
     *  - the subtle one — ML Kit reports face boxes in upright coordinates
     *    (InputImage applies EXIF rotation internally), while the crop
     *    library has its own view of the source image. Feeding a box from one
     *    coordinate space into the other lands the suggested crop 90 degrees
     *    out on exactly the photos users most often upload.
     *
     * Normalising first collapses both: from here on there is one coordinate
     * space, the file's own, and every consumer agrees on it.
     *
     * Runs on the caller's thread — always a background one, see [prepare].
     */
    private fun normalizeToUpright(context: Context, source: Uri, destination: File): Boolean {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        try {
            val stream = context.contentResolver.openInputStream(source)
            if (stream == null) {
                Log.w(TAG, "openInputStream returned null for $source")
                return false
            }
            // The result of this decode is deliberately discarded and must
            // NOT be null-checked: in inJustDecodeBounds mode decodeStream
            // returns null by contract, having written what we want into
            // `bounds` instead. Null-checking it — via `?.use { decode() }
            // ?: return false`, where the elvis binds to the lambda's value
            // rather than to the stream — made this function fail on every
            // single image, which is the bug this shape exists to prevent.
            stream.use { BitmapFactory.decodeStream(it, null, bounds) }
        } catch (e: Exception) {
            // Inside the try because a content provider can refuse or revoke
            // a grant here (SecurityException) — outside it, that would
            // escape prepare()'s bare Thread and crash the process rather
            // than telling the user to pick another photo.
            Log.w(TAG, "Couldn't read bounds for $source", e)
            return false
        }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            Log.w(TAG, "Undecodable image: ${bounds.outWidth}x${bounds.outHeight} for $source")
            return false
        }

        val orientation = try {
            context.contentResolver.openInputStream(source)?.use { stream ->
                ExifInterface(stream).getAttributeInt(
                    ExifInterface.TAG_ORIENTATION,
                    ExifInterface.ORIENTATION_NORMAL
                )
            } ?: ExifInterface.ORIENTATION_NORMAL
        } catch (e: Exception) {
            // A missing or malformed EXIF block is normal for screenshots and
            // re-saved images; treat it as "already upright" rather than
            // failing the whole pick.
            Log.w(TAG, "Couldn't read EXIF orientation; assuming normal", e)
            ExifInterface.ORIENTATION_NORMAL
        }

        val options = BitmapFactory.Options().apply {
            inSampleSize = sampleSizeFor(bounds.outWidth, bounds.outHeight, WORK_MAX_DIMENSION)
        }

        return try {
            // Unlike the bounds pass above, this decode DOES return the
            // bitmap, so a null result here is a real failure — but it can
            // mean either "no stream" or "undecodable", and the two are worth
            // telling apart in a log.
            val stream = context.contentResolver.openInputStream(source)
            if (stream == null) {
                Log.w(TAG, "openInputStream returned null on the decode pass for $source")
                return false
            }
            val decoded = stream.use { BitmapFactory.decodeStream(it, null, options) }
            if (decoded == null) {
                Log.w(TAG, "decodeStream returned null at inSampleSize=${options.inSampleSize} for $source")
                return false
            }

            val upright = applyOrientation(decoded, orientation)
            destination.parentFile?.mkdirs()
            // See compressUprightToOutput: compress()'s Boolean is the only
            // signal that the file was actually written.
            val written = destination.outputStream().use {
                upright.compress(Bitmap.CompressFormat.JPEG, WORK_QUALITY, it)
            }
            if (upright !== decoded) decoded.recycle()
            upright.recycle()
            if (!written) Log.w(TAG, "compress() failed writing ${destination.absolutePath}")
            written
        } catch (e: OutOfMemoryError) {
            // A source large enough to OOM even after sampling. Reported
            // rather than crashed: the user just picked a photo, and a
            // "try another image" is a far better outcome than a crash.
            Log.w(TAG, "Ran out of memory normalising the picked photo", e)
            false
        } catch (e: Exception) {
            Log.w(TAG, "Failed to normalise the picked photo", e)
            false
        }
    }

    /**
     * All eight EXIF orientations, not just the three rotations. The mirrored
     * states are rare from a camera but routine from front-facing selfie
     * apps, which is exactly the population uploading profile photos.
     */
    private fun applyOrientation(bitmap: Bitmap, orientation: Int): Bitmap {
        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_NORMAL,
            ExifInterface.ORIENTATION_UNDEFINED -> return bitmap
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.setRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.setRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.setRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.setScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.setScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> {
                matrix.setRotate(90f)
                matrix.postScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_TRANSVERSE -> {
                matrix.setRotate(270f)
                matrix.postScale(-1f, 1f)
            }
            else -> return bitmap
        }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }

    /**
     * Smallest power-of-two inSampleSize that brings the longest edge within
     * [maxDimension].
     *
     * The comparison is on `longest / sample` — the size the decode will
     * actually produce — not on the next step down. Testing the next step
     * looks equivalent but is off by one factor of two: a 4000px source
     * against a 2048 ceiling reads `4000/2 = 2000 >= 2048` as false and
     * stops at sample=1, decoding the full 4000px image. On a 4000x3000
     * photo that is a 48MB ARGB_8888 allocation instead of 12MB, which is an
     * OOM on a low-end device rather than a slightly bigger bitmap.
     */
    private fun sampleSizeFor(width: Int, height: Int, maxDimension: Int): Int {
        var sample = 1
        while (max(width, height) / sample > maxDimension) {
            sample *= 2
        }
        return sample
    }

    /**
     * Runs face detection on [source], which must already be upright.
     *
     * ACCURATE rather than FAST: this is a one-shot check on a still image
     * where a miss means wrongly refusing someone's photo, so the extra
     * hundred milliseconds is the right trade. Landmarks, contours and
     * classification are all off — only the bounding box is used, and each
     * of those adds work for nothing here.
     *
     * [onResult] fires on the main thread, exactly once.
     */
    fun detectFace(context: Context, source: Uri, onResult: (FaceCheck) -> Unit) {
        val image = try {
            InputImage.fromFilePath(context, source)
        } catch (e: Exception) {
            Log.w(TAG, "Couldn't build an InputImage from the photo", e)
            mainHandler.post { onResult(FaceCheck.Unavailable(e)) }
            return
        }

        val detector = FaceDetection.getClient(
            FaceDetectorOptions.Builder()
                .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE)
                .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_NONE)
                .setContourMode(FaceDetectorOptions.CONTOUR_MODE_NONE)
                .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_NONE)
                .setMinFaceSize(MIN_FACE_SIZE)
                .build()
        )

        detector.process(image)
            .addOnSuccessListener { faces ->
                detector.close()
                // Largest box wins when there's more than one face: in a
                // group photo the subject is overwhelmingly the nearest,
                // hence biggest, person.
                val largest = faces.maxByOrNull { it.boundingBox.width() * it.boundingBox.height() }
                onResult(if (largest == null) FaceCheck.None else FaceCheck.Found(largest.boundingBox))
            }
            .addOnFailureListener { e ->
                detector.close()
                // UNAVAILABLE specifically means the Play services model
                // hasn't been delivered yet. Anything else is also treated as
                // "couldn't look" rather than "no face" — the block is only
                // ever applied on a detector that actually ran and came back
                // empty.
                val unavailable = e is MlKitException && e.errorCode == MlKitException.UNAVAILABLE
                Log.w(TAG, "Face detection failed (unavailable=$unavailable)", e)
                onResult(FaceCheck.Unavailable(e))
            }
    }

    /**
     * Suggested crop window for a detected face: the box grown by
     * [FACE_CROP_EXPANSION], squared off (the crop is locked 1:1), and
     * clamped inside the image.
     *
     * Clamping shifts the square back inside the bounds rather than shrinking
     * it, so a face near an edge still gets a full-size suggestion instead of
     * a cramped one. If the image is smaller than the square in either axis
     * the whole image is used.
     */
    fun suggestedCropWindow(face: Rect, imageWidth: Int, imageHeight: Int): Rect {
        val side = min(
            (max(face.width(), face.height()) * FACE_CROP_EXPANSION).roundToInt(),
            min(imageWidth, imageHeight)
        )
        val half = side / 2

        val centerX = face.centerX().coerceIn(half, imageWidth - half)
        val centerY = face.centerY().coerceIn(half, imageHeight - half)

        return Rect(centerX - half, centerY - half, centerX + half, centerY + half)
    }

    /** Pixel dimensions of an upright JPEG, without decoding it. */
    fun imageSize(file: File): Pair<Int, Int> {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        return bounds.outWidth to bounds.outHeight
    }

    /**
     * Normalises [source] into [destination] off the main thread and reports
     * back on it. [onDone] receives false when the image couldn't be read or
     * decoded at all.
     */
    fun prepare(context: Context, source: Uri, destination: File, onDone: (Boolean) -> Unit) {
        Thread {
            val ok = normalizeToUpright(context, source, destination)
            mainHandler.post { onDone(ok) }
        }.start()
    }

    /**
     * Resize-and-re-encode without a crop step, for a photo the user never
     * picked and so was never offered the chance to frame — currently only
     * the suggested Google account photo. Same output settings as the crop
     * path, so nothing reaches Storage un-shrunk by either route.
     */
    fun compressUprightToOutput(sourceUpright: File, destination: File, onDone: (Boolean) -> Unit) {
        Thread {
            val ok = try {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(sourceUpright.absolutePath, bounds)

                val options = BitmapFactory.Options().apply {
                    inSampleSize = sampleSizeFor(bounds.outWidth, bounds.outHeight, OUTPUT_DIMENSION)
                }
                val decoded = BitmapFactory.decodeFile(sourceUpright.absolutePath, options)
                if (decoded == null) {
                    false
                } else {
                    // Never upscales: a source already under the ceiling is
                    // written through at its own size.
                    val scale = min(
                        1f,
                        OUTPUT_DIMENSION.toFloat() / max(decoded.width, decoded.height)
                    )
                    val scaled = if (scale < 1f) {
                        Bitmap.createScaledBitmap(
                            decoded,
                            (decoded.width * scale).roundToInt(),
                            (decoded.height * scale).roundToInt(),
                            true
                        )
                    } else {
                        decoded
                    }

                    destination.parentFile?.mkdirs()
                    // compress() reports success as a Boolean; discarding it
                    // would let a truncated or empty file be reported as a
                    // finished photo.
                    val written = destination.outputStream().use {
                        scaled.compress(Bitmap.CompressFormat.JPEG, OUTPUT_QUALITY, it)
                    }
                    if (scaled !== decoded) decoded.recycle()
                    scaled.recycle()
                    if (!written) Log.w(TAG, "compress() failed writing ${destination.absolutePath}")
                    written
                }
            } catch (e: OutOfMemoryError) {
                Log.w(TAG, "Ran out of memory compressing the photo", e)
                false
            } catch (e: Exception) {
                Log.w(TAG, "Failed to compress the photo", e)
                false
            }
            mainHandler.post { onDone(ok) }
        }.start()
    }
}
