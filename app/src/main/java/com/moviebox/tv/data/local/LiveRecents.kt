package com.moviebox.tv.data.local

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Recently watched live channels.
 *
 * Live TV was never recorded anywhere — [WatchHistoryEntity] only covers VOD,
 * so the "TV stations" side of the viewing history was simply empty. Channels
 * deliberately do NOT go into WatchHistory: they have no position/duration to
 * resume, and mixing them in would pollute Continue Watching with rows that
 * can't be continued.
 *
 * Small enough (capped at [MAX]) that SharedPreferences JSON is the right
 * store — no migration, no Room table, survives restarts.
 */
object LiveRecents {

    private const val PREFS = "live_recents"
    private const val KEY = "channels"
    private const val MAX = 30

    data class Entry(
        val id: String,
        val name: String,
        val logo: String?,
        val group: String?,
        val watchedAt: Long,
    )

    private fun prefs(ctx: Context) =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Record a channel view. Re-watching moves it to the front rather than
     *  adding a duplicate row. */
    fun record(ctx: Context, id: String, name: String, logo: String?, group: String?, now: Long) {
        if (id.isBlank()) return
        val kept = all(ctx).filter { it.id != id }
        val updated = listOf(Entry(id, name, logo, group, now)) + kept
        val arr = JSONArray()
        updated.take(MAX).forEach {
            arr.put(
                JSONObject()
                    .put("id", it.id)
                    .put("name", it.name)
                    .put("logo", it.logo ?: "")
                    .put("group", it.group ?: "")
                    .put("at", it.watchedAt),
            )
        }
        prefs(ctx).edit().putString(KEY, arr.toString()).apply()
    }

    /** Most-recent-first. */
    fun all(ctx: Context): List<Entry> {
        val raw = prefs(ctx).getString(KEY, null) ?: return emptyList()
        val arr = runCatching { JSONArray(raw) }.getOrNull() ?: return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            runCatching {
                val o = arr.getJSONObject(i)
                Entry(
                    id = o.optString("id"),
                    name = o.optString("name"),
                    logo = o.optString("logo").takeIf { it.isNotBlank() },
                    group = o.optString("group").takeIf { it.isNotBlank() },
                    watchedAt = o.optLong("at"),
                )
            }.getOrNull()?.takeIf { it.id.isNotBlank() }
        }
    }

    fun remove(ctx: Context, id: String) {
        val arr = JSONArray()
        all(ctx).filter { it.id != id }.forEach {
            arr.put(
                JSONObject().put("id", it.id).put("name", it.name)
                    .put("logo", it.logo ?: "").put("group", it.group ?: "")
                    .put("at", it.watchedAt),
            )
        }
        prefs(ctx).edit().putString(KEY, arr.toString()).apply()
    }

    fun clear(ctx: Context) = prefs(ctx).edit().remove(KEY).apply()
}
