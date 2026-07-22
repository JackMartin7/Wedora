package com.wedora.app

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.RenderEffect
import android.graphics.Shader
import android.os.Build
import android.widget.ImageView
import androidx.annotation.RequiresApi
import kotlin.math.max

/**
 * Blurs an ImageView's contents, used for the locked "featured" like tiles.
 *
 * Two paths, because minSdk is 24:
 *
 *  - API 31+ uses [RenderEffect], which blurs on the GPU at draw time and
 *    leaves the underlying drawable untouched.
 *  - Below that, the drawable is rasterised, shrunk hard and scaled back up.
 *    The downscale is the blur: bilinear filtering averages the pixels it
 *    discards, and stretching the result back reads as an out-of-focus image.
 *    It's cheap and needs no RenderScript, which is deprecated and would pull
 *    a whole toolchain in for one effect.
 *
 * The fallback replaces the view's image, so it must only be applied to a view
 * that stays blurred for its whole lifetime. These tiles are rebuilt on every
 * load rather than recycled, so that holds.
 */
private const val DOWNSCALE_FACTOR = 12

fun ImageView.applyLockedBlur() {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        applyRenderEffectBlur()
    } else {
        applyDownscaleBlur()
    }
}

@RequiresApi(Build.VERSION_CODES.S)
private fun ImageView.applyRenderEffectBlur() {
    setRenderEffect(
        RenderEffect.createBlurEffect(24f, 24f, Shader.TileMode.CLAMP)
    )
}

private fun ImageView.applyDownscaleBlur() {
    val source = drawable ?: return

    // Falls back to the view's own size for a drawable with no intrinsic one
    // (a vector placeholder reports -1), and gives up if neither is known yet.
    val width = max(source.intrinsicWidth, width)
    val height = max(source.intrinsicHeight, height)
    if (width <= 0 || height <= 0) return

    val full = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    Canvas(full).also { canvas ->
        source.setBounds(0, 0, width, height)
        source.draw(canvas)
    }

    val smallWidth = max(1, width / DOWNSCALE_FACTOR)
    val smallHeight = max(1, height / DOWNSCALE_FACTOR)
    val small = Bitmap.createScaledBitmap(full, smallWidth, smallHeight, true)
    val blurred = Bitmap.createScaledBitmap(small, width, height, true)

    full.recycle()
    if (small != blurred) small.recycle()

    setImageBitmap(blurred)
}
