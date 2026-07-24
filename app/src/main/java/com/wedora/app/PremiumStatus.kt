package com.wedora.app

import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration

/**
 * In-memory, session-wide cache of the signed-in user's Premium status.
 *
 * Screens that only need it to decide whether to show an upgrade prompt
 * (ProfileActivity's premium card, HomeActivity's crown icon,
 * PaymentSubscriptionActivity's opening state) read [isPremium] instead of
 * each running their own Firestore fetch. Quota-enforcing paths — LikeLimit,
 * MessageLimit, LikesActivity's blur decision, ProfileViewersActivity's gate
 * — deliberately keep their own dedicated reads rather than switch to this
 * cache: those decisions need the authoritative value at the moment of the
 * check, and a cache that hasn't warmed up yet (e.g. very early in a cold
 * start) could let a free user slip past a limit or blur real data for a
 * premium one. A stale upgrade-prompt read has no such consequence — worst
 * case it flickers correct on the next screen visit.
 *
 * Attached once from [WedoraApplication.onCreate], mirroring
 * [MatchNotificationWatcher]'s own AuthStateListener-driven attach/detach:
 * signing out clears the cached value and detaches, signing in (or the
 * already-signed-in state at cold start) attaches for that user. A live
 * Firestore listener (not a one-time read) so a Premium grant made by hand
 * via Firebase Console while the app is open is reflected without a restart.
 */
object PremiumStatus {

    private val firestore: FirebaseFirestore by lazy { FirebaseFirestore.getInstance() }
    private var listener: ListenerRegistration? = null

    private var cachedIsPremium: Boolean = false

    private val authListener = FirebaseAuth.AuthStateListener { auth ->
        // realUid, not currentUser?.uid: a guest's anonymous session (see
        // LoginActivity.continueAsGuest) has no users/{uid} document and can
        // never be Premium, so there's nothing for this cache to watch.
        val uid = auth.realUid
        if (uid == null) stop() else start(uid)
    }

    fun attach() {
        FirebaseAuth.getInstance().addAuthStateListener(authListener)
    }

    /** Best known value so far. False (never Premium) until the first snapshot lands. */
    fun isPremium(): Boolean = cachedIsPremium

    private fun start(uid: String) {
        stop()
        listener = firestore.collection(UserProfile.COLLECTION).document(uid)
            .addSnapshotListener { snapshot, _ ->
                cachedIsPremium = snapshot?.getBoolean(UserProfile.FIELD_IS_PREMIUM) ?: false
            }
    }

    private fun stop() {
        listener?.remove()
        listener = null
        cachedIsPremium = false
    }
}
