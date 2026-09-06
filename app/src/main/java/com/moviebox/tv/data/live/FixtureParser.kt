package com.moviebox.tv.data.live

import java.text.Normalizer

/**
 * Pulls the competition and the two sides out of a schedule event title.
 *
 * The catalog publishes titles like:
 *
 *     "⚽ England - Premier League : 🇬🇧 Hull City 🇬🇧 vs 🇬🇧 Aston Villa"
 *     "🏈 College Football : 🇺🇸 Kent State 🇺🇸 vs. 🇺🇸 South Carolina 🇺🇸"
 *
 * — sport emoji, a competition, a colon, then two flag-wrapped team names
 * around a "vs". Measured against a live 701-event payload, 87% of titles
 * split cleanly into two sides; the remainder are genuinely not fixtures
 * ("Big Brother 28 Live Feeds Cam 1", "Formula 1 Gran Premio de España |
 * Madrid, Spain | 11 – 13 September 2026") and are left as a single
 * "side" so they can still be followed as a plain title.
 *
 * Why parse at all instead of substring-matching the raw title: following
 * "Manchester United" must not fire for a Manchester City fixture. On the
 * same payload a naive substring match hit 2 events for "Manchester";
 * matching against a parsed side hit exactly the 1 where the club is
 * actually playing. See [FollowMatcher].
 */
object FixtureParser {

    /** Splits the two sides. Covers "vs", "vs.", "v", "v.", and "@". */
    private val VS = Regex("""\s+(?:vs\.?|v\.?|@)\s+""", RegexOption.IGNORE_CASE)
    private val NON_ALNUM = Regex("""[^a-z0-9 ]""")
    private val SPACES = Regex("""\s+""")
    /** Club-name noise that varies between sources: "FC Porto" / "Porto FC". */
    private val CLUB_AFFIX = Regex("""^(?:fc|afc|sc|ac|as|ss|cf|cd|sv|bk|if)\s+|\s+(?:fc|afc|sc|ac|cf)$""")

    /**
     * Common short forms, so a user typing what they'd actually say
     * ("Man Utd") follows the club the catalog spells out in full. Keys
     * and values are already [normalise]d.
     */
    private val ALIASES: Map<String, String> = mapOf(
        "man united" to "manchester united",
        "man utd" to "manchester united",
        "manchester utd" to "manchester united",
        "man u" to "manchester united",
        "mufc" to "manchester united",
        "man city" to "manchester city",
        "mcfc" to "manchester city",
        "spurs" to "tottenham hotspur",
        "tottenham" to "tottenham hotspur",
        "wolves" to "wolverhampton wanderers",
        "newcastle" to "newcastle united",
        "leeds" to "leeds united",
        "west ham" to "west ham united",
        "brighton" to "brighton hove albion",
        "psg" to "paris saint germain",
        "bayern" to "bayern munich",
        "barca" to "barcelona",
        "real" to "real madrid",
        "atletico" to "atletico madrid",
        "inter" to "inter milan",
        "juve" to "juventus",
        "dortmund" to "borussia dortmund",
        "bvb" to "borussia dortmund",
    )

    /**
     * Drops flags, emoji and variation selectors, which appear both around
     * team names and as a leading sport marker. Iterates code points, not
     * chars — every one of these lives outside the BMP, so a naive char
     * loop would leave orphaned surrogates behind.
     */
    fun stripMarks(s: String): String {
        val sb = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            val cp = s.codePointAt(i)
            val w = Character.charCount(cp)
            val drop = (cp in 0x1F1E6..0x1F1FF) || // regional indicators (flags)
                (cp in 0x1F300..0x1FAFF) ||        // emoji blocks
                (cp in 0x2600..0x27BF) ||          // misc symbols / dingbats
                cp == 0xFE0F || cp == 0xFE0E || cp == 0x200D
            if (!drop) sb.appendCodePoint(cp)
            i += w
        }
        return sb.toString()
    }

    /** Lowercase, de-accent, alphanumerics only — the comparison form. */
    fun normalise(s: String): String {
        val stripped = stripMarks(s).lowercase()
        val decomposed = Normalizer.normalize(stripped, Normalizer.Form.NFKD)
        // Drop the combining diacritics NFKD just split off, so
        // "Beşiktaş" and "Besiktas" compare equal.
        val deAccented = decomposed.filter { it.code !in 0x300..0x36F }
        return SPACES.replace(NON_ALNUM.replace(deAccented, " "), " ").trim()
    }

    /**
     * The identity a follow is stored and compared under: normalised, with
     * club affixes and known short forms folded in. "Man Utd", "FC
     * Manchester United" and "Manchester United" all land on the same key.
     */
    fun canonical(s: String): String {
        val n = CLUB_AFFIX.replace(normalise(s), "").trim()
        return ALIASES[n] ?: n
    }

    /** Competition + sides for one title. */
    data class Fixture(val competition: String, val sides: List<String>) {
        /** True when this really is A-vs-B rather than a standalone show. */
        val isFixture: Boolean get() = sides.size == 2
    }

    fun parse(title: String): Fixture {
        val t = stripMarks(title).trim()
        val colon = t.indexOf(':')
        val competition: String
        val fixture: String
        if (colon >= 0) {
            competition = t.substring(0, colon).trim().trim('-', '–', '—', ' ')
            fixture = t.substring(colon + 1).trim()
        } else {
            competition = ""
            fixture = t
        }
        val sides = VS.split(fixture)
            .map { SPACES.replace(it, " ").trim().trim('-', '–', '—', ',', ' ') }
            .filter { it.isNotEmpty() }
        return Fixture(competition, sides)
    }
}
