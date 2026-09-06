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
        var dev = RemoteAccess.touch(token, ip)
        // Loopback auto-approve: requests originating from 127.0.0.1 are
        // by definition from the same device (adb port-forward, an
        // on-device script, or a debug tool). Skip the pair-code / QR
        // flow entirely so `adb forward tcp:8080 tcp:8080 && curl
        // -X POST http://127.0.0.1:8080/api/play?ch=54` works out of
        // the box during development — no more asking the user to tap
        // the channel by hand on every rebuild.
        if (dev == null && (ip == "127.0.0.1" || ip == "::1")) {
            dev = RemoteAccess.pair(code = null, ip = ip, label = "loopback (adb)")
                .also { RemoteAccess.setRole(it.token, RemoteAccess.Role.SUPERUSER) }
        }
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
            uri == "/api/provider" && method == Method.POST -> {
                // Manual source switch for the playing title. An explicit pick
                // pins that provider (failover won't override it).
                RemoteController.pickProvider(p("label").orEmpty())
                ok()
            }

            uri == "/api/quality" && method == Method.POST -> {
                p("label")?.let { RemoteController.pickQuality(it) }; ok()
            }
            // Subtitle (CC) selection from the phone. lang="" (or absent)
            // turns subtitles off; any other value enables that language's
            // track on the TV player.
            uri == "/api/subtitle" && method == Method.POST -> {
                RemoteController.setSubtitle(p("lang")); ok()
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
                // When the query was a PERSON, name them so the SPA can head
                // the results with "Films with <name>" rather than presenting
                // a filmography as if it were an ordinary title match.
                val person = RemoteController.lastPersonMatch()
                if (person == null) json(arr.toString())
                else json(
                    JSONObject()
                        .put("results", arr)
                        .put(
                            "person",
                            JSONObject()
                                .put("name", person.name)
                                .put("department", person.department ?: "")
                                .put("profile", person.profileUrl ?: ""),
                        )
                        .toString(),
                )
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
                    // Resolve the trailer here (Cinemeta, keyless, cached) so
                    // the remote gets the same Trailer button the APK detail
                    // page has. repo.details doesn't carry it (it's a VM-layer
                    // add), so fetch directly. Title from the detail, falling
                    // back to the ?title= param the remote passes.
                    // TMDB enrichment: accurate poster/backdrop/rating/cast +
                    // the official trailer — the same data the APK detail page
                    // now shows. Matched by title+year+type; null on no match,
                    // so the base source metadata still renders.
                    val trailerTitle = d.title.ifBlank { p("title").orEmpty() }
                    val meta = if (trailerTitle.isNotBlank()) runBlocking {
                        RemoteController.enrichMetadata(trailerTitle, d.year, d.isSeries)
                    } else null
                    val trailer = meta?.trailerKey
                        ?: if (trailerTitle.isNotBlank()) runBlocking {
                            com.moviebox.tv.net.OpenSubtitlesClient
                                .trailerYouTubeId(trailerTitle, d.isSeries)
                        } else null
                    val cast = JSONArray()
                    meta?.cast?.forEach { c ->
                        cast.put(
                            JSONObject()
                                .put("name", c.name)
                                .put("character", c.character ?: "")
                                .put("profile", c.profileUrl ?: ""),
                        )
                    }
                    json(
                        JSONObject()
                            .put("title", d.title)
                            .put("description", meta?.overview?.ifBlank { null } ?: d.description ?: "")
                            .put("seasons", seasons)
                            .put("trailer", trailer ?: "")
                            .put("poster", meta?.posterUrl ?: "")
                            .put("backdrop", meta?.backdropUrl ?: "")
                            .put("rating", meta?.rating ?: JSONObject.NULL)
                            .put("cast", cast)
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
                    // Season/episode 0 is the movie convention — normalize to
                    // null so movies don't render as "S0E0 · Title" and don't
                    // show episode / up-next controls. Real seasons start at 1.
                    season = p("se")?.toIntOrNull()?.takeIf { it > 0 },
                    episode = p("ep")?.toIntOrNull()?.takeIf { it > 0 },
                    year = p("year")?.toIntOrNull(),
                )
                ok()
            }

            uri == "/api/history" -> {
                // Categorised + de-duplicated viewing history.
                //
                // Three problems this fixes: (1) movies, series and channels
                // were one undifferentiated pile; (2) the same show appeared
                // twice when watched from two sources or under two title
                // spellings ("Big Bang Theory" from 4KHDHub vs "The Big Bang
                // Theory" from MovieBox); (3) `type` is unreliable — some
                // rows carry type 0, so the kind is derived from season/
                // episode first and only falls back to the stored type.
                val arr = JSONArray()
                val seen = HashSet<String>()
                RemoteController.history().forEach {
                    val isSeries = it.season > 0 || it.episode > 0 || it.type == 2
                    // Some rows stored the PLAYER's display title, which
                    // carries the episode prefix ("S1E38 · Pursuit of Jade").
                    // That defeated de-duplication — every episode looked like
                    // a different show, so one series filled the list with a
                    // row per episode instead of appearing once. Strip it for
                    // both the key and what we show.
                    val showTitle = it.title
                        .replace(Regex("""^S\d+\s*E\d+\s*[·\-]\s*"""), "")
                        .trim()
                        .ifBlank { it.title }
                    // Collapse title variants: strip leading article, season
                    // decorations ("S24", "S1-S5") and non-alphanumerics, so
                    // the same show from different providers folds into one.
                    val dedupeKey = buildString {
                        append(if (isSeries) "s:" else "m:")
                        append(
                            showTitle.lowercase()
                                .replace(Regex("""\bs\d{1,2}(\s*-\s*s?\d{1,2})?\b"""), " ")
                                .replace(Regex("""^(the|a|an)\s+"""), "")
                                .replace(Regex("[^a-z0-9]+"), ""),
                        )
                    }
                    if (!seen.add(dedupeKey)) return@forEach
                    val cover = it.coverUrl?.takeIf { url -> url.isNotBlank() }
                        ?: RemoteController.knownCover(it.subjectId)
                        ?: ""
                    arr.put(
                        JSONObject()
                            .put("key", it.key)
                            .put("title", showTitle)
                            .put("cover", cover)
                            .put("season", it.season)
                            .put("episode", it.episode)
                            .put("subjectId", it.subjectId)
                            .put("type", it.type)
                            .put("progress", it.progress)
                            .put("kind", if (isSeries) "series" else "movie")
                            .put(
                                "provider",
                                com.moviebox.tv.data.Repository.Provider
                                    .of(it.subjectId).label,
                            ),
                    )
                }
                // Live channels live in their own store (no resume position),
                // surfaced here so the remote can show a "TV stations" group.
                RemoteController.liveRecents().forEach {
                    arr.put(
                        JSONObject()
                            .put("key", "live:" + it.id)
                            .put("title", it.name)
                            .put("cover", it.logo ?: "")
                            .put("season", 0)
                            .put("episode", 0)
                            .put("subjectId", "live:" + it.id)
                            .put("channelId", it.id)
                            .put("type", 0)
                            .put("progress", 0.0)
                            .put("kind", "channel")
                            .put("group", it.group ?: ""),
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
                        // startUnix is the authoritative event start time
                        // — needed by the SPA to keep long-running events
                        // (sports matches typically run 2-3h) on-screen
                        // instead of hiding them after the old 60-min
                        // "still on air" window. Set by the scraper's
                        // annotate_dates step; null on pre-annotation
                        // catalog dumps. The SPA falls back to the raw
                        // "time" string when start_unix is missing.
                        val ev = JSONObject()
                            .put("time", e.time)
                            .put("title", e.title)
                            .put("channels", chArr)
                        e.startUnix?.let { ev.put("start_unix", it) }
                        // Parsed sides + competition, so the phone can offer
                        // "Follow Hull City" / "Follow Aston Villa" on a row
                        // without reimplementing the fixture parser in JS.
                        // One parser, one alias table, one place to fix it.
                        val fx = com.moviebox.tv.data.live.FixtureParser.parse(e.title)
                        if (fx.isFixture) {
                            val sides = JSONArray()
                            fx.sides.forEach { side ->
                                sides.put(
                                    JSONObject()
                                        .put("name", side)
                                        .put(
                                            "key",
                                            com.moviebox.tv.data.live
                                                .FixtureParser.canonical(side),
                                        )
                                )
                            }
                            ev.put("sides", sides)
                        }
                        if (fx.competition.isNotBlank()) {
                            ev.put("competition", fx.competition)
                        }
                        evArr.put(ev)
                    }
                    arr.put(
                        JSONObject()
                            .put("category", cat)
                            .put("events", evArr)
                    )
                }
                json(arr.toString())
            }

            // ---- Follows: teams / competitions / shows to be told about ----
            uri == "/api/follows" && method == Method.GET -> {
                val db = com.moviebox.tv.data.local.AppDatabase.get(context)
                val rows = runBlocking { db.follows().allNow() }
                // Fall back to the warm cache so a cold app (Live tab never
                // opened) still reports real fixtures instead of claiming
                // there is nothing on today.
                val schedule = com.moviebox.tv.reminders.ReminderWarm
                    .scheduleOrCached(RemoteController.liveSchedule())
                val nowSec = System.currentTimeMillis() / 1000
                val arr = JSONArray()
                rows.forEach { f ->
                    // Attach the soonest fixture so the list is useful on its
                    // own -- "Manchester United, Sat 18:30 vs Arsenal" rather
                    // than a bare name the user then has to go hunt for.
                    val next = com.moviebox.tv.data.live.FollowMatcher
                        .upcoming(listOf(f), schedule, nowSec)
                        .firstOrNull()
                    val o = JSONObject()
                        .put("key", f.key)
                        .put("label", f.label)
                        .put("kind", f.kind)
                        .put("remind", f.remind)
                        .put("minutes", f.remindMinutes)
                    if (next != null) {
                        o.put("next_title", next.event.title)
                        o.put("next_opponent", next.opponent ?: JSONObject.NULL)
                        next.event.startUnix?.let { o.put("next_start", it) }
                        val chArr = JSONArray()
                        next.event.channels.forEach { ch ->
                            chArr.put(JSONObject().put("id", ch.id).put("name", ch.name))
                        }
                        o.put("next_channels", chArr)
                    }
                    arr.put(o)
                }
                json(arr.toString())
            }

            uri == "/api/follows" && method == Method.POST -> {
                val label = p("label").orEmpty().trim()
                if (label.isBlank()) {
                    json(JSONObject().put("ok", false).put("error", "label required").toString())
                } else {
                    // No kind= means "work it out per event" (FollowKind.AUTO)
                    // rather than freezing a guess now -- see FollowKind.
                    val kind = com.moviebox.tv.data.local.FollowKind.from(p("kind"))
                    val minutes = p("minutes")?.toIntOrNull()
                        ?: com.moviebox.tv.data.local.FollowEntity.DEFAULT_LEAD_MINUTES
                    val key = com.moviebox.tv.data.live.FixtureParser.canonical(label)
                    val db = com.moviebox.tv.data.local.AppDatabase.get(context)
                    runBlocking {
                        db.follows().add(
                            com.moviebox.tv.data.local.FollowEntity(
                                key = key,
                                label = label,
                                kind = kind.name,
                                remindMinutes = minutes.coerceIn(0, 24 * 60),
                                remind = true,
                                addedAt = System.currentTimeMillis(),
                            )
                        )
                        // Arm alarms for this follow immediately -- the user
                        // should be covered for tonight without having to
                        // wait for the next schedule refresh.
                        com.moviebox.tv.reminders.ReminderScheduler.reschedule(
                            context,
                            com.moviebox.tv.reminders.ReminderWarm
                                .scheduleOrCached(RemoteController.liveSchedule()),
                        )
                    }
                    json(
                        JSONObject().put("ok", true).put("key", key)
                            .put("kind", kind.name).toString()
                    )
                }
            }

            uri == "/api/follows/delete" && method == Method.POST -> {
                val key = p("key").orEmpty()
                val db = com.moviebox.tv.data.local.AppDatabase.get(context)
                runBlocking { db.follows().remove(key) }
                ok()
            }

            uri == "/api/follows/remind" && method == Method.POST -> {
                val key = p("key").orEmpty()
                val on = p("on")?.lowercase() != "false"
                val db = com.moviebox.tv.data.local.AppDatabase.get(context)
                runBlocking {
                    db.follows().setRemind(key, on)
                    p("minutes")?.toIntOrNull()?.let {
                        db.follows().setLead(key, it.coerceIn(0, 24 * 60))
                    }
                    com.moviebox.tv.reminders.ReminderScheduler.reschedule(
                        context,
                        com.moviebox.tv.reminders.ReminderWarm
                            .scheduleOrCached(RemoteController.liveSchedule()),
                    )
                }
                ok()
            }

            // Every upcoming fixture across all follows, soonest first.
            uri == "/api/follows/upcoming" -> {
                val db = com.moviebox.tv.data.local.AppDatabase.get(context)
                val rows = runBlocking { db.follows().allNow() }
                val nowSec = System.currentTimeMillis() / 1000
                val matches = com.moviebox.tv.data.live.FollowMatcher.upcoming(
                    rows,
                    com.moviebox.tv.reminders.ReminderWarm
                        .scheduleOrCached(RemoteController.liveSchedule()),
                    nowSec,
                )
                val arr = JSONArray()
                matches.forEach { m ->
                    val chArr = JSONArray()
                    m.event.channels.forEach { ch ->
                        chArr.put(JSONObject().put("id", ch.id).put("name", ch.name))
                    }
                    val start = m.event.startUnix ?: 0L
                    arr.put(
                        JSONObject()
                            .put("follow", m.follow.label)
                            .put("key", m.follow.key)
                            .put("title", m.event.title)
                            .put("opponent", m.opponent ?: JSONObject.NULL)
                            .put("category", m.event.category)
                            .put("time", m.event.time)
                            .put("start_unix", start)
                            .put("live", start in 1..nowSec)
                            .put("channels", chArr)
                    )
                }
                json(arr.toString())
            }

            // The most recent fired reminder, so the phone can show the
            // same banner the TV does.
            uri == "/api/reminders/pending" -> {
                val r = com.moviebox.tv.reminders.ReminderScheduler.pending.value
                if (r == null) {
                    json("{}")
                } else {
                    json(
                        JSONObject()
                            .put("key", r.eventKey)
                            .put("headline", r.headline())
                            .put("title", r.title)
                            .put("start_unix", r.startUnix)
                            .put("channel_id", r.channelId ?: JSONObject.NULL)
                            .put("channel_name", r.channelName ?: JSONObject.NULL)
                            .toString()
                    )
                }
            }

            uri == "/api/reminders/dismiss" && method == Method.POST -> {
                com.moviebox.tv.reminders.ReminderScheduler.clearInApp()
                ok()
            }

            // Live playback A/B switch. Tunneled video bypasses ExoPlayer's
            // render callbacks, so frame counters read zero either way --
            // the only honest test is to flip it and look at the screen.
            uri == "/api/debug/tunneling" -> {
                p("on")?.let {
                    com.moviebox.tv.data.LiveTuning.setForceNoTunneling(
                        it.lowercase() == "false" || it == "0",
                    )
                }
                json(
                    JSONObject()
                        .put("forceNoTunneling",
                            com.moviebox.tv.data.LiveTuning.forceNoTunneling)
                        .toString()
                )
            }

            // Which other feeds carry what I am watching, and which of
            // them is actually healthy?
            //
            // A big fixture is mirrored across dozens of channels and the
            // one the schedule lists first is not necessarily the one that
            // holds up -- Sky Sports PL measured 13380 kbps and failed
            // outright while USA Network carried the same match at 4640
            // kbps with 2x headroom. Each candidate is measured with a
            // short, bandwidth-capped probe (LiveStreamProxy.probe) so
            // checking costs live playback almost nothing.
            //
            //   /api/live/feeds?id=130           siblings + any results held
            //   /api/live/feeds?id=130&probe=1   ALSO start probing them all
            //
            // Probing runs in the BACKGROUND and this returns immediately
            // with whatever has landed so far: 52 feeds at ~10 s each is
            // minutes of work, far too long to hold an HTTP request open.
            // Poll the same URL to watch the ranking fill in.
            // Force the auto-switch decision path (sibling lookup ->
            // probe/cached -> best pick -> switch) without waiting for a
            // real stall. Shipping an auto-switch that has never been seen
            // to fire is not shipping a feature.
            uri == "/api/debug/autoswitch" && method == Method.POST -> {
                RemoteController.forceAutoSwitch()
                ok()
            }

            uri == "/api/live/feeds" -> {
                val id = p("id").orEmpty().ifBlank {
                    RemoteController.currentLiveChannelId().orEmpty()
                }
                val siblings = RemoteController.siblingFeeds(id).toMutableMap()
                if (siblings.isEmpty() && id.isNotBlank()) {
                    siblings[id] = RemoteController.liveChannels()
                        .firstOrNull { it.id == id }?.name ?: id
                }
                if (p("probe") == "1" || p("probe") == "true") {
                    RemoteController.rankFeeds(id)
                }
                val ranker = com.moviebox.tv.data.live.FeedRanker
                val schedule = com.moviebox.tv.reminders.ReminderWarm
                    .scheduleOrCached(RemoteController.liveSchedule())
                val event = schedule.firstOrNull { ev ->
                    ev.channels.any { it.id == id }
                }?.title.orEmpty()

                val arr = JSONArray()
                // Measured feeds first, best headroom at the top; unmeasured
                // trail behind in catalog order.
                val sorted = siblings.entries.sortedWith(
                    compareByDescending<Map.Entry<String, String>> {
                        ranker.cached(it.key)?.result?.headroom ?: -1f
                    }
                )
                sorted.forEach { (cid, name) ->
                    val o = JSONObject()
                        .put("id", cid)
                        .put("name", name)
                        .put("current", cid == id)
                        .put(
                            "lang",
                            com.moviebox.tv.data.live.ChannelLanguage
                                .guess(name).name,
                        )
                    ranker.cached(cid)?.result?.let { r ->
                        o.put("ok", r.ok)
                            .put("playlist_ms", r.playlistMs)
                            .put("kbps", r.throughputKbps)
                            .put("declared_kbps", r.declaredKbps)
                            .put("headroom", String.format("%.2f", r.headroom).toDouble())
                            .put("host", r.host)
                            .put("note", r.note)
                    }
                    arr.put(o)
                }
                json(
                    JSONObject()
                        .put("channel", id)
                        .put("event", event)
                        .put("probing", ranker.isProbing())
                        .put("progress", ranker.progress())
                        .put("min_headroom", ranker.MIN_HEADROOM.toDouble())
                        .put("feeds", arr)
                        .toString()
                )
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

            // Shareable diagnostics bundle. Anonymous by construction --
            // see DiagnosticsReport: no serial/IP/account, viewing titles
            // redacted, URLs reduced to host, fresh random report id.
            uri == "/api/diagnostics" -> {
                json(
                    com.moviebox.tv.debug.DiagnosticsReport.build(context)
                )
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
        // Which source this title comes from, so the SPA can badge it the way
        // the APK does (it previously showed 4KHDHub rows indistinguishably
        // from MovieBox ones).
        .put("provider", com.moviebox.tv.data.Repository.Provider.of(it.subjectId).label)

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
        .put("cover", RemoteController.nowPlayingCover ?: "")
        // Subtitle tracks + current selection for the phone's CC menu.
        .put("subtitles", JSONArray().apply {
            RemoteController.availableSubtitles.forEach { (code, name) ->
                put(JSONObject().put("code", code).put("name", name))
            }
        })
        // Source the current stream came from + the sources selectable for
        // it, so the remote can show the origin and switch it.
        // What the TV is doing right now while a title resolves ("Checking
        // VixSrc…"), so the phone shows the same reason for a wait.
        .put("loading", com.moviebox.tv.data.live.LiveStatus.message.value ?: "")
        .put("provider", RemoteController.currentProvider)
        .put("providers", JSONArray(RemoteController.providerOptions()))
        .put("subtitle", run {
            // Report the current selection only if it's still valid for
            // what's playing — content switches invalidate a stale pick.
            val cur = RemoteController.currentSubtitleLang
            if (cur != null && RemoteController.availableSubtitles.any { it.first == cur })
                cur else ""
        })
        .toString()

    /** Cap on feeds probed in one request — each costs a short read. */
    private val MAX_PROBE_FEEDS = 8

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
