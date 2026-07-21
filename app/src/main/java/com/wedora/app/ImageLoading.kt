package com.wedora.app

import android.content.Context
import android.net.Uri
import android.widget.ImageView
import androidx.annotation.DrawableRes
import com.bumptech.glide.Glide
import java.io.File

/**
 * Loads [photoUrl] into this ImageView when present. When null (no photo set,
 * or a guest with no Firebase user at all), this is a no-op and the view keeps
 * whatever placeholder drawable it already has from its XML `src`.
 *
 * Not currently called — profile photos are device-local (see
 * [loadLocalProfilePhoto]) rather than a Firebase Auth photoUrl. Kept for the
 * still-stubbed Google/Facebook sign-in, which would populate exactly this
 * field.
 */
fun ImageView.loadAvatarOrPlaceholder(photoUrl: Uri?, @DrawableRes placeholderRes: Int) {
    if (photoUrl == null) return
    Glide.with(this)
        .load(photoUrl)
        .placeholder(placeholderRes)
        .error(placeholderRes)
        .centerCrop()
        .into(this)
}

/**
 * Loads [uid]'s locally-stored profile photo (see [LocalProfilePrefs]) into
 * this ImageView, if one was saved and the file still exists. Otherwise a
 * no-op — the view keeps its XML placeholder drawable.
 */
fun ImageView.loadLocalProfilePhoto(context: Context, uid: String) {
    val path = LocalProfilePrefs.getPhotoPath(context, uid) ?: return
    val file = File(path)
    if (!file.exists()) return
    Glide.with(this)
        .load(file)
        .centerCrop()
        .into(this)
}
