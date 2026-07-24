package com.wedora.app

import android.content.Context
import android.util.Log
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.DocumentChange
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration

/**
 * Local (in-process only) notifications for "someone viewed your profile" —
 * Premium only, since free users can't see this data at all (see
 * ProfileViewersActivity's teaser gate).
 *
 * Started once from [WedoraApplication.onCreate], mirroring
 * [MatchNotificationWatcher]'s own AuthStateListener-driven attach/detach.
 * Unlike that watcher, attaching for a signed-in user isn't enough on its own
 * to start listening for views: a second, inner gate watches the user's own
 * `isPremium` field and only attaches the `profileViews/{uid}/viewers`
 * listener while it's true, detaching it the moment it isn't (including a
 * Premium grant/revocation made by hand via Firebase Console while the app is
 * running) — this is what keeps a free user from paying for a Firestore
 * listener on data their own UI never shows them.
 *
 * Same "first snapshot after any attach is a baseline, never notified on"
 * rule as MatchNotificationWatcher, applied here to the viewers listener:
 * this guards against re-notifying on every existing viewer each time
 * Premium status flips on, not just at cold start.
 */
object ProfileViewNotificationWatcher {

    private const val TAG = "WedoraNotify"

    private lateinit var appContext: Context
    private val firestore: FirebaseFirestore by lazy { FirebaseFirestore.getInstance() }

    private var userDocListener: ListenerRegistration? = null
    private var viewersListener: ListenerRegistration? = null

    /** True once the first (baseline) snapshot for the current viewers attach has landed. */
    private var isWarm = false

    private var selfUid: String? = null

    private val authListener = FirebaseAuth.AuthStateListener { auth ->
        // realUid, not currentUser?.uid: a guest's anonymous session (see
        // LoginActivity.continueAsGuest) has no users/{uid} document and can
        // never be Premium, so there's nothing for this watcher to do.
        val uid = auth.realUid
        if (uid == null) stop() else start(uid)
    }

    /** Call once, from Application.onCreate. */
    fun attach(context: Context) {
        appContext = context.applicationContext
        FirebaseAuth.getInstance().addAuthStateListener(authListener)
    }

    private fun start(uid: String) {
        stop() // in case of a uid switch without an intervening null (shouldn't happen, but cheap to guard)

        selfUid = uid
        userDocListener = firestore.collection(UserProfile.COLLECTION).document(uid)
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    Log.w(TAG, "Profile-view watcher's isPremium check failed", error)
                    return@addSnapshotListener
                }
                val isPremium = snapshot?.getBoolean(UserProfile.FIELD_IS_PREMIUM) ?: false
                if (isPremium) attachViewersListener(uid) else detachViewersListener()
            }
    }

    private fun stop() {
        userDocListener?.remove()
        userDocListener = null
        detachViewersListener()
        selfUid = null
    }

    private fun attachViewersListener(uid: String) {
        if (viewersListener != null) return // already attached for this uid

        isWarm = false
        viewersListener = firestore.collection(PROFILE_VIEWS_COLLECTION).document(uid)
            .collection(PROFILE_VIEWS_SUBCOLLECTION_VIEWERS)
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    Log.w(TAG, "Profile-view watcher failed", error)
                    return@addSnapshotListener
                }
                if (snapshot == null) return@addSnapshotListener

                if (!isWarm) {
                    isWarm = true
                    return@addSnapshotListener
                }

                snapshot.documentChanges.forEach { change ->
                    if (change.type != DocumentChange.Type.ADDED) return@forEach
                    val viewerUid = change.document.id
                    // Defensive only — recordProfileView already skips self-views
                    // at the write itself, so this document should never exist.
                    if (viewerUid == uid) return@forEach
                    AppNotifications.notifyProfileView(appContext, viewerUid)
                }
            }
    }

    private fun detachViewersListener() {
        viewersListener?.remove()
        viewersListener = null
        isWarm = false
    }
}
