package com.wedora.app

import android.content.Context
import android.net.Uri
import android.util.Log
import android.widget.ImageView
import androidx.annotation.DrawableRes
import com.bumptech.glide.Glide
import com.bumptech.glide.signature.ObjectKey
import java.io.File

private const val TAG = "WedoraImageLoading"

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
 * Loads [url] — another user's hosted photo (see PhotoUploadService's
 * `photoUrl`) — into this ImageView, falling back to [placeholderRes] while
 * it loads, on a null/blank url, or if the load fails.
 *
 * Unlike [loadAvatarOrPlaceholder]'s no-op-on-null, this always resolves to
 * something explicitly: a RecyclerView row reused for an item with no photo
 * must not keep showing whatever the previous item's photo was, which a
 * silent no-op here would let happen.
 *
 * Belt-and-suspenders against `IllegalArgumentException: You cannot start a
 * load for a destroyed activity`: this is called from many adapters across
 * the app, each with its own async gap (a Firestore callback, an Activity
 * recreation from a theme toggle) where the host could have been destroyed
 * by the time this actually runs. The real fix is each caller checking its
 * own liveness before getting this far (see HomeActivity.isUsable for the
 * pattern) — that avoids the wasted work, not just the crash — but this
 * catch is the last line of defence for whichever caller doesn't.
 */
fun ImageView.loadRemoteProfilePhoto(
    url: String?,
    @DrawableRes placeholderRes: Int = R.drawable.ic_avatar_placeholder
) {
    try {
        Glide.with(this)
            .load(url?.takeIf { it.isNotBlank() })
            .placeholder(placeholderRes)
            .error(placeholderRes)
            .centerCrop()
            .into(this)
    } catch (e: IllegalArgumentException) {
        Log.w(TAG, "Skipped a photo load — host is no longer usable", e)
    }
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
        // The photo is always written to the same UID-keyed path, and Glide's
        // default cache key is the path alone — so without a signature that
        // changes with the file, editing your photo would keep showing the old
        // one everywhere it's already been loaded.
        .signature(ObjectKey(file.lastModified()))
        .centerCrop()
        .into(this)
}
