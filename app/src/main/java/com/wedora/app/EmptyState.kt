package com.wedora.app

import android.view.View
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import com.wedora.app.databinding.ViewEmptyStateBinding

/**
 * Fills a shared empty-state view: illustration, title, subtitle and an
 * optional action.
 *
 * Every screen routes through here so the five empty states can't drift apart
 * in wording weight, spacing or type scale — the thing that usually happens
 * when each one is a bare TextView styled in its own layout.
 *
 * [actionLabel] and [onAction] go together: supply both for a CTA, neither for
 * a plain message. The button is hidden rather than disabled when absent, so
 * nothing suggests an action that isn't there.
 */
fun ViewEmptyStateBinding.show(
    @DrawableRes icon: Int,
    @StringRes title: Int,
    @StringRes subtitle: Int,
    @StringRes actionLabel: Int? = null,
    onAction: (() -> Unit)? = null
) {
    ivEmptyIcon.setImageResource(icon)
    tvEmptyTitle.setText(title)
    tvEmptySubtitle.setText(subtitle)

    if (actionLabel == null || onAction == null) {
        btnEmptyAction.visibility = View.GONE
        btnEmptyAction.setOnClickListener(null)
    } else {
        btnEmptyAction.visibility = View.VISIBLE
        btnEmptyAction.setText(actionLabel)
        btnEmptyAction.setOnClickListener { onAction() }
    }

    root.visibility = View.VISIBLE
}

/**
 * Fades the empty state in rather than snapping it on.
 *
 * Used where it replaces content the user was just looking at — the last card
 * leaving the stack — so the screen doesn't appear to blink. Idempotent: a
 * second call while already visible won't restart the fade.
 */
fun ViewEmptyStateBinding.fadeIn(durationMs: Long = 260L) {
    if (root.visibility == View.VISIBLE && root.alpha == 1f) return
    root.alpha = 0f
    root.visibility = View.VISIBLE
    root.animate().alpha(1f).setDuration(durationMs).start()
}

fun ViewEmptyStateBinding.hide() {
    root.animate().cancel()
    root.visibility = View.GONE
    root.alpha = 1f
}
