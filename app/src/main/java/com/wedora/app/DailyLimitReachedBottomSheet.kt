package com.wedora.app

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import androidx.core.os.bundleOf
import androidx.fragment.app.FragmentManager
import com.google.firebase.auth.FirebaseAuth

/**
 * "Daily Limit Reached" sheet, shown when a free-tier user hits either of the
 * two independent daily caps — 10 likes or 5 messages. Same title/buttons
 * either way; only the subtitle names which one. "Maybe Later" just dismisses
 * (the base class's default secondary action); "Upgrade Now" hands off to the
 * host to open PaymentSubscriptionActivity.
 */
class DailyLimitReachedBottomSheet : ConfirmBottomSheet() {

    enum class Kind(val subtitleRes: Int) {
        LIKES(R.string.like_limit_sheet_subtitle),
        MESSAGES(R.string.message_limit_sheet_subtitle)
    }

    /** Implemented by any screen that can show this sheet. */
    interface Host {
        fun onUpgradeFromDailyLimitRequested()

        /**
         * The rewarded-ad middle option. Default no-op so a host that
         * doesn't offer it is unaffected — [tertiaryLabelRes] below is what
         * decides whether the button is even shown.
         */
        fun onWatchAdForBonusRequested(kind: Kind) = Unit
    }

    private val kind: Kind
        get() = Kind.valueOf(requireArguments().getString(ARG_KIND)!!)

    override val iconRes = R.drawable.ic_crown_accent
    override val titleRes = R.string.daily_limit_sheet_title
    override val subtitleRes: Int get() = kind.subtitleRes
    override val primaryLabelRes = R.string.daily_limit_sheet_upgrade
    override val secondaryLabelRes = R.string.daily_limit_sheet_maybe_later

    /**
     * Leading button icons. The base class recolours each to its button's own
     * text colour, so ic_crown is reused as-is on the accent-filled primary
     * rather than needing the ic_crown_accent variant the sheet's header disc
     * uses.
     *
     * ic_play_circle is the only new drawable here — nothing in the set read
     * as "video". Close on "Maybe Later" marks it as the dismiss without
     * competing with the two real choices above it.
     */
    override val primaryIconRes = R.drawable.ic_crown
    override val tertiaryIconRes = R.drawable.ic_play_circle
    override val secondaryIconRes = R.drawable.ic_close

    /**
     * The rewarded-ad option — a middle path between the wall and a
     * subscription. Stated as an explicit value exchange in the label
     * itself, which is what AdMob requires of a rewarded placement.
     *
     * `get() =`, not `=`. Anything reading [kind] must be a custom getter:
     * a property initializer runs inside the constructor, and [show] can
     * only attach the arguments *after* constructing, so an eager
     * `= when (kind)` here threw IllegalStateException from
     * requireArguments() before the sheet could open at all — for both
     * kinds, not just one. [subtitleRes] above is the same shape for the
     * same reason.
     */
    override val tertiaryLabelRes: Int? get() = when (kind) {
        Kind.LIKES -> R.string.daily_limit_watch_ad_like
        Kind.MESSAGES -> R.string.daily_limit_watch_ad_message
    }

    override fun onPrimary() {
        (activity as? Host)?.onUpgradeFromDailyLimitRequested()
    }

    override fun onTertiary() {
        (activity as? Host)?.onWatchAdForBonusRequested(kind)
    }

    private val tickHandler = Handler(Looper.getMainLooper())

    /**
     * Drives the rewarded cooldown countdown on the Watch Ad button.
     *
     * Re-reads [RewardedCooldownPrefs] every second rather than counting
     * down from a captured value, so the label stays honest if the sheet
     * outlives a config change or the clock moves underneath it. At zero it
     * re-enables the button in place — no dismiss-and-reopen needed.
     *
     * The countdown is global across both quotas, so the copy says "another
     * ad" rather than naming likes or messages: hitting the message wall
     * shortly after earning a like bonus is a normal way to land here, and
     * "another message in 8:32" would be wrong in that case.
     */
    private val tick = object : Runnable {
        override fun run() {
            val uid = FirebaseAuth.getInstance().realUid
            val remaining = if (uid == null) {
                0L
            } else {
                RewardedCooldownPrefs.remainingMs(requireContext(), uid)
            }

            if (remaining <= 0L) {
                setTertiaryState(enabled = true)
                return
            }

            setTertiaryState(
                enabled = false,
                label = getString(
                    R.string.rewarded_cooldown_button,
                    RewardedCooldownPrefs.formatRemaining(remaining)
                )
            )
            tickHandler.postDelayed(this, 1000L)
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        // Runs inline rather than posted, so the button is already in its
        // correct state on the sheet's first frame — a Watch Ad button that
        // renders enabled and then blinks into a countdown reads as a bug.
        tick.run()
    }

    override fun onDestroyView() {
        tickHandler.removeCallbacksAndMessages(null)
        super.onDestroyView()
    }

    companion object {
        private const val ARG_KIND = "kind"
        private const val TAG = "daily_limit_reached"

        fun show(fragmentManager: FragmentManager, kind: Kind) {
            DailyLimitReachedBottomSheet()
                .apply { arguments = bundleOf(ARG_KIND to kind.name) }
                .show(fragmentManager, TAG)
        }
    }
}
