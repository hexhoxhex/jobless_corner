package com.moviebox.tv.data.live

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Sorts fixtures into the leagues people actually name, with everything
 * else in one honest bucket.
 *
 * A day's schedule is 794 events across **169 distinct competitions** —
 * Romanian Liga II, J1 League, Women's College Volleyball, Davis Cup. Listed
 * flat, the Premier League match someone opened the app for is buried among
 * them. The catalog's own `category` doesn't help: nearly every football
 * fixture arrives under one heading, "All Soccer Events" (419 of today's
 * events), so the grouping has to come from the competition inside the title.
 *
 * Matching is on the competition string the catalog publishes, which is
 * stable and explicit — "England - Premier League", "Germany - Bundesliga",
 * "France - Ligue 1". That precision matters: a bare "premier league" match
 * would sweep in the Ukrainian, Russian and Israeli Premier Leagues, so the
 * top-flight English entry requires the country with it.
 *
 * [customLeagues] are the viewer's own groupings and are matched BEFORE the
 * built-ins, so someone who makes a "My teams" league that mentions
 * Bundesliga gets their own grouping rather than ours.
 */
object LeagueCatalog {

    data class League(
        val key: String,
        val name: String,
        /** Normalised substrings; any hit assigns the league. */
        val patterns: List<String>,
        val custom: Boolean = false,
    )

    /** Where anything unmatched lands. Never persisted, never editable. */
    val OTHER = League("other", "Other leagues", emptyList())

    private const val PREFS = "leagues"
    private const val KEY_CUSTOM = "custom"

    /**
     * The well-known ones, best-known first — this is also the display order.
     *
     * English second tier and below are one "EFL" group on purpose: that is
     * how people refer to them, and splitting Championship / League One /
     * League Two three ways would recreate the clutter this fixes.
     */
    private val BUILT_IN: List<League> = listOf(
        League("epl", "Premier League", listOf(
            "england - premier league", "english premier league",
        )),
        League("efl", "EFL", listOf(
            "england - championship", "england - league one",
            "england - league two", "efl", "carabao cup",
        )),
        League("laliga", "La Liga", listOf(
            "spain - la liga", "spain - laliga", "spain - primera division",
            "laliga",
        )),
        // "bundesliga" alone is too greedy — measured against a live day it
        // also caught Austria's Bundesliga, a HANDBALL Bundesliga and the
        // Frauen-Bundesliga. Requiring the country keeps the 15 German
        // men's fixtures and drops the other four.
        League("bundesliga", "Bundesliga", listOf(
            "germany - bundesliga", "2 bundesliga",
        )),
        League("seriea", "Serie A", listOf("italy - serie a")),
        League("ligue1", "Ligue 1", listOf("france - ligue 1")),
        League("ucl", "Champions League", listOf("champions league")),
        League("uel", "Europa League", listOf("europa league")),
        League("uecl", "Conference League", listOf("conference league")),
        League("eredivisie", "Eredivisie", listOf("eredivisie")),
        League("primeira", "Primeira Liga", listOf(
            "portugal - primeira liga", "primeira liga",
        )),
        League("scottish", "Scottish Premiership", listOf("scottish premiership")),
        League("mls", "MLS", listOf("major league soccer", "usa - mls", " mls")),
        League("saudi", "Saudi Pro League", listOf("saudi pro league")),
        League("intl", "Internationals", listOf(
            "world cup", "euro qualif", "nations league", "friendlies",
            "africa cup of nations", "copa america",
        )),
    )

    @Volatile private var custom: List<League> = emptyList()
    @Volatile private var loaded = false

    /** Strip flags, emoji and punctuation so matching is about the words. */
    fun normalise(s: String): String {
        val sb = StringBuilder(s.length)
        for (ch in s.lowercase()) {
            val code = ch.code
            // Regional-indicator flags, the invisible TAG block used for
            // the England/Scotland flags, and emoji all carry no meaning
            // for matching and vary between rows.
            val isEmojiish = code in 0x1F000..0x1FAFF || code in 0x2600..0x27BF ||
                code == 0xFE0F || code in 0xE0000..0xE007F
            when {
                isEmojiish -> sb.append(' ')
                ch.isLetterOrDigit() || ch == '-' || ch == ' ' -> sb.append(ch)
                else -> sb.append(' ')
            }
        }
        return sb.toString().replace(Regex("\\s+"), " ").trim()
    }

    private fun ensureLoaded() {
        if (loaded) return
        synchronized(this) {
            if (loaded) return
            custom = runCatching {
                val raw = com.moviebox.tv.App.instance
                    .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .getString(KEY_CUSTOM, null) ?: return@runCatching emptyList()
                val arr = JSONArray(raw)
                (0 until arr.length()).mapNotNull { i ->
                    val o = arr.optJSONObject(i) ?: return@mapNotNull null
                    val name = o.optString("name").takeIf { it.isNotBlank() }
                        ?: return@mapNotNull null
                    val pats = o.optJSONArray("patterns")
                    val patterns = (0 until (pats?.length() ?: 0))
                        .mapNotNull { j -> pats?.optString(j)?.takeIf { it.isNotBlank() } }
                        .map(::normalise)
                    League(
                        key = o.optString("key").ifBlank { keyFor(name) },
                        name = name,
                        patterns = patterns.ifEmpty { listOf(normalise(name)) },
                        custom = true,
                    )
                }
            }.getOrDefault(emptyList())
            loaded = true
        }
    }

    private fun keyFor(name: String): String =
        "custom_" + normalise(name).replace(' ', '_').take(24)

    private fun save() {
        runCatching {
            val arr = JSONArray()
            custom.forEach { l ->
                arr.put(
                    JSONObject()
                        .put("key", l.key)
                        .put("name", l.name)
                        .put("patterns", JSONArray(l.patterns)),
                )
            }
            com.moviebox.tv.App.instance
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putString(KEY_CUSTOM, arr.toString()).apply()
        }
    }

    /** Built-ins plus the viewer's own, in display order. */
    fun all(): List<League> {
        ensureLoaded()
        return custom + BUILT_IN
    }

    fun customOnly(): List<League> {
        ensureLoaded()
        return custom
    }

    /**
     * Which league this competition belongs to, or null for [OTHER].
     * The viewer's own groupings win over ours.
     */
    fun leagueFor(competition: String): League? {
        if (competition.isBlank()) return null
        ensureLoaded()
        val comp = normalise(competition)
        if (comp.isEmpty()) return null
        return (custom + BUILT_IN).firstOrNull { league ->
            league.patterns.any { it.isNotBlank() && comp.contains(it) }
        }
    }

    /**
     * Is this event football at all?
     *
     * Leagues only make sense for football; tennis, baseball and college
     * volleyball keep the heading the catalog gave them. Three signals,
     * because no single one covers the day: most titles open with the ball
     * emoji (382 of 794 measured), a few sit under a "Soccer" category
     * without it, and anything whose competition we recognise as a league
     * is football by definition.
     */
    fun isFootball(title: String, category: String?): Boolean {
        if (title.trimStart().startsWith("\u26BD")) return true
        val cat = (category ?: "").lowercase()
        if (cat.contains("soccer")) return true
        val comp = title.substringBefore(':', "")
        return comp.isNotBlank() && leagueFor(comp) != null
    }

    /**
     * The heading an event should appear under: its league when it is
     * football, otherwise the catalog's own category untouched.
     */
    fun groupFor(title: String, category: String?): String {
        if (!isFootball(title, category)) return category.orEmpty()
        val comp = title.substringBefore(':', "")
        return leagueFor(comp)?.name ?: OTHER.name
    }

    /** Add one of the viewer's own leagues. Returns it, or null if invalid. */
    fun addCustom(name: String, patterns: List<String>): League? {
        val clean = name.trim()
        if (clean.isBlank()) return null
        ensureLoaded()
        val pats = patterns.map(::normalise).filter { it.isNotBlank() }
            .ifEmpty { listOf(normalise(clean)) }
        val league = League(keyFor(clean), clean, pats, custom = true)
        synchronized(this) {
            custom = custom.filterNot { it.key == league.key } + league
            save()
        }
        return league
    }

    fun removeCustom(key: String): Boolean {
        ensureLoaded()
        synchronized(this) {
            val before = custom.size
            custom = custom.filterNot { it.key == key }
            if (custom.size == before) return false
            save()
        }
        return true
    }
}
