package com.wedora.app

import android.net.Uri
import android.widget.ImageView
import androidx.annotation.DrawableRes
import com.bumptech.glide.Glide

/**
 * Loads [photoUrl] into this ImageView when present. When null (no photo set,
 * or a guest with no Firebase user at all), this is a no-op and the view keeps
 * whatever placeholder drawable it already has from its XML `src`.
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
