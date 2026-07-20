package com.wedora.app

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.firebase.auth.FirebaseAuth
import com.wedora.app.databinding.ActivityHomeBinding

class HomeActivity : AppCompatActivity() {

    private lateinit var binding: ActivityHomeBinding

    private val adapter by lazy {
        MatchCardAdapter(
            // Liking is a guest-gated action; passing/dismissing only affect the
            // local feed, so they stay available.
            onLike = { requireAccount { toast("Liked ${it.name}") } },
            onSuperlike = { requireAccount { toast("Super liked ${it.name}") } },
            onPass = { toast("Passed on ${it.name}") },
            onDismiss = { toast("Dismissed ${it.name}") },
            onMore = { toast("More options for ${it.name}") }
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityHomeBinding.inflate(layoutInflater)
        setContentView(binding.root)

        showSignedInUser()
        setUpFeed()
        setUpBottomNav()

        binding.btnDarkMode.setOnClickListener { toggleDarkMode() }

        binding.btnMenu.setOnClickListener {
            // TODO: open the navigation drawer / overflow menu
            toast(getString(R.string.cd_menu))
        }
    }

    /** Greet the signed-in user by name, falling back to the sample name. */
    private fun showSignedInUser() {
        val displayName = FirebaseAuth.getInstance().currentUser?.displayName
        if (!displayName.isNullOrBlank()) {
            binding.tvUserName.text = displayName
        }
    }

    private fun setUpFeed() {
        binding.rvMatches.layoutManager = LinearLayoutManager(this)
        binding.rvMatches.adapter = adapter
        // TODO: replace with real data — wiring the feed is a separate task.
        adapter.submitList(MatchCard.sampleCards())
    }

    private fun setUpBottomNav() {
        binding.bottomNav.selectedItemId = R.id.nav_home
        binding.bottomNav.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_home -> true

                // Chats and Profile are account-only; guests are sent to sign-up.
                R.id.nav_chats, R.id.nav_profile -> {
                    requireAccount { toast("${item.title} coming soon") }
                    false
                }

                // TODO: swap in the real destinations as those screens are built
                else -> {
                    toast("${item.title} coming soon")
                    false
                }
            }
        }
    }

    /**
     * Runs [action] for signed-in users. Guests are redirected to sign-up instead,
     * which is the single gate for every account-only feature on this screen.
     */
    private fun requireAccount(action: () -> Unit) {
        if (GuestPrefs.isGuest(this)) {
            Toast.makeText(this, R.string.guest_action_blocked, Toast.LENGTH_SHORT).show()
            startActivity(Intent(this, SignUpActivity::class.java))
        } else {
            action()
        }
    }

    private fun toggleDarkMode() {
        val enabled = ThemePrefs.isDarkEnabled(this)
        // Recreates the activity so the new night-mode resources are applied.
        ThemePrefs.setDarkEnabled(this, !enabled)
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }
}
