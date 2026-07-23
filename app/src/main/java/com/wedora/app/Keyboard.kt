package com.wedora.app

import android.content.Context
import android.view.View
import android.view.inputmethod.InputMethodManager
import androidx.core.content.getSystemService

/**
 * Soft-keyboard helpers, shared by the search bars.
 *
 * [showKeyboard] focuses the view first and posts the request, because a view
 * that has only just been made visible isn't yet in a state where the IME will
 * open for it.
 */
fun View.showKeyboard() {
    requestFocus()
    post {
        val imm = context.getSystemService<InputMethodManager>()
        imm?.showSoftInput(this, InputMethodManager.SHOW_IMPLICIT)
    }
}

fun View.hideKeyboard() {
    val imm = context.getSystemService<InputMethodManager>()
    imm?.hideSoftInputFromWindow(windowToken, 0)
}
