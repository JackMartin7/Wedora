package com.wedora.app

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.activity.addCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.Query
import com.wedora.app.databinding.ActivityChatThreadBinding

/**
 * A conversation with one matched user, backed by a real-time listener on
 * `matches/{matchId}/messages`.
 *
 * Takes the other user's UID and name rather than a match ID — the match ID is
 * derived via [Match.idFor], so callers can't pass one that disagrees with the
 * participants.
 */
class ChatThreadActivity : AppCompatActivity() {

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
    private lateinit var matchId: String

    private var messagesListener: ListenerRegistration? = null
    private lateinit var adapter: MessageAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityChatThreadBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val otherUserId = intent.getStringExtra(EXTRA_OTHER_USER_ID)
        val uid = FirebaseAuth.getInstance().currentUser?.uid
        if (otherUserId.isNullOrBlank() || uid == null) {
            Log.w(TAG, "Missing other user id or no signed-in user")
            Toast.makeText(this, R.string.error_chat_load_failed, Toast.LENGTH_LONG).show()
            finish()
            return
        }
        selfUid = uid
        matchId = Match.idFor(selfUid, otherUserId)

        binding.tvChatTitle.text = intent.getStringExtra(EXTRA_OTHER_USER_NAME).orEmpty()
        binding.btnBack.setOnClickListener { goToChats() }
        // Route the system/gesture back to the same place, so it doesn't return
        // to whatever happened to launch the thread (a Home card, a profile).
        onBackPressedDispatcher.addCallback(this) { goToChats() }
        binding.btnSend.setOnClickListener { sendMessage() }

        adapter = MessageAdapter(selfUid)
        binding.rvMessages.layoutManager = LinearLayoutManager(this)
        binding.rvMessages.adapter = adapter

        observeMessages()
    }

    private fun messagesCollection() =
        firestore.collection(Match.COLLECTION)
            .document(matchId)
            .collection(Match.SUBCOLLECTION_MESSAGES)

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
        binding.tvChatEmpty.visibility = if (messages.isEmpty()) View.VISIBLE else View.GONE
        adapter.submitList(messages) {
            // Runs after the list has been diffed and laid out, so the scroll
            // target actually exists.
            if (messages.isNotEmpty()) {
                binding.rvMessages.scrollToPosition(messages.size - 1)
            }
        }
    }

    private fun sendMessage() {
        val text = binding.etMessage.text.toString().trim()
        if (text.isEmpty()) return

        // Cleared immediately: the snapshot listener echoes the message back
        // from the local cache before the server round-trip, so the thread
        // updates without waiting.
        binding.etMessage.text.clear()

        val message = mapOf(
            Message.FIELD_SENDER_ID to selfUid,
            Message.FIELD_TEXT to text,
            Message.FIELD_SENT_AT to FieldValue.serverTimestamp()
        )

        messagesCollection().add(message)
            .addOnFailureListener { e ->
                Log.w(TAG, "Failed to send message", e)
                // Put the text back so it isn't silently lost.
                binding.etMessage.setText(text)
                binding.etMessage.setSelection(text.length)
                Toast.makeText(this, R.string.error_message_send_failed, Toast.LENGTH_LONG).show()
            }
    }

    /**
     * Back always returns to the Chats list — the conversation's natural parent
     * — rather than to whatever launched the thread. CLEAR_TOP reuses an
     * existing Chats instance instead of stacking a second; finish() drops this
     * thread so it isn't left underneath.
     */
    private fun goToChats() {
        startActivity(
            Intent(this, ChatsActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
        )
        finish()
    }

    override fun onDestroy() {
        messagesListener?.remove()
        super.onDestroy()
    }
}
