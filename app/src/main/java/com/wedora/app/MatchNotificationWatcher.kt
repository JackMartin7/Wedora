package com.wedora.app

import android.content.Context
import android.util.Log
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.DocumentChange

/**
 * Local (in-process only) notifications for new matches, messages and likes.
 *
 * Started once from [WedoraApplication.onCreate] via a [FirebaseAuth]
 * `AuthStateListener` — not tied to any Activity's lifecycle, so it keeps
 * running while the app is backgrounded and not just while something is on
 * screen, matching "fires while the process is alive." Signing out detaches
 * it; signing in (or the already-signed-in state at cold start) attaches it
 * for that user.
 *
 * A single `matches` listener covers all three categories, since likes,
 * matches and messages are all fields on the same document:
 *  - A brand-new match document with exactly one liker who isn't this user
 *    is a like received (not yet mutual) — "New Like".
 *  - An existing match transitioning to mutual, where the participant who
 *    just got added isn't this user, is "New Match" — the other person
 *    completed it.
 *  - An existing match's `lastMessage` changing, where the sender isn't this
 *    user, is a new message — unless [ActiveChatTracker] says that thread is
 *    already open.
 *
 * "Already seen, don't re-notify after a restart": the first snapshot
 * delivered after *any* attach — cold start, sign-in, or a process restart —
 * is always treated as a baseline and never notified on; only genuine deltas
 * against it (via documentChanges()) on later snapshots fire anything. A
 * restart always means a fresh attach, so whatever already existed before the
 * restart is baseline again, not "new". No separate SharedPreferences
 * watermark is needed on top of that — it would just be re-deriving the same
 * guarantee a second way, this time on the process's own in-memory
 * [knownMatches] cache instead of the SDK's.
 */
object MatchNotificationWatcher {

    private const val TAG = "WedoraNotify"

    private lateinit var appContext: Context
    private val firestore: FirebaseFirestore by lazy { FirebaseFirestore.getInstance() }

    private var matchesListener: ListenerRegistration? = null

    /** True once the first (baseline) snapshot for the current attach has landed. */
    private var isWarm = false

    /** Last known state per matchId, for diffing the next snapshot against. */
    private val knownMatches = mutableMapOf<String, Match>()

    /**
     * Who [knownMatches] currently belongs to, or null when signed out or a
     * guest. Handed to observers so they never have to ask Auth themselves and
     * risk disagreeing with the data they were just given.
     */
    private var currentUid: String? = null

    /**
     * Anything that wants the live match set without opening its own listener.
     *
     * This exists because BottomNavHelper used to run the exact same
     * whereArrayContains query, re-attached on every tab screen's onStart -
     * and since the nav finishes each tab as it switches, every tab tap paid
     * for a fresh initial snapshot of every match the user has. This watcher
     * already holds that data for the whole process, so the badges can read it
     * for nothing.
     */
    private val observers = mutableSetOf<(List<Match>, String?) -> Unit>()

    /**
     * Registers [observer] and IMMEDIATELY replays the current state to it.
     *
     * The replay is the important half. This watcher warms once at app start,
     * so by the time any tab screen subscribes it is long past its priming
     * snapshot and no further change may arrive for minutes. Without the
     * replay a subscriber would sit at zero until something happened to move -
     * badges would simply look empty.
     *
     * Not warm yet means the empty replay is correct rather than stale: the
     * priming emit below follows the moment it lands.
     */
    fun addObserver(observer: (List<Match>, String?) -> Unit) {
        observers.add(observer)
        observer(if (isWarm) knownMatches.values.toList() else emptyList(), currentUid)
    }

    fun removeObserver(observer: (List<Match>, String?) -> Unit) {
        observers.remove(observer)
    }

    /**
     * Pushes the current set to every observer.
     *
     * Called once per snapshot batch rather than once per document change, so
     * a snapshot touching five documents produces one badge update, not five.
     * Iterates a copy so an observer removing itself mid-emit cannot mutate
     * the set being walked.
     */
    private fun emit() {
        val snapshot = knownMatches.values.toList()
        observers.toList().forEach { it(snapshot, currentUid) }
    }

    private val authListener = FirebaseAuth.AuthStateListener { auth ->
        // realUid, not currentUser?.uid: a guest's anonymous session (see
        // LoginActivity.continueAsGuest) has no matches and never will, so
        // there's nothing for this watcher to do for one.
        val uid = auth.realUid
        if (uid == null) stop() else start(uid)
    }

    /** Call once, from Application.onCreate. */
    fun attach(context: Context) {
        appContext = context.applicationContext
        FirebaseAuth.getInstance().addAuthStateListener(authListener)
    }

    private fun start(selfUid: String) {
        stop() // in case of a uid switch without an intervening null (shouldn't happen, but cheap to guard)

        isWarm = false
        knownMatches.clear()
        currentUid = selfUid
        matchesListener = firestore.collection(Match.COLLECTION)
            .whereArrayContains(Match.FIELD_USERS, selfUid)
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    Log.w(TAG, "Match watcher failed", error)
                    return@addSnapshotListener
                }
                if (snapshot == null) return@addSnapshotListener

                if (!isWarm) {
                    snapshot.documents.forEach { doc ->
                        Match.from(doc)?.let { knownMatches[it.id] = it }
                    }
                    isWarm = true
                    // Covers an observer that subscribed during the cold window
                    // and got the empty replay; addObserver covers everyone
                    // who arrives after this point.
                    emit()
                    return@addSnapshotListener
                }

                snapshot.documentChanges.forEach { change ->
                    handleChange(change, selfUid)
                }
                emit()
            }
    }

    private fun stop() {
        matchesListener?.remove()
        matchesListener = null
        isWarm = false
        knownMatches.clear()
        currentUid = null
        // Sign-out, or switching to a guest: observers must clear rather than
        // keep showing the previous account's counts.
        emit()
    }

    private fun handleChange(change: DocumentChange, selfUid: String) {
        if (change.type == DocumentChange.Type.REMOVED) {
            knownMatches.remove(change.document.id)
            return
        }

        val match = Match.from(change.document) ?: return
        val previous = knownMatches[match.id]
        knownMatches[match.id] = match

        val otherUid = match.otherUserId(selfUid) ?: return

        if (change.type == DocumentChange.Type.ADDED) {
            handleAdded(match, otherUid)
        } else {
            handleModified(match, previous, otherUid)
        }
    }

    /**
     * A match document is only ever created with a single liker (the rules
     * require `likedUsers == [creator]`), so a brand-new doc is always a
     * one-sided like. If that liker is this user, it's their own action.
     */
    private fun handleAdded(match: Match, otherUid: String) {
        if (match.likedUsers.singleOrNull() == otherUid) {
            AppNotifications.notifyNewLike(appContext, match.id)
        }
    }

    private fun handleModified(match: Match, previous: Match?, otherUid: String) {
        val becameMutual = match.isMutual() && previous?.isMutual() != true
        if (becameMutual) {
            // Armed regardless of who completed the match — liking someone
            // back is as good a moment to ask as being liked back. Only
            // armed here; whether it actually surfaces is decided by the
            // shared budget in RatePromptPrefs when HomeActivity consumes it.
            RatePromptPrefs.armMatchTrigger(appContext)

            // Whoever's UID is newly present is who just completed the match.
            val completedBy = match.likedUsers.firstOrNull { it !in previous?.likedUsers.orEmpty() }
            if (completedBy == otherUid) {
                resolveDisplayName(otherUid) { name ->
                    AppNotifications.notifyNewMatch(appContext, match.id, otherUid, name ?: otherUid)
                }
            }
        }

        val newMessage = match.lastMessage
        val previousMessage = previous?.lastMessage
        val isGenuinelyNewMessage = newMessage != null &&
            newMessage.senderId == otherUid &&
            newMessage.sentAt != previousMessage?.sentAt
        if (isGenuinelyNewMessage) {
            val threadOpen = ActiveChatTracker.openThreadUid == otherUid
            Log.d(
                TAG,
                "New message detected on match ${match.id} from $otherUid " +
                    "(threadCurrentlyOpen=$threadOpen)"
            )
            if (!threadOpen) {
                val text = newMessage?.text.orEmpty()
                resolveDisplayName(otherUid) { name ->
                    AppNotifications.notifyNewMessage(appContext, match.id, otherUid, name ?: otherUid, text)
                }
            }
        }
    }

    private fun resolveDisplayName(uid: String, onResult: (String?) -> Unit) {
        firestore.collection(UserProfile.COLLECTION).document(uid).get()
            .addOnSuccessListener { onResult(UserProfile.from(it).displayName?.takeIf { n -> n.isNotBlank() }) }
            .addOnFailureListener { onResult(null) }
    }
}
