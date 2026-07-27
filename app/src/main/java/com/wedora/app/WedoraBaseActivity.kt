package com.wedora.app

import android.os.Bundle
import android.view.View
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.updatePadding

/**
 * Common base for every Activity in the app. Centralizes the two things
 * edge-to-edge (targetSdk 35, mandatory from Android 15) requires every
 * screen to do, rather than repeating them ~29 times: opt in via
 * enableEdgeToEdge(), and explicitly set both system bars' icon contrast,
 * now that neither bar paints an opaque background of its own for icons to
 * contrast against — that's what android:statusBarColor/windowLightStatusBar
 * used to hand-roll per theme, and both are deprecated/ignored under
 * edge-to-edge on API 35+.
 *
 * Icon appearance follows the same light/dark split @bool/wedora_light_status_bar
 * already resolves per values/values-night, for both bars: everything this
 * app draws under either system bar is one of the two backgrounds
 * (wedora_bg or wedora_surface) that flip together with that same bool (see
 * colors.xml), so one flag covers both bars on every regular screen.
 * Splash draws its own always-dark gradient behind both bars regardless of
 * theme, so it overrides [isLightSystemBars] with a fixed value instead of
 * reading the bool.
 */
abstract class WedoraBaseActivity : AppCompatActivity() {

    protected open val isLightSystemBars: Boolean
        get() = resources.getBoolean(R.bool.wedora_light_status_bar)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        val controller = WindowInsetsControllerCompat(window, window.decorView)
        controller.isAppearanceLightStatusBars = isLightSystemBars
        controller.isAppearanceLightNavigationBars = isLightSystemBars
    }

    /**
     * Shared inset handling for the five bottom-nav tab screens (Home,
     * Explore, Likes, Chats, Profile). [root] is the screen's top-level
     * ConstraintLayout, [bottomNav] its BottomNavigationView.
     *
     * The status bar inset becomes extra top padding on [root], not on
     * whatever view happens to sit at the top of the screen: every one of
     * these five layouts already anchors its topmost view to the
     * ConstraintLayout's own top edge with a plain XML margin, and
     * ConstraintLayout treats a parent's own padding as that edge for a
     * "toTopOf=parent" constraint — so padding root pushes the whole top
     * bar down by exactly the inset while its internal geometry (row
     * height, that existing margin) is untouched. Padding the top bar view
     * itself instead would grow *its* height and, for a horizontally
     * centered row like Home's greetingContainer, only shift the visible
     * content down by half the added padding.
     *
     * The navigation-bar/gesture inset goes the other way: extra bottom
     * padding on [bottomNav] itself, not on root. Padding root would leave
     * a strip of root's own background colour (wedora_bg) showing below
     * bottomNav in the inset area, since bottomNav draws a different
     * background (bg_bottom_nav / wedora_surface). Padding bottomNav
     * directly means that surface colour extends all the way to the true
     * screen edge — no seam — while its icons/labels sit in the
     * now-shorter content area above the inset.
     *
     * Takes the larger of systemBars() and mandatorySystemGestures() for
     * the bottom inset rather than just systemBars(): both report the
     * gesture-nav pill's height on stock Android, but this is defensive
     * against OEM skins where the two have disagreed.
     *
     * Reads each view's *current* padding once, before the listener can
     * ever fire, and adds the inset on top of that — the listener itself
     * can re-fire (rotation, multi-window), and adding onto whatever
     * padding is already set at that point would keep compounding it on
     * every call instead of resolving to a fixed value.
     */
    protected fun applyBottomNavScreenInsets(root: View, bottomNav: View) {
        val rootInitialTop = root.paddingTop
        val navInitialBottom = bottomNav.paddingBottom

        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val gestures = insets.getInsets(WindowInsetsCompat.Type.mandatorySystemGestures())
            val bottomInset = maxOf(systemBars.bottom, gestures.bottom)

            view.updatePadding(top = rootInitialTop + systemBars.top)
            bottomNav.updatePadding(bottom = navInitialBottom + bottomInset)

            insets
        }
    }
}
