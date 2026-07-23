package com.wedora.app

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.activity.addCallback
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
import java.util.Date

/**
 * The conversation list: one row per match, showing the last message or a
 * prompt to start one, with an unread badge when the other person has written
 * something this user hasn't opened.
 */
class ChatsActivity : AppCompatActivity(), DeleteChatsBottomSheet.Host {

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

    /** The full conversation list, kept so search can filter it in memory. */
    private var allPreviews: List<ChatPreview> = emptyList()

    private val adapter = ChatListAdapter(
        onClick = { chat ->
            startActivity(ChatThreadActivity.intent(this, chat.otherUserId, chat.name))
        },
        onSelectionChanged = { selectionMode, count -> updateSelectionUi(selectionMode, count) }
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityChatsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.rvChats.layoutManager = LinearLayoutManager(this)
        binding.rvChats.adapter = adapter

        setUpWedoraBottomNav(binding.bottomNav, R.id.nav_chats)

        binding.btnBack.setOnClickListener { goToHome() }

        binding.btnSearch.setOnClickListener { expandSearch() }
        binding.btnSearchClose.setOnClickListener { collapseSearch() }
        binding.etSearch.addTextChangedListener(SimpleTextWatcher {
            applyChatFilter(currentQuery())
        })

        binding.btnCancelSelection.setOnClickListener { adapter.exitSelectionMode() }
        binding.btnDeleteSelection.setOnClickListener { confirmDeleteSelected() }

        // Back exits selection mode first, then closes the search bar; only
        // once neither is active does it behave like the back arrow.
        onBackPressedDispatcher.addCallback(this) {
            when {
                adapter.selectionMode -> adapter.exitSelectionMode()
                binding.searchBar.visibility == View.VISIBLE -> collapseSearch()
                else -> goToHome()
            }
        }
    }

    // ----- Multi-select delete ------------------------------------------------

    /**
     * Swaps the top bar between its normal contents and the selection bar.
     *
     * Search and selection share that same row, so only one can show at a
     * time — entering selection mode while search is open collapses it first
     * rather than trying to lay out both. Reuses collapseSearch() itself
     * rather than re-deriving its visibility/animation logic here.
     */
    private fun updateSelectionUi(selectionMode: Boolean, count: Int) {
        if (selectionMode && binding.searchBar.visibility == View.VISIBLE) {
            collapseSearch()
        }

        val normalVisibility = if (selectionMode) View.GONE else View.VISIBLE
        // INVISIBLE, not GONE, for the same reason setTopBarVisible uses it —
        // content below is positioned off btnBack's slot. selectionBar has no
        // opaque background of its own (unlike searchBar), so leaving btnBack
        // VISIBLE underneath would keep a sliver of it tappable — goToHome()
        // firing mid-selection.
        binding.btnBack.visibility = if (selectionMode) View.INVISIBLE else View.VISIBLE
        binding.tvChatsTitle.visibility = normalVisibility
        binding.btnSearch.visibility = normalVisibility

        binding.selectionBar.visibility = if (selectionMode) View.VISIBLE else View.GONE
        if (selectionMode) {
            binding.tvSelectionCount.text =
                resources.getQuantityString(R.plurals.chats_selected_count, count, count)
        }
    }

    private fun confirmDeleteSelected() {
        val count = adapter.selectedMatchIds().size
        if (count == 0) return
        DeleteChatsBottomSheet.show(supportFragmentManager, count)
    }

    /**
     * Optimistic: the selected rows drop out of the list immediately, before
     * the write confirms. If it fails, they're put back and the user is told —
     * the same pattern ProfileDetailActivity uses for like/unlike. Without a
     * rollback, a failed write would leave the rows hidden locally forever: a
     * live listener only re-fires on an actual document change, and a failed
     * write is exactly the case where nothing changed.
     */
    override fun onDeleteChatsConfirmed() {
        val selfUid = FirebaseAuth.getInstance().currentUser?.uid ?: return
        val matchIds = adapter.selectedMatchIds()
        val previousPreviews = allPreviews

        allPreviews = allPreviews.filterNot { it.matchId in matchIds }
        applyChatFilter(currentQuery())
        adapter.exitSelectionMode()

        hideMatchesForUser(firestore, matchIds, selfUid)
            .addOnFailureListener { e ->
                Log.w(TAG, "Failed to delete ${matchIds.size} chat(s)", e)
                allPreviews = previousPreviews
                applyChatFilter(currentQuery())
                Toast.makeText(this, R.string.error_delete_chats_failed, Toast.LENGTH_LONG).show()
            }
    }

    // ----- Search -----------------------------------------------------------

    private fun currentQuery(): String = binding.etSearch.text?.toString().orEmpty()

    private fun expandSearch() {
        setTopBarVisible(false)
        binding.searchBar.visibility = View.VISIBLE
        binding.searchBar.alpha = 0f
        binding.searchBar.translationX = 16 * resources.displayMetrics.density
        binding.searchBar.animate().alpha(1f).translationX(0f).setDuration(200).start()
        binding.etSearch.showKeyboard()
    }

    private fun collapseSearch() {
        binding.etSearch.hideKeyboard()
        // Clearing the field restores the full list through the text watcher.
        binding.etSearch.setText("")
        binding.searchBar.animate().alpha(0f).setDuration(150).withEndAction {
            binding.searchBar.visibility = View.GONE
            binding.searchBar.translationX = 0f
        }.start()
        setTopBarVisible(true)
    }

    private fun setTopBarVisible(visible: Boolean) {
        val v = if (visible) View.VISIBLE else View.GONE
        // Hidden while searching so a stray tap can't leave Chats mid-type; the
        // X on the search bar (and system back) is the only way out of search.
        //
        // INVISIBLE, not GONE: the whole top region — the list, skeleton and
        // empty state — is positioned below btnBack, so removing it from layout
        // would shift everything up. Invisible keeps its slot (and the search
        // bar's vertical anchor) intact.
        binding.btnBack.visibility = if (visible) View.VISIBLE else View.INVISIBLE
        binding.tvChatsTitle.visibility = v
        binding.btnSearch.visibility = v
    }

    /**
     * Filters the loaded conversations by [query] against the other person's
     * name and the last-message preview, in memory. Blank restores the list.
     */
    private fun applyChatFilter(query: String) {
        // Nothing loaded (empty inbox / guest / error): the load path already
        // shows the right empty state, so don't stomp it with a blank list.
        if (allPreviews.isEmpty()) return

        val q = query.trim()
        val filtered =
            if (q.isEmpty()) allPreviews
            else allPreviews.filter { it.matchesSearch(q) }

        if (filtered.isEmpty()) {
            binding.rvChats.visibility = View.GONE
            binding.emptyState.show(
                R.drawable.ic_search,
                R.string.empty_search_title,
                getString(R.string.empty_chats_no_results, q)
            )
        } else {
            binding.emptyState.hide()
            binding.rvChats.visibility = View.VISIBLE
            adapter.submitList(filtered)
        }
    }

    private fun ChatPreview.matchesSearch(query: String): Boolean {
        val q = query.lowercase()
        return name.lowercase().contains(q) ||
            lastMessage?.lowercase()?.contains(q) == true
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

        // Only blank the list for a genuine first load. onStart reattaches this
        // listener every time the screen comes back to the foreground —
        // including a reused ChatsActivity returning from a closed chat thread
        // — and unconditionally swapping already-rendered rows for the skeleton
        // there produced a visible blank/reloading frame during that return
        // transition. With rows already on screen, the fresh snapshot below
        // updates them in place via DiffUtil instead.
        if (allPreviews.isEmpty()) showLoading()
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

                // "Deleted for me": a match the current user has hidden is
                // excluded from their own list, same as if it didn't exist —
                // the other participant's list, and the underlying match and
                // messages, are untouched.
                val matches = snapshot?.documents?.mapNotNull { Match.from(it) }
                    .orEmpty()
                    .filterNot { it.isHiddenFor(selfUid) }
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
            loadLastSeenThenRender(matches, selfUid)
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
                loadLastSeenThenRender(matches, selfUid)
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

    /**
     * Batched, one-time presence read for everyone currently in the list —
     * unlike [nameCache], not skipped for already-known users, since presence
     * (unlike a name) goes stale. A single get() per render rather than a
     * listener per row: the online dot is "close enough", not real-time, so
     * this matches how the list already refreshes on every message event.
     */
    private fun loadLastSeenThenRender(matches: List<Match>, selfUid: String) {
        val otherUids = matches.mapNotNull { it.otherUserId(selfUid) }.distinct()
        if (otherUids.isEmpty()) {
            render(matches, selfUid, emptyMap())
            return
        }

        val tasks = otherUids.chunked(WHERE_IN_CHUNK).map { chunk ->
            firestore.collection(UserProfile.COLLECTION)
                .whereIn(FieldPath.documentId(), chunk)
                .get()
        }

        Tasks.whenAllSuccess<QuerySnapshot>(tasks)
            .addOnSuccessListener { snapshots ->
                val lastSeenByUid = snapshots.flatMap { it.documents }
                    .associate { it.id to UserProfile.from(it).lastSeen }
                render(matches, selfUid, lastSeenByUid)
            }
            .addOnFailureListener { e ->
                // Presence is a nicety — render without dots rather than
                // blocking the list on a second read failing.
                Log.w(TAG, "Failed to load presence for chat list", e)
                render(matches, selfUid, emptyMap())
            }
    }

    private fun render(matches: List<Match>, selfUid: String, lastSeenByUid: Map<String, Date?>) {
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
                unreadCount = lastMessage?.unreadCount ?: 0,
                lastSeen = lastSeenByUid[otherUid]
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
        allPreviews = previews
        binding.progressLoading.visibility = View.GONE

        // The listener re-fires on every message, so only the first pass has a
        // skeleton to clear; afterwards this is a no-op on an already-hidden view.
        // What ends up visible (list vs. search-empty) is decided by the filter.
        if (binding.skeletonChats.visibility == View.VISIBLE) {
            binding.skeletonChats.crossfadeToContent(binding.rvChats)
        }
        // Re-apply any active search so a live update doesn't drop the query.
        applyChatFilter(currentQuery())
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
}
