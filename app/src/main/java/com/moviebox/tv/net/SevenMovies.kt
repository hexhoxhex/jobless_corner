package com.moviebox.tv.net

import com.moviebox.tv.data.PlayInfo
import com.moviebox.tv.data.Quality
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONObject
import java.io.IOException
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

/**
 * 7Movies (7movies.ac) — TMDB-keyed HLS from several servers.
 *
 * The site hosts no video. Its player is the vidrift embed, which asks its
 * own API for streams; that API answers plain HTTP (no Cloudflare check, no
 * cookies, measured 2026-10-11):
 *
 *   GET /api/boot/{movie/<tmdb> | tv/<tmdb>/<s>/<e>}  -> meta.playbackToken
 *       (signed, per episode, ~12 h) and meta.evionUrl (a partner playlist,
 *       often ""). A title it has blocked answers 451.
 *   GET /api/source/<same>?token=…&provider=<server>   -> streams[].
 *
 * Servers:
 *   - Vela: 1080p / 720p / 480p H.264 as three separate playlists (no master,
 *     so no adaptation) through vidrift's relay. 15-29 Mbps measured. Its
 *     lookup is slow the first time an episode is asked for (~9 s) and fast
 *     after (~1.3 s), so the next episode is warmed while one plays ([warm]).
 *   - Titan: one 720p H.264 playlist, direct; films come multi-audio. Answers
 *     in 1-2 s, so it is asked alongside Vela and starts playback when Vela
 *     hasn't answered within [VELA_HEAD_START_MS].
 *   - Orion: adaptive, HEVC only, on MovieBox's own CDN — skipped on a device
 *     without an HEVC decoder.
 *   - Lyra: was 502 on 2026-10-11; kept in case it comes back.
 *   - Evion: the partner playlist from boot.
 *
 * Every link is checked with [StreamProbe] before it is offered, so a server
 * whose links don't play is skipped rather than counted as a success.
 * None of the servers carries subtitles for films; Repository attaches
 * OpenSubtitles tracks.
 *
 * The site's player code changes almost daily (a server was removed on the
 * day this was written), so expect breakage; the remote provider config can
 * switch this source off without a release.
 */
object SevenMovies {

    const val PREFIX = "7m:"

    private const val BASE = "https://embed.vidrift.net"
    private const val TAG = "SevenMovies"
    private const val UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"

    /** What vidrift's own player page sends. Its CDNs answered without them
     *  on 2026-10-11, but these are what a viewer's browser would send. */
    private val STREAM_HEADERS = mapOf("Referer" to "$BASE/", "Origin" to BASE)

    /** Mirrors MainViewModel.DEFAULT_QUALITY. */
    private const val APP_DEFAULT_QUALITY = 720

    /** How long Vela, the better stream, gets before Titan starts playback. */
    private const val VELA_HEAD_START_MS = 3_500L
    /** How long, after Vela's head start, to wait for Orion's language
     *  list (it usually answers with the others). */
    private const val ORION_LIST_WAIT_MS = 2_500L

    /** Stop trying more servers once this much time has gone, so the next
     *  source in the chain still gets its turn. */
    private const val BUDGET_MS = 22_000L

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(12, TimeUnit.SECONDS)
        .callTimeout(12, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    /** For [warm]: nobody waits on it, and Vela's first lookup of an
     *  episode can take longer than [client] allows. */
    private val warmClient: OkHttpClient = client.newBuilder()
        .readTimeout(30, TimeUnit.SECONDS)
        .callTimeout(30, TimeUnit.SECONDS)
        .build()

    /** For [warm]: lookups nobody waits on. */
    private val background = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val warmed = java.util.Collections.synchronizedSet(mutableSetOf<String>())

    /** Tokens (per episode, valid ~12 h) and the Vela answer fetched by
     *  [warm], so the next episode skips both lookups when it starts. */
    private class Kept<T>(val value: T, val at: Long = android.os.SystemClock.elapsedRealtime()) {
        fun fresh(maxAgeMs: Long) = android.os.SystemClock.elapsedRealtime() - at < maxAgeMs
    }
    private val tokens = java.util.concurrent.ConcurrentHashMap<String, Kept<JSONObject>>()
    private val warmVela = java.util.concurrent.ConcurrentHashMap<String, Kept<JSONObject>>()
    private const val TOKEN_KEEP_MS = 60 * 60_000L
    private const val VELA_KEEP_MS = 10 * 60_000L

    /** Boot's meta for [path], from memory when recent. */
    private suspend fun bootMeta(path: String): JSONObject? {
        tokens[path]?.takeIf { it.fresh(TOKEN_KEEP_MS) }?.let { return it.value }
        val meta = fetchJson("$BASE/api/boot/$path")?.optJSONObject("meta") ?: return null
        if (meta.optString("playbackToken").isNotBlank()) tokens[path] = Kept(meta)
        return meta
    }

    private val hevc: Boolean by lazy {
        runCatching {
            android.media.MediaCodecList(android.media.MediaCodecList.REGULAR_CODECS)
                .codecInfos.any { info ->
                    !info.isEncoder &&
                        info.supportedTypes.any { it.equals("video/hevc", true) }
                }
        }.getOrDefault(false)
    }

    private fun pathOf(tmdbId: Int, season: Int, episode: Int) =
        if (season > 0) "tv/$tmdbId/$season/$episode" else "movie/$tmdbId"

    /** Resolve a TMDB id to a playable stream. [season]/[episode] of 0 means
     *  "movie". Null when no server has a link that plays. Subtitles are
     *  attached by the caller (Repository), which can key them by IMDb id. */
    suspend fun resolvePlay(
        tmdbId: Int,
        season: Int,
        episode: Int,
        title: String,
        resolution: String = "best",
    ): PlayInfo? = findStream(pathOf(tmdbId, season, episode), title, season, episode, resolution)

    /** Ask for an episode's Vela streams without playing it, so vidrift has
     *  them ready when the viewer gets there (the first lookup of an episode
     *  takes ~9 s, the next ~1.3 s). Once per episode per run. */
    fun warm(tmdbId: Int, season: Int, episode: Int) {
        val path = pathOf(tmdbId, season, episode)
        if (!warmed.add(path)) return
        background.launch {
            val token = bootMeta(path)?.optString("playbackToken")
                ?.takeIf { it.isNotBlank() } ?: return@launch
            val answer = fetchJson(sourceUrl(path, token, "vela"), warmClient)
            val ok = answer?.optBoolean("success") == true
            if (ok) warmVela[path] = Kept(answer!!)
            android.util.Log.i(TAG, "warmed $path: $ok")
        }
    }

    private fun sourceUrl(path: String, token: String, server: String) =
        "$BASE/api/source/$path?token=${enc(token)}&provider=$server" +
            "&hevc=${if (hevc) 1 else 0}"

    private suspend fun findStream(
        path: String, title: String, season: Int, episode: Int, resolution: String,
    ): PlayInfo? = coroutineScope {
        val started = android.os.SystemClock.elapsedRealtime()
        fun spent() = android.os.SystemClock.elapsedRealtime() - started
        val meta = bootMeta(path) ?: return@coroutineScope null
        val token = meta.optString("playbackToken").takeIf { it.isNotBlank() } ?: run {
            android.util.Log.w(TAG, "$path: no playback token")
            return@coroutineScope null
        }
        var dubbed: PlayInfo? = null

        /** The server's stream if it plays and isn't a dub; a dub is kept
         *  aside in [dubbed] as a last resort. */
        suspend fun consider(server: String, data: JSONObject?): PlayInfo? {
            if (data == null || !data.optBoolean("success")) {
                android.util.Log.i(TAG, "$path $server: nothing")
                return null
            }
            val streams = data.optJSONArray("streams") ?: return null
            val entries = (0 until streams.length()).mapNotNull { i ->
                val s = streams.optJSONObject(i) ?: return@mapNotNull null
                val raw = s.optString("url").ifBlank { s.optString("proxyUrl") }
                if (raw.isBlank()) null
                else Entry(
                    url = if (raw.startsWith("/")) BASE + raw else raw,
                    name = s.optString("name"),
                )
            }
            if (entries.isEmpty()) return null
            val info = withContext(Dispatchers.IO) {
                if (server == "vela") {
                    vela(entries, data.optInt("maxRes", 1080), resolution, title, season, episode)
                } else {
                    oneOf(server, entries, title, season, episode)
                }
            } ?: return null
            if (isDub(info.selectedDub)) {
                if (dubbed == null) dubbed = info
                return null
            }
            android.util.Log.i(
                TAG, "$path via $server (${info.selected}, ${info.selectedDub}) in ${spent()} ms",
            )
            return info
        }

        // Vela names no language. For most titles it is the original, but
        // a new release can come from a dubbed copy: VisionQuest S1E1
        // (2026-10-18) played in another language from Vela, while Orion
        // listed Original, Hindi, Tamil, Telugu and more. Orion's list is the
        // tell — when a title has dubs going round, Vela can't be trusted and
        // Orion's "Original" copy (one audio track) goes first.
        //
        // Not Titan, though it calls its stream "English": it is one MPEG-TS
        // with four audio tracks — Tamil, Telugu, Hindi, English, in that
        // order (ffprobe) — and in HLS the player reads only the FIRST audio
        // track of a TS, so the English preference can't reach the English
        // one. It played Tamil.
        val titleKey = path.split('/').take(2).joinToString("/")
        // Warmed while the previous episode played: no lookups at all —
        // unless this show is known to have dubs.
        warmVela.remove(path)?.takeIf { it.fresh(VELA_KEEP_MS) }?.let { kept ->
            if (titleKey !in dubbedTitles) {
                consider("vela", kept.value)?.let { return@coroutineScope it }
            }
        }
        // Vela and Titan are asked together; Vela gets a head start. Orion's
        // list comes along to tell whether dubs exist.
        val vela: Deferred<JSONObject?> = async { fetchJson(sourceUrl(path, token, "vela")) }
        val titan: Deferred<JSONObject?> = async { fetchJson(sourceUrl(path, token, "titan")) }
        val orion: Deferred<JSONObject?> = async { fetchJson(sourceUrl(path, token, "orion")) }
        val velaEarly = withTimeoutOrNull(VELA_HEAD_START_MS) { vela.await() }
        val orionList = withTimeoutOrNull(ORION_LIST_WAIT_MS) { orion.await() }
        val dubs = dubNames(orionList)
        if (dubs.isNotEmpty()) {
            dubbedTitles.add(titleKey)
            android.util.Log.i(
                TAG, "$path: dubs exist (${dubs.joinToString()}) — " +
                    "preferring a copy labelled English or original over Vela",
            )
            if (hevc) consider("orion", orionList)?.let { vela.cancel(); return@coroutineScope it }
            android.util.Log.i(TAG, "$path: Orion's original doesn't play here — falling back to Vela")
        }
        if (velaEarly != null) {
            consider("vela", velaEarly)?.let { titan.cancel(); return@coroutineScope it }
        }
        if (dubs.isEmpty()) {
            consider("titan", titan.await())?.let { vela.cancel(); return@coroutineScope it }
        }
        if (velaEarly == null) {
            consider("vela", vela.await())?.let { return@coroutineScope it }
        }
        for (server in listOfNotNull("orion".takeIf { hevc }, "lyra")) {
            if (spent() > BUDGET_MS) break
            val data = if (server == "orion" && orionList != null) orionList
                else fetchJson(sourceUrl(path, token, server))
            consider(server, data)?.let { return@coroutineScope it }
        }
        // The partner library, when boot named one.
        meta.optString("evionUrl").takeIf { it.isNotBlank() }?.let { url ->
            val plays = spent() <= BUDGET_MS &&
                withContext(Dispatchers.IO) { StreamProbe.check(url, STREAM_HEADERS) } != null
            if (plays) {
                android.util.Log.i(TAG, "$path via evion in ${spent()} ms")
                return@coroutineScope playInfo(
                    url, "Auto", listOf(Quality("Auto", url)), "Original", title, season, episode,
                )
            }
        }
        dubbed?.let { android.util.Log.i(TAG, "$path: only a dub (${it.selectedDub})") }
        dubbed
    }

    private data class Entry(val url: String, val name: String)

    /** Dubbed languages a server's answer lists ("Orion · Hindi" -> Hindi). */
    private fun dubNames(data: JSONObject?): List<String> {
        val streams = data?.optJSONArray("streams") ?: return emptyList()
        return (0 until streams.length()).mapNotNull { i ->
            streams.optJSONObject(i)?.optString("name")
                ?.takeIf { isDub(it) }
                ?.substringAfter('·')?.trim()
                ?.ifBlank { null }
        }.distinct()
    }

    /** Titles ("tv/213375") seen with dubs, so a warmed Vela stream for
     *  their next episode isn't used without the check. */
    private val dubbedTitles: MutableSet<String> =
        java.util.concurrent.ConcurrentHashMap.newKeySet()

    /** Vela's renditions come highest first (verified from the init
     *  segments on 2026-10-11: 1920x1080, 1280x720, 852x480). There is no
     *  master playlist, so the player can't adapt: start at the app's
     *  default quality unless the viewer picked one; a stall drops to the
     *  next lower one through the player's quality fallback. */
    private fun vela(
        entries: List<Entry>, maxRes: Int, resolution: String,
        title: String, season: Int, episode: Int,
    ): PlayInfo? {
        val ladder = listOf(2160, 1080, 720, 480, 360, 240)
            .filter { it <= (if (maxRes > 0) maxRes else 1080) }
        val qualities = entries.take(ladder.size).mapIndexed { i, e ->
            Quality("${ladder[i]}P", e.url)
        }
        if (qualities.isEmpty()) return null
        val want = when {
            resolution.equals("best", true) -> Int.MAX_VALUE
            else -> resolution.filter(Char::isDigit).toIntOrNull() ?: APP_DEFAULT_QUALITY
        }
        fun height(q: Quality) = q.label.filter(Char::isDigit).toIntOrNull() ?: 0
        val first = qualities.filter { height(it) <= want }.maxByOrNull(::height)
            ?: qualities.minByOrNull(::height)!!
        // The chosen rendition first, then the others, nearest first.
        val order = listOf(first) + qualities.filter { it != first }
            .sortedBy { kotlin.math.abs(height(it) - height(first)) }
        for (q in order) {
            val url = q.mediaUrl ?: continue
            if (StreamProbe.check(url, STREAM_HEADERS) == null) {
                android.util.Log.w(TAG, "vela ${q.label}: the stream refused us")
                continue
            }
            return playInfo(url, q.label, qualities, "Original", title, season, episode)
        }
        return null
    }

    /** Servers that answer with one stream per audio language: the original
     *  or English one, else the first that plays (held as a dub). */
    private fun oneOf(
        server: String, entries: List<Entry>, title: String, season: Int, episode: Int,
    ): PlayInfo? {
        val ordered = entries.sortedBy { if (isDub(it.name)) 1 else 0 }
        for (e in ordered) {
            if (StreamProbe.check(e.url, STREAM_HEADERS) == null) {
                android.util.Log.w(TAG, "$server ${e.name}: the stream refused us")
                continue
            }
            val language = e.name.substringAfter('·').trim().ifBlank { "Original" }
            return playInfo(e.url, "Auto", listOf(Quality("Auto", e.url)), language,
                title, season, episode)
        }
        return null
    }

    /** A named language other than the original/English one. */
    private fun isDub(name: String): Boolean {
        val n = name.lowercase()
        if (n.isBlank()) return false
        return !(n.contains("original") || n.contains("english") || n.contains("multi"))
    }

    private fun playInfo(
        url: String, selected: String, qualities: List<Quality>, language: String,
        title: String, season: Int, episode: Int,
    ) = PlayInfo(
        title = title,
        mediaUrl = url,
        selected = selected,
        qualities = qualities,
        captions = emptyList(),
        dubs = emptyList(),
        selectedDub = language,
        season = season,
        episode = episode,
        episodeTitle = title,
        durationSec = 0,
        headers = STREAM_HEADERS,
        hls = true,
    )

    /** GET JSON; cancelling the coroutine cancels the request, so a server
     *  that loses the race doesn't keep the caller waiting. */
    private suspend fun fetchJson(url: String, via: OkHttpClient = client): JSONObject? =
        suspendCancellableCoroutine { cont ->
            val call = via.newCall(
                Request.Builder().url(url)
                    .header("User-Agent", UA)
                    .header("Referer", "$BASE/embed2/play")
                    .header("Accept", "application/json")
                    .get().build(),
            )
            cont.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (cont.isActive) cont.resume(null)
                }

                override fun onResponse(call: Call, response: Response) {
                    val json = response.use { r ->
                        if (!r.isSuccessful) {
                            // 451 "Playback is not available for this title"
                            // is how it refuses a title it has blocked
                            // (Breaking Bad, 2026-10-11).
                            android.util.Log.i(
                                TAG,
                                "${url.substringAfter("/api/").substringBefore('?')}: " +
                                    "HTTP ${r.code} ${runCatching { r.peekBody(200).string() }
                                        .getOrDefault("").take(120)}",
                            )
                            null
                        } else {
                            runCatching { JSONObject(r.body?.string().orEmpty()) }.getOrNull()
                        }
                    }
                    if (cont.isActive) cont.resume(json)
                }
            })
        }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
}
