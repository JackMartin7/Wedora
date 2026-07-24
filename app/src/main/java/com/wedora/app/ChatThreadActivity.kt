package com.wedora.app

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.activity.addCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.Query
import com.wedora.app.databinding.ActivityChatThreadBinding
import java.util.Date

/**
 * A conversation with one matched user, backed by a real-time listener on
 * `matches/{matchId}/messages`.
 *
 * Takes the other user's UID and name rather than a match ID — the match ID is
 * derived via [Match.idFor], so callers can't pass one that disagrees with the
 * participants.
 */
class ChatThreadActivity : AppCompatActivity(), DailyLimitReachedBottomSheet.Host {

    companion object {
        private const val TAG = "WedoraChat"
        private const val EXTRA_OTHER_USER_ID = "extra_other_user_id"
        private const val EXTRA_OTHER_USER_NAME = "extra_other_user_name"

        fun intent(context: Context, otherUserId: String, otherUserName: String): Intent =
            Intent(context, ChatThreadActivity::class.java)
                .putExtra(EXTRA_OTHER_USER_ID, otherUserId)
                .putExtra(EXTRA_OTHER_USER_NAME, otherUserName)
    }

    private lateinit var binding: ActivityChatThreadBinding
    private val firestore: FirebaseFirestore by lazy { FirebaseFirestore.getInstance() }

    private lateinit var selfUid: String
    private lateinit var otherUid: String
    private lateinit var matchId: String

    private var messagesListener: ListenerRegistration? = null

    /** Live view of the other user's presence, so the status updates in place. */
    private var statusListener: ListenerRegistration? = null

    private lateinit var adapter: MessageAdapter

    /**
     * Newest incoming message already marked read, so a re-delivered snapshot
     * (metadata changes fire the listener too) doesn't rewrite the same zero.
     */
    private var lastReadMessageId: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityChatThreadBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val otherUserId = intent.getStringExtra(EXTRA_OTHER_USER_ID)
        val uid = FirebaseAuth.getInstance().realUid
        if (otherUserId.isNullOrBlank() || uid == null) {
            Log.w(TAG, "Missing other user id or no signed-in user")
            Toast.makeText(this, R.string.error_chat_load_failed, Toast.LENGTH_LONG).show()
            finish()
            return
        }
        selfUid = uid
        otherUid = otherUserId
        matchId = Match.idFor(selfUid, otherUid)

        binding.tvChatTitle.text = intent.getStringExtra(EXTRA_OTHER_USER_NAME).orEmpty()
        // The whole header (avatar + name + status) opens the other person's
        // profile. Uses the intent factory so the extra key can't drift.
        binding.chatHeader.setOnClickListener {
            startActivity(ProfileDetailActivity.intent(this, otherUid))
        }
        binding.btnBack.setOnClickListener { goToChats() }
        // Route the system/gesture back to the same place, so it doesn't return
        // to whatever happened to launch the thread (a Home card, a profile).
        onBackPressedDispatcher.addCallback(this) { goToChats() }
        binding.btnSend.setOnClickListener { sendMessage() }

        adapter = MessageAdapter(selfUid)
        binding.rvMessages.layoutManager = LinearLayoutManager(this)
        binding.rvMessages.adapter = adapter

        observeMessages()
        observeOtherUserStatus()
        applyMessagingGate()
    }

    /**
     * Live listener on the other user's document, for their presence line. A
     * listener rather than a one-shot read so "Online" / "last seen" updates
     * while the thread is open; its first callback is also the initial load, so
     * there's no separate get(). Fails open — a read error just leaves the
     * status hidden.
     */
    private fun observeOtherUserStatus() {
        statusListener = firestore.collection(UserProfile.COLLECTION).document(otherUid)
            .addSnapshotListener { snapshot, error ->
                if (error != null || snapshot == null) {
                    if (error != null) Log.w(TAG, "Presence listener failed", error)
                    return@addSnapshotListener
                }
                renderStatus(UserProfile.from(snapshot).lastSeen)
            }
    }

    private fun renderStatus(lastSeen: Date?) {
        val label = OnlineStatus.format(lastSeen)
        if (label == null) {
            binding.statusRow.visibility = View.GONE
            return
        }

        binding.statusRow.visibility = View.VISIBLE
        binding.tvChatStatus.text = label

        val online = OnlineStatus.isOnline(lastSeen)
        binding.vOnlineDot.visibility = if (online) View.VISIBLE else View.GONE
        binding.tvChatStatus.setTextColor(
            ContextCompat.getColor(
                this,
                if (online) R.color.wedora_online else R.color.wedora_text_secondary
            )
        )
    }

    /**
     * Reflects the other person's "only matched users can message me" setting
     * in the composer, so someone who can't send finds out before typing
     * rather than from a failed write.
     *
     * This is presentation only — firestore.rules is what actually enforces
     * the setting. That's why every read here fails *open*: a network blip
     * shouldn't lock someone out of a conversation they're entitled to, and if
     * they aren't, the rule rejects the message anyway.
     *
     * Evaluated on open. A like-back that arrives while the thread is already
     * on screen won't unlock the composer until it's reopened — the match
     * document isn't listened to here, only the messages under it.
     */
    private fun applyMessagingGate() {
        firestore.collection(UserProfile.COLLECTION).document(otherUid).get()
            .addOnSuccessListener { userDoc ->
                if (!UserProfile.from(userDoc).onlyMatchesCanMessage) {
                    setComposerLocked(false)
                    return@addOnSuccessListener
                }
                // A one-sided like still creates the match document, so the
                // gate turns on "is it mutual", not "does a match exist".
                matchExistsQuery(firestore, selfUid, otherUid)
                    .addOnSuccessListener { snapshot ->
                        val mutual = snapshot.documents.firstOrNull()
                            ?.let { Match.from(it)?.isMutual() } == true
                        setComposerLocked(!mutual)
                    }
                    .addOnFailureListener { e ->
                        Log.w(TAG, "Couldn't check match state for messaging gate", e)
                        setComposerLocked(false)
                    }
            }
            .addOnFailureListener { e ->
                Log.w(TAG, "Couldn't read recipient messaging setting", e)
                setComposerLocked(false)
            }
    }

    private fun setComposerLocked(locked: Boolean) {
        binding.etMessage.isEnabled = !locked
        binding.btnSend.isEnabled = !locked
        binding.etMessage.setHint(
            if (locked) R.string.chat_locked_until_match else R.string.chat_hint_message
        )
    }

    private fun matchDocument() =
        firestore.collection(Match.COLLECTION).document(matchId)

    private fun messagesCollection() =
        matchDocument().collection(Match.SUBCOLLECTION_MESSAGES)

    private fun observeMessages() {
        messagesListener = messagesCollection()
            .orderBy(Message.FIELD_SENT_AT, Query.Direction.ASCENDING)
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    Log.w(TAG, "Message listener failed", error)
                    Toast.makeText(this, R.string.error_chat_load_failed, Toast.LENGTH_LONG).show()
                    return@addSnapshotListener
                }

                val messages = snapshot?.documents
                    ?.mapNotNull { Message.from(it) }
                    // Re-sorted locally because a message awaiting its server
                    // timestamp is ordered by the query as though it had none,
                    // which would briefly place a just-sent message at the top
                    // of the thread. Message.from reads sentAt with ESTIMATE,
                    // so sorting here puts it where the user expects.
                    ?.sortedBy { it.sentAt?.toDate()?.time ?: Long.MAX_VALUE }
                    .orEmpty()

                showMessages(messages)
            }
    }

    private fun showMessages(messages: List<Message>) {
        markReadIfIncoming(messages)

        binding.tvChatEmpty.visibility = if (messages.isEmpty()) View.VISIBLE else View.GONE
        adapter.submitList(messages) {
            // Runs after the list has been diffed and laid out, so the scroll
            // target actually exists.
            if (messages.isNotEmpty()) {
                binding.rvMessages.scrollToPosition(messages.size - 1)
            }
        }
    }

    /**
     * unreadCount increments rather than being set: the recipient's unread
     * count is whatever it was plus this message. It's safe to increment
     * from wherever it stands because opening a thread zeroes it, so by the
     * time this user is sending, their own side has already been cleared.
     *
     * Drops otherUid out of hiddenBy in the same write: if they'd "deleted"
     * this chat, a new message from this side brings it back — the standard
     * WhatsApp-style behavior, not a block. All of that write shape now lives
     * in sendMessageRespectingDailyLimit, which also folds in the free-tier
     * message count so a send and its count update are one atomic batch.
     */
    private fun sendMessage() {
        val text = binding.etMessage.text.toString().trim()
        if (text.isEmpty()) return

        // Cleared immediately: the snapshot listener echoes the message back
        // from the local cache before the server round-trip, so the thread
        // updates without waiting. Restored if the send doesn't happen —
        // daily limit, or the write itself failing — so nothing typed is lost.
        binding.etMessage.text.clear()

        sendMessageRespectingDailyLimit(firestore, selfUid, otherUid, matchId, text) { attempt ->
            when (attempt) {
                is MessageSendAttempt.DailyLimitReached -> {
                    binding.etMessage.setText(text)
                    binding.etMessage.setSelection(text.length)
                    DailyLimitReachedBottomSheet.show(
                        supportFragmentManager, DailyLimitReachedBottomSheet.Kind.MESSAGES
                    )
                }
                is MessageSendAttempt.Started -> attempt.task.addOnFailureListener { e ->
                    Log.w(TAG, "Failed to send message", e)
                    binding.etMessage.setText(text)
                    binding.etMessage.setSelection(text.length)
                    Toast.makeText(this, R.string.error_message_send_failed, Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    override fun onUpgradeFromDailyLimitRequested() {
        startActivity(Intent(this, PaymentSubscriptionActivity::class.java))
    }

    /**
     * Clears this user's unread count once they've actually seen the messages.
     *
     * Only when the newest message came from the *other* person. The counter is
     * shared and always refers to whoever didn't send last, so zeroing it after
     * sending would wipe the other side's badge instead of this user's.
     *
     * Driven off the message list rather than a separate read of the match doc:
     * the listener already knows who sent last, and it fires both on open and
     * when a message arrives while the thread is in view — so a chat read live
     * stays read.
     */
    private fun markReadIfIncoming(messages: List<Message>) {
        val newest = messages.lastOrNull() ?: return
        if (newest.senderId == selfUid) return
        if (newest.id == lastReadMessageId) return

        lastReadMessageId = newest.id
        matchDocument().update(Match.PATH_LM_UNREAD_COUNT, 0L)
            .addOnFailureListener { e -> Log.w(TAG, "Failed to clear unread count", e) }
    }

    /**
     * Back always returns to the Chats list — the conversation's natural parent
     * — rather than to whatever launched the thread. finish() drops this thread
     * so it isn't left underneath.
     *
     * CLEAR_TOP alone does NOT reuse an existing Chats instance — with the
     * default "standard" launch mode, CLEAR_TOP still finishes and recreates
     * the target. SINGLE_TOP (paired here with ChatsActivity's own
     * launchMode="singleTop") is what actually reuses it via onNewIntent, which
     * matters beyond avoiding a wasted rebuild: without it, the already-loaded
     * list is torn down and Chats reopens empty while its Firestore listener
     * reattaches, and that blank frame is what showed through as a flash during
     * the slide transition, most visibly in dark mode.
     */
    private fun goToChats() {
        startActivity(
            Intent(this, ChatsActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        )
        finish()
        // Starting the parent is an "open" as far as Android is concerned, so
        // without this the list slides in from the right like a push.
        applyBackTransition()
    }

    /**
     * Marks this conversation as the one on screen, so
     * [MatchNotificationWatcher] skips it — no point notifying about a message
     * the user is already reading. Set in onResume/cleared in onPause rather
     * than onCreate/onDestroy, so backgrounding the app (home button, screen
     * off) while the thread is still on top correctly re-enables notifications
     * for it, and returning to the foreground suppresses them again.
     */
    override fun onResume() {
        super.onResume()
        ActiveChatTracker.openThreadUid = otherUid
    }

    override fun onPause() {
        if (ActiveChatTracker.openThreadUid == otherUid) {
            ActiveChatTracker.openThreadUid = null
        }
        super.onPause()
    }

    override fun onDestroy() {
        messagesListener?.remove()
        statusListener?.remove()
        super.onDestroy()
    }
}
