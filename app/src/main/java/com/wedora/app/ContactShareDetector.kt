package com.wedora.app

/**
 * Detects attempts to share contact details in a chat message — phone
 * numbers, emails, links, and named third-party messaging apps.
 *
 * This is the client half of a two-layer check: it produces the in-app
 * warning and blocks the send before anything is written, and
 * firestore.rules re-runs an equivalent (deliberately simpler) check so a
 * modified client that skips this one still can't write the message. See
 * that file's own hasNoContactInfo() for what the rules layer can and can't
 * reproduce.
 *
 * Every threshold here is tuned to UNDER-detect rather than over-block. The
 * product decision is that a flagged message is blocked outright with no
 * "send anyway" override, which makes a false positive a hard wall in front
 * of a legitimate message — much more costly than letting a determined user
 * through, since they still have to get past the rules layer and leave a
 * logged attempt behind either way (see [Moderation.CONTACT_ATTEMPTS_COLLECTION]).
 */
object ContactShareDetector {

    /** Which detector fired — stored on the logged attempt for moderation. */
    enum class Category(val id: String) {
        PHONE("phone"),
        EMAIL("email"),
        URL("url"),
        APP_NAME("app_name")
    }

    /**
     * Consecutive digits (after separators are stripped) that read as a phone
     * number rather than an ordinary quantity.
     *
     * 9, deliberately above the two things that collide below it. A 7-digit
     * run catches plain PKR amounts like "1500000", entirely plausible when
     * salary or expenses come up; an 8-digit run catches any all-numeric
     * date once hyphens are stripped ("2024-01-15" and "12-25-2024" both
     * collapse to 8), and birthdays and wedding dates are core subject
     * matter on a marriage app. Blocking either outright — with no
     * send-anyway override — is worse than missing the rarer 7-to-8-digit
     * landline.
     *
     * Still catches every realistic mobile number: Pakistani 03xx (11),
     * international +92 (12), and 10-digit formats.
     */
    private const val MIN_DIGIT_RUN = 9

    /**
     * Consecutive spelled-out digits ("nine eight seven ...") that read as a
     * dictated phone number. Lower than [MIN_DIGIT_RUN] because the false
     * positive rate is effectively nil — ordinary writing never strings six
     * number-words together with nothing but punctuation between them.
     */
    private const val MIN_SPELLED_RUN = 6

    /**
     * Stripped before counting digit runs, so "0300-123 4567" and
     * "(0300) 1234567" both collapse to one run.
     *
     * Deliberately does NOT include "," or "/": stripping those would merge
     * "1,500,000" and "12/25/2024" into long runs and block them. "-" IS
     * stripped, since not stripping it would miss the very common
     * "0300-1234567" form entirely — that's what pushes an all-numeric date
     * to 8 digits, and why [MIN_DIGIT_RUN] sits at 9 rather than 8.
     */
    private val SEPARATORS = Regex("""[ .()\-]""")

    private val EMAIL = Regex("""[a-z0-9._%+-]+@[a-z0-9.-]+\.[a-z]{2,}""")

    private val URL_SCHEME = Regex("""(https?://|www\.)""")

    /**
     * A closed TLD list on purpose. An open `\.[a-z]{2,}` would fire on
     * "M.Sc", "U.S.A", and any sentence missing a space after a full stop
     * ("store.Then"), all of which are ordinary text.
     */
    private val BARE_DOMAIN =
        Regex("""\.(com|net|org|io|me|co|ly|gg|app|link|xyz|info|site|online|shop|store)\b""")

    /**
     * Unambiguous brand names only. Deliberately excluded: signal, line,
     * imo, snap, zoom, discord and meet — every one is ordinary English
     * ("I'll signal you", "drop me a line", "IMO", "snap out of it"), and
     * with no send-anyway override, matching them would make those sentences
     * unsendable. The cost is that "add me on signal" passes; the moment a
     * handle or number follows it, the other detectors catch it.
     *
     * Word boundaries are load-bearing: a bare "insta" substring would fire
     * on "instant", "installation" and "instance".
     */
    private val APP_NAMES = Regex(
        """\b(whatsapp|whats ?app|wa\.me|instagram|insta|telegram|t\.me|snapchat|viber|wechat|tiktok|skype|messenger)\b"""
    )

    private const val DIGIT_WORD =
        "(?:zero|one|two|three|four|five|six|seven|eight|nine|oh)"

    private val SPELLED_RUN =
        Regex("""\b(?:$DIGIT_WORD[^a-z0-9]{0,3}){${MIN_SPELLED_RUN - 1},}$DIGIT_WORD\b""")

    private val DIGIT_RUN = Regex("""[0-9]{$MIN_DIGIT_RUN,}""")

    /**
     * Every category [text] trips, or an empty set when it's clean. A
     * message can trip several at once (an email contains a bare domain, for
     * instance) — all of them are recorded rather than just the first, since
     * the point of the log is spotting patterns across repeat attempts.
     */
    fun detect(text: String): Set<Category> {
        val normalized = normalizeDigits(text).lowercase()
        val found = mutableSetOf<Category>()

        if (EMAIL.containsMatchIn(normalized)) found += Category.EMAIL
        if (URL_SCHEME.containsMatchIn(normalized) || BARE_DOMAIN.containsMatchIn(normalized)) {
            found += Category.URL
        }
        if (APP_NAMES.containsMatchIn(normalized)) found += Category.APP_NAME

        // Separators are stripped only for this check — doing it before the
        // email/URL checks above would destroy the dots they depend on.
        val compact = normalized.replace(SEPARATORS, "")
        if (DIGIT_RUN.containsMatchIn(compact) || SPELLED_RUN.containsMatchIn(normalized)) {
            found += Category.PHONE
        }

        return found
    }

    /**
     * Folds Arabic-Indic (٠-٩) and Extended Arabic-Indic (۰-۹) digits down to
     * ASCII so they count toward a run.
     *
     * This is the one detection step the rules layer genuinely cannot
     * reproduce — `[0-9]` there won't match them and the alternative is an
     * unwieldy pattern — which matters more than usual for this app's
     * audience. A message using them is blocked here but WOULD pass the
     * server-side backstop if the client check were bypassed.
     *
     * Leetspeak (o->0, l->1) is deliberately NOT folded: "loooooool" would
     * become a 10-digit run and get a perfectly ordinary message blocked.
     */
    private fun normalizeDigits(text: String): String {
        if (text.none { it in '٠'..'٩' || it in '۰'..'۹' }) return text
        val out = StringBuilder(text.length)
        for (ch in text) {
            out.append(
                when (ch) {
                    in '٠'..'٩' -> '0' + (ch - '٠')
                    in '۰'..'۹' -> '0' + (ch - '۰')
                    else -> ch
                }
            )
        }
        return out.toString()
    }
}
