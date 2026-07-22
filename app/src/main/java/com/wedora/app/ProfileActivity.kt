package com.wedora.app

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.wedora.app.databinding.ActivityProfileBinding
import com.wedora.app.databinding.ItemSettingsRowBinding

class ProfileActivity : AppCompatActivity() {

    private companion object {
        const val TAG = "WedoraProfile"
    }

    private lateinit var binding: ActivityProfileBinding
    private val firestore: FirebaseFirestore by lazy { FirebaseFirestore.getInstance() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityProfileBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setUpDarkModeSwitch()
        setUpSettingsRows()
        setUpWedoraBottomNav(binding.bottomNav, R.id.nav_profile)

        binding.btnHistory.setOnClickListener { toast(getString(R.string.cd_history)) }
        binding.btnEditProfile.setOnClickListener {
            startActivity(Intent(this, EditProfileActivity::class.java))
        }
        // Replaces the old "Payment & Subscription" settings row, which opened
        // the same screen from further down the list.
        binding.cardPremium.setOnClickListener {
            startActivity(Intent(this, PaymentSubscriptionActivity::class.java))
        }
    }

    /**
     * Re-read on every resume rather than once in onCreate, so returning from
     * EditProfileActivity shows the new values immediately. This screen is a
     * bottom-nav destination the user lands on repeatedly, so the extra read
     * is bounded by navigation, not by anything in a loop.
     */
    override fun onResume() {
        super.onResume()
        showSignedInUser()
    }

    /**
     * Name and email are real Firebase account data; the photo is the
     * device-local file saved at sign-up (see [LocalProfilePrefs]), not a
     * Firebase photoUrl; age/city/country are read from Firestore below. The
     * stats card stays a placeholder — there's no backend field for it yet.
     */
    private fun showSignedInUser() {
        // Cleared first so a resume never shows the last visit's numbers while
        // the new ones load, and so a guest never sees a signed-in user's.
        resetStats()

        if (GuestPrefs.isGuest(this)) {
            binding.tvProfileName.text = getString(R.string.guest_label)
            binding.tvProfileEmail.visibility = View.GONE
            binding.tvProfileAgeLocation.visibility = View.GONE
            // A guest has no Firestore document, so there is nothing for the
            // editor to load or save — it would open, fail its read and close.
            binding.btnEditProfile.visibility = View.GONE
            // Likewise no account to attach a subscription to.
            binding.cardPremium.visibility = View.GONE
            binding.tvProfileIntent.visibility = View.GONE
            return
        }

        binding.btnEditProfile.visibility = View.VISIBLE
        binding.cardPremium.visibility = View.VISIBLE

        val user = FirebaseAuth.getInstance().currentUser
        binding.tvProfileName.text = user?.displayName?.takeIf { it.isNotBlank() }
            ?: getString(R.string.default_profile_name)

        val email = user?.email
        if (!email.isNullOrBlank()) {
            binding.tvProfileEmail.text = email
            binding.tvProfileEmail.visibility = View.VISIBLE
        } else {
            binding.tvProfileEmail.visibility = View.GONE
        }

        user?.uid?.let {
            binding.ivProfilePhoto.loadLocalProfilePhoto(this, it)
            // Fired together, not chained: the two reads are independent, so
            // each stat appears as soon as its own data lands.
            loadProfileDocument(it)
            loadMatchStats(it)
        }
    }

    /**
     * One read of the user document, serving both the "{age} years old •
     * {city}, {country}" line and the profile-completion stat — they need the
     * same fields, and this runs on every resume, so reading twice would
     * double the cost of every visit to this screen for nothing.
     *
     * The line stays hidden if the read fails or the fields aren't there — the
     * Complete Profile gate normally guarantees they are, but an older session
     * or a network failure shouldn't render a half-empty line.
     */
    private fun loadProfileDocument(uid: String) {
        firestore.collection(UserProfile.COLLECTION).document(uid).get()
            .addOnSuccessListener { snapshot ->
                val profile = UserProfile.from(snapshot)

                val line = profile.ageLocationLine(this)
                if (line == null) {
                    binding.tvProfileAgeLocation.visibility = View.GONE
                } else {
                    binding.tvProfileAgeLocation.text = line
                    binding.tvProfileAgeLocation.visibility = View.VISIBLE
                }

                val intentLine =
                    MarriageIntent.summaryLine(this, profile.myStatus, profile.lookingFor)
                if (intentLine == null) {
                    binding.tvProfileIntent.visibility = View.GONE
                } else {
                    binding.tvProfileIntent.text = intentLine
                    binding.tvProfileIntent.visibility = View.VISIBLE
                }

                showCompletion(calculateProfileCompletion(this, uid, profile))
            }
            .addOnFailureListener { e ->
                // The placeholder stays rather than showing a figure: a wrong
                // completion score would send the user editing fields that are
                // already filled in.
                Log.w(TAG, "Couldn't load the profile document", e)
                binding.tvProfileAgeLocation.visibility = View.GONE
            }
    }

    // ----- Stats ----------------------------------------------------------

    /**
     * Both counts come from a single query. "Matches" is every match document
     * containing me; "Likes" is the subset someone else initiated, which is a
     * client-side filter rather than a second query — pairing array-contains
     * with an inequality would need a composite index, and an inequality also
     * silently drops documents that lack the field, which is exactly the
     * legacy data the filter has to keep counting.
     */
    private fun loadMatchStats(uid: String) {
        firestore.collection(Match.COLLECTION)
            .whereArrayContains(Match.FIELD_USERS, uid)
            .get()
            .addOnSuccessListener { snapshot ->
                val matches = snapshot.documents.mapNotNull { Match.from(it) }
                binding.tvStatMatches.text = formatStatCount(matches.size)
                binding.tvStatLikes.text =
                    formatStatCount(matches.count { it.isLikeFor(uid) })
            }
            .addOnFailureListener { e ->
                // Left as the placeholder — showing 0 would read as "nobody
                // likes you" when the truth is that we don't know yet.
                Log.w(TAG, "Couldn't load match stats", e)
            }
    }

    private fun showCompletion(percent: Int) {
        binding.tvStatProfilePercent.text =
            getString(R.string.profile_stat_percent_format, percent)
        binding.tvStatProfilePercent.setTextColor(
            ContextCompat.getColor(
                this,
                if (percent >= PROFILE_COMPLETE_THRESHOLD) R.color.wedora_success
                else R.color.wedora_accent
            )
        )
    }

    /**
     * Back to "—" before each load. Without this a resume would show the
     * previous visit's figures while the new ones are in flight, which is
     * indistinguishable from fresh data that simply hasn't changed.
     */
    private fun resetStats() {
        val placeholder = getString(R.string.profile_stat_placeholder)
        binding.tvStatMatches.text = placeholder
        binding.tvStatLikes.text = placeholder
        binding.tvStatProfilePercent.text = placeholder
        binding.tvStatProfilePercent.setTextColor(
            ContextCompat.getColor(this, R.color.wedora_text)
        )
    }

    private fun setUpDarkModeSwitch() {
        // Set the initial state before attaching the listener so restoring it
        // doesn't immediately re-trigger a mode change.
        binding.switchDarkMode.isChecked = ThemePrefs.isDarkEnabled(this)
        binding.switchDarkMode.setOnCheckedChangeListener { _, isChecked ->
            ThemePrefs.setDarkEnabled(this, isChecked)
        }
    }

    private fun setUpSettingsRows() {
        val rows = listOf(
            SettingsRow(R.drawable.ic_account, R.string.settings_account) {
                startActivity(Intent(this, AccountSettingsActivity::class.java))
            },
            SettingsRow(R.drawable.ic_notifications, R.string.settings_notifications) {
                startActivity(Intent(this, NotificationsSettingsActivity::class.java))
            },
            SettingsRow(R.drawable.ic_privacy, R.string.settings_privacy) {
                startActivity(Intent(this, PrivacySafetyActivity::class.java))
            },
            SettingsRow(R.drawable.ic_help, R.string.settings_help) {
                startActivity(Intent(this, HelpCenterActivity::class.java))
            },
            SettingsRow(R.drawable.ic_logout, R.string.settings_logout) {
                logOut()
            }
        )

        val inflater = LayoutInflater.from(this)
        rows.forEach { row ->
            val rowBinding = ItemSettingsRowBinding.inflate(inflater, binding.settingsContainer, true)
            rowBinding.ivRowIcon.setImageResource(row.iconRes)
            rowBinding.tvRowLabel.setText(row.labelRes)
            rowBinding.root.setOnClickListener { row.onClick() }
        }
    }

    private fun logOut() {
        FirebaseAuth.getInstance().signOut()
        GuestPrefs.clearGuest(this)
        startActivity(
            Intent(this, LoginActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        )
        finish()
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }
}
