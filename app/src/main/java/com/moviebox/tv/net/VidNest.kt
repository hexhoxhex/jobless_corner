package com.moviebox.tv.net

import com.moviebox.tv.data.PlayInfo
import com.moviebox.tv.data.Quality
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * VidNest — a TMDB-keyed adaptive-HLS provider (ported from cinepro-org/core
 * `providers/vidnest`).
 *
 * Chosen after live-testing all 16 cinepro providers: only this one and
 * [Icefy] still work. Nine are dead (dead DNS, dead endpoints, rotated keys)
 * and four sit behind captcha/attestation walls. This one needs no auth, no
 * cookies and no real crypto — the payload is plain JSON under a custom
 * base64 ALPHABET, which is obfuscation rather than encryption.
 *
 * Measured 2026-08-03: 12-16 Mbps, a 4-rendition ladder up to 1080p, clean
 * `video/mp2t` segments, hits for both movies and TV.
 *
 * The CDN 404s without the exact Referer, and the payload TELLS US which one
 * to use (it differs per sub-server), so it travels back in
 * [PlayInfo.headers] rather than being hardcoded.
 */
object VidNest {

    const val PREFIX = "vn:"

    private const val BASE = "https://new.vidnest.fun"
    private const val TAG = "VidNest"
    private const val UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"

    /** Sub-servers in preference order. `allmovies` was the most reliable in
     *  testing (8/8 catalogue hits, movies and TV); `hollymoviehd` is a solid
     *  second and also offers a progressive MP4. `moviebox` is deliberately
     *  omitted: it resolves to the same hakunaymatata CDN the MovieBox
     *  provider already uses AND rate-limited (428/429) on every attempt. */
    private val SERVERS = listOf("allmovies", "hollymoviehd")

    /** The payload is standard base64 with a shuffled alphabet. */
    private const val CUSTOM =
        "RB0fpH8ZEyVLkv7c2i6MAJ5u3IKFDxlS1NTsnGaqmXYdUrtzjwObCgQP94hoeW+/="
    private const val STANDARD =
        "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/="

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    /** For the stream check: a link that hasn't answered in 6 s is no use. */
    private val probeClient: OkHttpClient = client.newBuilder()
        .callTimeout(6, TimeUnit.SECONDS)
        .build()

    /** Resolve a TMDB id to a playable HLS stream. season/episode of 0 means
     *  "movie". Returns null when no sub-server carries the title, so the
     *  caller falls through to the next provider. */
    suspend fun resolvePlay(
        tmdbId: Int,
        season: Int,
        episode: Int,
        title: String,
    ): PlayInfo? = withContext(Dispatchers.IO) {
        // A server that only carries a dub is a last resort, not an answer.
        // This used to take the first server that returned anything, so
        // Spider-Man came back from `allmovies` with its single Hindi track
        // and played in Hindi — with no way to change it. Keep looking for
        // an English track across the remaining servers first.
        var dubbedFallback: PlayInfo? = null
        for (server in SERVERS) {
            val path =
                if (season > 0) "/$server/tv/$tmdbId/$season/$episode"
                else "/$server/movie/$tmdbId"
            val raw = get(BASE + path) ?: continue
            val payload = decodePayload(raw) ?: continue
            val streams = payload.optJSONArray("streams") ?: continue

            // Prefer an English track. The provider returns one stream entry
            // per audio language (English/Hindi/Tamil/Telugu), not per
            // quality — picking blind lands the viewer in a dub.
            val entries = (0 until streams.length()).mapNotNull {
                runCatching { streams.getJSONObject(it) }.getOrNull()
            }.filter { it.optString("url").isNotBlank() }
            if (entries.isEmpty()) continue
            // English first, then the rest in the order given — each one is
            // only taken if its stream actually answers.
            val ordered = entries.sortedByDescending {
                it.optString("language").equals("English", true)
            }
            for (chosen in ordered) {
                val url = chosen.optString("url")
                val headers = chosen.optJSONObject("headers")?.let { h ->
                    h.keys().asSequence().associateWith { k -> h.optString(k) }
                        .filterValues { v -> v.isNotBlank() }
                }.orEmpty()
                val language = chosen.optString("language").ifBlank { "Original" }
                val kind = probe(url, headers)
                if (kind == null) {
                    android.util.Log.w(
                        TAG,
                        "$server $language: the stream refused us — skipping " +
                            "(${url.substringBefore('?').take(80)})",
                    )
                    continue
                }
                android.util.Log.i(
                    TAG,
                    "resolved tmdb=$tmdbId s=${season}e=$episode via $server " +
                        "(${entries.size} audio tracks, picked $language, $kind)",
                )
                val info = PlayInfo(
                    title = title,
                    // The master playlist — ExoPlayer adapts across its renditions.
                    mediaUrl = url,
                    selected = "Auto",
                    qualities = listOf(Quality("Auto", url)),
                    captions = emptyList(),
                    dubs = emptyList(),
                    selectedDub = language,
                    season = season,
                    episode = episode,
                    episodeTitle = title,
                    durationSec = 0,
                    headers = headers,
                    hls = kind == "hls" || chosen.optString("type").equals("hls", true),
                )
                // "Original" means the provider named no language, which for this
                // catalogue is the original soundtrack — good enough. Anything
                // else named is a dub: hold it aside and try the next server.
                if (language.equals("English", true) || language == "Original") {
                    return@withContext info
                }
                if (dubbedFallback == null) dubbedFallback = info
                break
            }
        }
        dubbedFallback
    }

    /**
     * Check the stream the way the player will fetch it (same headers):
     * "hls" for a playlist whose first variant and first segment answer,
     * "file" for anything else that answers, null when it refuses.
     *
     * VidNest keeps handing out links that don't play (goodstream.cc,
     * 2026-10-09: the player got 403). Without this check that counted as a
     * success, the app remembered VidNest for the show, and the player sat on
     * the error. The top playlist can answer while what's behind it doesn't,
     * so this follows it down to one segment.
     */
    private fun probe(url: String, headers: Map<String, String>): String? = runCatching {
        val h = Constants.mediaHeadersFor(headers)
        fun request(u: String) = Request.Builder().url(u).apply {
            h.forEach { (k, v) -> header(k, v) }
        }
        // The start of [u]'s body, or null when it refuses or serves a page
        // (a challenge or error) instead of media.
        fun open(u: String): String? =
            probeClient.newCall(request(u).get().build()).execute().use { r ->
                if (!r.isSuccessful) return@use null
                val head = r.peekBody(64L * 1024).string()
                    .trimStart('\uFEFF', ' ', '\r', '\n', '\t')
                if (r.header("Content-Type").orEmpty().contains("text/html", true) ||
                    head.startsWith("<")
                ) null else head
            }
        fun firstUri(playlist: String, base: String): String? =
            playlist.lineSequence().map { it.trim() }
                .firstOrNull { it.isNotEmpty() && !it.startsWith("#") }
                ?.let { base.toHttpUrlOrNull()?.resolve(it)?.toString() }

        var text = open(url) ?: return@runCatching null
        if (!text.startsWith("#EXTM3U")) return@runCatching "file"
        var base = url
        if (text.contains("#EXT-X-STREAM-INF")) {
            val variant = firstUri(text, base) ?: return@runCatching null
            text = open(variant)?.takeIf { it.startsWith("#EXTM3U") }
                ?: return@runCatching null
            base = variant
        }
        val segment = firstUri(text, base) ?: return@runCatching "hls"
        val ok = probeClient.newCall(
            request(segment).header("Range", "bytes=0-1023").get().build(),
        ).execute().use { it.isSuccessful }
        if (ok) "hls" else null
    }.getOrNull()

    /** `{"data":"<custom-base64>"}` → the decoded JSON object. */
    private fun decodePayload(raw: String): JSONObject? = runCatching {
        val data = JSONObject(raw).optString("data").takeIf { it.isNotBlank() }
            ?: return null
        val translated = buildString(data.length) {
            for (c in data) {
                val i = CUSTOM.indexOf(c)
                append(if (i >= 0) STANDARD[i] else c)
            }
        }
        val bytes = android.util.Base64.decode(translated, android.util.Base64.DEFAULT)
        JSONObject(String(bytes, Charsets.UTF_8))
    }.getOrNull()

    private fun get(url: String): String? = runCatching {
        client.newCall(
            Request.Builder().url(url).header("User-Agent", UA).get().build(),
        ).execute().use { if (it.isSuccessful) it.body?.string() else null }
    }.getOrNull()
}
