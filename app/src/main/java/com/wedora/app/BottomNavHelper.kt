package com.wedora.app

import android.content.Intent
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.bottomnavigation.BottomNavigationView

/**
 * Wires a [BottomNavigationView] the same way on every tab screen: marks
 * [activeTabId] selected, and switches to the matching Activity when another
 * tab is tapped.
 *
 * Chats and Profile are account-only regardless of which tab you tap them
 * from, so guests are redirected to sign-up instead of navigating — this is
 * the single place that gate is enforced for tab navigation. (HomeActivity
 * separately handles its own card actions, which is a different concern from
 * tab switching.)
 */
fun AppCompatActivity.setUpWedoraBottomNav(bottomNav: BottomNavigationView, activeTabId: Int) {
    bottomNav.selectedItemId = activeTabId
    bottomNav.setOnItemSelectedListener { item ->
        if (item.itemId == activeTabId) return@setOnItemSelectedListener true

        val isGuestGatedTab = item.itemId == R.id.nav_chats || item.itemId == R.id.nav_profile
        if (isGuestGatedTab && GuestPrefs.isGuest(this)) {
            Toast.makeText(this, R.string.guest_action_blocked, Toast.LENGTH_SHORT).show()
            startActivity(Intent(this, SignUpActivity::class.java))
            return@setOnItemSelectedListener false
        }

        // Every tab now has a screen, so there's no null case and no
        // hardcoded "coming soon" toast — Explore says that itself, in a real
        // screen with the nav still under it.
        val destination = when (item.itemId) {
            R.id.nav_home -> HomeActivity::class.java
            R.id.nav_maps -> ExploreActivity::class.java
            R.id.nav_match -> LikesActivity::class.java
            R.id.nav_chats -> ChatsActivity::class.java
            R.id.nav_profile -> ProfileActivity::class.java
            else -> return@setOnItemSelectedListener false
        }

        startActivity(Intent(this, destination))
        finish()
        // Tabs are peers, not a stack — a slide would suggest one sits inside
        // another, and switching back would appear to go the wrong way.
        applyLateralTransition()
        true
    }
}
