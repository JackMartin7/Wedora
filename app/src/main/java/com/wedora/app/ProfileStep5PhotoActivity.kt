package com.wedora.app

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.google.android.material.imageview.ShapeableImageView
import com.google.firebase.firestore.SetOptions
import java.io.File
import java.io.IOException

/**
 * Step 5: the profile photo — the only optional step.
 *
 * The local copy (see [LocalProfilePrefs]) is what this device's own Profile
 * screen reads, and that part of the flow is unchanged: writing nothing here
 * and the routing gate not checking this step are both still about the local
 * copy, not about whether the upload below has succeeded — a user who skips,
 * or whose upload fails, is still fully set up as far as the server is
 * concerned, and would otherwise be sent back here forever.
 *
 * On top of that, a picked photo is now also uploaded (see
 * [PhotoUploadService]) so OTHER users' devices have something to load — see
 * uploadThenSaveUrl. That part is fire-and-forget: it never blocks Continue
 * or Skip, and a failure only shows a toast, since the local copy already
 * covers this user's own Profile screen either way.
 *
 * Unlike the other steps it saves on pick rather than on Continue. There is no
 * document write to batch it with, and both Continue and Skip end the flow at
 * the same place, so deferring would only create a way to lose the picked file.
 */
class ProfileStep5PhotoActivity : ProfileStepActivity() {

    private lateinit var photoView: ShapeableImageView
    private lateinit var actionLabel: TextView

    /**
     * Google's account photo, shown as a suggestion when there's no local
     * pick yet — set only by [showSuggestedGooglePhoto], cleared by
     * [savePickedPhoto] the moment the user actually chooses their own.
     * [onStepSaved] is what turns "shown" into "accepted": tapping Continue
     * with this still set (i.e. never overridden by a real pick) is the
     * user's explicit confirmation, not a silent default. Skip bypasses
     * [onStepSaved] entirely (see [ProfileStepActivity]'s own Continue vs
     * Skip wiring), so skipping never applies a suggestion the user didn't
     * act on either way.
     */
    private var suggestedGooglePhotoUrl: String? = null

    private val pickImageLauncher =
        registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
            if (uri != null) savePickedPhoto(uri)
        }

    // Visual step number only — class name and its own string resources
    // (step5_title/step5_subtitle) stay as-is.
    override val stepNumber = 6
    override val titleRes = R.string.step5_title
    override val subtitleRes = R.string.step5_subtitle
    override val contentLayoutRes = R.layout.view_step_photo

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Guard: the base finishes early when there's no signed-in user, and
        // the views below would not have been bound.
        if (isFinishing) return

        binding.tvStepSkip.visibility = View.VISIBLE
        binding.tvStepSkip.setOnClickListener { finishSetup() }
    }

    override fun bindContent(content: View) {
        photoView = content.findViewById(R.id.ivStepPhoto)
        actionLabel = content.findViewById(R.id.tvStepPhotoAction)

        photoView.setOnClickListener { pickImageLauncher.launch("image/*") }
        actionLabel.setOnClickListener { pickImageLauncher.launch("image/*") }

        // Shows a photo already chosen on a previous pass through the flow.
        val localPhoto = LocalProfilePrefs.getPhotoPath(this, uid)
            ?.let { File(it) }
            ?.takeIf { it.exists() }

        val googlePhoto = auth.currentUser?.photoUrl

        when {
            localPhoto != null -> showPhoto(localPhoto)
            // A suggestion, not an accepted photo — same "confirm or
            // change" treatment as ProfileStep1NameActivity's pre-filled
            // name field. See [suggestedGooglePhotoUrl]'s own doc comment
            // for what turns this into an actual saved photo.
            googlePhoto != null -> showSuggestedGooglePhoto(googlePhoto)
            else -> Unit
        }
    }

    /** Continue is always available — the photo is optional. */
    override fun isStepValid(): Boolean = true

    /** Nothing goes to Firestore: the photo never leaves the device. */
    override fun stepUpdates(): Map<String, Any?> = emptyMap()

    override fun nextStep(): Class<*> = HomeActivity::class.java

    /**
     * Ends the setup flow rather than stacking Home on top of five steps that
     * should no longer be reachable by back.
     *
     * Tapping Continue with [suggestedGooglePhotoUrl] still set — meaning the
     * user saw the Google account photo pre-filled and didn't replace it —
     * is what actually accepts it: only now does it get saved as this
     * account's photoUrl, fire-and-forget same as [uploadThenSaveUrl]'s own
     * write. Stored directly rather than downloaded and re-uploaded through
     * PhotoUploadService: it's already a stable HTTPS URL Google itself
     * serves, so re-hosting it would just be a slower path to an equivalent
     * string.
     */
    override fun onStepSaved() {
        val acceptedGooglePhoto = suggestedGooglePhotoUrl
        if (acceptedGooglePhoto != null) {
            firestore.collection(UserProfile.COLLECTION).document(uid)
                .set(mapOf(UserProfile.FIELD_PHOTO_URL to acceptedGooglePhoto), SetOptions.merge())
                .addOnFailureListener { e ->
                    logFirestoreWriteFailure(TAG, "Failed to save accepted Google photo url", e)
                }
        }
        finishSetup()
    }

    private fun finishSetup() {
        startActivity(
            Intent(this, HomeActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        )
        finish()
    }

    /**
     * Copies the picked image into internal storage straight away rather than
     * holding the picker's Uri, whose grant isn't guaranteed to outlive this
     * screen.
     */
    private fun savePickedPhoto(uri: Uri) {
        try {
            val destination = File(filesDir, "profile_photos/$uid.jpg")
            destination.parentFile?.mkdirs()
            contentResolver.openInputStream(uri)?.use { input ->
                destination.outputStream().use { output -> input.copyTo(output) }
            } ?: throw IOException("openInputStream returned null")

            // An explicit pick replaces any Google suggestion outright —
            // onStepSaved only checks this for the case where the user never
            // did this, so clearing it here isn't strictly load-bearing, but
            // leaving a stale suggestion around after a real pick would be
            // confusing for anything added later that reads it.
            suggestedGooglePhotoUrl = null
            LocalProfilePrefs.setPhotoPath(this, uid, destination.absolutePath)
            showPhoto(destination)
            uploadThenSaveUrl(destination)
        } catch (e: IOException) {
            Log.w(TAG, "Failed to copy picked profile photo", e)
            Toast.makeText(this, R.string.error_photo_copy_failed, Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * Fire-and-forget: never blocks Continue/Skip, and a failure only shows
     * a toast — [file] is already saved locally either way (see
     * savePickedPhoto), so this user's own Profile screen is fine regardless
     * of whether other users can see the photo yet. Re-picking retries this
     * from scratch; EditProfileActivity's own upload is the other retry path
     * once setup is finished.
     *
     * The Firestore write is NOT gated on isFinishing — Continue/Skip call
     * finish() synchronously right after this fires off, well before the
     * network round trip completes, so by the time the callback lands the
     * Activity is routinely already finishing. A Firestore write has no
     * dependency on the Activity being alive; only the failure Toast
     * (genuinely UI) is guarded by isFinishing.
     */
    private fun uploadThenSaveUrl(file: File) {
        PhotoUploadService.uploadProfilePhoto(file.absolutePath, uid) { success, url, error ->
            if (success && url != null) {
                firestore.collection(UserProfile.COLLECTION).document(uid)
                    .set(mapOf(UserProfile.FIELD_PHOTO_URL to url), SetOptions.merge())
                    .addOnFailureListener { e ->
                        logFirestoreWriteFailure(TAG, "Uploaded photo but failed to save its url", e)
                    }
            } else {
                Log.w(TAG, "Profile photo upload failed: $error")
                if (!isFinishing) {
                    Toast.makeText(this, R.string.error_photo_upload_failed, Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun showPhoto(file: File) {
        // Padding and the placeholder tint go once a real photo fills the
        // circle, so it reads as a photo rather than an icon on a swatch.
        photoView.setPadding(0, 0, 0, 0)
        actionLabel.setText(R.string.change_photo)

        // Both caches skipped: the path never changes between picks, so Glide
        // would otherwise redisplay the previous image.
        Glide.with(this)
            .load(file)
            .skipMemoryCache(true)
            .diskCacheStrategy(DiskCacheStrategy.NONE)
            .centerCrop()
            .into(photoView)
    }

    /**
     * The suggestion itself — see [suggestedGooglePhotoUrl]'s doc comment
     * for what turns showing it into actually saving it. Visually identical
     * to [showPhoto] (same padding/label change, so it reads as "you have a
     * photo" rather than something tentative-looking) since the whole point
     * is that this is a real, usable photo the user is free to just keep —
     * the "still needs confirming" part is a matter of what onStepSaved
     * does with it, not how it looks here. A remote URL rather than a File,
     * so no cache-skipping: unlike a local pick, this one URL never changes
     * underneath the same uid.
     */
    private fun showSuggestedGooglePhoto(uri: Uri) {
        suggestedGooglePhotoUrl = uri.toString()
        photoView.setPadding(0, 0, 0, 0)
        actionLabel.setText(R.string.change_photo)

        Glide.with(this)
            .load(uri)
            .centerCrop()
            .into(photoView)
    }
}
