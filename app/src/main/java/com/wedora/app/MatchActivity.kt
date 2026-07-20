package com.wedora.app

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.wedora.app.databinding.ActivityMatchBinding

class MatchActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMatchBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMatchBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // TODO: replace the radar's placeholder avatars with real match data.
        setUpWedoraBottomNav(binding.bottomNav, R.id.nav_match)
    }
}
