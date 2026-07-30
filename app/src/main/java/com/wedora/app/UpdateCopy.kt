package com.wedora.app

import android.content.Context
import android.util.Log
import com.google.firebase.remoteconfig.FirebaseRemoteConfig
import com.google.firebase.remoteconfig.ktx.remoteConfigSettings
import org.json.JSONObject

/**
 * The update flow's remotely-controlled copy and its one server-side switch.
 *
 * Release notes and the forced-update reason live in Remote Config so wording
 * can be corrected after a release has already shipped — the copy for build N
 * cannot be edited by shipping build N+1, which is exactly when it matters.
 *
 * Everything here falls back to a bundled local default
 * (res/xml/remote_config_defaults.xml), so a fetch failure, a first launch, or
 * a project with no Remote Config set up at all still produces sensible
 * copy rather than blank space.
 */
object UpdateCopy {

    private const val TAG = "WedoraUpdate"

    /**
     * JSON object keyed by versionCode, e.g.
     * `{"10": ["Faster match loading", "Read receipts"]}` — keyed rather than a
     * flat list so notes stay attached to the build they describe when several
     * releases go out close together.
     */
    private const val KEY_RELEASE_NOTES = "update_release_notes"

    /**
     * JSON object mapping versionCode -> user-facing versionName, e.g.
     * `{"11": "1.2.0"}`.
     *
     * Needed because Play Core exposes only `availableVersionCode` for the
     * update it is offering — never its versionName. Without this the nudge
     * would have to label the download with the version the user is ALREADY on
     * ("Version 1.1.2"), which reads as being offered what they already have.
     * Keyed rather than a single string so a stale publish can't attach the
     * wrong name to a different build; unknown simply omits the name.
     */
    private const val KEY_VERSION_NAMES = "update_version_names"

    /** Plain string shown on the blocking screen; must state a concrete reason. */
    private const val KEY_FORCED_REASON = "update_forced_reason"

    /** Machine-readable tag for the reason, for analytics segmentation. */
    private const val KEY_FORCED_REASON_CODE = "update_forced_reason_code"

    /**
     * The kill switch.
     *
     * DANGER: any build whose versionCode is below this is blocked out of the
     * app entirely. Setting it above your live versionCode locks out the whole
     * install base, and the only recovery is another Remote Config publish —
     * the app cannot be used to fix it. Default is 0 (block nobody), and it
     * should only ever be raised to a versionCode that is already live and
     * confirmed rolled out.
     *
     * This is intentionally a floor on top of Play's own priority-5 signal, not
     * a replacement for it: Play's priority is set per-release at publish time
     * and cannot be changed afterwards, which is useless for a security issue
     * discovered later.
     */
    private const val KEY_MIN_SUPPORTED_VERSION = "update_min_supported_version"

    /** Notes shown when Remote Config has nothing for this versionCode. */
    private const val MAX_RELEASE_NOTES = 3

    private var config: FirebaseRemoteConfig? = null

    /**
     * Takes no Context: FirebaseRemoteConfig resolves itself from the already
     * initialised FirebaseApp, and the defaults come from a resource id. Kept
     * as an explicit attach() anyway so app startup reads as one list of
     * subsystems being brought up (see WedoraApplication.onCreate).
     */
    fun attach() {
        config = FirebaseRemoteConfig.getInstance().apply {
            setConfigSettingsAsync(
                remoteConfigSettings {
                    // The update surfaces are read on a Home resume, so an hour
                    // is frequent enough to correct copy the same day without
                    // fetching on every foreground.
                    minimumFetchIntervalInSeconds = 3600
                }
            )
            setDefaultsAsync(R.xml.remote_config_defaults)
            fetchAndActivate().addOnCompleteListener { task ->
                if (!task.isSuccessful) {
                    // Non-fatal by construction: every getter below falls back
                    // to the bundled defaults, so this only means "copy may be
                    // stale", never "the update flow is broken".
                    Log.w(TAG, "Remote Config fetch failed; using bundled defaults", task.exception)
                }
            }
        }
        // Deliberately not tied to the fetch completing — a first launch reads
        // defaults immediately rather than waiting on the network.
        Log.d(TAG, "UpdateCopy attached (defaults active)")
    }

    /**
     * Up to [MAX_RELEASE_NOTES] short lines describing [versionCode], or the
     * generic fallback list when Remote Config has nothing keyed for it. The
     * sheet caps at 3 lines; a longer list is truncated rather than scrolled,
     * because a scrolling release-note list on an interruption is a sign the
     * copy is wrong, not that the sheet needs to grow.
     */
    fun releaseNotes(context: Context, versionCode: Int): List<String> {
        val raw = config?.getString(KEY_RELEASE_NOTES).orEmpty()
        val parsed = runCatching {
            if (raw.isBlank()) return@runCatching emptyList<String>()
            val forVersion = JSONObject(raw).optJSONArray(versionCode.toString())
                ?: return@runCatching emptyList<String>()
            (0 until forVersion.length()).mapNotNull { forVersion.optString(it).takeIf(String::isNotBlank) }
        }.getOrElse {
            Log.w(TAG, "Malformed $KEY_RELEASE_NOTES; falling back to generic notes", it)
            emptyList()
        }

        return parsed.take(MAX_RELEASE_NOTES).ifEmpty {
            listOf(context.getString(R.string.update_notes_generic))
        }
    }

    /**
     * The user-facing versionName for [versionCode], or null when Remote Config
     * has no mapping for it. Callers must handle null by omitting the version
     * rather than substituting the installed one — see [KEY_VERSION_NAMES].
     */
    fun targetVersionName(versionCode: Int): String? {
        val raw = config?.getString(KEY_VERSION_NAMES).orEmpty()
        if (raw.isBlank()) return null
        return runCatching {
            JSONObject(raw).optString(versionCode.toString()).takeIf(String::isNotBlank)
        }.getOrElse {
            Log.w(TAG, "Malformed $KEY_VERSION_NAMES", it)
            null
        }
    }

    /** Concrete reason for a forced update. Never a bare "please update". */
    fun forcedReason(context: Context): String =
        config?.getString(KEY_FORCED_REASON)?.takeIf { it.isNotBlank() }
            ?: context.getString(R.string.update_required_reason_default)

    fun forcedReasonCode(): String =
        config?.getString(KEY_FORCED_REASON_CODE)?.takeIf { it.isNotBlank() } ?: "unspecified"

    /** See [KEY_MIN_SUPPORTED_VERSION]'s warning before ever raising this. */
    fun minSupportedVersion(): Int = (config?.getLong(KEY_MIN_SUPPORTED_VERSION) ?: 0L).toInt()
}
