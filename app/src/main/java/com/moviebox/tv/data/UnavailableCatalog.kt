package com.moviebox.tv.data

import android.content.Context
import android.content.SharedPreferences
import java.util.concurrent.ConcurrentHashMap

/**
 * Remembers TMDB ids we've tried to play but couldn't bridge to a real
 * aoneroom stream. The home then quietly drops them on subsequent loads so the
 * user doesn't keep bumping into dead clicks. Self-healing: a fresh install or
 * a `Clear unavailable` action wipes the set.
 */
object UnavailableCatalog {

    private lateinit var prefs: SharedPreferences

    /** subjectId -> when we failed to play it. */
    private val cached = ConcurrentHashMap<String, Long>()

    /**
     * How long a failure is allowed to hide a title.
     *
     * These marks used to be permanent: nothing ever called [clear] (verified
     * — it had no callers), so one bad afternoon hid a title from search and
     * the home rows for the life of the install. On 2026-09-18 the catalog
     * spent hours answering "invalid token" for EVERY title, and every play
     * attempted in that window marked its title dead forever. A failure is
     * evidence about a moment, not a permanent property of a film, so it now
     * expires and the title gets another chance.
     */
    private const val TTL_MS = 3L * 24 * 3600_000  // 3 days

    fun init(context: Context) {
        if (::prefs.isInitialized) return
        prefs = context.applicationContext
            .getSharedPreferences("unavailable_catalog", Context.MODE_PRIVATE)
        // KEY_V2 is a deliberate break from the old un-timestamped set: those
        // entries carry no date, most were recorded during the outage above,
        // and there is no way to tell a real dead title from collateral.
        // Dropping them once is the honest migration.
        val now = System.currentTimeMillis()
        prefs.getStringSet(KEY_V2, emptySet())?.forEach { entry ->
            val id = entry.substringBeforeLast('|')
            val at = entry.substringAfterLast('|').toLongOrNull() ?: return@forEach
            if (id.isNotBlank() && now - at < TTL_MS) cached[id] = at
        }
        if (prefs.contains(KEY)) prefs.edit().remove(KEY).apply()
    }

    fun mark(subjectId: String) {
        if (subjectId.isBlank()) return
        cached[subjectId] = System.currentTimeMillis()
        save()
    }

    fun isUnavailable(subjectId: String): Boolean {
        val at = cached[subjectId] ?: return false
        if (System.currentTimeMillis() - at >= TTL_MS) {
            cached.remove(subjectId)
            save()
            return false
        }
        return true
    }

    fun clear() { cached.clear(); save() }

    fun size(): Int = cached.size

    private fun save() {
        if (::prefs.isInitialized) {
            prefs.edit()
                .putStringSet(
                    KEY_V2,
                    cached.entries.map { "${it.key}|${it.value}" }.toSet(),
                )
                .apply()
        }
    }

    /** Legacy, un-timestamped. Read once at [init] only to delete it. */
    private const val KEY = "unavailable"
    private const val KEY_V2 = "unavailable_v2"
}
