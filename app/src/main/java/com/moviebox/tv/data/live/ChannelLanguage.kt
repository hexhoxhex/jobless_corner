package com.moviebox.tv.data.live

/**
 * Guesses the commentary language of a feed from its channel name.
 *
 * Needed because auto-switch picks purely on measured headroom, and the
 * healthiest mirror of a Premier League match is regularly "DAZN1 Spain"
 * or "Canal+ Extra 1 Poland" — a stream that works perfectly and is
 * useless to someone who wants English commentary. Switching a viewer
 * into another language without asking is its own kind of failure.
 *
 * The catalog has no language field, so this reads the country/market
 * suffix the names carry ("Sky Sports Main Event UK", "ESPN Argentina",
 * "beIN Sports MENA English 1"). That is a heuristic, not metadata: it is
 * used only to ORDER otherwise-acceptable candidates, never to hide a feed
 * or to reject the only working one.
 */
object ChannelLanguage {

    /** Markets whose feeds carry English commentary. */
    private val ENGLISH_MARKERS = listOf(
        "uk", "usa", "us", "england", "english", "ireland", "irish",
        "australia", "aussie", "new zealand", "nz", "canada", "scotland",
        "wales", "south africa", "nigeria", "kenya", "india", "singapore",
        "malaysia", "philippines", "caribbean",
    )

    /** Markets that are reliably NOT English. Deliberately explicit rather
     *  than "anything not in the English list" — an unknown name should be
     *  treated as unknown, not as foreign. */
    private val NON_ENGLISH_MARKERS = listOf(
        "spain", "espana", "españa", "espanol", "español", "latino",
        "portugal", "brazil", "brasil", "france", "french", "germany",
        "deutschland", "italia", "italy", "poland", "polska", "turkey",
        "turkiye", "türkiye", "greece", "greek", "serbia", "croatia",
        "slovenia", "bulgaria", "romania", "czech", "slovakia", "hungary",
        "denmark", "sweden", "norway", "finland", "netherlands", "belgium",
        "russia", "ukraine", "israel", "arabia", "mena", "qatar", "egypt",
        "argentina", "mexico", "chile", "colombia", "japan", "korea",
        "china", "thailand", "vietnam", "indonesia", "cyprus", "albania",
        "macedonia", "bosnia", "montenegro", "austria", "switzerland",
    )

    /** Trailing market codes the catalog uses ("Sky Sport Calcio IT",
     *  "Canal+ Sport SK"). Checked only as the LAST token so "it" inside
     *  a word can never match. */
    private val NON_ENGLISH_CODES = setOf(
        "de", "it", "fr", "es", "pt", "pl", "tr", "gr", "rs", "hr",
        "si", "bg", "ro", "cz", "sk", "hu", "dk", "se", "no", "fi",
        "nl", "be", "ru", "ua", "il", "jp", "kr", "cn", "br", "mx",
        "ar", "at", "ch", "cy", "al", "mk", "ba", "me",
    )

    /** Brands whose commentary language is reliable regardless of any
     *  market suffix. Measured against the real catalog: "Telemundo" and
     *  "Movistar Liga de Campeones" carry no country word, so without
     *  these they scored UNKNOWN and could out-rank a genuinely neutral
     *  feed. Kept short and only where the brand really is single-language.
     *  Note "Sky Sports" (UK) vs "Sky Sport" (DE/IT/NZ) — the plural is
     *  the British one. */
    private val ENGLISH_BRANDS = listOf(
        "sky sports", "supersport", "stan sport", "tnt sports",
    )
    private val NON_ENGLISH_BRANDS = listOf(
        "movistar", "telemundo", "univision", "tudn", "deportes",
        "sportv", "globo", "tyc", "esporte", "eleven sports",
    )

    enum class Guess { ENGLISH, OTHER, UNKNOWN }

    fun guess(channelName: String?): Guess {
        val n = (channelName ?: "").lowercase()
        if (n.isBlank()) return Guess.UNKNOWN
        // "beIN Sports MENA English 1" is an English feed in a non-English
        // market, and the explicit word wins over the market suffix.
        if (n.contains("english")) return Guess.ENGLISH
        val lastToken = n.trim().substringAfterLast(' ')
        if (lastToken in NON_ENGLISH_CODES) return Guess.OTHER
        // Brand beats absence-of-market: "Telemundo" names no country but
        // is not an English feed.
        if (NON_ENGLISH_BRANDS.any { n.contains(it) }) return Guess.OTHER
        if (ENGLISH_BRANDS.any { n.contains(it) }) return Guess.ENGLISH
        val nonEnglish = NON_ENGLISH_MARKERS.any { marker(n, it) }
        val english = ENGLISH_MARKERS.any { marker(n, it) }
        return when {
            nonEnglish && !english -> Guess.OTHER
            english && !nonEnglish -> Guess.ENGLISH
            // Both or neither matched — say so rather than guessing.
            else -> Guess.UNKNOWN
        }
    }

    /** Word-boundary-ish containment: "us" must not match "russia". */
    private fun marker(haystack: String, needle: String): Boolean {
        var from = 0
        while (true) {
            val i = haystack.indexOf(needle, from)
            if (i < 0) return false
            val beforeOk = i == 0 || !haystack[i - 1].isLetterOrDigit()
            val after = i + needle.length
            val afterOk = after >= haystack.length || !haystack[after].isLetterOrDigit()
            if (beforeOk && afterOk) return true
            from = i + 1
        }
    }

    /** Ranking preference: English first, unknown next, other last. */
    fun preference(channelName: String?): Int = when (guess(channelName)) {
        Guess.ENGLISH -> 0
        Guess.UNKNOWN -> 1
        Guess.OTHER -> 2
    }
}
