package com.wedora.app

import java.util.Locale

/**
 * The canonical country list, plus the matching needed to make sense of the
 * free-text values already stored on profiles.
 *
 * Built from [Locale.getISOCountries] rather than a hand-typed list or a
 * dependency: the platform already ships ISO 3166-1, and deriving names from it
 * means the list cannot drift out of date or contain typos.
 *
 * WHAT IS STORED IS THE ENGLISH NAME, not the ISO code. The code would be the
 * better format in the abstract - stable, locale-independent, and what the flag
 * is derived from anyway - but the data decided it. Of the profiles that have a
 * country today, roughly half already hold exactly a canonical English name
 * ("Pakistan", "India", "Bangladesh"), so names keep those working untouched,
 * keep ageLocationLine rendering "Islamabad, Pakistan" rather than "Islamabad,
 * PK", and keep LocationResolver's forward-geocode call working as it is.
 * Switching to codes would have made every existing row non-matching until a
 * migration ran, with mixed "PK"/"Pakistan" data broken in both directions
 * meanwhile.
 */
object Countries {

    /**
     * Canonical English names, sorted. Some ISO codes have no display name on a
     * given device (the platform returns the code back), and those are dropped
     * rather than shown as two-letter rows.
     */
    val names: List<String> by lazy {
        Locale.getISOCountries()
            .mapNotNull { code -> displayName(code)?.let { it } }
            .distinct()
            .sorted()
    }

    /** Normalised name -> ISO code, for every canonical name. */
    private val codeByName: Map<String, String> by lazy {
        Locale.getISOCountries()
            .mapNotNull { code -> displayName(code)?.let { normalise(it) to code } }
            .toMap()
    }

    /**
     * Known variants of canonical names, normalised on both sides.
     *
     * Deliberately a short curated map rather than a sweep over every installed
     * locale's display names. That sweep would catch more - the stored data
     * holds Arabic, Bengali and Albanian country names, written there by the
     * device geocoder rather than by users - but it means hundreds of locales
     * times hundreds of countries built before the first match can be tested,
     * which is far too much work to put behind a feed filter. The localized
     * stragglers are one or two profiles each and resolve themselves the next
     * time those users open Edit Profile.
     *
     * The entries here are the ones that actually appear in the data, plus the
     * obvious English variants around them.
     */
    private val ALIASES: Map<String, String> by lazy {
        mapOf(
            "usa" to "US",
            "u s a" to "US",
            "united states of america" to "US",
            "america" to "US",
            "uk" to "GB",
            "u k" to "GB",
            "great britain" to "GB",
            "britain" to "GB",
            "england" to "GB",
            "scotland" to "GB",
            "wales" to "GB",
            "northern ireland" to "GB",
            "turkey" to "TR",
            "cameroun" to "CM",
            "ivory coast" to "CI",
            "holland" to "NL",
            "south korea" to "KR",
            "north korea" to "KP",
            "russia" to "RU",
            "vietnam" to "VN",
            "uae" to "AE",
            "drc" to "CD",
            "congo kinshasa" to "CD",
            "burma" to "MM",
            "czech republic" to "CZ",
            "swaziland" to "SZ",
            "macedonia" to "MK"
        )
    }

    /** English display name for an ISO code, or null when the device has none. */
    private fun displayName(code: String): String? =
        Locale.Builder().setRegion(code).build()
            .getDisplayCountry(Locale.ENGLISH)
            .takeIf { it.isNotBlank() && !it.equals(code, ignoreCase = true) }

    /**
     * Lowercased, whitespace-collapsed, with the curly apostrophe folded onto
     * the straight one.
     *
     * That last part is not cosmetic: the stored data contains "Côte d'Ivoire"
     * with a typographic apostrophe while the platform's own name uses a
     * different one, so the two failed to match despite being the same string
     * to a reader.
     */
    private fun normalise(raw: String): String =
        raw.trim()
            .lowercase(Locale.ENGLISH)
            .replace('’', '\'')
            .replace(Regex("[\\s.]+"), " ")

    /** Every valid ISO code, uppercased, for the two-letter shortcut below. */
    private val isoCodes: Set<String> by lazy {
        Locale.getISOCountries().map { it.uppercase(Locale.ENGLISH) }.toSet()
    }

    /**
     * ISO code for any stored value - a canonical name, a known alias, or an
     * ISO code already.
     *
     * The two-letter branch is what lets LocationResolver hand this the
     * geocoder's countryCode directly, and it also means a stray code stored on
     * some profile still matches the right country rather than nothing.
     */
    fun codeFor(value: String?): String? {
        val raw = value?.trim() ?: return null
        if (raw.isEmpty()) return null
        if (raw.length == 2) {
            val upper = raw.uppercase(Locale.ENGLISH)
            if (upper in isoCodes) return upper
        }
        val key = normalise(raw)
        return codeByName[key] ?: ALIASES[key]
    }

    /** The canonical name for any stored value, or null when unrecognisable. */
    fun canonicalise(value: String?): String? =
        codeFor(value)?.let { displayName(it) }

    /**
     * Whether [stored] refers to the same country as [selected].
     *
     * This is what lets a filter for "United States" still match the profiles
     * holding "USA". An exact match short-circuits, so the common case costs
     * one string comparison; only a miss pays for the code lookup.
     *
     * Falls back to comparing the raw strings when neither side resolves to a
     * code, so a value the list does not know about can still match itself.
     */
    fun matches(stored: String?, selected: String): Boolean {
        if (stored == null) return false
        if (stored.equals(selected, ignoreCase = true)) return true
        val storedCode = codeFor(stored)
        val selectedCode = codeFor(selected)
        return storedCode != null && storedCode == selectedCode
    }

    /**
     * The flag emoji for a country name, or null when it cannot be resolved.
     *
     * Regional indicator symbols - each letter of the ISO code offset into the
     * A-Z indicator block. No assets and no library.
     *
     * Rendering is NOT guaranteed everywhere: some Android builds and OEM fonts
     * lack the flag glyphs and show a two-letter box instead. That degrades to
     * something legible rather than breaking, which is why this is preferred
     * over shipping ~250 drawables.
     */
    fun flagFor(name: String?): String? {
        val code = codeFor(name) ?: return null
        if (code.length != 2) return null
        val base = 0x1F1E6 - 'A'.code
        return String(Character.toChars(base + code[0].code)) +
            String(Character.toChars(base + code[1].code))
    }
}
