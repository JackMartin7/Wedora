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
import com.bumptech.glide.request.target.CustomTarget
import com.bumptech.glide.request.transition.Transition
import com.google.android.material.imageview.ShapeableImageView
import com.google.firebase.firestore.SetOptions
import java.io.File

/**
 * Step 6: the profile photo. Optional, like [ProfileStep6InterestsActivity]
 * that follows it — neither blocks Continue.
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
 * document write to batch it with, and both Continue and Skip end at the same
 * place — Interests, now the final step — so deferring would only create a
 * way to lose the picked file.
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

    /**
     * The downloaded, upright, face-checked Google photo — set only once
     * [validateThenSuggestGooglePhoto] has cleared it, and the thing
     * [onStepSaved] actually re-hosts. Null means there is nothing to accept,
     * whatever [suggestedGooglePhotoUrl] says.
     */
    private var validatedGooglePhotoFile: File? = null

    private val pickImageLauncher =
        registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
            if (uri != null) photoFlow.start(uri, File(filesDir, "profile_photos/$uid.jpg"))
        }

    /**
     * Normalise -> face check -> crop -> re-check, before anything is saved.
     * The picked image no longer reaches the filesystem untouched: what
     * lands at the destination is the finished 1024px JPEG.
     */
    private val photoFlow = ProfilePhotoFlow(
        activity = this,
        onReady = ::onPhotoReady,
        onRejected = { messageRes -> Toast.makeText(this, messageRes, Toast.LENGTH_LONG).show() },
        onBusyChanged = ::setPhotoBusy
    )

    // Visual step number only — class name and its own string resources
    // (step5_title/step5_subtitle) stay as-is.
    override val stepNumber = 6
    override val stepId = OnboardingAnalytics.STEP_NAME_PHOTO
    override val titleRes = R.string.step5_title
    override val subtitleRes = R.string.step5_subtitle
    override val contentLayoutRes = R.layout.view_step_photo

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Guard: the base finishes early when there's no signed-in user, and
        // the views below would not have been bound.
        if (isFinishing) return

        binding.tvStepSkip.visibility = View.VISIBLE
        binding.tvStepSkip.setOnClickListener {
            OnboardingAnalytics.stepSkip(stepNumber, stepId)
            finishSetup()
        }
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
            // Not shown until it has passed the same face check a picked
            // photo would — see validateThenSuggestGooglePhoto.
            googlePhoto != null -> validateThenSuggestGooglePhoto(googlePhoto)
            else -> Unit
        }
    }

    /** Continue is always available — the photo is optional. */
    override fun isStepValid(): Boolean = true

    /** Nothing goes to Firestore: the photo never leaves the device. */
    override fun stepUpdates(): Map<String, Any?> = emptyMap()

    override fun nextStep(): Class<*> = ProfileStep6InterestsActivity::class.java

    /**
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
        val validated = validatedGooglePhotoFile
        if (suggestedGooglePhotoUrl != null && validated != null) {
            acceptGooglePhoto(validated)
        }
        finishSetup()
    }

    /**
     * Re-hosts the accepted Google photo through the app's own Storage rather
     * than saving Google's URL directly.
     *
     * This reverses the previous decision, which called re-hosting "a slower
     * path to an equivalent string". That was true when the URL was all this
     * step produced; it isn't any more. A photo reaching other users' feeds
     * without passing the face check, or at whatever dimensions Google
     * happens to serve, would be the one way around both new guarantees.
     * [ProfilePhotoPipeline.compressUprightToOutput] never upscales, so a
     * small Google avatar stays small rather than being inflated to 1024.
     *
     * No crop UI on this path: the user never picked this image or asked to
     * frame it, and hijacking Continue with a crop screen would be a worse
     * trade than accepting Google's own framing.
     *
     * Fire-and-forget, like [uploadThenSaveUrl] — Continue has already
     * navigated by the time any of this lands.
     */
    private fun acceptGooglePhoto(uprightFile: File) {
        val destination = File(filesDir, "profile_photos/$uid.jpg")
        ProfilePhotoPipeline.compressUprightToOutput(uprightFile, destination) { ok ->
            if (!ok) {
                Log.w(TAG, "Couldn't compress the accepted Google photo; leaving photoUrl unset")
                return@compressUprightToOutput
            }
            LocalProfilePrefs.setPhotoPath(this, uid, destination.absolutePath)
            uploadThenSaveUrl(destination)
        }
    }

    /**
     * Downloads the Google account photo and runs it through the same
     * normalise + face check the picker path uses, before it is ever offered.
     *
     * Done here rather than at Continue so the block stays silent: a Google
     * photo with no detectable face simply isn't suggested, and the user sees
     * the ordinary "Add Photo" state instead of an error about a photo they
     * never chose.
     */
    private fun validateThenSuggestGooglePhoto(url: Uri) {
        val working = File(cacheDir, GOOGLE_PHOTO_FILENAME)
        Glide.with(this)
            .asFile()
            .load(url)
            .into(object : CustomTarget<File>() {
                override fun onResourceReady(resource: File, transition: Transition<in File>?) {
                    if (isFinishing || isDestroyed) return
                    ProfilePhotoPipeline.prepare(
                        this@ProfileStep5PhotoActivity,
                        Uri.fromFile(resource),
                        working
                    ) { prepared ->
                        if (!prepared || isFinishing || isDestroyed) return@prepare
                        ProfilePhotoPipeline.detectFace(
                            this@ProfileStep5PhotoActivity,
                            Uri.fromFile(working)
                        ) { check ->
                            if (isFinishing || isDestroyed) return@detectFace
                            // Unavailable fails open here too, matching the
                            // picker path: an undownloaded model shouldn't
                            // silently withhold a perfectly good suggestion.
                            if (check is ProfilePhotoPipeline.FaceCheck.None) {
                                Log.w(TAG, "Google account photo has no detectable face; not suggesting it")
                                return@detectFace
                            }
                            validatedGooglePhotoFile = working
                            showSuggestedGooglePhoto(url)
                        }
                    }
                }

                override fun onLoadCleared(placeholder: android.graphics.drawable.Drawable?) = Unit

                override fun onLoadFailed(errorDrawable: android.graphics.drawable.Drawable?) {
                    Log.w(TAG, "Couldn't download the Google account photo; not suggesting it")
                }
            })
    }

    /**
     * Both Continue and Skip land here — the photo is optional either way, so
     * unlike every other step there's nothing that distinguishes them beyond
     * whether a suggested Google photo gets accepted (see [onStepSaved]).
     * Interests is now the final step, so this is a normal
     * goToNextStep()-style handoff rather than ending the flow.
     */
    private fun finishSetup() {
        startActivity(Intent(this, ProfileStep6InterestsActivity::class.java))
        finish()
    }

    /**
     * The finished photo, already face-checked, cropped and compressed by
     * [ProfilePhotoFlow], and already written to the destination file.
     *
     * Nothing here waits on the picker's Uri any more — the flow consumed it
     * well before this point, so the old concern about that grant not
     * outliving the screen is handled upstream.
     */
    private fun onPhotoReady(file: File) {
        // An explicit pick replaces any Google suggestion outright —
        // onStepSaved only checks this for the case where the user never
        // did this, so clearing it here isn't strictly load-bearing, but
        // leaving a stale suggestion around after a real pick would be
        // confusing for anything added later that reads it.
        suggestedGooglePhotoUrl = null
        LocalProfilePrefs.setPhotoPath(this, uid, file.absolutePath)
        showPhoto(file)
        uploadThenSaveUrl(file)
    }

    /**
     * Disables the two pick affordances while the pipeline runs. Decoding a
     * 12MP image and running the detector takes a second or two, and a tap
     * that appears to do nothing invites a second tap and a second pick.
     */
    private fun setPhotoBusy(busy: Boolean) {
        photoView.isEnabled = !busy
        actionLabel.isEnabled = !busy
        actionLabel.setText(
            when {
                busy -> R.string.photo_processing
                LocalProfilePrefs.getPhotoPath(this, uid) != null -> R.string.change_photo
                else -> R.string.add_photo
            }
        )
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
        // Reached only from validateThenSuggestGooglePhoto, i.e. only once
        // the photo has been downloaded and cleared the face check.
        suggestedGooglePhotoUrl = uri.toString()
        photoView.setPadding(0, 0, 0, 0)
        actionLabel.setText(R.string.change_photo)

        Glide.with(this)
            .load(uri)
            .centerCrop()
            .into(photoView)
    }

    private companion object {
        /** cacheDir, not filesDir: this is only ever an intermediate — the
         *  accepted photo is written to the real destination by
         *  [acceptGooglePhoto]. */
        const val GOOGLE_PHOTO_FILENAME = "google_photo_working.jpg"
    }
}
