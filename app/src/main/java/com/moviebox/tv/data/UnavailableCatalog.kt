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
    private val cached = ConcurrentHashMap.newKeySet<String>()

    fun init(context: Context) {
        if (::prefs.isInitialized) return
        prefs = context.applicationContext
            .getSharedPreferences("unavailable_catalog", Context.MODE_PRIVATE)
        prefs.getStringSet(KEY, emptySet())?.let { cached.addAll(it) }
    }

    fun mark(subjectId: String) {
        if (subjectId.isBlank()) return
        if (cached.add(subjectId)) save()
    }

    fun isUnavailable(subjectId: String): Boolean = subjectId in cached

    fun clear() { cached.clear(); save() }

    fun size(): Int = cached.size

    private fun save() {
        if (::prefs.isInitialized) {
            prefs.edit().putStringSet(KEY, cached.toSet()).apply()
        }
    }

    private const val KEY = "unavailable"
}
