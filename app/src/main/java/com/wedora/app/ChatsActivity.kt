package com.wedora.app

import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.gms.tasks.Tasks
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldPath
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.QuerySnapshot
import com.wedora.app.databinding.ActivityChatsBinding

/**
 * The conversation list: one row per match, showing the last message or a
 * prompt to start one.
 */
class ChatsActivity : AppCompatActivity() {

    private companion object {
        const val TAG = "WedoraChat"

        /** Firestore caps `whereIn` values, so profile lookups go out in chunks. */
        const val WHERE_IN_CHUNK = 10
    }

    private lateinit var binding: ActivityChatsBinding
    private val firestore: FirebaseFirestore by lazy { FirebaseFirestore.getInstance() }

    private val adapter = ChatListAdapter { chat ->
        startActivity(ChatThreadActivity.intent(this, chat.otherUserId, chat.name))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityChatsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.rvChats.layoutManager = LinearLayoutManager(this)
        binding.rvChats.adapter = adapter

        setUpWedoraBottomNav(binding.bottomNav, R.id.nav_chats)

        binding.btnBack.setOnClickListener { onBackPressedDispatcher.onBackPressed() }
        binding.btnSearch.setOnClickListener { toast(getString(R.string.cd_search)) }
        binding.btnMore.setOnClickListener { toast(getString(R.string.cd_more)) }
    }

    override fun onStart() {
        super.onStart()
        // Reloaded on every entry so a message sent in a thread is reflected
        // when the user comes back here.
        loadConversations()
    }

    private fun loadConversations() {
        if (GuestPrefs.isGuest(this)) {
            showEmpty(getString(R.string.chats_empty_guest))
            return
        }

        val selfUid = FirebaseAuth.getInstance().currentUser?.uid
        if (selfUid == null) {
            showEmpty(getString(R.string.chats_load_error))
            return
        }

        showLoading()
        firestore.collection(Match.COLLECTION)
            .whereArrayContains(Match.FIELD_USERS, selfUid)
            .get()
            .addOnSuccessListener { snapshot ->
                val matches = snapshot.documents.mapNotNull { Match.from(it) }
                if (matches.isEmpty()) {
                    showEmpty(getString(R.string.chats_empty))
                } else {
                    loadNamesAndLastMessages(matches, selfUid)
                }
            }
            .addOnFailureListener { e ->
                Log.w(TAG, "Failed to load matches", e)
                showEmpty(getString(R.string.chats_load_error))
            }
    }

    /**
     * Resolves each match into a row. Names come from a chunked whereIn over
     * the user documents; the last message is one small query per match.
     *
     * That per-match query is the obvious scaling cost here. The alternative is
     * denormalising the last message onto the match document on every send,
     * which removes the N queries but introduces data that can drift out of
     * sync with the messages subcollection. At this size the extra reads are
     * the cheaper trade.
     */
    private fun loadNamesAndLastMessages(matches: List<Match>, selfUid: String) {
        val otherUids = matches.mapNotNull { it.otherUserId(selfUid) }.distinct()
        if (otherUids.isEmpty()) {
            showEmpty(getString(R.string.chats_empty))
            return
        }

        val nameTasks = otherUids.chunked(WHERE_IN_CHUNK).map { chunk ->
            firestore.collection(UserProfile.COLLECTION)
                .whereIn(FieldPath.documentId(), chunk)
                .get()
        }

        Tasks.whenAllSuccess<QuerySnapshot>(nameTasks)
            .addOnSuccessListener { nameSnapshots ->
                val namesByUid = nameSnapshots
                    .flatMap { it.documents }
                    .mapNotNull { doc ->
                        val name = UserProfile.from(doc).displayName?.takeIf { it.isNotBlank() }
                        name?.let { doc.id to it }
                    }
                    .toMap()

                // Matches whose user document is missing or unnamed are dropped
                // rather than rendered as a blank row.
                val usable = matches.filter { namesByUid.containsKey(it.otherUserId(selfUid)) }
                if (usable.isEmpty()) {
                    showEmpty(getString(R.string.chats_empty))
                    return@addOnSuccessListener
                }

                val lastMessageTasks = usable.map { match ->
                    firestore.collection(Match.COLLECTION)
                        .document(match.id)
                        .collection(Match.SUBCOLLECTION_MESSAGES)
                        .orderBy(Message.FIELD_SENT_AT, Query.Direction.DESCENDING)
                        .limit(1)
                        .get()
                }

                Tasks.whenAllSuccess<QuerySnapshot>(lastMessageTasks)
                    .addOnSuccessListener { messageSnapshots ->
                        // whenAllSuccess preserves input order, so index i here
                        // is the last message for usable[i].
                        val previews = usable.mapIndexed { index, match ->
                            val last = messageSnapshots.getOrNull(index)
                                ?.documents?.firstOrNull()
                                ?.let { Message.from(it) }
                            val otherUid = match.otherUserId(selfUid).orEmpty()

                            ChatPreview(
                                matchId = match.id,
                                otherUserId = otherUid,
                                name = namesByUid[otherUid].orEmpty(),
                                lastMessage = last?.text,
                                lastMessageAt = last?.sentAt ?: match.createdAt
                            )
                        }.sortedByDescending {
                            it.lastMessageAt?.toDate()?.time ?: Long.MIN_VALUE
                        }

                        showConversations(previews)
                    }
                    .addOnFailureListener { e ->
                        Log.w(TAG, "Failed to load last messages", e)
                        showEmpty(getString(R.string.chats_load_error))
                    }
            }
            .addOnFailureListener { e ->
                Log.w(TAG, "Failed to load matched profiles", e)
                showEmpty(getString(R.string.chats_load_error))
            }
    }

    private fun showLoading() {
        binding.progressLoading.visibility = View.VISIBLE
        binding.tvChatsEmpty.visibility = View.GONE
        binding.rvChats.visibility = View.GONE
    }

    private fun showEmpty(message: String) {
        binding.progressLoading.visibility = View.GONE
        binding.rvChats.visibility = View.GONE
        binding.tvChatsEmpty.visibility = View.VISIBLE
        binding.tvChatsEmpty.text = message
    }

    private fun showConversations(previews: List<ChatPreview>) {
        binding.progressLoading.visibility = View.GONE
        binding.tvChatsEmpty.visibility = View.GONE
        binding.rvChats.visibility = View.VISIBLE
        adapter.submitList(previews)
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }
}
