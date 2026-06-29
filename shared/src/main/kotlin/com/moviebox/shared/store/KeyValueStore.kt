package com.moviebox.shared.store

/**
 * Tiny key-value abstraction for the shared H5 client to persist its
 * cookie/bearer cache across launches. On Android backed by
 * SharedPreferences, on Desktop by a properties file in
 * ~/.vijanabarubaru/cookies.properties.
 *
 * Read/write blocks the calling thread — fine for the tiny rows we
 * persist (one short JSON blob).
 */
interface KeyValueStore {
    fun getString(key: String): String?
    fun putString(key: String, value: String?)
}

/** In-memory fallback if no real store has been installed yet. Tests +
 *  the pre-onCreate window on Android use this. */
class InMemoryKeyValueStore : KeyValueStore {
    private val m = mutableMapOf<String, String>()
    override fun getString(key: String): String? = m[key]
    override fun putString(key: String, value: String?) {
        if (value == null) m.remove(key) else m[key] = value
    }
}
