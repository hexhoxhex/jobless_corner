package com.moviebox.tv.data.live

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * What THIS TV has learned about each channel, from its own network.
 *
 * Why this exists: the catalogue's verdicts come from GitHub Actions, and the
 * wrapper source throttles datacentre IPs so hard that CI can no longer
 * verify anything. Measured 2026-10-06: the root page alone took 48 s from a
 * runner against ~0.2 s from home, the refresh resolved 0 of 899 channels on
 * every run, and the catalogue had been frozen since 2026-10-04 behind a green
 * workflow. 126 channels had no verdict at all, so every user's app hid them.
 *
 * The TV, meanwhile, resolves these channels fine — it does so every time
 * someone watches one. This ledger keeps those answers, plus the ones the
 * background [HomeSweepWorker] gathers, so they can be published back into
 * the shared catalogue (`scripts/publish_home_health.py` in the scraper repo
 * pulls them from `/api/live/health`).
 *
 * Shape matches the `results` entries of the scraper's health.json, with
 * `source: "home"` so a merged file says where each verdict came from.
 * Times are epoch SECONDS for the same reason.
 */
object HomeHealthLedger {

    data class Entry(
        /** "ok" or "down". */
        val status: String,
        val checkedAt: Long,
        val host: String?,
        val reason: String?,
        /** Consecutive "down" verdicts. One failure can be a blip; the
         *  publisher only hides a channel from everyone after two. */
        val fails: Int,
    )

    private const val FILE = "home_health.json"
    private val lock = Any()
    private var cache: MutableMap<String, Entry>? = null

    fun record(
        ctx: Context,
        channelId: String,
        ok: Boolean,
        host: String? = null,
        reason: String? = null,
    ) {
        synchronized(lock) {
            val map = load(ctx)
            val prevFails = map[channelId]?.takeIf { it.status == "down" }?.fails ?: 0
            map[channelId] = Entry(
                status = if (ok) "ok" else "down",
                checkedAt = System.currentTimeMillis() / 1000,
                host = host,
                reason = if (ok) null else reason,
                fails = if (ok) 0 else prevFails + 1,
            )
            save(ctx, map)
        }
    }

    /** Epoch seconds of the last verdict for [channelId]; 0 when there is none. */
    fun lastCheckedAt(ctx: Context, channelId: String): Long =
        synchronized(lock) { load(ctx)[channelId]?.checkedAt ?: 0L }

    /** "ok", "down", or null when this TV has no verdict for [channelId]. */
    fun statusOf(ctx: Context, channelId: String): String? =
        synchronized(lock) { load(ctx)[channelId]?.status }

    fun size(ctx: Context): Int = synchronized(lock) { load(ctx).size }

    fun toJson(ctx: Context): String {
        val snapshot = synchronized(lock) { HashMap(load(ctx)) }
        val arr = JSONArray()
        snapshot.entries
            .sortedBy { it.key.toIntOrNull() ?: Int.MAX_VALUE }
            .forEach { (id, e) ->
                arr.put(
                    JSONObject()
                        .put("id", id)
                        .put("status", e.status)
                        .put("checked_at", e.checkedAt)
                        .put("host", e.host ?: JSONObject.NULL)
                        .put("fail_reason", e.reason ?: JSONObject.NULL)
                        .put("fails", e.fails)
                        .put("source", "home"),
                )
            }
        return JSONObject()
            .put("generated_at", System.currentTimeMillis() / 1000)
            .put("count", arr.length())
            .put("results", arr)
            .toString()
    }

    // ------------------------------------------------------------ storage --

    private fun file(ctx: Context) = File(ctx.applicationContext.filesDir, FILE)

    /** Caller holds [lock]. */
    private fun load(ctx: Context): MutableMap<String, Entry> {
        cache?.let { return it }
        val map = HashMap<String, Entry>()
        runCatching {
            val f = file(ctx)
            if (f.exists()) {
                val o = JSONObject(f.readText())
                for (id in o.keys()) {
                    val e = o.getJSONObject(id)
                    map[id] = Entry(
                        status = e.optString("status"),
                        checkedAt = e.optLong("checked_at"),
                        host = e.optString("host").takeIf { it.isNotBlank() && it != "null" },
                        reason = e.optString("fail_reason")
                            .takeIf { it.isNotBlank() && it != "null" },
                        fails = e.optInt("fails"),
                    )
                }
            }
        }
        cache = map
        return map
    }

    /** Caller holds [lock]. Write-then-rename, so a process killed mid-write
     *  (WorkManager stops workers at ten minutes) never leaves half a file. */
    private fun save(ctx: Context, map: Map<String, Entry>) {
        runCatching {
            val o = JSONObject()
            for ((id, e) in map) {
                o.put(
                    id,
                    JSONObject()
                        .put("status", e.status)
                        .put("checked_at", e.checkedAt)
                        .put("host", e.host ?: "")
                        .put("fail_reason", e.reason ?: "")
                        .put("fails", e.fails),
                )
            }
            val target = file(ctx)
            val tmp = File(target.parentFile, "$FILE.tmp")
            tmp.writeText(o.toString())
            if (!tmp.renameTo(target)) {
                target.delete()
                tmp.renameTo(target)
            }
        }
    }
}
