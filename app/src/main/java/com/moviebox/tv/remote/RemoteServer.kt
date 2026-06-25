package com.moviebox.tv.remote

import android.content.Context
import fi.iki.elonen.NanoHTTPD
import java.net.Inet4Address
import java.net.NetworkInterface
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject

private val LANG_ALIAS = mapOf(
    "hindi" to "hi", "tamil" to "ta", "telugu" to "te",
    "malayalam" to "ml", "kannada" to "kn", "bengali" to "bn",
    "urdu" to "ur", "marathi" to "mr", "punjabi" to "pa",
    "english" to "en", "spanish" to "es", "french" to "fr",
    "german" to "de", "japanese" to "ja", "korean" to "ko",
    "chinese" to "zh", "arabic" to "ar", "russian" to "ru",
    "portuguese" to "pt", "italian" to "it",
)

/**
 * Tiny embedded web server. Static assets (`/`, `/remote.css`, `/remote.js`)
 * are public so the phone can load the SPA. Every `api` call must carry a
 * token (`Authorization: Bearer <token>` or `Cookie: token=<token>`); the
 * device's role determines what it can call. The first device to scan the
 * QR (whose URL carries the pair code) becomes superuser.
 */
class RemoteServer(
    private val context: Context,
    port: Int = PORT,
) : NanoHTTPD(port) {

    override fun serve(session: IHTTPSession): Response {
        return try {
            route(session)
        } catch (e: Exception) {
            json(JSONObject().put("error", e.message ?: "error").toString())
        }.apply {
            addHeader("Access-Control-Allow-Origin", "*")
            addHeader("Access-Control-Allow-Headers", "Authorization, Content-Type")
            addHeader("Access-Control-Allow-Methods", "GET, POST, OPTIONS")
        }
    }

    private fun route(session: IHTTPSession): Response {
        val uri = session.uri
        val method = session.method
        if (method == Method.OPTIONS) return newFixedLengthResponse("")

        fun p(name: String) = session.parameters[name]?.firstOrNull()

        val ip = session.remoteIpAddress ?: ""

        // ---- public routes ----
        when (uri) {
            "/", "/index.html" -> return html(asset("remote.html"))
            "/remote.css" -> return text("text/css", asset("remote.css"))
            "/remote.js" -> return text("application/javascript", asset("remote.js"))
        }

        if (uri == "/api/pair" && method == Method.POST) {
            val code = p("code")
            val label = p("label").orEmpty().ifBlank { defaultLabel(ip) }
            val dev = RemoteAccess.pair(code, ip, label)
            // Fresh pair = someone just scanned the QR. Close the overlay on
            // the TV. Idempotent if the overlay was never open.
            RemoteController.onClientActive()
            return json(
                JSONObject()
                    .put("token", dev.token)
                    .put("role", dev.role.name)
                    .put("label", dev.label)
                    .toString()
            )
        }

        // ---- authenticated routes ----
        val token = tokenFrom(session)
        val dev = RemoteAccess.touch(token, ip)
        if (!RemoteAccess.canAccess(dev)) {
            return newFixedLengthResponse(
                Response.Status.FORBIDDEN, "application/json",
                JSONObject()
                    .put("error", "Not approved")
                    .put("role", dev?.role?.name ?: "NONE")
                    .toString()
            )
        }
        return when {
            uri == "/api/me" -> json(
                JSONObject()
                    .put("token", dev!!.token)
                    .put("role", dev.role.name)
                    .put("label", dev.label)
                    .toString()
            )
            uri == "/api/me/label" && method == Method.POST -> {
                RemoteAccess.setLabel(dev!!.token, p("label").orEmpty()); ok()
            }
            uri == "/api/state" -> json(stateJson())

            uri == "/api/playpause" -> { RemoteController.playPause(); ok() }
            // Episode + close controls for the phone SPA — previously the
            // user could only seek, not change episode or quit playback
            // from the phone, which made the remote useless mid-series.
            uri == "/api/episode/next" -> { RemoteController.nextEpisode(); ok() }
            uri == "/api/episode/prev" -> { RemoteController.prevEpisode(); ok() }
            uri == "/api/player/close" -> { RemoteController.closePlayer(); ok() }
            // SPA-triggered live reset. Bounces the LiveStreamProxy socket
            // and clears the resolve-failure counter without restarting
            // the whole app. Surfaced as the "Restart live" button on the
            // Live tab of the phone remote.
            uri == "/api/live/reset" && method == Method.POST -> {
                RemoteController.resetLivePlayback(); ok()
            }
            uri == "/api/quality" && method == Method.POST -> {
                p("label")?.let { RemoteController.pickQuality(it) }; ok()
            }
            uri == "/api/dub" && method == Method.POST -> {
                p("name")?.let { RemoteController.pickDub(it) }; ok()
            }
            uri == "/api/seekby" -> {
                RemoteController.seekBy(p("ms")?.toLongOrNull() ?: 0L); ok()
            }
            uri == "/api/seek" -> {
                // Absolute seek — used by the SPA scrubber when the user drops
                // the thumb. Clamped to [0, duration-1s] so we don't trigger
                // STATE_ENDED accidentally.
                val target = p("ms")?.toLongOrNull() ?: 0L
                RemoteController.seekTo(target); ok()
            }
            uri == "/api/volume" -> {
                when {
                    p("up") != null -> RemoteController.volumeUp()
                    p("down") != null -> RemoteController.volumeDown()
                    p("set") != null -> RemoteController.setVolumePercent(
                        p("set")?.toIntOrNull() ?: 0,
                    )
                }
                ok()
            }

            uri == "/api/search" -> {
                val items = runBlocking { RemoteController.search(p("q").orEmpty()) }
                val deny = dev!!.denyLanguages.toSet()
                val arr = JSONArray()
                items
                    .filter { keepByLanguage(it.title, deny) }
                    .forEach { arr.put(itemJson(it)) }
                json(arr.toString())
            }

            uri == "/api/details" -> {
                val d = runBlocking { RemoteController.details(p("subjectId").orEmpty()) }
                if (d == null) json("{\"seasons\":[]}")
                else {
                    val seasons = JSONArray()
                    d.seasons.forEach { s ->
                        seasons.put(
                            JSONObject()
                                .put("season", s.season)
                                .put("episodes", s.episodes)
                        )
                    }
                    json(
                        JSONObject()
                            .put("description", d.description ?: "")
                            .put("seasons", seasons)
                            .toString()
                    )
                }
            }

            uri == "/api/episodes" -> {
                // Real season → episode list for the phone's picker (kills
                // phantom Seasons 1-8 / arbitrary episode numbers).
                val isSeries = (p("type")?.toIntOrNull() ?: 2) == 2 ||
                    p("isSeries") == "1"
                val map = runBlocking {
                    RemoteController.episodes(
                        subjectId = p("subjectId").orEmpty(),
                        title = p("title"),
                        year = p("year")?.toIntOrNull(),
                        isSeries = isSeries,
                    )
                }
                val seasons = JSONArray()
                map.toSortedMap().forEach { (se, eps) ->
                    seasons.put(
                        JSONObject()
                            .put("season", se)
                            .put("episodes", JSONArray(eps.sorted()))
                    )
                }
                json(JSONObject().put("seasons", seasons).toString())
            }

            uri == "/api/play" && method == Method.POST -> {
                RemoteController.playOnTv(
                    subjectId = p("subjectId").orEmpty(),
                    title = p("title").orEmpty(),
                    coverUrl = p("cover"),
                    type = p("type")?.toIntOrNull() ?: 0,
                    season = p("se")?.toIntOrNull(),
                    episode = p("ep")?.toIntOrNull(),
                    year = p("year")?.toIntOrNull(),
                )
                ok()
            }

            uri == "/api/history" -> {
                val arr = JSONArray()
                RemoteController.history().forEach {
                    arr.put(
                        JSONObject()
                            .put("key", it.key)
                            .put("title", it.title)
                            .put("cover", it.coverUrl ?: "")
                            .put("season", it.season)
                            .put("episode", it.episode)
                            .put("subjectId", it.subjectId)
                            .put("type", it.type)
                            .put("progress", it.progress)
                    )
                }
                json(arr.toString())
            }

            uri == "/api/history/delete" && method == Method.POST -> {
                RemoteController.deleteHistory(p("key").orEmpty()); ok()
            }

            uri == "/api/history/clear" && method == Method.POST -> {
                RemoteController.clearHistory(); ok()
            }

            uri == "/api/downloads" -> {
                val arr = JSONArray()
                RemoteController.downloads().forEach {
                    val pct = if (it.totalBytes > 0)
                        (it.downloadedBytes * 100 / it.totalBytes).toInt() else 0
                    arr.put(
                        JSONObject()
                            .put("key", it.key)
                            .put("title", it.title)
                            .put("cover", it.coverUrl ?: "")
                            .put("season", it.season)
                            .put("episode", it.episode)
                            .put("episodeTitle", it.episodeTitle ?: "")
                            .put("status", it.status)
                            .put("percent", pct)
                            .put("totalBytes", it.totalBytes)
                            .put("downloadedBytes", it.downloadedBytes)
                    )
                }
                json(arr.toString())
            }

            uri == "/api/downloads/start" && method == Method.POST -> {
                RemoteController.startDownload(
                    subjectId = p("subjectId").orEmpty(),
                    title = p("title").orEmpty(),
                    coverUrl = p("cover"),
                    type = p("type")?.toIntOrNull() ?: 0,
                    season = p("se")?.toIntOrNull(),
                    episode = p("ep")?.toIntOrNull(),
                )
                ok()
            }

            uri == "/api/downloads/delete" && method == Method.POST -> {
                RemoteController.deleteDownload(p("key").orEmpty()); ok()
            }

            uri == "/api/browse" -> {
                val slice = p("slice").orEmpty()
                val items = runBlocking { RemoteController.browse(slice) }
                val deny = dev!!.denyLanguages.toSet()
                val arr = JSONArray()
                items
                    .filter { keepByLanguage(it.title, deny) }
                    .forEach { arr.put(itemJson(it)) }
                json(arr.toString())
            }

            // ---- Live TV ----
            uri == "/api/live/channels" -> {
                // First open also kicks off the VM fetch so the SPA doesn't
                // have to wait for the user to open the LIVE tab on the TV.
                RemoteController.ensureLiveLoaded()
                // Recovery hatch: ?force=1 re-pulls channels.json past the
                // cache (async — fresh list lands on the next poll).
                if (p("force") == "1") RemoteController.forceLiveReload()
                val q = p("q").orEmpty().lowercase().trim()
                val group = p("group").orEmpty()
                val channels = RemoteController.liveChannels()
                val arr = JSONArray()
                // No artificial cap — the catalog has ~750 playable channels
                // and clipping at 500 hid ~250 of them from the SPA's grid.
                // The SPA already paginates client-side, so we can ship the
                // whole list without bloating the wire (channels.json is
                // ~200 KB JSON, fine over Wi-Fi).
                val sweep = RemoteController.liveSweep()
                channels
                    .asSequence()
                    .filter { it.isPlayable }
                    .filter { group.isEmpty() || it.group == group }
                    .filter { q.isEmpty() || it.name.lowercase().contains(q) }
                    .forEach { c ->
                        // "sweep" is an advisory hint from data/health.json.
                        // SPA renders an "Often offline" badge when status
                        // is "down" but keeps the card clickable. Null
                        // when no sweep data is available yet.
                        val sweepStatus = sweep[c.id]?.status
                        arr.put(
                            JSONObject()
                                .put("id", c.id)
                                .put("name", c.displayName)
                                .put("logo", c.logo ?: JSONObject.NULL)
                                .put("group", c.group ?: JSONObject.NULL)
                                .put("sweep", sweepStatus ?: JSONObject.NULL)
                        )
                    }
                json(
                    JSONObject()
                        .put("loaded", RemoteController.liveLoaded())
                        .put("channels", arr)
                        .toString()
                )
            }

            uri == "/api/live/groups" -> {
                val arr = JSONArray()
                RemoteController.liveChannels()
                    .asSequence()
                    .mapNotNull { it.group }
                    .distinct()
                    .forEach { arr.put(it) }
                json(arr.toString())
            }

            uri == "/api/live/schedule" -> {
                val schedule = RemoteController.liveSchedule()
                // Bucket by category and time-sort within each so the SPA can
                // render directly.
                val byCat = LinkedHashMap<String, MutableList<com.moviebox.tv.data.live.ScheduleEvent>>()
                for (e in schedule) byCat.getOrPut(e.category) { mutableListOf() }.add(e)
                val arr = JSONArray()
                byCat.forEach { (cat, events) ->
                    val evArr = JSONArray()
                    events.sortedBy { it.time }.forEach { e ->
                        val chArr = JSONArray()
                        e.channels.forEach { ch ->
                            chArr.put(
                                JSONObject().put("id", ch.id).put("name", ch.name)
                            )
                        }
                        evArr.put(
                            JSONObject()
                                .put("time", e.time)
                                .put("title", e.title)
                                .put("channels", chArr)
                        )
                    }
                    arr.put(
                        JSONObject()
                            .put("category", cat)
                            .put("events", evArr)
                    )
                }
                json(arr.toString())
            }

            uri == "/api/live/play" && method == Method.POST -> {
                val id = p("id").orEmpty()
                if (id.isBlank()) {
                    newFixedLengthResponse(
                        Response.Status.BAD_REQUEST, "application/json",
                        "{\"error\":\"id required\"}",
                    )
                } else {
                    RemoteController.playLiveChannel(id)
                    ok()
                }
            }

            uri == "/api/debug" -> {
                // Telemetry snapshot for the Debug pane. JSON. No auth gating
                // beyond the standard pair-token check above — pairing
                // already implies the user is on the trusted Wi-Fi.
                json(com.moviebox.tv.debug.Telemetry.snapshotJson())
            }

            uri == "/api/debug/clear" && method == Method.POST -> {
                // Wipe in-memory event log + per-channel stats. Persisted
                // per-day rollups are kept unless ?all=1 is passed.
                if (p("all") == "1") com.moviebox.tv.debug.Telemetry.clearAll()
                else com.moviebox.tv.debug.Telemetry.clearSession()
                ok()
            }

            uri == "/api/debug/bandwidth" && method == Method.POST -> {
                // Run a real-world download throughput test from the TV's
                // own egress. Synchronous on the request thread because the
                // SPA polls /api/debug while this is running and we want
                // serialised access to results. ~1-3 seconds on a healthy
                // link, up to readTimeout (20 s) on a dead one.
                val probe = com.moviebox.tv.debug.BandwidthProbe()
                val result = kotlinx.coroutines.runBlocking { probe.measure() }
                com.moviebox.tv.debug.Telemetry.note(
                    when (result.verdict) {
                        "excellent", "good" -> com.moviebox.tv.debug.Telemetry.Severity.INFO
                        "fair"               -> com.moviebox.tv.debug.Telemetry.Severity.WARN
                        else                 -> com.moviebox.tv.debug.Telemetry.Severity.ERROR
                    },
                    "Bandwidth: %.1f Mbps (%s)".format(result.mbps, result.verdict),
                )
                json(result.toJson())
            }

            uri == "/api/network" -> {
                // Lightweight ping endpoint the SPA polls. Returns state +
                // how long we've been in it. SPA renders a banner when
                // anything other than "online".
                val s = com.moviebox.tv.debug.NetworkMonitor.state.value
                json(
                    """{"state":"${s.name.lowercase()}",""" +
                    """"sinceMs":${com.moviebox.tv.debug.NetworkMonitor.timeInStateMs()}}""",
                )
            }

            uri == "/api/update" -> {
                // Latest update-check result. Stays "available:false" when
                // the device is on the latest build OR the check hasn't
                // succeeded yet (e.g. no Wi-Fi at launch).
                json(RemoteController.pendingUpdateJson())
            }

            uri == "/api/genres" -> {
                val tv = p("tv") == "1"
                val genres = runBlocking {
                    if (tv) RemoteController.tvGenres() else RemoteController.movieGenres()
                }
                val arr = JSONArray()
                genres.forEach { g ->
                    arr.put(JSONObject().put("id", g.id).put("name", g.name))
                }
                json(arr.toString())
            }

            uri == "/api/me/prefs" && method == Method.POST -> {
                RemoteAccess.setPrefs(
                    dev!!.token,
                    networks = p("networks")?.split(",")?.filter { it.isNotBlank() },
                    genres = p("genres")?.split(",")?.mapNotNull { it.toIntOrNull() },
                    denyLanguages = p("denyLanguages")?.split(",")
                        ?.filter { it.isNotBlank() },
                )
                ok()
            }

            uri == "/api/me/prefs" -> json(
                JSONObject()
                    .put("networks", JSONArray(dev!!.networks))
                    .put("genres",   JSONArray(dev.genres))
                    .put("denyLanguages", JSONArray(dev.denyLanguages))
                    .toString()
            )

            // ---- superuser only ----
            uri.startsWith("/api/devices") && !RemoteAccess.isSuperuser(dev) ->
                newFixedLengthResponse(
                    Response.Status.FORBIDDEN, "application/json",
                    "{\"error\":\"Superuser only\"}",
                )

            uri == "/api/devices" -> {
                val arr = JSONArray()
                RemoteAccess.all().forEach { d ->
                    arr.put(
                        JSONObject()
                            .put("token", d.token)
                            .put("label", d.label)
                            .put("role", d.role.name)
                            .put("ip", d.ip)
                            .put("firstSeen", d.firstSeen)
                            .put("lastSeen", d.lastSeen)
                            .put("isMe", d.token == dev!!.token)
                    )
                }
                json(arr.toString())
            }

            uri == "/api/devices/role" && method == Method.POST -> {
                val t = p("token").orEmpty()
                val role = runCatching {
                    RemoteAccess.Role.valueOf(p("role").orEmpty())
                }.getOrNull()
                if (role != null) RemoteAccess.setRole(t, role)
                ok()
            }

            uri == "/api/devices/remove" && method == Method.POST -> {
                RemoteAccess.remove(p("token").orEmpty()); ok()
            }

            uri == "/api/pair_code" -> json(
                JSONObject().put("code", RemoteAccess.pairCode).toString()
            )

            uri == "/api/pair_code/regen" && method == Method.POST -> json(
                JSONObject().put("code", RemoteAccess.regeneratePairCode()).toString()
            )

            else -> newFixedLengthResponse(
                Response.Status.NOT_FOUND, "text/plain", "Not found",
            )
        }
    }

    private fun defaultLabel(ip: String): String =
        if (ip.isBlank()) "Phone" else "Phone (${ip.substringAfterLast('.')})"

    private fun itemJson(it: com.moviebox.tv.data.Item): JSONObject =
        JSONObject()
            .put("subjectId", it.subjectId)
            .put("title", it.title)
            .put("cover", it.coverUrl ?: "")
            .put("type", it.type.code)
            .put("year", it.year ?: 0)
            .put("rating", it.rating ?: 0.0)
            .put("isSeries", it.isSeries)
            .put("overview", it.overview ?: "")

    /** Drops titles whose language tag is in the deny list — e.g. "[Hindi]". */
    private fun keepByLanguage(title: String, deny: Set<String>): Boolean {
        if (deny.isEmpty()) return true
        val tag = Regex("[\\[(](\\w+)[\\])]").findAll(title)
            .map { it.groupValues[1].lowercase() }.toList()
        if (tag.isEmpty()) return true
        val denyNorm = deny.map { it.lowercase() }.toSet()
        return tag.none { it in denyNorm || LANG_ALIAS[it] in denyNorm }
    }

    private fun tokenFrom(session: IHTTPSession): String? {
        val h = session.headers
        h["authorization"]?.removePrefix("Bearer ")?.trim()?.takeIf { it.isNotEmpty() }
            ?.let { return it }
        val cookie = h["cookie"] ?: return null
        val match = Regex("token=([A-Za-z0-9_\\-]+)").find(cookie)
        return match?.groupValues?.get(1)
    }

    private fun stateJson(): String = JSONObject()
        .put("title", RemoteController.nowPlayingTitle)
        .put("position", RemoteController.positionMs)
        .put("duration", RemoteController.durationMs)
        .put("playing", RemoteController.isPlaying)
        .put("volume", RemoteController.volumePercent())
        .put("quality", RemoteController.selectedQuality)
        .put("qualities", JSONArray(RemoteController.availableQualities))
        .put("dub", RemoteController.selectedDub)
        .put("dubs", JSONArray(RemoteController.availableDubs))
        // SPA toggles the "Prev / Next episode" row on these. Null on
        // movies + live (which don't have episode coordinates).
        .put("season", RemoteController.currentSeason ?: JSONObject.NULL)
        .put("episode", RemoteController.currentEpisode ?: JSONObject.NULL)
        // Identify the playing item so the phone's episode picker can
        // enumerate its real seasons/episodes and jump within them.
        .put("subjectId", RemoteController.nowPlayingSubjectId ?: JSONObject.NULL)
        .put("type", RemoteController.nowPlayingType)
        .put("year", RemoteController.nowPlayingYear ?: JSONObject.NULL)
        .toString()

    private fun ok() = json("{\"ok\":true}")

    private fun json(body: String) =
        newFixedLengthResponse(Response.Status.OK, "application/json", body)

    private fun text(mime: String, body: String) =
        newFixedLengthResponse(Response.Status.OK, mime, body)

    private fun html(body: String) =
        newFixedLengthResponse(Response.Status.OK, "text/html", body)

    private fun asset(name: String): String =
        context.assets.open(name).bufferedReader().use { it.readText() }

    companion object {
        const val PORT = 8080

        fun localIp(): String? {
            return runCatching {
                NetworkInterface.getNetworkInterfaces().toList()
                    .filter { it.isUp && !it.isLoopback }
                    .flatMap { it.inetAddresses.toList() }
                    .filterIsInstance<Inet4Address>()
                    .firstOrNull { it.isSiteLocalAddress }
                    ?.hostAddress
            }.getOrNull()
        }
    }
}

/** Starts the remote server once and exposes its URL. */
object RemoteServerManager {
    @Volatile private var server: RemoteServer? = null

    fun ensureStarted(context: Context): Boolean {
        if (server == null) {
            val s = RemoteServer(context.applicationContext)
            val started = runCatching {
                s.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false)
            }.isSuccess
            if (started) server = s
            return started
        }
        return true
    }

    fun url(): String? =
        RemoteServer.localIp()?.let { "http://$it:${RemoteServer.PORT}" }

    /** URL embedded in the QR — carries the current pair code. */
    fun pairUrl(): String? = url()?.let { "$it/?pair=${RemoteAccess.pairCode}" }
}
