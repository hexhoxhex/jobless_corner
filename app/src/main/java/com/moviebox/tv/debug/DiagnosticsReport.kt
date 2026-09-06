package com.moviebox.tv.debug

import android.content.Context
import android.os.Build
import org.json.JSONArray
import org.json.JSONObject

/**
 * Builds a shareable diagnostics bundle for a stream problem.
 *
 * Designed to be safe to hand over: it carries what is needed to debug a
 * failing feed (counters, timings, measured feed health, device class) and
 * deliberately not who the viewer is or what they watch.
 *
 * Anonymity is a property of what we COLLECT, not a promise bolted on at
 * send time:
 *  - no device serial, Android id, account, IP or hostname;
 *  - no pairing code or remote-device label;
 *  - VOD titles redacted — what someone watches is their business, and a
 *    stream fault is diagnosable without it. Live CHANNEL names are kept,
 *    because which feed broke is the entire question;
 *  - URLs reduced to host only, so signed tokens can never ride along;
 *  - a random per-report id, regenerated each time, so two reports cannot
 *    be linked back to one installation.
 */
object DiagnosticsReport {

    /** Redact anything that looks like a signed URL down to its host. */
    private val URL_RE = Regex("""https?://([^/\s"]+)\S*""")

    /** "Playing <title>" style messages carry viewing history. */
    private val TITLE_RE = Regex(
        """\b(playing|loading|resolved|title)\b[:=]?\s*"?([^"\n,]{3,60})"?""",
        RegexOption.IGNORE_CASE,
    )

    fun build(context: Context, includeFeeds: Boolean = true): String {
        val out = JSONObject()

        // Random per-report id. Not persisted: reports are not linkable.
        out.put("report_id", java.util.UUID.randomUUID().toString().take(8))
        out.put("schema", 1)

        // Device class, not device identity.
        out.put(
            "device",
            JSONObject()
                .put("model", Build.MODEL)
                .put("manufacturer", Build.MANUFACTURER)
                .put("android", Build.VERSION.RELEASE)
                .put("sdk", Build.VERSION.SDK_INT)
                .put("abi", Build.SUPPORTED_ABIS.firstOrNull() ?: "")
        )
        out.put(
            "app",
            JSONObject()
                .put("version", appVersion(context))
                .put("package", context.packageName)
        )
        out.put("memory", memoryInfo(context))

        // Telemetry snapshot, with viewing detail scrubbed.
        val snapshot = runCatching { JSONObject(Telemetry.snapshotJson()) }
            .getOrDefault(JSONObject())
        scrubEvents(snapshot)
        out.put("telemetry", snapshot)

        // Measured feed health — the most useful part for a stream report,
        // and inherently non-personal.
        if (includeFeeds) {
            val feeds = JSONArray()
            com.moviebox.tv.data.live.FeedRanker.snapshot().forEach { (id, ranked) ->
                val r = ranked.result
                feeds.put(
                    JSONObject()
                        .put("channel", id)
                        .put("ok", r.ok)
                        .put("playlist_ms", r.playlistMs)
                        .put("kbps", r.throughputKbps)
                        .put("declared_kbps", r.declaredKbps)
                        .put("headroom", String.format("%.2f", r.headroom).toDouble())
                        .put("host", r.host)
                        .put("note", r.note)
                )
            }
            out.put("feeds", feeds)
        }
        return out.toString(2)
    }

    /** Strip titles and full URLs out of the event log in place. */
    private fun scrubEvents(snapshot: JSONObject) {
        val events = snapshot.optJSONArray("events") ?: return
        for (i in 0 until events.length()) {
            val ev = events.optJSONObject(i) ?: continue
            val msg = ev.optString("message", "")
            if (msg.isBlank()) continue
            var clean = URL_RE.replace(msg) { m -> m.groupValues[1] }
            clean = TITLE_RE.replace(clean) { m -> "${m.groupValues[1]} <redacted>" }
            ev.put("message", clean)
        }
    }

    private fun appVersion(context: Context): String = runCatching {
        val pm = context.packageManager
        @Suppress("DEPRECATION")
        val info = pm.getPackageInfo(context.packageName, 0)
        info.versionName ?: "?"
    }.getOrDefault("?")

    private fun memoryInfo(context: Context): JSONObject {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE)
            as android.app.ActivityManager
        val mi = android.app.ActivityManager.MemoryInfo()
        runCatching { am.getMemoryInfo(mi) }
        return JSONObject()
            .put("avail_mb", mi.availMem / (1024 * 1024))
            .put("total_mb", mi.totalMem / (1024 * 1024))
            .put("low", mi.lowMemory)
    }
}
