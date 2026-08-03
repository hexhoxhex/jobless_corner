package com.moviebox.tv.net

import com.moviebox.tv.data.PlayInfo
import com.moviebox.tv.data.Quality
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Icefy — a TMDB-keyed adaptive-HLS provider (ported from cinepro-org/core
 * `providers/icefy`).
 *
 * One of only two cinepro providers still alive (see [VidNest]). The fastest
 * measured of the lot — 24-26 Mbps — and on completely separate
 * infrastructure, so it doesn't fail at the same time as the others, which is
 * the whole point of having it as well as VidNest.
 *
 * Two things make it fussier than it looks:
 *
 *  1. **Referer AND Origin are load-bearing.** With a User-Agent alone the
 *     edge serves a Cloudflare interactive challenge (403 "Just a moment…").
 *     With both headers it answers 200 every time — so they ride along in
 *     [PlayInfo.headers] and get attached to every segment request too.
 *
 *  2. **Segments are MPEG-TS behind a 120-byte fake PNG header** and served as
 *     `Content-Type: image/png` (PNG signature + IHDR, IEND at offset 112, TS
 *     sync 0x47 at offset 120 — verified consistent across sampled segments).
 *     ExoPlayer's TsExtractor sniffs for the sync pattern across the first
 *     188 bytes, so it sees through the wrapper without help; this is
 *     nevertheless the reason to verify this provider on-device rather than
 *     trusting a manifest fetch.
 */
object Icefy {

    const val PREFIX = "ice:"

    private const val BASE = "https://streams.icefy.top"
    private const val TAG = "Icefy"
    private const val UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"

    /** Sent on the API call AND handed back for the media requests. */
    private val STREAM_HEADERS = mapOf(
        "Referer" to BASE,
        "Origin" to BASE,
    )

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    private val STREAM_INF_RE =
        Regex("#EXT-X-STREAM-INF:[^\\n]*RESOLUTION=(\\d+)x(\\d+)[^\\n]*\\n([^\\n]+)")

    /** Resolve a TMDB id to its HLS master playlist. season/episode of 0 means
     *  "movie". Null when this provider doesn't carry the title. */
    suspend fun resolvePlay(
        tmdbId: Int,
        season: Int,
        episode: Int,
        title: String,
    ): PlayInfo? = withContext(Dispatchers.IO) {
        val path =
            if (season > 0) "/tv/$tmdbId/$season/$episode"
            else "/movie/$tmdbId"
        val raw = get(BASE + path) ?: return@withContext null
        val master = runCatching { JSONObject(raw).optString("stream") }.getOrNull()
            ?.takeIf { it.startsWith("http") } ?: return@withContext null

        // Confirm it really is a playlist before handing it to the player —
        // the endpoint answers 200 with a challenge page when the headers are
        // wrong, and that would otherwise reach ExoPlayer as "media".
        val manifest = get(master) ?: return@withContext null
        if (!manifest.trimStart().startsWith("#EXTM3U")) return@withContext null

        val ladder = STREAM_INF_RE.findAll(manifest).map { m ->
            Quality("${m.groupValues[2]}p", m.groupValues[3].trim())
        }.toList()
        android.util.Log.i(
            TAG,
            "resolved tmdb=$tmdbId s=${season}e=$episode -> ${ladder.size} renditions",
        )
        PlayInfo(
            title = title,
            // Master playlist, so ExoPlayer adapts between renditions.
            mediaUrl = master,
            selected = "Auto",
            qualities = listOf(Quality("Auto", master)) +
                ladder.sortedByDescending {
                    it.label.filter(Char::isDigit).toIntOrNull() ?: 0
                },
            captions = emptyList(),
            dubs = emptyList(),
            selectedDub = "Original",
            season = season,
            episode = episode,
            episodeTitle = title,
            durationSec = 0,
            headers = STREAM_HEADERS,
        )
    }

    private fun get(url: String): String? = runCatching {
        val b = Request.Builder().url(url).header("User-Agent", UA)
        STREAM_HEADERS.forEach { (k, v) -> b.header(k, v) }
        client.newCall(b.get().build()).execute().use {
            if (it.isSuccessful) it.body?.string() else null
        }
    }.getOrNull()
}
