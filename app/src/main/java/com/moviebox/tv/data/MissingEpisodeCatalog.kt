package com.moviebox.tv.data

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
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

    /** Reactive view of the marked-missing set. Compose surfaces
     *  (the episode picker) collectAsState() this so they re-render the
     *  instant an episode is marked missing after a failed play — the
     *  user navigates back to details and the phantom episode is gone,
     *  no app restart needed. Emits a fresh immutable snapshot on every
     *  change. */
    private val _flow = MutableStateFlow<Set<String>>(emptySet())
    val flow: StateFlow<Set<String>> = _flow

    fun init(context: Context) {
        if (::prefs.isInitialized) return
        prefs = context.applicationContext
            .getSharedPreferences("missing_episodes", Context.MODE_PRIVATE)
        prefs.getStringSet(KEY, emptySet())?.let {
            cached.addAll(it)
            _flow.value = cached.toSet()
        }
    }

    private fun key(subjectId: String, season: Int, episode: Int): String =
        "$subjectId:$season:$episode"

    fun mark(subjectId: String, season: Int, episode: Int) {
        if (subjectId.isBlank()) return
        if (cached.add(key(subjectId, season, episode))) {
            _flow.value = cached.toSet()
            save()
        }
    }

    fun isMissing(subjectId: String, season: Int, episode: Int): Boolean =
        key(subjectId, season, episode) in cached

    /** True if [subjectId] season [season] episode [episode] is NOT marked
     *  missing — convenience for filtering episode lists. */
    fun isPresent(subjectId: String, season: Int, episode: Int): Boolean =
        !isMissing(subjectId, season, episode)

    fun clear() {
        cached.clear()
        _flow.value = emptySet()
        save()
    }

    private fun save() {
        if (::prefs.isInitialized) {
            prefs.edit().putStringSet(KEY, cached.toSet()).apply()
        }
    }

    private const val KEY = "missing"
}
