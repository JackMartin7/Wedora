package com.wedora.app

import com.google.firebase.auth.FirebaseAuth

/**
 * The signed-in user's uid, or null for anyone without a genuine account —
 * including a guest's anonymous Firebase session.
 *
 * Guests are signed in anonymously (see LoginActivity.continueAsGuest) purely
 * so Firestore's `request.auth != null` read rules let the browse-only feed
 * queries through — GuestPrefs.isGuest() remains the single source of truth
 * for "is this a guest" everywhere else in the app. Without this property,
 * every write-gating check that used to read `currentUser?.uid == null` as
 * "nobody real is signed in" would silently start succeeding for a guest's
 * anonymous uid instead — liking, messaging, blocking, reporting, passing,
 * and presence would all start writing real Firestore data tied to a session
 * that's supposed to be read-only. Every one of those call sites should read
 * this instead of `currentUser?.uid` directly.
 */
val FirebaseAuth.realUid: String?
    get() = currentUser?.takeUnless { it.isAnonymous }?.uid
