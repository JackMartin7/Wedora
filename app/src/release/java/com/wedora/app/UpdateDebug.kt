package com.wedora.app

import androidx.fragment.app.FragmentActivity

/**
 * Release build: there is no state previewer.
 *
 * The debug variant of this file (app/src/debug/.../UpdateDebug.kt) has the
 * real implementation. Splitting by source set rather than branching on a
 * BuildConfig flag means the previewer's code and its strings are physically
 * absent from a release APK — nothing to reach by reflection, nothing for R8 to
 * have to prove unreachable — while ProfileActivity still compiles against one
 * symbol either way.
 */
object UpdateDebug {

    /** Always false here, so the long-press keeps its production behaviour. */
    const val ENABLED = false

    @Suppress("UNUSED_PARAMETER")
    fun showPicker(activity: FragmentActivity) = Unit
}
