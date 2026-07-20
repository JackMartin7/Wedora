package com.wedora.app

import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import com.wedora.app.databinding.ActivityChatsBinding

class ChatsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityChatsBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityChatsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setUpNewMatchesStrip()
        setUpChatList()
        setUpWedoraBottomNav(binding.bottomNav, R.id.nav_chats)

        binding.btnBack.setOnClickListener { onBackPressedDispatcher.onBackPressed() }
        binding.btnSearch.setOnClickListener { toast(getString(R.string.cd_search)) }
        binding.btnMore.setOnClickListener { toast(getString(R.string.cd_more)) }
    }

    private fun setUpNewMatchesStrip() {
        binding.rvNewMatches.layoutManager =
            LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        // TODO: replace with real data — wiring is a separate task.
        binding.rvNewMatches.adapter = NewMatchAdapter(NewMatch.sampleMatches()) { match ->
            toast("Opened ${match.name}")
        }
    }

    private fun setUpChatList() {
        binding.rvChats.layoutManager = LinearLayoutManager(this)
        val adapter = ChatListAdapter { chat -> toast("Opened chat with ${chat.name}") }
        binding.rvChats.adapter = adapter
        // TODO: replace with real data — wiring is a separate task.
        adapter.submitList(ChatPreview.sampleConversations())
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }
}
