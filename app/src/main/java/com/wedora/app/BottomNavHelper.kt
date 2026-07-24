package com.wedora.app

import android.content.Intent
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration

/**
 * Wires a [BottomNavigationView] the same way on every tab screen: marks
 * [activeTabId] selected, switches to the matching Activity when another tab
 * is tapped, and keeps the Likes/Chats badges live for as long as this screen
 * is in front.
 *
 * No tab is guest-gated here anymore. Profile has its own guest state (see
 * ProfileActivity.showGuestChrome) and Chats now shows two hardcoded demo
 * conversations for a guest (see ChatsActivity.observeConversations) rather
 * than being blocked outright — both are previews of the real thing, not
 * dead ends, so a guest navigates to every tab the same way a signed-in user
 * does.
 */
fun AppCompatActivity.setUpWedoraBottomNav(bottomNav: BottomNavigationView, activeTabId: Int) {
    bottomNav.selectedItemId = activeTabId
    bottomNav.setOnItemSelectedListener { item ->
        if (item.itemId == activeTabId) return@setOnItemSelectedListener true

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

    // Self-contained via the Activity's own lifecycle rather than requiring
    // every one of the five tab screens to add its own onStart/onStop
    // boilerplate — three of them (Explore, Likes, Profile) have no listener
    // of their own today and would need it added purely for this. Same
    // "listener lives only while the screen is in front" shape those that DO
    // have one already use (Home, Chats), just expressed once here instead of
    // five times.
    lifecycle.addObserver(BottomNavBadgeObserver(this, bottomNav))
}

/**
 * Live unseen-likes and unread-messages counts on the Likes and Chats tabs.
 *
 * Both read the same `matches` collection and the same conditions the rest of
 * the app already uses for these exact numbers — Home's notification bell
 * reads the identical unseen-likes test, and ChatsActivity's own row badges
 * sum the identical per-conversation unread counts — so this doesn't
 * introduce a second source of truth, only a second place the first one is
 * shown. A chat "deleted for me" (hiddenBy) is excluded from both counts for
 * the same reason: it isn't shown on the Chats/Likes screens themselves, so
 * counting it on the nav badge would disagree with what tapping the tab
 * actually reveals.
 */
private class BottomNavBadgeObserver(
    private val activity: AppCompatActivity,
    private val bottomNav: BottomNavigationView
) : DefaultLifecycleObserver {

    private val firestore: FirebaseFirestore by lazy { FirebaseFirestore.getInstance() }
    private var listener: ListenerRegistration? = null

    override fun onStart(owner: LifecycleOwner) {
        val selfUid = FirebaseAuth.getInstance().currentUser
            ?.takeUnless { GuestPrefs.isGuest(activity) }?.uid

        if (selfUid == null) {
            bottomNav.applyBadgeCount(R.id.nav_match, 0)
            bottomNav.applyBadgeCount(R.id.nav_chats, 0)
            return
        }

        listener = firestore.collection(Match.COLLECTION)
            .whereArrayContains(Match.FIELD_USERS, selfUid)
            .addSnapshotListener { snapshot, _ ->
                val matches = snapshot?.documents
                    ?.mapNotNull { Match.from(it) }
                    .orEmpty()
                    .filterNot { it.isHiddenFor(selfUid) }

                bottomNav.applyBadgeCount(
                    R.id.nav_match,
                    matches.count { it.isUnseenLikeFor(selfUid) }
                )
                bottomNav.applyBadgeCount(
                    R.id.nav_chats,
                    matches.sumOf { match ->
                        val lastMessage = match.lastMessage
                        if (lastMessage?.senderId != null && lastMessage.senderId != selfUid) {
                            lastMessage.unreadCount
                        } else {
                            0
                        }
                    }
                )
            }
    }

    override fun onStop(owner: LifecycleOwner) {
        listener?.remove()
        listener = null
    }
}

/** Shows [count] as an accent badge on [menuItemId], or hides it at zero. */
private fun BottomNavigationView.applyBadgeCount(menuItemId: Int, count: Int) {
    if (count <= 0) {
        getBadge(menuItemId)?.isVisible = false
        return
    }
    val badge = getOrCreateBadge(menuItemId)
    badge.isVisible = true
    badge.number = count
    // Material appends "+" once the count exceeds what fits, i.e. "99+".
    badge.maxCharacterCount = 2
    badge.backgroundColor = ContextCompat.getColor(context, R.color.wedora_accent)
    badge.badgeTextColor = ContextCompat.getColor(context, R.color.white)
}
