package com.wedora.app

import android.graphics.Rect
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

    /**
     * General-purpose variant of [applyBottomNavScreenInsets] for every
     * screen that isn't one of the five bottom-nav tabs: no
     * BottomNavigationView to treat specially, just a root layout whose top
     * and bottom content both sit directly on root's own edges with plain
     * XML margins — a title/back-arrow row at the top, a floating
     * Continue/Save/Send/Apply-style button or bar at the bottom. That's
     * the shape almost every remaining screen in the app uses.
     *
     * [topTarget]/[bottomTarget] default to [root] itself, which is correct
     * whenever the bottom element is a "floating" view with margins on
     * every side rather than a full-bleed bar touching the screen's edges —
     * same reasoning as [applyBottomNavScreenInsets]'s doc comment: a
     * floating button already has root's own background visible around it,
     * so pushing it up via root padding introduces no colour seam. Pass a
     * different [bottomTarget] (e.g. a full-width message composer bar with
     * its own background) for the rare screen where that doesn't hold.
     *
     * [applyTop]/[applyBottom] let a screen with only one edge that needs
     * handling skip the other, rather than padding a side nothing sits
     * against.
     *
     * [applyIme] additionally folds the on-screen keyboard's own inset into
     * [bottomTarget]'s bottom padding whenever it's showing. This is not
     * optional plumbing left over from pre-edge-to-edge windowSoftInputMode
     * behaviour: enableEdgeToEdge() calls
     * WindowCompat.setDecorFitsSystemWindows(window, false), and per
     * Android's own edge-to-edge guidance that stops the window from
     * automatically resizing for the IME at all — android:windowSoftInputMode
     * ="adjustResize" in the manifest no longer does anything by itself once
     * a screen has opted into edge-to-edge, regardless of API level. Without
     * this, a field low enough on the screen to end up under the keyboard's
     * covered area isn't just visually hidden: it's not receiving touches
     * either, since the (invisible, undrawn, but still present) IME window
     * sits above it in z-order and swallows the tap before it ever reaches
     * the field. Screens with only a lone, higher-up field (search bars,
     * etc.) don't need this — only ones where a field can end up low enough
     * on screen for the keyboard to reach it.
     *
     * Scrolling the focused field into view (once there's room for it) needs
     * two separate triggers, not one: the window insets callback below fires
     * when the *keyboard itself* appears — covers tapping a field while the
     * keyboard is closed — but moving focus via the keyboard's own "Next"
     * action (email -> password) doesn't change the IME inset at all, since
     * the keyboard was already showing and stays exactly the same height.
     * No new insets dispatch means the callback below never re-runs, so that
     * path needs its own listener: a global focus-change listener on root's
     * view tree, gated on the keyboard actually being up right now (tracked
     * in [imeInsetShowing]) so it doesn't try to scroll on ordinary
     * navigation while the keyboard is closed.
     */
    protected fun applyEdgeInsets(
        root: View,
        topTarget: View = root,
        bottomTarget: View = root,
        applyTop: Boolean = true,
        applyBottom: Boolean = true,
        applyIme: Boolean = false
    ) {
        val topInitial = topTarget.paddingTop
        val bottomInitial = bottomTarget.paddingBottom
        var imeInsetShowing = false

        fun scrollIntoView(view: View) {
            view.post {
                view.requestRectangleOnScreen(Rect(0, 0, view.width, view.height), false)
            }
        }

        if (applyIme) {
            root.viewTreeObserver.addOnGlobalFocusChangeListener { _, newFocus ->
                if (imeInsetShowing && newFocus != null) {
                    scrollIntoView(newFocus)
                }
            }
        }

        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val gestures = insets.getInsets(WindowInsetsCompat.Type.mandatorySystemGestures())

            if (applyTop) {
                topTarget.updatePadding(top = topInitial + systemBars.top)
            }
            if (applyBottom) {
                var bottomInset = maxOf(systemBars.bottom, gestures.bottom)
                var imeInset = 0
                if (applyIme) {
                    imeInset = insets.getInsets(WindowInsetsCompat.Type.ime()).bottom
                    imeInsetShowing = imeInset > 0
                    bottomInset = maxOf(bottomInset, imeInset)
                }
                bottomTarget.updatePadding(bottom = bottomInitial + bottomInset)

                // The padding above only makes room; it doesn't move the
                // scroll position, so the now-focused-but-still-offscreen
                // field would otherwise stay hidden until the user scrolled
                // manually. root.findFocus() is the field that was just
                // tapped (focus is requested synchronously on touch, well
                // before this listener fires from the keyboard's own
                // show animation).
                if (imeInsetShowing) {
                    root.findFocus()?.let(::scrollIntoView)
                }
            }

            insets
        }
    }
}
