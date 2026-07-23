package com.wedora.app

import android.text.Editable
import android.text.TextWatcher

/**
 * A TextWatcher that only cares about "the text changed" — for re-validating a
 * form as the user types. Shared so the auth screens don't each re-declare the
 * same three-method boilerplate.
 */
class SimpleTextWatcher(private val onChanged: () -> Unit) : TextWatcher {
    override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
    override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
    override fun afterTextChanged(s: Editable?) = onChanged()
}
