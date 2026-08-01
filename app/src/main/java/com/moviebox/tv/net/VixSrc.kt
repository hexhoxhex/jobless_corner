package com.moviebox.tv.net

import com.moviebox.tv.data.CaptionTrack
import com.moviebox.tv.data.PlayInfo
import com.moviebox.tv.data.Quality
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * VixSrc — a TMDB-keyed HLS provider (ported from cinepro-org/core's
 * `providers/vixsrc`).
 *
 * Why this provider matters: it serves an ADAPTIVE HLS ladder
 * (1080p ~4.5 Mbps / 720p ~1.8 / 480p ~1.1, all H.264 avc1) rather than a
 * single fixed-bitrate file. ExoPlayer negotiates down when the pipe is thin,
 * so it degrades to a lower rendition instead of buffer-starving into the
 * one-frame-at-a-time freeze that multi-GB [FourKHdHub] rips produce. It also
 * carries real audio + subtitle renditions in the manifest.
 *
 * Addressed purely by TMDB id, which the catalog already carries — no title
 * matching, so no wrong-movie risk.
 *
 * Chain: /api/movie/{tmdb} -> {src} embed -> page vars (token/expires/url)
 * -> master playlist. Tokens are short-lived and per-request, so the whole
 * chain must run in one pass (re-fetching the page mints a DIFFERENT token
 * that won't validate against an earlier playlist URL).
 */
object VixSrc {

    const val PREFIX = "vix:"

    private const val BASE = "https://vixsrc.to"
    private const val UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/150 Safari/537.36"
    private const val TAG = "VixSrc"

    /** Mirrors MainViewModel.DEFAULT_QUALITY — the app-wide default, which
     *  must not be mistaken for a deliberate user quality pick. */
    private const val APP_DEFAULT_QUALITY = "720p"

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    private val TOKEN_RE = Regex("token[\"']\\s*:\\s*[\"']([^\"']+)")
    private val EXPIRES_RE = Regex("expires[\"']\\s*:\\s*[\"']([^\"']+)")
    private val PLAYLIST_RE = Regex("url\\s*:\\s*[\"']([^\"']+)")
    private val STREAM_INF_RE =
        Regex("#EXT-X-STREAM-INF:[^\\n]*RESOLUTION=(\\d+)x(\\d+)[^\\n]*\\n([^\\n]+)")
    private val SUBS_RE =
        Regex("#EXT-X-MEDIA:TYPE=SUBTITLES[^\\n]*LANGUAGE=\"([^\"]+)\"[^\\n]*URI=\"([^\"]+)\"")
    private val SUBS_NAME_RE = Regex("NAME=\"([^\"]+)\"")

    /** Resolve a TMDB id to a playable HLS master playlist.
     *  [season]/[episode] of 0 means "movie". Returns null when this provider
     *  doesn't carry the title, so the caller can fall through to another. */
    suspend fun resolvePlay(
        tmdbId: Int,
        season: Int,
        episode: Int,
        title: String,
        resolution: String = "best",
    ): PlayInfo? = withContext(Dispatchers.IO) {
        val apiUrl =
            if (season > 0) "$BASE/api/tv/$tmdbId/$season/$episode"
            else "$BASE/api/movie/$tmdbId"
        val apiRaw = get(apiUrl, "$BASE/") ?: return@withContext null
        val src = runCatching { JSONObject(apiRaw).optString("src") }.getOrNull()
            ?.takeIf { it.isNotBlank() } ?: return@withContext null
        val embedUrl = if (src.startsWith("http")) src else BASE + src

        // One fetch — token/expires/url must come from the SAME response.
        val html = get(embedUrl, "$BASE/") ?: return@withContext null
        val token = TOKEN_RE.find(html)?.groupValues?.get(1) ?: return@withContext null
        val expires = EXPIRES_RE.find(html)?.groupValues?.get(1) ?: return@withContext null
        val playlist = PLAYLIST_RE.find(html)?.groupValues?.get(1) ?: return@withContext null
        if (expiredAt(expires)) return@withContext null

        val sep = if (playlist.contains("?")) "&" else "?"
        val master = "$playlist${sep}token=$token&expires=$expires&h=1"
        val manifest = get(master, embedUrl) ?: return@withContext null
        if (!manifest.startsWith("#EXTM3U")) return@withContext null

        // Hand ExoPlayer the MASTER url so it can adapt between renditions —
        // that adaptation is the whole point of using this provider. The
        // per-rendition entries are exposed as manual quality options.
        val ladder = STREAM_INF_RE.findAll(manifest).map { m ->
            val height = m.groupValues[2]
            Quality("${height}p", m.groupValues[3].trim())
        }.toList()
        val captions = SUBS_RE.findAll(manifest).mapNotNull { m ->
            val lang = m.groupValues[1]
            val uri = m.groupValues[2]
            val name = SUBS_NAME_RE.find(m.value)?.groupValues?.get(1) ?: lang
            if (uri.isBlank()) null else CaptionTrack(lang, name, uri)
        }.distinctBy { it.code }.toList()

        val qualities = buildList {
            add(Quality("Auto", master))
            addAll(ladder.sortedByDescending { it.label.filter(Char::isDigit).toIntOrNull() ?: 0 })
        }
        // Default to the MASTER playlist ("Auto"), never a fixed rendition.
        // Handing ExoPlayer a single-rendition media playlist disables ABR —
        // measured on a thin link that pinned 720p (1.8 Mbps) to a 226 ms
        // buffer and constant rebuffering, while the master drops to 480p
        // (1.1 Mbps) and holds. Only an EXPLICIT user quality pick (i.e. one
        // that isn't the app-wide default) selects a fixed rendition.
        // The app-wide default ("720p") is NOT a user choice — it happens to
        // match a rendition label, which is how ABR got disabled by accident.
        val explicit = resolution.isNotBlank() &&
            !resolution.equals("best", true) &&
            !resolution.equals("auto", true) &&
            !resolution.equals(APP_DEFAULT_QUALITY, true)
        val selected =
            (if (explicit) qualities.firstOrNull { it.label.equals(resolution, true) } else null)
                ?: qualities.first()
        android.util.Log.i(
            TAG,
            "resolved tmdb=$tmdbId s=${season}e=$episode -> ${ladder.size} renditions, " +
                "${captions.size} subs",
        )
        PlayInfo(
            title = title,
            mediaUrl = selected.mediaUrl ?: master,
            selected = selected.label,
            qualities = qualities,
            captions = captions,
            dubs = emptyList(),
            selectedDub = "Original",
            season = season,
            episode = episode,
            episodeTitle = title,
            durationSec = 0,
        )
    }

    private fun expiredAt(expires: String): Boolean {
        val secs = expires.toLongOrNull() ?: return false
        return secs * 1000L < System.currentTimeMillis()
    }

    private fun get(url: String, referer: String?): String? = runCatching {
        val b = Request.Builder().url(url).header("User-Agent", UA)
        referer?.let { b.header("Referer", it) }
        client.newCall(b.get().build()).execute().use {
            if (it.isSuccessful) it.body?.string() else null
        }
    }.getOrNull()
}
