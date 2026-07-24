package com.wedora.app

import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore

/**
 * Keeps the signed-in user's `lastSeen` fresh so other people can see whether
 * they're around.
 *
 * Observes the *process* lifecycle (via ProcessLifecycleOwner, registered in
 * [WedoraApplication]) rather than any single activity, so it fires once when
 * the app comes to the foreground and once when it goes to the background —
 * regardless of which screen the user is on, and without every activity having
 * to remember to do it.
 *
 * Every write is fire-and-forget and silent on failure: presence is a nicety,
 * never worth a toast, and a missed update just means a slightly stale "last
 * seen" that the next transition corrects. Only the owner writes their own
 * document, so there's no new rule to add.
 */
object PresenceTracker : DefaultLifecycleObserver {

    /** App came to the foreground. */
    override fun onStart(owner: LifecycleOwner) = touch()

    /** App went to the background. */
    override fun onStop(owner: LifecycleOwner) = touch()

    private fun touch() {
        // realUid, not currentUser?.uid: a guest's anonymous session has no
        // users/{uid} document to update at all (guests never go through
        // profile setup), so this would just fail on every foreground/
        // background transition instead of being skipped outright.
        val uid = FirebaseAuth.getInstance().realUid ?: return
        FirebaseFirestore.getInstance()
            .collection(UserProfile.COLLECTION)
            .document(uid)
            .update(UserProfile.FIELD_LAST_SEEN, FieldValue.serverTimestamp())
            // No document yet (mid-signup) or offline — presence is best-effort.
            .addOnFailureListener { /* silent by design */ }
    }
}
