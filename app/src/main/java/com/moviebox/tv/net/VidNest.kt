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

    /** Resolve a TMDB id to a playable HLS stream. season/episode of 0 means
     *  "movie". Returns null when no sub-server carries the title, so the
     *  caller falls through to the next provider. */
    suspend fun resolvePlay(
        tmdbId: Int,
        season: Int,
        episode: Int,
        title: String,
    ): PlayInfo? = withContext(Dispatchers.IO) {
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
            val chosen = entries.firstOrNull {
                it.optString("language").equals("English", true)
            } ?: entries.first()

            val url = chosen.optString("url")
            val headers = chosen.optJSONObject("headers")?.let { h ->
                h.keys().asSequence().associateWith { k -> h.optString(k) }
                    .filterValues { v -> v.isNotBlank() }
            }.orEmpty()
            val language = chosen.optString("language").ifBlank { "Original" }
            android.util.Log.i(
                TAG,
                "resolved tmdb=$tmdbId s=${season}e=$episode via $server " +
                    "(${entries.size} audio tracks, picked $language)",
            )
            return@withContext PlayInfo(
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
            )
        }
        null
    }

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
