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
import java.io.File
import java.io.IOException

/**
 * Step 5: the profile photo — the only optional step.
 *
 * The photo is device-local (see [LocalProfilePrefs]): never uploaded, never
 * written to Firestore. That's why this step writes nothing and why the
 * routing gate doesn't check it — a user who skips is fully set up as far as
 * the server is concerned, and would otherwise be sent back here forever.
 *
 * Unlike the other steps it saves on pick rather than on Continue. There is no
 * document write to batch it with, and both Continue and Skip end the flow at
 * the same place, so deferring would only create a way to lose the picked file.
 */
class ProfileStep5PhotoActivity : ProfileStepActivity() {

    private lateinit var photoView: ShapeableImageView
    private lateinit var actionLabel: TextView

    private val pickImageLauncher =
        registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
            if (uri != null) savePickedPhoto(uri)
        }

    override val stepNumber = 5
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
        LocalProfilePrefs.getPhotoPath(this, uid)
            ?.let { File(it) }
            ?.takeIf { it.exists() }
            ?.let { showPhoto(it) }
    }

    /** Continue is always available — the photo is optional. */
    override fun isStepValid(): Boolean = true

    /** Nothing goes to Firestore: the photo never leaves the device. */
    override fun stepUpdates(): Map<String, Any?> = emptyMap()

    override fun nextStep(): Class<*> = HomeActivity::class.java

    /**
     * Ends the setup flow rather than stacking Home on top of five steps that
     * should no longer be reachable by back.
     */
    override fun onStepSaved() = finishSetup()

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

            LocalProfilePrefs.setPhotoPath(this, uid, destination.absolutePath)
            showPhoto(destination)
        } catch (e: IOException) {
            Log.w(TAG, "Failed to copy picked profile photo", e)
            Toast.makeText(this, R.string.error_photo_copy_failed, Toast.LENGTH_SHORT).show()
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
}
