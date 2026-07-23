package com.wedora.app

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.wedora.app.databinding.ActivityExploreBinding

/**
 * The Explore tab.
 *
 * A placeholder, and honest about it: the map experience needs a Maps API key
 * and coordinates stored on both sides of a match, neither of which exists
 * yet. Showing a real screen that says "coming soon" beats a tab that toasts,
 * and it gives the bottom nav a genuine destination so the tab behaves like
 * every other one.
 *
 * The button routes to the swipe feed — the thing that does work today — so
 * the screen isn't a dead end.
 */
class ExploreActivity : AppCompatActivity() {

    private lateinit var binding: ActivityExploreBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityExploreBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setUpWedoraBottomNav(binding.bottomNav, R.id.nav_maps)

        binding.btnFindPeople.addPressScale()
        binding.btnFindPeople.setOnClickListener {
            // Same start-and-finish as a tab switch, since Discover is a peer
            // rather than a screen nested under this one.
            startActivity(Intent(this, HomeActivity::class.java))
            finish()
            applyLateralTransition()
        }
    }
}
