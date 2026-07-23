package com.wedora.app

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.gms.tasks.Tasks
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldPath
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.QuerySnapshot
import com.wedora.app.databinding.ActivityChatsBinding

/**
 * The conversation list: one row per match, showing the last message or a
 * prompt to start one, with an unread badge when the other person has written
 * something this user hasn't opened.
 */
class ChatsActivity : AppCompatActivity() {

    private companion object {
        const val TAG = "WedoraChat"

        /** Firestore caps `whereIn` values, so profile lookups go out in chunks. */
        const val WHERE_IN_CHUNK = 10

        /** Roughly a screenful, so the list looks populated while it loads. */
        const val SKELETON_ROWS = 4
    }

    private lateinit var binding: ActivityChatsBinding
    private val firestore: FirebaseFirestore by lazy { FirebaseFirestore.getInstance() }

    private var matchesListener: ListenerRegistration? = null

    /**
     * Display names, kept across snapshots. The match listener re-fires on
     * every new message; without this the list would re-query every
     * participant's profile each time a message arrived.
     */
    private val nameCache = mutableMapOf<String, String>()

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

        binding.btnBack.setOnClickListener { goToHome() }
        binding.btnSearch.setOnClickListener { toast(getString(R.string.cd_search)) }
        binding.btnMore.setOnClickListener { toast(getString(R.string.cd_more)) }
    }

    override fun onStart() {
        super.onStart()
        observeConversations()
    }

    override fun onStop() {
        matchesListener?.remove()
        matchesListener = null
        super.onStop()
    }

    /**
     * Live listener rather than a one-shot read, so a new message updates the
     * preview, ordering and unread badge without leaving and re-entering.
     *
     * Everything a row needs beyond the name now lives on the match document
     * itself (see [Match.LastMessage]), so this no longer runs a per-match
     * query for the newest message.
     */
    private fun observeConversations() {
        if (GuestPrefs.isGuest(this)) {
            showEmpty(
                R.drawable.ic_support_chat,
                R.string.empty_chats_guest_title,
                R.string.empty_chats_guest_subtitle
            )
            return
        }

        val selfUid = FirebaseAuth.getInstance().currentUser?.uid
        if (selfUid == null) {
            showEmpty(
                    R.drawable.ic_support_chat,
                    R.string.empty_chats_error_title,
                    R.string.empty_chats_error_subtitle
                )
            return
        }

        showLoading()
        matchesListener = firestore.collection(Match.COLLECTION)
            .whereArrayContains(Match.FIELD_USERS, selfUid)
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    Log.w(TAG, "Match listener failed", error)
                    showEmpty(
                    R.drawable.ic_support_chat,
                    R.string.empty_chats_error_title,
                    R.string.empty_chats_error_subtitle
                )
                    return@addSnapshotListener
                }

                val matches = snapshot?.documents?.mapNotNull { Match.from(it) }.orEmpty()
                if (matches.isEmpty()) {
                    showEmpty(
                        R.drawable.ic_support_chat,
                        R.string.empty_chats_title,
                        R.string.empty_chats_subtitle
                    )
                } else {
                    resolveNamesThenRender(matches, selfUid)
                }
            }
    }

    /** Fetches only the names not already cached, then renders. */
    private fun resolveNamesThenRender(matches: List<Match>, selfUid: String) {
        val missing = matches
            .mapNotNull { it.otherUserId(selfUid) }
            .distinct()
            .filterNot { nameCache.containsKey(it) }

        if (missing.isEmpty()) {
            render(matches, selfUid)
            return
        }

        val nameTasks = missing.chunked(WHERE_IN_CHUNK).map { chunk ->
            firestore.collection(UserProfile.COLLECTION)
                .whereIn(FieldPath.documentId(), chunk)
                .get()
        }

        Tasks.whenAllSuccess<QuerySnapshot>(nameTasks)
            .addOnSuccessListener { snapshots ->
                snapshots.flatMap { it.documents }.forEach { doc ->
                    UserProfile.from(doc).displayName
                        ?.takeIf { it.isNotBlank() }
                        ?.let { nameCache[doc.id] = it }
                }
                render(matches, selfUid)
            }
            .addOnFailureListener { e ->
                Log.w(TAG, "Failed to load matched profiles", e)
                showEmpty(
                    R.drawable.ic_support_chat,
                    R.string.empty_chats_error_title,
                    R.string.empty_chats_error_subtitle
                )
            }
    }

    private fun render(matches: List<Match>, selfUid: String) {
        val previews = matches.mapNotNull { match ->
            val otherUid = match.otherUserId(selfUid) ?: return@mapNotNull null
            // A match whose user document is missing or unnamed is dropped
            // rather than rendered as a blank row.
            val name = nameCache[otherUid] ?: return@mapNotNull null
            val lastMessage = match.lastMessage

            ChatPreview(
                matchId = match.id,
                otherUserId = otherUid,
                name = name,
                lastMessage = lastMessage?.text,
                // Falls back to the match date so a chat nobody has written in
                // still sorts sensibly among the rest.
                lastMessageAt = lastMessage?.sentAt ?: match.createdAt,
                isUnread = match.hasUnreadFor(selfUid),
                unreadCount = lastMessage?.unreadCount ?: 0
            )
        }.sortedByDescending { it.lastMessageAt?.toDate()?.time ?: Long.MIN_VALUE }

        if (previews.isEmpty()) {
            showEmpty(
                        R.drawable.ic_support_chat,
                        R.string.empty_chats_title,
                        R.string.empty_chats_subtitle
                    )
        } else {
            showConversations(previews)
        }
    }

    private fun showLoading() {
        binding.progressLoading.visibility = View.GONE
        binding.emptyState.hide()
        binding.rvChats.visibility = View.GONE
        binding.skeletonChats.showSkeleton(R.layout.item_skeleton_chat_row, SKELETON_ROWS)
    }

    private fun showEmpty(
        @DrawableRes icon: Int,
        @StringRes title: Int,
        @StringRes subtitle: Int
    ) {
        binding.skeletonChats.hideSkeleton()
        binding.progressLoading.visibility = View.GONE
        binding.rvChats.visibility = View.GONE
        binding.emptyState.show(icon, title, subtitle)
    }

    private fun showConversations(previews: List<ChatPreview>) {
        binding.progressLoading.visibility = View.GONE
        binding.emptyState.hide()
        adapter.submitList(previews)

        // The listener re-fires on every message, so only the first pass has a
        // skeleton to clear; afterwards this is a no-op on an already-hidden view.
        if (binding.skeletonChats.visibility == View.VISIBLE) {
            binding.skeletonChats.crossfadeToContent(binding.rvChats)
        } else {
            binding.rvChats.visibility = View.VISIBLE
        }
    }

    /**
     * The back arrow returns to Home rather than finishing. The bottom-nav
     * helper finishes each tab as it switches, so by the time Chats is open the
     * back stack is just this screen — a plain finish() would exit the app.
     * CLEAR_TOP reuses the existing Home if one is still around instead of
     * stacking a second; finish() drops Chats so it isn't left underneath.
     */
    private fun goToHome() {
        startActivity(
            Intent(this, HomeActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
        )
        finish()
        applyBackTransition()
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }
}
