package com.moviebox.tv.data.live

/**
 * Which sport an event belongs to, so the schedule reads as sports rather
 * than as the catalog's 50 loose headings.
 *
 * A live day publishes headings like "Ice Hockey OHL", "Ice Hockey (NHL",
 * "Ice Hockey USHL" and "Ice Hockey Women's College Ice Hockey" — four
 * separate blocks for one sport — alongside "🎾 Davis Cup / 🎾 ATP Tour /
 * 🎾 WTA Sao Paulo / 🎾 WTA Guadalajara" as a single heading, "Baseball (MLB"
 * with its bracket left open, and "Motorsport" covering both cars and bikes.
 * Collapsing them onto one sport name is what makes the list scannable.
 *
 * Football is deliberately NOT collapsed here: it is the bulk of the day and
 * it gets split by league instead — see [LeagueCatalog].
 */
object SportCatalog {

    const val FOOTBALL = "Football"

    /**
     * Display order. Football's leagues come first (handled by the caller),
     * then these — roughly by how much of a schedule they usually occupy.
     */
    val ORDER: List<String> = listOf(
        FOOTBALL,
        "Motor sports",
        "Basketball",
        "Tennis",
        "American Football",
        "Ice Hockey",
        "Baseball",
        "Cricket",
        "Rugby",
        "Combat sports",
        "Golf",
        "Cycling",
        "Volleyball",
        "Handball",
        "Snooker & Darts",
        "Horse Racing",
        "Water sports",
        "Athletics",
        "TV & Entertainment",
    )

    /**
     * Keyword → sport. Order matters: the first hit wins, so the more
     * specific entries sit above the looser ones. "college football" has to
     * beat the bare "football" (which in this catalog means soccer), and
     * "ice hockey" has to beat "hockey".
     */
    private val RULES: List<Pair<List<String>, String>> = listOf(
        listOf(
            "formula 1", "formula1", "f1 ", "grand prix", "motogp", "moto gp",
            "moto2", "moto3", "nascar", "indycar", "rally", "superbike",
            "motorsport", "motor sport", "motocross", "speedway", "drag racing",
            "wec ", "le mans", "formula e",
        ) to "Motor sports",
        listOf(
            "college football", "nfl", "ncaa football", "american football",
            "cfl ", "xfl",
        ) to "American Football",
        listOf("ice hockey", "nhl", "khl", "ahl", " ohl", "ushl", "shl") to "Ice Hockey",
        listOf(
            "basketball", "nba", "wnba", "euroleague", "ncaa basketball",
        ) to "Basketball",
        listOf(
            "tennis", "atp", "wta", "davis cup", "billie jean", "wimbledon",
            "roland garros", "us open tennis",
        ) to "Tennis",
        listOf("baseball", "mlb", "npb") to "Baseball",
        listOf("cricket", "ipl ", "t20", "odi ", "the hundred") to "Cricket",
        listOf("rugby", "top 14", "super rugby", "nrl", "six nations") to "Rugby",
        listOf(
            "boxing", "mma", "ufc", "bellator", "wrestling", "wwe", "aew",
            "kickboxing", "judo", "karate",
        ) to "Combat sports",
        listOf("golf", "pga", "liv golf", "ryder cup") to "Golf",
        listOf("cycling", "tour de france", "giro", "vuelta", "velodrome") to "Cycling",
        listOf("volleyball", "beach volley") to "Volleyball",
        listOf("handball") to "Handball",
        listOf("snooker", "darts", "pool ", "billiards") to "Snooker & Darts",
        listOf("horse racing", "horseracing", "racing from", "greyhound") to "Horse Racing",
        listOf(
            "sailing", "boating", "rowing", "swimming", "surfing", "water polo",
            "diving",
        ) to "Water sports",
        listOf("athletics", "track and field", "marathon", "diamond league") to "Athletics",
        listOf(
            "big brother", "tv shows", "reality", "wwe raw", "talk show",
            "upcoming events",
        ) to "TV & Entertainment",
    )

    /**
     * The sport for an event, or null when nothing matches — in which case
     * the caller keeps the catalog's own heading rather than inventing one.
     */
    fun sportFor(title: String, category: String?): String? {
        if (LeagueCatalog.isFootball(title, category)) return FOOTBALL
        val hay = LeagueCatalog.normalise((category ?: "") + " " + title)
        if (hay.isBlank()) return null
        RULES.forEach { (keys, sport) ->
            if (keys.any { hay.contains(it) }) return sport
        }
        return null
    }

    /** Where a sport sits in [ORDER]; unknown sports sort last. */
    fun rank(sport: String?): Int {
        val i = ORDER.indexOf(sport ?: "")
        return if (i < 0) ORDER.size else i
    }
}
