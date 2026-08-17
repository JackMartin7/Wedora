package com.wedora.app

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.activity.addCallback
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.firebase.Timestamp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.MetadataChanges
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
 *
 * A guest opening one of the two demo conversations from ChatsActivity gets
 * an entirely different, Firestore-free path instead — see
 * [setUpDemoThread] and [demoIntent]. The two never mix: onCreate branches
 * on EXTRA_DEMO before touching anything the real path needs (selfUid,
 * matchId, Firestore listeners), so nothing below that branch has to account
 * for a demo thread at all.
 */
class ChatThreadActivity :
    WedoraBaseActivity(),
    DailyLimitReachedBottomSheet.Host,
    GuestChatBlockedBottomSheet.Host,
    MessageActionsBottomSheet.Host,
    RateAppBottomSheet.Host {

    companion object {
        private const val TAG = "WedoraChat"
        private const val EXTRA_OTHER_USER_ID = "extra_other_user_id"
        private const val EXTRA_OTHER_USER_NAME = "extra_other_user_name"
        private const val EXTRA_DEMO = "extra_demo"

        /**
         * MessageAdapter renders a message as sent-by-me when its senderId
         * equals this. Every demo message's senderId is the demo otherUid
         * instead (see demoMessagesFor), so nothing in a demo thread can ever
         * match this and render as a sent bubble — "received bubbles only",
         * as specified.
         */
        private const val DEMO_SELF_UID = "demo-self"

        /**
         * How many messages the live listener holds, and how many a "load
         * older" page fetches. Loading the whole thread on every open scaled
         * with conversation length forever — this caps the steady-state cost
         * to "however far back the user actually scrolls."
         */
        private const val MESSAGES_PAGE_SIZE = 30L

        /**
         * Trigger the next older page a little before the user hits the
         * literal top, so it's already loading by the time they get there.
         */
        private const val LOAD_OLDER_THRESHOLD = 5

        fun intent(context: Context, otherUserId: String, otherUserName: String): Intent =
            Intent(context, ChatThreadActivity::class.java)
                .putExtra(EXTRA_OTHER_USER_ID, otherUserId)
                .putExtra(EXTRA_OTHER_USER_NAME, otherUserName)

        /**
         * A guest's preview thread — no match, no messages collection, no
         * other real user behind [demoName] at all. See setUpDemoThread.
         */
        fun demoIntent(context: Context, demoName: String): Intent =
            Intent(context, ChatThreadActivity::class.java)
                .putExtra(EXTRA_OTHER_USER_NAME, demoName)
                .putExtra(EXTRA_DEMO, true)
    }

    private lateinit var binding: ActivityChatThreadBinding
    private val firestore: FirebaseFirestore by lazy { FirebaseFirestore.getInstance() }

    private lateinit var selfUid: String
    private lateinit var otherUid: String
    private lateinit var otherUserName: String
    private lateinit var matchId: String

    /**
     * Set by [onReplyRequested] (MessageActionsBottomSheet's Reply option),
     * cleared once [sendMessage] uses it or [clearStagedReply] cancels it.
     * Null means the composer is a plain send with no reply attached.
     */
    private var stagedReply: Message? = null

    private var messagesListener: ListenerRegistration? = null

    /**
     * Every message loaded this session — the live window plus however many
     * older pages have been fetched — keyed by id so the live snapshot
     * (which only ever contains the newest [MESSAGES_PAGE_SIZE]) and one-shot
     * older-page reads can both write into the same set without duplicating
     * anything. Nothing is ever evicted — a message that ages out of the live
     * window stays here from whenever it was first loaded.
     */
    private val loadedMessages = mutableMapOf<String, Message>()

    /**
     * Pagination cursor: the oldest message loaded so far. Starts out
     * following the live listener's own oldest doc (there's nothing older
     * loaded yet); once [loadOlderMessages] fetches a page, its own cursor
     * takes over — see [hasLoadedOlderPage].
     */
    private var oldestLoadedSnapshot: DocumentSnapshot? = null

    /**
     * False until the first manual "load older" page lands. While false, the
     * live listener keeps [oldestLoadedSnapshot] pointed at its own oldest
     * doc (which shifts forward as new messages push the window along) —
     * once true, only [loadOlderMessages] moves the cursor, so a live update
     * arriving mid-scroll-back can't yank it forward past history already
     * fetched.
     */
    private var hasLoadedOlderPage = false

    private var hasMoreOlderMessages = true
    private var isLoadingOlderMessages = false

    /** Live view of the other user's presence, so the status updates in place. */
    private var statusListener: ListenerRegistration? = null

    /**
     * Live view of the match doc's lastReadAt map, so the other person's
     * checkmarks flip from grey to blue the moment they open this thread —
     * no reopen needed. See observeReadReceipts.
     */
    private var readReceiptListener: ListenerRegistration? = null

    /**
     * Whichever message the match doc's lastMessage currently reflects, kept
     * in step by the same live listener readReceiptListener already runs —
     * no extra read. deleteMessageForEveryone needs this to decide whether
     * the message being deleted IS the one shown in the Chats list preview;
     * see that function's own doc comment for the narrow, accepted race this
     * implies.
     */
    private var lastMessageId: String? = null

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
        // applyIme: pushes etMessage/btnSend above the keyboard, same fix as
        // Login/SignUp's password field — rvMessages sits in a plain
        // ConstraintLayout rather than a ScrollView though, so it has no
        // scrolling ancestor for the base class's own focus-follow logic to
        // reach; onInsetsApplied re-runs this screen's own scroll-to-bottom
        // instead, so the latest message stays above the composer rather
        // than sliding out of view behind it as the keyboard opens/closes.
        applyEdgeInsets(binding.root, applyIme = true) {
            val itemCount = binding.rvMessages.adapter?.itemCount ?: 0
            if (itemCount > 0) {
                binding.rvMessages.scrollToPosition(itemCount - 1)
            }
        }

        if (intent.getBooleanExtra(EXTRA_DEMO, false)) {
            setUpDemoThread()
            return
        }

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
        otherUserName = intent.getStringExtra(EXTRA_OTHER_USER_NAME).orEmpty()

        binding.tvChatTitle.text = otherUserName
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
        binding.btnCancelReply.setOnClickListener { clearStagedReply() }

        adapter = MessageAdapter(
            selfUid,
            otherUserName,
            onLongPress = { message -> showMessageActions(message) },
            onReplyPreviewTapped = { messageId -> jumpToMessage(messageId) }
        )
        binding.rvMessages.layoutManager = LinearLayoutManager(this)
        binding.rvMessages.adapter = adapter
        binding.rvMessages.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                val lm = recyclerView.layoutManager as? LinearLayoutManager ?: return
                if (lm.findFirstVisibleItemPosition() in 0..LOAD_OLDER_THRESHOLD) {
                    loadOlderMessages()
                }
            }
        })

        observeMessages()
        observeOtherUserStatus()
        observeReadReceipts()
        markThreadOpened()
        applyMessagingGate()
    }

    /**
     * A guest's preview thread — everything here is either hardcoded copy or
     * client-only state; nothing touches Firestore, and there is no real
     * match, real other user, or real message ever written anywhere.
     *
     * otherUid is still assigned (to a fake id that can never match a real
     * one) rather than left uninitialized, purely so onResume/onPause/
     * onDestroy below — none of which know or need to know this is a demo
     * thread — don't crash reading a lateinit property nothing set.
     */
    private fun setUpDemoThread() {
        val demoName = intent.getStringExtra(EXTRA_OTHER_USER_NAME).orEmpty()
        otherUid = "demo-$demoName"

        binding.tvChatTitle.text = demoName
        // No real presence behind a demo conversation to show a status for.
        binding.statusRow.visibility = View.GONE
        // No real profile behind it either, so the header doesn't open one.
        binding.chatHeader.isClickable = false
        binding.chatHeader.isFocusable = false

        binding.btnBack.setOnClickListener { goToChats() }
        onBackPressedDispatcher.addCallback(this) { goToChats() }

        val demoAdapter = MessageAdapter(DEMO_SELF_UID, demoName)
        binding.rvMessages.layoutManager = LinearLayoutManager(this)
        binding.rvMessages.adapter = demoAdapter
        val demoMessages = demoMessagesFor(demoName)
        binding.tvChatEmpty.visibility = View.GONE
        demoAdapter.submitList(demoMessages) {
            binding.rvMessages.scrollToPosition(demoMessages.size - 1)
        }

        // The composer works normally — a guest can type freely — but Send
        // always shows the block sheet instead of writing anywhere. Text
        // isn't cleared on that tap: leaving it in place is the less
        // surprising of the two acceptable outcomes once "Keep Exploring"
        // dismisses the sheet and leaves the guest right back in this thread.
        binding.etMessage.isEnabled = true
        binding.btnSend.isEnabled = true
        binding.btnSend.setOnClickListener {
            GuestChatBlockedBottomSheet.show(supportFragmentManager)
        }
    }

    override fun onSignUpFromChatBlockedRequested() {
        startActivity(Intent(this, SignUpActivity::class.java))
    }

    /** Two received-only messages, the first matching the ChatsActivity preview line for continuity. */
    private fun demoMessagesFor(demoName: String): List<Message> {
        val (firstRes, secondRes) = if (demoName == getString(R.string.guest_demo_chat_sarah_name)) {
            R.string.guest_demo_chat_sarah_message_1 to R.string.guest_demo_chat_sarah_message_2
        } else {
            R.string.guest_demo_chat_ahmed_message_1 to R.string.guest_demo_chat_ahmed_message_2
        }

        val now = System.currentTimeMillis()
        return listOf(
            Message(
                id = "demo-1",
                senderId = otherUid,
                text = getString(firstRes),
                sentAt = Timestamp(Date(now - 2 * 60 * 60 * 1000))
            ),
            Message(
                id = "demo-2",
                senderId = otherUid,
                text = getString(secondRes),
                sentAt = Timestamp(Date(now - 90 * 60 * 1000))
            )
        )
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
                val profile = UserProfile.from(snapshot)
                renderStatus(profile.lastSeen)
                binding.ivChatAvatar.loadRemoteProfilePhoto(profile.photoUrl)
            }
    }

    /**
     * Live listener on the match doc itself (not the messages subcollection)
     * for the other participant's lastReadAt — the piece that lets a sent
     * bubble's checkmark turn blue while this screen is already open,
     * without waiting for a new message or a reopen. Fails open, same as
     * observeOtherUserStatus: a read error just leaves checkmarks grey.
     */
    private fun observeReadReceipts() {
        readReceiptListener = matchDocument()
            .addSnapshotListener { snapshot, error ->
                if (error != null || snapshot == null) {
                    if (error != null) Log.w(TAG, "Read receipt listener failed", error)
                    return@addSnapshotListener
                }
                adapter.updateReadReceipt(snapshot.getTimestamp(Match.pathLastReadAt(otherUid)))
                lastMessageId = snapshot.getString(Match.PATH_LM_MESSAGE_ID)
            }
    }

    /**
     * Marks this conversation read for [selfUid] the moment the thread is
     * opened, independent of whatever markReadIfIncoming does per-message —
     * so a thread with no new incoming message still records that this user
     * looked at it just now.
     */
    private fun markThreadOpened() {
        matchDocument().update(Match.pathLastReadAt(selfUid), FieldValue.serverTimestamp())
            .addOnFailureListener { e -> Log.w(TAG, "Failed to mark thread opened", e) }
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
            // Newest page only — the whole-history load this replaces was the
            // one place actually driving unbounded read growth as a
            // conversation aged. Older messages come from loadOlderMessages()
            // instead, one page at a time, only when the user scrolls for it.
            .orderBy(Message.FIELD_SENT_AT, Query.Direction.DESCENDING)
            .limit(MESSAGES_PAGE_SIZE)
            // INCLUDE, not the default: a just-sent message's own snapshot
            // update — hasPendingWrites flipping false once Firestore
            // confirms it — is a metadata-only change. Without this, that
            // transition never re-fires the listener and MessageAdapter's
            // single checkmark would never advance to double.
            .addSnapshotListener(MetadataChanges.INCLUDE) { snapshot, error ->
                if (error != null) {
                    Log.w(TAG, "Message listener failed", error)
                    Toast.makeText(this, R.string.error_chat_load_failed, Toast.LENGTH_LONG).show()
                    return@addSnapshotListener
                }
                if (snapshot == null) return@addSnapshotListener

                snapshot.documents.forEach { doc ->
                    Message.from(doc)?.let { loadedMessages[it.id] = it }
                }
                // Only the live window moves the cursor until a manual "load
                // older" has happened — see hasLoadedOlderPage's own comment.
                if (!hasLoadedOlderPage) {
                    oldestLoadedSnapshot = snapshot.documents.lastOrNull()
                    hasMoreOlderMessages = snapshot.documents.size.toLong() >= MESSAGES_PAGE_SIZE
                }

                showMessages(sortedLoadedMessages())
            }
    }

    // Re-sorted locally, same reason as before: a message awaiting its server
    // timestamp is ordered by the query as though it had none, which would
    // briefly place a just-sent message at the top of the thread.
    // Message.from reads sentAt with ESTIMATE, so sorting here puts it where
    // the user expects.
    //
    // "Delete for me" messages are filtered out here rather than at the
    // point they're loaded into loadedMessages — that map stays the full,
    // canonical set of everything the live listener/pagination has ever
    // seen, so a later snapshot (e.g. this same message's deletedFor
    // changing) still finds it there to update rather than treating it as
    // new. Only the outward-facing display list drops it.
    private fun sortedLoadedMessages(): List<Message> =
        loadedMessages.values
            .filterNot { selfUid in it.deletedFor }
            .sortedBy { it.sentAt?.toDate()?.time ?: Long.MAX_VALUE }

    /**
     * Fetches the next page of older messages when the user scrolls near the
     * top. A one-shot get(), not a listener — history that's already loaded
     * doesn't need to stay live, only the recent window observeMessages()
     * covers does.
     */
    private fun loadOlderMessages() {
        val cursor = oldestLoadedSnapshot ?: return
        if (isLoadingOlderMessages || !hasMoreOlderMessages) return
        isLoadingOlderMessages = true
        binding.progressLoadOlder.visibility = View.VISIBLE

        messagesCollection()
            .orderBy(Message.FIELD_SENT_AT, Query.Direction.DESCENDING)
            .startAfter(cursor)
            .limit(MESSAGES_PAGE_SIZE)
            .get()
            .addOnSuccessListener { snapshot ->
                isLoadingOlderMessages = false
                binding.progressLoadOlder.visibility = View.GONE
                hasLoadedOlderPage = true
                hasMoreOlderMessages = snapshot.size().toLong() >= MESSAGES_PAGE_SIZE
                snapshot.documents.lastOrNull()?.let { oldestLoadedSnapshot = it }
                if (snapshot.isEmpty) return@addOnSuccessListener

                snapshot.documents.forEach { doc ->
                    Message.from(doc)?.let { loadedMessages[it.id] = it }
                }
                showMessages(sortedLoadedMessages(), keepScrollAnchor = true)
            }
            .addOnFailureListener { e ->
                isLoadingOlderMessages = false
                binding.progressLoadOlder.visibility = View.GONE
                Log.w(TAG, "Failed to load older messages", e)
            }
    }

    private fun showMessages(messages: List<Message>, keepScrollAnchor: Boolean = false) {
        markReadIfIncoming(messages)

        binding.tvChatEmpty.visibility = if (messages.isEmpty()) View.VISIBLE else View.GONE

        // Prepending older messages: keep whatever the user was looking at in
        // place instead of the normal scroll-to-bottom, or "load more" would
        // yank them back down to the newest message every time.
        if (keepScrollAnchor) {
            val lm = binding.rvMessages.layoutManager as LinearLayoutManager
            val anchorPosition = lm.findFirstVisibleItemPosition()
            val anchorId = adapter.currentList.getOrNull(anchorPosition)?.id
            val anchorOffset = lm.findViewByPosition(anchorPosition)?.top ?: 0

            adapter.submitList(messages) {
                val newPosition = anchorId?.let { id -> messages.indexOfFirst { it.id == id } } ?: -1
                if (newPosition >= 0) {
                    lm.scrollToPositionWithOffset(newPosition, anchorOffset)
                }
            }
            return
        }

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

        // Contact-sharing check runs before anything is cleared, staged or
        // written: a flagged message is blocked outright with no send-anyway
        // path, and the composer deliberately keeps what was typed so the
        // user can edit it down rather than losing it. firestore.rules
        // re-checks this server-side for a client that skips the UI.
        val flagged = ContactShareDetector.detect(text)
        if (flagged.isNotEmpty()) {
            ContactShareBlockedBottomSheet.show(supportFragmentManager)
            // Fire-and-forget: the message is blocked either way, and a
            // failed log is nothing the user could act on.
            logContactShareAttempt(
                firestore, selfUid, matchId, text, flagged.map { it.id }
            ).addOnFailureListener { e ->
                Log.w(TAG, "Failed to log blocked contact-share attempt", e)
            }
            return
        }

        // Captured before clearing, same reasoning as text below — restored
        // together if the send doesn't happen.
        val replyForSend = stagedReply
        val replyPreview = replyForSend?.let {
            Message.ReplyPreview(
                messageId = it.id,
                senderId = it.senderId,
                text = (if (it.deleted) getString(R.string.message_deleted_placeholder) else it.text)
                    .take(Message.REPLY_PREVIEW_MAX_CHARS)
            )
        }

        // Cleared immediately: the snapshot listener echoes the message back
        // from the local cache before the server round-trip, so the thread
        // updates without waiting. Restored if the send doesn't happen —
        // daily limit, or the write itself failing — so nothing typed is lost.
        binding.etMessage.text.clear()
        clearStagedReply()

        sendMessageRespectingDailyLimit(firestore, selfUid, otherUid, matchId, text, replyPreview) { attempt ->
            when (attempt) {
                is MessageSendAttempt.DailyLimitReached -> {
                    binding.etMessage.setText(text)
                    binding.etMessage.setSelection(text.length)
                    replyForSend?.let { stageReply(it) }
                    DailyLimitReachedBottomSheet.show(
                        supportFragmentManager, DailyLimitReachedBottomSheet.Kind.MESSAGES
                    )
                }
                is MessageSendAttempt.Started -> {
                    // The onMessageSent Cloud Function (functions/src/index.ts)
                    // fires straight off this write once it lands, so the only
                    // thing left for this Activity on success is the
                    // rate-prompt milestone — the snapshot listener echoes the
                    // message back on its own.
                    attempt.task
                        .addOnSuccessListener {
                            // Counted on confirmed success rather than on
                            // attempt, so a send that never landed doesn't
                            // move the user toward the milestone.
                            RatePromptPrefs.recordMessageSent(this)
                            maybeShowRatePrompt()
                        }
                        .addOnFailureListener { e ->
                            Log.w(TAG, "Failed to send message", e)
                            binding.etMessage.setText(text)
                            binding.etMessage.setSelection(text.length)
                            replyForSend?.let { stageReply(it) }
                            Toast.makeText(this, R.string.error_message_send_failed, Toast.LENGTH_LONG).show()
                        }
                }
            }
        }
    }

    override fun onUpgradeFromDailyLimitRequested() {
        startActivity(Intent(this, PaymentSubscriptionActivity::class.java))
    }

    override fun onWatchAdForBonusRequested(kind: DailyLimitReachedBottomSheet.Kind) {
        runRewardedBonusFlow(kind)
    }

    /**
     * Shows the rate prompt if this send was the one that earned it.
     *
     * Lives here rather than in an app-wide observer because a sheet needs a
     * FragmentManager: every object attached in WedoraApplication
     * ([PremiumStatus], [CrashReporting], MatchNotificationWatcher) manages
     * state and never puts anything on screen, precisely because it has no
     * Activity to put it on.
     *
     * Guests never reach this at all — setUpDemoThread wires Send to the
     * blocked sheet instead, so a demo thread can't advance the milestone.
     */
    private fun maybeShowRatePrompt() {
        // The send confirmation can land after the user has left the thread.
        if (isFinishing || isDestroyed) return
        if (!RatePromptPrefs.shouldPrompt(this, RatePromptPrefs.Trigger.THIRD_MESSAGE)) return

        RatePromptPrefs.recordPromptShown(this)
        RateAppBottomSheet.show(supportFragmentManager)
    }

    /**
     * Settled before the intent is fired, not after: nothing reports back
     * whether a rating was actually left, so "they went to the listing" is
     * the strongest signal available and re-asking past it would be nagging.
     * That holds even if the listing fails to open below — a user who opted
     * in has already answered the question this prompt asks.
     */
    override fun onRateAppRequested() {
        RatePromptPrefs.settle(this)
        openPlayStoreListing()
    }

    /**
     * Populates and reveals the composer's staged-reply strip — see
     * [stagedReply]'s own doc comment. [message.deleted] is defensive: the
     * actions sheet already refuses to open for a deleted message, so this
     * only matters if a delete lands in the narrow window between long-press
     * and tapping Reply.
     */
    private fun stageReply(message: Message) {
        stagedReply = message
        binding.tvReplyPreviewLabel.text = if (message.senderId == selfUid) {
            getString(R.string.replying_to_you)
        } else {
            getString(R.string.replying_to_format, otherUserName)
        }
        binding.tvReplyPreviewText.text =
            if (message.deleted) getString(R.string.message_deleted_placeholder) else message.text
        binding.replyPreviewContainer.visibility = View.VISIBLE
    }

    private fun clearStagedReply() {
        stagedReply = null
        binding.replyPreviewContainer.visibility = View.GONE
    }

    override fun onReplyRequested(messageId: String) {
        loadedMessages[messageId]?.let { stageReply(it) }
    }

    /**
     * Scrolls to and briefly highlights the original message a quoted-reply
     * preview points at. Only works if that message is currently in the
     * adapter's bound list — the paginated history beyond what's loaded
     * isn't searched, since that could mean an open-ended chain of
     * loadOlderMessages() calls for a reply to something from very far back.
     */
    private fun jumpToMessage(messageId: String) {
        val position = adapter.currentList.indexOfFirst { it.id == messageId }
        if (position < 0) {
            Toast.makeText(this, R.string.message_original_not_loaded, Toast.LENGTH_SHORT).show()
            return
        }
        binding.rvMessages.smoothScrollToPosition(position)
        adapter.flashHighlight(messageId)
    }

    private fun showMessageActions(message: Message) {
        MessageActionsBottomSheet.show(
            supportFragmentManager,
            message.id,
            isOwnMessage = message.senderId == selfUid,
            currentReaction = message.reactions[selfUid]
        )
    }

    /**
     * Tapping the emoji that's already this user's reaction removes it;
     * tapping any other one sets/replaces it — decided here, against
     * loadedMessages' current state, since the sheet itself only knows which
     * emoji was tapped, not what (if anything) the user already reacted
     * with by the time they tap it.
     */
    override fun onReactionPicked(messageId: String, emoji: String) {
        val current = loadedMessages[messageId]?.reactions?.get(selfUid)
        val task = if (current == emoji) {
            removeMessageReaction(firestore, matchId, messageId, selfUid)
        } else {
            setMessageReaction(firestore, matchId, messageId, selfUid, emoji)
        }
        task.addOnFailureListener { e ->
            Log.w(TAG, "Failed to update reaction", e)
            Toast.makeText(this, R.string.error_message_react_failed, Toast.LENGTH_LONG).show()
        }
    }

    override fun onDeleteForMeRequested(messageId: String) {
        deleteMessageForMe(firestore, matchId, messageId, selfUid)
            .addOnFailureListener { e ->
                Log.w(TAG, "Failed to delete message for me", e)
                Toast.makeText(this, R.string.error_message_delete_failed, Toast.LENGTH_LONG).show()
            }
    }

    override fun onDeleteForEveryoneRequested(messageId: String) {
        deleteMessageForEveryone(firestore, matchId, messageId, lastMessageId)
            .addOnFailureListener { e ->
                Log.w(TAG, "Failed to delete message for everyone", e)
                Toast.makeText(this, R.string.error_message_delete_failed, Toast.LENGTH_LONG).show()
            }
    }

    /**
     * Clears this user's unread count and bumps their read receipt once
     * they've actually seen the messages.
     *
     * Only when the newest message came from the *other* person. The counter is
     * shared and always refers to whoever didn't send last, so zeroing it after
     * sending would wipe the other side's badge instead of this user's — and
     * likewise there's nothing of theirs to mark read if they sent last.
     *
     * Driven off the message list rather than a separate read of the match doc:
     * the listener already knows who sent last, and it fires both on open and
     * when a message arrives while the thread is in view — so a chat read live
     * stays read, and the other person's checkmarks turn blue without them
     * needing to reopen this thread either.
     */
    private fun markReadIfIncoming(messages: List<Message>) {
        val newest = messages.lastOrNull() ?: return
        if (newest.senderId == selfUid) return
        if (newest.id == lastReadMessageId) return

        lastReadMessageId = newest.id
        matchDocument().update(
            Match.PATH_LM_UNREAD_COUNT, 0L,
            Match.pathLastReadAt(selfUid), FieldValue.serverTimestamp()
        ).addOnFailureListener { e -> Log.w(TAG, "Failed to clear unread count", e) }
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
    /**
     * Shows the between-screens interstitial first when one is due — see
     * [InterstitialAds] for the budget shared with the swipe and
     * profile-close sources.
     *
     * The ad must precede the navigation: an Activity that has already
     * finished can't host one. [InterstitialAds.show] guarantees its
     * callback fires exactly once even with no ad loaded, so this always
     * reaches [navigateToChats].
     */
    private fun goToChats() {
        if (InterstitialAds.onEvent(this, InterstitialAds.Trigger.CHAT_EXIT)) {
            InterstitialAds.show(this) { navigateToChats() }
        } else {
            navigateToChats()
        }
    }

    private fun navigateToChats() {
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
        readReceiptListener?.remove()
        super.onDestroy()
    }
}
