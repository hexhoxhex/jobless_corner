package com.moviebox.tv.data

import android.content.Context
import android.content.SharedPreferences
import java.util.concurrent.ConcurrentHashMap

/**
 * Remembers (subjectId, season, episode) tuples that aoneroom advertised
 * via seasonInfo.maxEp but didn't actually have a file for when the user
 * tried to play. Same shape as [UnavailableCatalog] but at episode
 * granularity.
 *
 * Wire-up:
 *   - Mark on resolveEpisode failure (Repository).
 *   - Skip when picking the next/previous episode (PlayerScreen,
 *     MainViewModel).
 *   - Surface a clear "Episode unavailable" message instead of letting
 *     playback fail silently.
 */
object MissingEpisodeCatalog {

    private lateinit var prefs: SharedPreferences
    private val cached = ConcurrentHashMap.newKeySet<String>()

    fun init(context: Context) {
        if (::prefs.isInitialized) return
        prefs = context.applicationContext
            .getSharedPreferences("missing_episodes", Context.MODE_PRIVATE)
        prefs.getStringSet(KEY, emptySet())?.let { cached.addAll(it) }
    }

    private fun key(subjectId: String, season: Int, episode: Int): String =
        "$subjectId:$season:$episode"

    fun mark(subjectId: String, season: Int, episode: Int) {
        if (subjectId.isBlank()) return
        if (cached.add(key(subjectId, season, episode))) save()
    }

    fun isMissing(subjectId: String, season: Int, episode: Int): Boolean =
        key(subjectId, season, episode) in cached

    fun clear() { cached.clear(); save() }

    private fun save() {
        if (::prefs.isInitialized) {
            prefs.edit().putStringSet(KEY, cached.toSet()).apply()
        }
    }

    private const val KEY = "missing"
}
