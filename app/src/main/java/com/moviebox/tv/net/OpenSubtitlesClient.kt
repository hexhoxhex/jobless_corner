package com.moviebox.tv.net

import android.util.Log
import com.moviebox.tv.App
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Fetches external English subtitles when the aoneroom catalog serves none —
 * the common case for titles like Family Guy whose files are a single
 * foreign-dubbed audio track with zero captions.
 *
 * Uses two KEYLESS Stremio community addons (no API key, no account, no
 * quota gate — they proxy OpenSubtitles for the Stremio ecosystem):
 *   1. Cinemeta   — title  → IMDB id
 *        GET https://v3-cinemeta.strem.io/catalog/{movie|series}/top/search={q}.json
 *   2. OpenSubtitles v3 — IMDB id (+ SxEx) → subtitle download URLs
 *        GET https://opensubtitles-v3.strem.io/subtitles/series/{imdb}:{s}:{e}.json
 *        GET https://opensubtitles-v3.strem.io/subtitles/movie/{imdb}.json
 *
 * The subtitle URL serves plain UTF-8 SRT, which we download once and cache
 * to filesDir/subs/<key>.srt so re-watching an episode never re-fetches. The
 * cache never expires (a subtitle for S8E1 doesn't change). Return value is a
 * file:// URI ExoPlayer loads as a SubtitleConfiguration.
 *
 * Keyless legacy note: the direct OpenSubtitles.com REST API needs a
 * registered Api-Key and the classic rest.opensubtitles.org endpoint now
 * 302-redirects to a deprecation page; the Stremio addons are the reliable
 * no-key path as of 2026-07.
 */
object OpenSubtitlesClient {

    private const val TAG = "OpenSubs"
    private const val CINEMETA = "https://v3-cinemeta.strem.io/catalog"
    private const val OPENSUBS = "https://opensubtitles-v3.strem.io/subtitles"
    private const val UA =
        "Mozilla/5.0 (Linux; Android 12) MovieBoxTV/0.1"

    private val http: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(12, TimeUnit.SECONDS)
            .followRedirects(true)
            .build()
    }

    private val cacheDir: File by lazy {
        File(App.instance.filesDir, "subs").apply { mkdirs() }
    }

    /**
     * Return a `file://` URI to a cached English .srt for the given title,
     * resolving IMDB id + fetching + caching if we don't already have one.
     * Null on any miss (no IMDB match, no English sub, network error).
     * Hops to IO internally — safe to call from any dispatcher.
     *
     * @param season/episode 0 for a movie; >0 for a series episode.
     */
    suspend fun findEnglish(
        title: String, season: Int, episode: Int,
    ): String? = withContext(Dispatchers.IO) {
        val cleanTitle = cleanTitle(title)
        if (cleanTitle.isBlank()) return@withContext null
        val isSeries = season > 0

        val cacheKey = buildString {
            append(cleanTitle.lowercase().replace(Regex("[^a-z0-9]+"), "_"))
            if (isSeries) append("_s${season}e${episode}")
        }.trim('_')
        val cacheFile = File(cacheDir, "$cacheKey.srt")
        if (cacheFile.exists() && cacheFile.length() > 0) {
            return@withContext cacheFile.toUri()
        }

        val imdb = runCatching { searchImdbId(cleanTitle, isSeries) }.getOrNull()
            ?: return@withContext null
        val subUrl = runCatching { subtitleUrl(imdb, season, episode) }.getOrNull()
            ?: return@withContext null
        val ok = runCatching { downloadTo(subUrl, cacheFile) }.getOrDefault(false)
        if (ok) cacheFile.toUri() else null
    }

    /** Cinemeta title → IMDB id (e.g. "tt0182576"). Picks the first result
     *  whose name matches (case-insensitive) or, failing an exact match,
     *  the top (most-popular) result. */
    private fun searchImdbId(title: String, isSeries: Boolean): String? {
        val type = if (isSeries) "series" else "movie"
        val q = java.net.URLEncoder.encode(title, "UTF-8")
        val req = Request.Builder()
            .url("$CINEMETA/$type/top/search=$q.json")
            .header("User-Agent", UA).header("Accept", "application/json")
            .get().build()
        http.newCall(req).execute().use { r ->
            if (!r.isSuccessful) return null
            val metas = JSONObject(r.body?.string().orEmpty())
                .optJSONArray("metas") ?: return null
            if (metas.length() == 0) return null
            // Prefer an exact name match; else take the top result.
            var fallback: String? = null
            for (i in 0 until metas.length()) {
                val m = metas.getJSONObject(i)
                val id = m.optString("id").takeIf { it.startsWith("tt") } ?: continue
                if (fallback == null) fallback = id
                if (m.optString("name").equals(title, ignoreCase = true)) {
                    Log.i(TAG, "imdb '$title' -> $id (exact)")
                    return id
                }
            }
            Log.i(TAG, "imdb '$title' -> $fallback (top)")
            return fallback
        }
    }

    /** OpenSubtitles v3 addon: IMDB id (+ SxEx) → first English SRT URL. */
    private fun subtitleUrl(imdb: String, season: Int, episode: Int): String? {
        val path = if (season > 0) "series/$imdb:$season:$episode"
        else "movie/$imdb"
        val req = Request.Builder()
            .url("$OPENSUBS/$path.json")
            .header("User-Agent", UA).header("Accept", "application/json")
            .get().build()
        http.newCall(req).execute().use { r ->
            if (!r.isSuccessful) return null
            val subs = JSONObject(r.body?.string().orEmpty())
                .optJSONArray("subtitles") ?: return null
            for (i in 0 until subs.length()) {
                val s = subs.getJSONObject(i)
                // lang codes here are 3-letter ("eng"); accept en/eng.
                val lang = s.optString("lang")
                if (lang.equals("eng", true) || lang.equals("en", true)) {
                    val url = s.optString("url").takeIf { it.isNotBlank() }
                    if (url != null) {
                        Log.i(TAG, "sub $path -> ${url.take(80)}")
                        return url
                    }
                }
            }
            Log.w(TAG, "no English sub for $path")
            return null
        }
    }

    /** Download the .srt at [url] into [dest]. Returns true on success. */
    private fun downloadTo(url: String, dest: File): Boolean {
        val req = Request.Builder().url(url).header("User-Agent", UA).get().build()
        http.newCall(req).execute().use { r ->
            if (!r.isSuccessful) return false
            val bytes = r.body?.bytes() ?: return false
            // Sanity: an SRT starts with "1" + timestamps; guard against an
            // error page slipping through as a subtitle.
            if (bytes.size < 32) return false
            dest.writeBytes(bytes)
            Log.i(TAG, "cached ${bytes.size}B -> ${dest.name}")
            return true
        }
    }

    private fun File.toUri(): String = "file://${this.absolutePath}"

    /** Strip catalog cruft so the Cinemeta search matches the canonical
     *  name. aoneroom appends " S1-S23", "(2021)", "Season N", etc. */
    private fun cleanTitle(raw: String): String =
        raw.replace(Regex("\\bS\\d+\\s*-\\s*S\\d+\\b", RegexOption.IGNORE_CASE), "")
            .replace(Regex("\\bSeason\\s*\\d+.*$", RegexOption.IGNORE_CASE), "")
            .replace(Regex("\\(\\d{4}\\)"), "")
            .replace(Regex("\\s{2,}"), " ")
            .trim()
}
