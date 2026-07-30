package com.wedora.app

import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.os.Bundle
import android.text.SpannableString
import android.text.Spanned
import android.text.TextPaint
import android.text.method.LinkMovementMethod
import android.text.style.ClickableSpan
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.wedora.app.databinding.ActivityProfileBinding
import com.wedora.app.databinding.ItemAppVersionRowBinding
import com.wedora.app.databinding.ItemSettingsRowBinding

class ProfileActivity : WedoraBaseActivity(), LogoutBottomSheet.Host, UpdateRepository.Observer {

    private companion object {
        const val TAG = "WedoraProfile"

        /**
         * Where App Version lands in settingsContainer: after Account Settings
         * (0) and Notifications (1), before Privacy & Safety — the placement
         * the update spec asks for.
         */
        const val APP_VERSION_ROW_INDEX = 2
    }

    private lateinit var binding: ActivityProfileBinding
    private val firestore: FirebaseFirestore by lazy { FirebaseFirestore.getInstance() }

    private var appVersionRow: ItemAppVersionRowBinding? = null
    private var rowShimmer: android.animation.Animator? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityProfileBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applyBottomNavScreenInsets(binding.root, binding.bottomNav)

        setUpDarkModeSwitch()
        setUpSettingsRows()
        setUpWedoraBottomNav(binding.bottomNav, R.id.nav_profile)
        setUpExitConfirmOnBackPress {
            val (kind, count) = resolveExitConfirmKind(
                unseenLikes = binding.bottomNav.currentBadgeCount(R.id.nav_match),
                unreadMessages = binding.bottomNav.currentBadgeCount(R.id.nav_chats)
            )
            ExitConfirmBottomSheet.show(supportFragmentManager, kind, count)
        }

        // Re-checked at click time rather than captured once, so this stays
        // correct even if guest status somehow changes without the Activity
        // being recreated — greyed out rather than hidden (see showGuestChrome)
        // matches this screen's whole "preview, not blocked" tone for guests.
        binding.btnHistory.setOnClickListener {
            if (GuestPrefs.isGuest(this)) {
                toast(getString(R.string.guest_action_blocked))
            } else {
                startActivity(Intent(this, MatchHistoryActivity::class.java))
            }
        }
        binding.btnEditProfile.setOnClickListener {
            startActivity(Intent(this, EditProfileActivity::class.java))
        }
        // Replaces the old "Payment & Subscription" settings row, which opened
        // the same screen from further down the list.
        binding.cardPremium.setOnClickListener {
            startActivity(Intent(this, PaymentSubscriptionActivity::class.java))
        }

        // Guest-only controls. Harmless to wire up unconditionally — both
        // stay GONE for a signed-in user, so their listeners are simply never
        // reachable.
        binding.btnGuestSignUp.setOnClickListener {
            startActivity(Intent(this, SignUpActivity::class.java))
        }
        binding.guestCtaBanner.setOnClickListener {
            startActivity(Intent(this, SignUpActivity::class.java))
        }
        binding.tvGuestCtaText.text = buildGuestCtaText()
        binding.tvGuestCtaText.movementMethod = LinkMovementMethod.getInstance()
        binding.tvGuestLogIn.setOnClickListener {
            startActivity(Intent(this, LoginActivity::class.java))
        }
        // Only a guest's columns carry the lock badge (see setStatsLocked),
        // but the listener itself re-checks rather than relying on that —
        // same reasoning as btnHistory above.
        val guestStatToast = View.OnClickListener {
            if (GuestPrefs.isGuest(this)) toast(getString(R.string.guest_stats_locked_toast))
        }
        binding.statColumnMatches.setOnClickListener(guestStatToast)
        binding.statColumnLikes.setOnClickListener(guestStatToast)
        binding.statColumnProfile.setOnClickListener(guestStatToast)
    }

    /**
     * The CTA banner's body copy plus a trailing "Sign Up" span that reads
     * inline with the sentence, tappable on its own in addition to the whole
     * banner already being clickable — belt and suspenders, same reasoning
     * as LikesActivity's premium banner having both a click target of its
     * own and a dedicated action button.
     */
    private fun buildGuestCtaText(): SpannableString {
        val body = getString(R.string.guest_cta_banner_text)
        val link = getString(R.string.premium_banner_signup_action)
        val full = "$body  $link"
        val linkStart = full.length - link.length

        return SpannableString(full).apply {
            setSpan(
                object : ClickableSpan() {
                    override fun onClick(widget: View) {
                        startActivity(Intent(this@ProfileActivity, SignUpActivity::class.java))
                    }

                    override fun updateDrawState(ds: TextPaint) {
                        ds.color = ContextCompat.getColor(this@ProfileActivity, R.color.wedora_accent)
                        ds.isUnderlineText = false
                        ds.typeface = Typeface.create(ds.typeface, Typeface.BOLD)
                    }
                },
                linkStart,
                full.length,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
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
            showGuestChrome()
            return
        }

        binding.btnHistory.alpha = 1f
        binding.tvGuestSubtitle.visibility = View.GONE
        binding.tvGuestGenderPill.visibility = View.GONE
        binding.btnGuestSignUp.visibility = View.GONE
        binding.guestCtaBanner.visibility = View.GONE
        binding.tvGuestLogIn.visibility = View.GONE
        setStatsLocked(false)

        binding.btnEditProfile.visibility = View.VISIBLE
        // Best cached guess up front so there's no upgrade-card flash before
        // loadProfileDocument's read lands; that read reconciles it below.
        showPremiumState(PremiumStatus.isPremium())

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
     * The whole guest state: a preview of what an account unlocks, not a
     * locked-out screen. Silhouette photo, no real name/email/location line,
     * a full-width Sign Up CTA in place of Edit Profile, blurred-style stats
     * with a lock badge each, the pink CTA banner, and a bottom Log In link
     * — everything a signed-in user's chrome hides or replaces.
     */
    private fun showGuestChrome() {
        binding.ivProfilePhoto.setImageResource(R.drawable.ic_avatar_placeholder)
        binding.tvProfileName.text = GuestPrefs.guestDisplayName(this)
        binding.tvGuestSubtitle.visibility = View.VISIBLE
        binding.tvProfileEmail.visibility = View.GONE
        binding.tvProfileAgeLocation.visibility = View.GONE
        binding.tvProfileIntent.visibility = View.GONE
        showGuestGenderPill()

        // A guest has no Firestore document, so there is nothing for the
        // editor to load or save — it would open, fail its read and close.
        // The full-width Sign Up CTA takes its place instead.
        binding.btnEditProfile.visibility = View.GONE
        binding.btnGuestSignUp.visibility = View.VISIBLE

        // Likewise no account to attach a subscription to.
        binding.cardPremium.visibility = View.GONE
        binding.premiumMemberBadge.visibility = View.GONE

        // Greyed rather than hidden, matching this screen's "preview, not
        // blocked" tone — the icon signals there's a real history feature
        // behind sign-up, not that the button doesn't exist.
        binding.btnHistory.alpha = disabledAlpha(0.4f)

        // A guest makes no Firestore read, so nothing will ever arrive to
        // replace a skeleton — show the card's "—" placeholders (already set
        // by resetStats) with the lock badges instead of a shimmer that never
        // resolves.
        binding.skeletonProfile.root.stopShimmer()
        binding.skeletonProfile.root.visibility = View.GONE
        binding.statsCard.visibility = View.VISIBLE
        setStatsLocked(true)

        binding.guestCtaBanner.visibility = View.VISIBLE
        binding.tvGuestLogIn.visibility = View.VISIBLE
    }

    /**
     * "Male • Looking for Female" — the guest equivalent of the signed-in
     * tvProfileIntent pill. Hidden rather than shown with a blank or partial
     * label if either value is missing, which is the normal case today:
     * nothing currently sets GuestPrefs' gender fields (see
     * GuestPrefs.setGuestGenderPreferences's own doc comment), so this stays
     * hidden until a guest-facing gender prompt exists to populate them.
     */
    private fun showGuestGenderPill() {
        val gender = GuestPrefs.guestGender(this)?.let { genderLabel(it) }
        val interestedIn = GuestPrefs.guestInterestedIn(this)?.let { genderLabel(it) }

        if (gender == null || interestedIn == null) {
            binding.tvGuestGenderPill.visibility = View.GONE
            return
        }

        binding.tvGuestGenderPill.text =
            getString(R.string.guest_gender_pill_format, gender, interestedIn)
        binding.tvGuestGenderPill.visibility = View.VISIBLE
    }

    /** Resolves a stored Gender.firestoreValue back to its display label. */
    private fun genderLabel(firestoreValue: String): String? =
        Gender.values().firstOrNull { it.firestoreValue == firestoreValue }
            ?.let { getString(it.labelRes) }

    /**
     * The alpha this app dims a "locked for guests" element to. Light mode
     * keeps [lightAlpha] (0.4 for btnHistory, 0.5 for a settings row)
     * unchanged; dark mode uses a fixed, higher floor instead, because the
     * same alpha composites toward a much darker background there and the
     * result falls under WCAG's non-text 3:1 minimum. Measured: a settings
     * row's lock icon (tinted wedora_text_secondary, see setUpSettingsRows)
     * blended at 50% over wedora_bg dark computes to ~2.98:1 — below 3:1.
     * At the 0.68 used here it computes to ~4.13:1, and the row's label text
     * (wedora_text, full strength before dimming) clears WCAG AA's 4.5:1
     * text minimum by a wide margin at this alpha too.
     */
    private fun disabledAlpha(lightAlpha: Float): Float =
        if (ThemePrefs.isDarkEnabled(this)) 0.68f else lightAlpha

    /** Toggles the three stat columns' lock badges, guest vs signed-in. */
    private fun setStatsLocked(locked: Boolean) {
        val visibility = if (locked) View.VISIBLE else View.GONE
        binding.ivStatLockMatches.visibility = visibility
        binding.ivStatLockLikes.visibility = visibility
        binding.ivStatLockProfile.visibility = visibility
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
        showStatsSkeleton()
        firestore.collection(UserProfile.COLLECTION).document(uid).get()
            .addOnSuccessListener { snapshot ->
                val profile = UserProfile.from(snapshot)
                // Authoritative value from this screen's own read — reconciles
                // the cached guess shown at the top of showSignedInUser().
                showPremiumState(profile.isPremium)

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
                // Stop skeleton shimmer and show stats card with no animation.
                binding.skeletonProfile.root.stopShimmer()
                binding.skeletonProfile.root.visibility = View.GONE
                binding.statsCard.visibility = View.VISIBLE
            }
            .addOnFailureListener { e ->
                // The placeholder stays rather than showing a figure: a wrong
                // completion score would send the user editing fields that are
                // already filled in.
                Log.w(TAG, "Couldn't load the profile document", e)
                binding.tvProfileAgeLocation.visibility = View.GONE
                // Reveal the card even on failure — its "—" placeholders say
                // "unknown". Stop the skeleton shimmer and show the stats card.
                binding.skeletonProfile.root.stopShimmer()
                binding.skeletonProfile.root.visibility = View.GONE
                binding.statsCard.visibility = View.VISIBLE
            }
    }

    /**
     * Swaps between the upgrade card and the "Premium Member" badge — never
     * both, never neither, for a signed-in user. Called once with the cached
     * guess and again once loadProfileDocument's own read confirms it.
     */
    private fun showPremiumState(isPremium: Boolean) {
        binding.cardPremium.visibility = if (isPremium) View.GONE else View.VISIBLE
        binding.premiumMemberBadge.visibility = if (isPremium) View.VISIBLE else View.GONE
    }

    /**
     * Hides the real stats card behind its placeholder. Called on every load,
     * including the reload on resume — the numbers are re-read each time, so
     * they're genuinely unknown again until it lands.
     */
    private fun showStatsSkeleton() {
        binding.statsCard.visibility = View.GONE
        binding.skeletonProfile.root.visibility = View.VISIBLE
        binding.skeletonProfile.root.alpha = 1f
        binding.skeletonProfile.root.startShimmer()
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

    /**
     * Built once here rather than re-evaluated per resume, same as
     * btnEditProfile/cardPremium's own click listeners above — guest status
     * isn't expected to change without this Activity being recreated (signing
     * up happens on a different screen).
     *
     * Dark Mode is the one row excluded from the guest treatment entirely —
     * it isn't in this list at all, since it's a device preference with its
     * own switch further up the layout, not account data.
     */
    private fun setUpSettingsRows() {
        val isGuest = GuestPrefs.isGuest(this)
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
            SettingsRow(R.drawable.ic_eye, R.string.settings_profile_viewers) {
                startActivity(Intent(this, ProfileViewersActivity::class.java))
            },
            SettingsRow(R.drawable.ic_help, R.string.settings_help) {
                startActivity(Intent(this, HelpCenterActivity::class.java))
            },
            SettingsRow(R.drawable.ic_terms, R.string.settings_terms) {
                startActivity(TermsOfServiceActivity.intent(this, fromSignup = false))
            },
            SettingsRow(R.drawable.ic_policy, R.string.settings_privacy_policy) {
                startActivity(Intent(this, PrivacyPolicyActivity::class.java))
            }
        )

        // Hidden entry point: only ever appended for WedoraAdmin.UID, with
        // no other discoverable hint anywhere in the UI that this row could
        // exist. Placed after building the base list rather than as a
        // SettingsRow entry filtered out below, so a non-admin's list is
        // built exactly as if this feature didn't exist at all.
        val adminRow = if (FirebaseAuth.getInstance().currentUser?.uid == WedoraAdmin.UID) {
            listOf(
                SettingsRow(R.drawable.ic_flag, R.string.settings_admin_reports) {
                    startActivity(Intent(this, AdminReportsActivity::class.java))
                }
            )
        } else {
            emptyList()
        }

        val logoutRow = listOf(
            // guestEnabled: leaving guest mode needs no account, so this is
            // the one row a guest can actually use — see showGuestChrome's
            // own bottom Log In link for the parallel "leave guest mode"
            // route that doesn't require opening the settings list at all.
            SettingsRow(R.drawable.ic_logout, R.string.settings_logout, guestEnabled = true) {
                LogoutBottomSheet().show(supportFragmentManager, "logout")
            }
        )

        val inflater = LayoutInflater.from(this)
        (rows + adminRow + logoutRow).forEach { row ->
            val rowBinding = ItemSettingsRowBinding.inflate(inflater, binding.settingsContainer, true)
            rowBinding.ivRowIcon.setImageResource(row.iconRes)
            rowBinding.tvRowLabel.setText(row.labelRes)
            if (isGuest && !row.guestEnabled) {
                rowBinding.root.alpha = disabledAlpha(0.5f)
                rowBinding.ivRowChevron.setImageResource(R.drawable.ic_lock)
                // ic_lock.xml's own fill is a hardcoded white, meant for the
                // badge-on-accent-circle context it was built for (see
                // item_like_featured.xml) — not for sitting directly on this
                // row's plain background the way ic_chevron_right (which it
                // replaces here) already does via its own baked-in
                // wedora_text_secondary fill. Explicit tint matches that.
                rowBinding.ivRowChevron.imageTintList =
                    ColorStateList.valueOf(ContextCompat.getColor(this, R.color.wedora_text_secondary))
                rowBinding.root.setOnClickListener {
                    toast(getString(R.string.guest_settings_locked_toast))
                }
            } else {
                rowBinding.root.setOnClickListener { row.onClick() }
            }
        }

        insertAppVersionRow()
    }

    /**
     * The App Version row (spec surface 06), inserted between Notifications and
     * Privacy & Safety.
     *
     * Added by index after the loop rather than as a [SettingsRow] because it
     * needs a subtitle, an inline button and a shimmer overlay that
     * item_settings_row has no slots for — and because it must sit OUTSIDE the
     * guest treatment: the installed app version is device information, not
     * account data, so it stays readable for a guest exactly like the Dark Mode
     * switch does.
     */
    private fun insertAppVersionRow() {
        appVersionRow = ItemAppVersionRowBinding.inflate(
            LayoutInflater.from(this), binding.settingsContainer, false
        ).also { row ->
            binding.settingsContainer.addView(row.root, APP_VERSION_ROW_INDEX)

            row.btnRowUpdate.setOnClickListener {
                UpdateRepository.startFlexible(this, UpdateAnalytics.SURFACE_SETTINGS_ROW)
            }
            // Support affordance from the spec: force a fresh check, bypassing
            // the 4-hour cache. In a debug build this opens the state previewer
            // instead — see UpdateDebug.
            row.root.setOnLongClickListener {
                if (UpdateDebug.ENABLED) {
                    UpdateDebug.showPicker(this)
                } else {
                    row.tvRowSubtitle.setText(R.string.update_row_checking)
                    UpdateRepository.check(force = true)
                }
                true
            }
        }
        renderAppVersionRow(UpdateRepository.state)
    }

    /**
     * Paints the row for [state]. Called on every state change while Profile is
     * open, so the row flips in place rather than needing a revisit.
     */
    private fun renderAppVersionRow(state: UpdateState) {
        val row = appVersionRow ?: return
        val versionName = UpdateRepository.currentVersionName()
        val pending = state is UpdateState.Available ||
            state is UpdateState.Downloading ||
            state is UpdateState.Downloaded

        row.rowRoot.setBackgroundResource(
            if (pending) R.drawable.bg_update_row_pending else 0
        )
        row.btnRowUpdate.visibility = if (state is UpdateState.Available) View.VISIBLE else View.GONE

        row.tvRowSubtitle.text = when (state) {
            is UpdateState.Available ->
                getString(R.string.update_row_available, versionName)
            is UpdateState.Downloading ->
                getString(R.string.update_downloading_percent, state.percent)
            is UpdateState.Downloaded ->
                getString(R.string.update_ready_body)
            is UpdateState.UpToDate ->
                if (state.reachable) {
                    getString(R.string.update_row_up_to_date, versionName)
                } else {
                    // Distinct from "up to date": we genuinely don't know.
                    getString(R.string.update_row_unreachable, versionName)
                }
            // Idle (not checked yet) and Failed both read as the plain version.
            else -> getString(R.string.update_row_up_to_date, versionName)
        }

        row.tvRowSubtitle.setTextColor(
            ContextCompat.getColor(
                this,
                if (pending) R.color.wedora_accent else R.color.wedora_text_secondary
            )
        )

        applyRowShimmer(row, state)
    }

    /**
     * Runs the shimmer only for a pending update the user hasn't looked at yet,
     * once per versionCode. A settings row that shimmers forever is noise
     * rather than a cue, so seeing it here is what switches it off.
     */
    private fun applyRowShimmer(row: ItemAppVersionRowBinding, state: UpdateState) {
        val available = state as? UpdateState.Available
        if (available == null || UpdatePrefs.hasSeenRow(this, available.versionCode)) {
            rowShimmer?.cancel()
            rowShimmer = null
            row.shimmer.visibility = View.GONE
            return
        }
        if (rowShimmer != null) return

        row.shimmer.visibility = View.VISIBLE
        row.rowRoot.post {
            rowShimmer = Motion.shimmer(row.shimmer, row.rowRoot.width)
            // Null means reduced motion — no sweep, so no band either.
            if (rowShimmer == null) row.shimmer.visibility = View.GONE
        }
        // Seen now — the next visit for this same version shows a static row.
        UpdatePrefs.recordRowSeen(this, available.versionCode)
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    override fun onStart() {
        super.onStart()
        // The row reflects whatever the repository already knows; no check is
        // forced here, so opening Profile never costs a Play round trip.
        UpdateRepository.addObserver(this)
    }

    override fun onStop() {
        UpdateRepository.removeObserver(this)
        super.onStop()
    }

    override fun onUpdateState(state: UpdateState) {
        renderAppVersionRow(state)
    }

    override fun onDestroy() {
        rowShimmer?.cancel()
        rowShimmer = null
        appVersionRow = null
        super.onDestroy()
    }

    /** From [LogoutBottomSheet] once the user confirms. */
    override fun onLogoutConfirmed() {
        // Already correct for a guest with no changes needed: signOut() ends
        // whatever Firebase session exists, anonymous (see
        // LoginActivity.continueAsGuest) or real, the same way.
        FirebaseAuth.getInstance().signOut()
        GuestPrefs.clearGuest(this)
        startActivity(
            Intent(this, LoginActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        )
        finish()
    }
}
