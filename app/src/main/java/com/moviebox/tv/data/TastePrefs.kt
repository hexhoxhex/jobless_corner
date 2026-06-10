package com.moviebox.tv.data

import android.content.Context
import android.content.SharedPreferences

/**
 * TV-level taste preferences: which language tags to hide from the home /
 * search rows (e.g. "hi" drops every "[Hindi]" entry). Backed by
 * SharedPreferences so it survives restarts.
 */
object TastePrefs {
    private lateinit var prefs: SharedPreferences

    /** Default deny list — most users on Western locales don't want these. */
    private val DEFAULT_DENY = setOf(
        "hi", "ta", "te", "ml", "kn", "bn", "ur", "mr", "pa",
    )

    fun init(context: Context) {
        if (::prefs.isInitialized) return
        prefs = context.applicationContext
            .getSharedPreferences("taste_prefs", Context.MODE_PRIVATE)
        if (!prefs.contains(KEY_DENY)) {
            prefs.edit().putStringSet(KEY_DENY, DEFAULT_DENY).apply()
        }
    }

    fun denyLanguages(): Set<String> =
        prefs.getStringSet(KEY_DENY, DEFAULT_DENY)!!

    fun setDenyLanguages(codes: Collection<String>) {
        prefs.edit().putStringSet(KEY_DENY, codes.toSet()).apply()
    }

    private const val KEY_DENY = "denyLanguages"
}
