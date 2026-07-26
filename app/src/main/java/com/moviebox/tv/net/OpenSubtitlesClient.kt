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
 * Fetches external subtitles in ANY language when the aoneroom catalog
 * serves none — the common case for titles whose files are a single
 * foreign-dubbed audio track (Family Guy S8E1 = Spanish audio, no captions;
 * K-dramas served with only the original Korean audio; etc.).
 *
 * Uses two KEYLESS Stremio community addons (no API key, no account, no
 * quota gate — they proxy OpenSubtitles):
 *   1. Cinemeta        title → IMDB id
 *        v3-cinemeta.strem.io/catalog/{movie|series}/top/search={q}.json
 *   2. OpenSubtitles v3  IMDB id (+ SxEx) → subtitle URLs in ~45 languages
 *        opensubtitles-v3.strem.io/subtitles/series/{imdb}:{s}:{e}.json
 *        opensubtitles-v3.strem.io/subtitles/movie/{imdb}.json
 *
 * [list] returns one [Sub] per available language (the top-ranked file for
 * that language). The `.url` is the addon's direct SRT link — passed straight
 * to ExoPlayer as a SubtitleConfiguration, so only the language the user
 * actually selects gets downloaded, not all 45. The resolved IMDB id is
 * cached in memory so re-opening episodes of the same show skips the
 * Cinemeta lookup.
 */
object OpenSubtitlesClient {

    private const val TAG = "OpenSubs"
    private const val CINEMETA = "https://v3-cinemeta.strem.io/catalog"
    private const val OPENSUBS = "https://opensubtitles-v3.strem.io/subtitles"
    private const val UA = "Mozilla/5.0 (Linux; Android 12) MovieBoxTV/0.1"

    /** One selectable subtitle track. [code] is ISO-639-1 (en, ko, es…) for
     *  ExoPlayer's setPreferredTextLanguage; [name] is the display label. */
    data class Sub(val code: String, val name: String, val url: String)

    private val http: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(12, TimeUnit.SECONDS)
            .followRedirects(true)
            .build()
    }

    // title(clean)|series? → imdb id. Small in-memory cache.
    private val imdbCache = java.util.concurrent.ConcurrentHashMap<String, String>()

    /**
     * All available subtitle languages for the title, one entry per language.
     * Empty on any miss (no IMDB match, addon down, no subs). Hops to IO.
     *
     * @param season/episode 0 for a movie; >0 for a series episode.
     */
    suspend fun list(
        title: String, season: Int, episode: Int,
    ): List<Sub> = withContext(Dispatchers.IO) {
        val cleanTitle = cleanTitle(title)
        if (cleanTitle.isBlank()) return@withContext emptyList()
        val isSeries = season > 0
        val imdb = runCatching { imdbId(cleanTitle, isSeries) }.getOrNull()
            ?: return@withContext emptyList()
        runCatching { subtitlesFor(imdb, season, episode) }.getOrDefault(emptyList())
    }

    private fun imdbId(title: String, isSeries: Boolean): String? {
        val key = title.lowercase()
        imdbCache[key]?.let { return it }
        val want = normalizeName(title)
        if (want.isBlank()) return null
        // Search BOTH catalogs (the requested type first) — aoneroom's
        // isSeries flag is sometimes wrong (seasons=[] on a real series), so
        // trusting it alone matched a same-named MOVIE for a SERIES (the
        // "Stranger Things shows a wrong movie trailer" bug). Collect
        // candidates from both, then require an EXACT normalized-name match.
        // NO loose "top result" fallback — a near-miss title (e.g. "Affinity"
        // matching a different Asian film) was pulling wrong-language subs and
        // wrong trailers. Better to show nothing than the wrong thing.
        val primary = if (isSeries) "series" else "movie"
        val secondary = if (isSeries) "movie" else "series"
        data class Cand(val id: String, val name: String, val type: String)
        val cands = mutableListOf<Cand>()
        for (type in listOf(primary, secondary)) {
            val q = java.net.URLEncoder.encode(title, "UTF-8")
            val req = Request.Builder()
                .url("$CINEMETA/$type/top/search=$q.json")
                .header("User-Agent", UA).header("Accept", "application/json")
                .get().build()
            runCatching {
                http.newCall(req).execute().use { r ->
                    if (!r.isSuccessful) return@use
                    val metas = JSONObject(r.body?.string().orEmpty())
                        .optJSONArray("metas") ?: return@use
                    for (i in 0 until metas.length()) {
                        val m = metas.getJSONObject(i)
                        val id = m.optString("id").takeIf { it.startsWith("tt") }
                            ?: continue
                        cands.add(Cand(id, m.optString("name"), type))
                    }
                }
            }
        }
        // Exact normalized-name match, preferring the requested type.
        val match = cands.firstOrNull {
            normalizeName(it.name) == want && it.type == primary
        } ?: cands.firstOrNull { normalizeName(it.name) == want }
        if (match != null) {
            imdbCache[key] = match.id
            Log.i(TAG, "imdb '$title' -> ${match.id} (${match.type}, exact)")
            return match.id
        }
        Log.w(TAG, "imdb '$title' -> no exact match (${cands.size} candidates)")
        return null
    }

    /** Lowercase, strip punctuation + edition/year noise, collapse spaces —
     *  so "Marvel's Agents of S.H.I.E.L.D." and "Marvel Agents of SHIELD"
     *  compare equal, but "Affinity" and "Affinity: Chapter 2" don't. */
    private fun normalizeName(s: String): String =
        s.lowercase()
            .replace(Regex("\\(\\d{4}\\)"), "")
            .replace(Regex("[^a-z0-9]+"), " ")
            .trim()

    /** Query the addon and return one Sub per language (top file per lang). */
    private fun subtitlesFor(imdb: String, season: Int, episode: Int): List<Sub> {
        val path = if (season > 0) "series/$imdb:$season:$episode" else "movie/$imdb"
        val req = Request.Builder()
            .url("$OPENSUBS/$path.json")
            .header("User-Agent", UA).header("Accept", "application/json")
            .get().build()
        http.newCall(req).execute().use { r ->
            if (!r.isSuccessful) return emptyList()
            val arr = JSONObject(r.body?.string().orEmpty())
                .optJSONArray("subtitles") ?: return emptyList()
            // First occurrence per language wins (addon orders by relevance).
            val byLang = LinkedHashMap<String, Sub>()
            for (i in 0 until arr.length()) {
                val s = arr.getJSONObject(i)
                val raw = s.optString("lang").lowercase()
                val url = s.optString("url").takeIf { it.isNotBlank() } ?: continue
                val code = LANG_2.getOrDefault(raw, raw.take(2))
                if (byLang.containsKey(code)) continue
                val name = LANG_NAMES.getOrDefault(raw, raw.uppercase())
                byLang[code] = Sub(code, name, url)
            }
            // Sort: English first (most-wanted default), then alphabetical.
            val subs = byLang.values.sortedWith(
                compareByDescending<Sub> { it.code == "en" }.thenBy { it.name }
            )
            Log.i(TAG, "subs $path -> ${subs.size} languages: " +
                subs.joinToString(",") { it.code })
            return subs
        }
    }

    /**
     * YouTube video id for the title's trailer, via Cinemeta's meta endpoint
     * (keyless): title → IMDB id → meta.trailerStreams[0].ytId. Null on any
     * miss. Reuses the same IMDB resolution as [list] (cached). Lives here
     * because this object already wraps the Stremio addons; the trailer meta
     * and the subtitle lookup share the Cinemeta base + IMDB cache.
     */
    suspend fun trailerYouTubeId(
        title: String, isSeries: Boolean,
    ): String? = withContext(Dispatchers.IO) {
        val cleanTitle = cleanTitle(title)
        if (cleanTitle.isBlank()) return@withContext null
        val imdb = runCatching { imdbId(cleanTitle, isSeries) }.getOrNull()
            ?: return@withContext null
        val type = if (isSeries) "series" else "movie"
        val req = Request.Builder()
            .url("https://v3-cinemeta.strem.io/meta/$type/$imdb.json")
            .header("User-Agent", UA).header("Accept", "application/json")
            .get().build()
        runCatching {
            http.newCall(req).execute().use { r ->
                if (!r.isSuccessful) return@use null
                val meta = JSONObject(r.body?.string().orEmpty())
                    .optJSONObject("meta") ?: return@use null
                // Prefer trailerStreams[].ytId; fall back to trailers[].source.
                meta.optJSONArray("trailerStreams")?.let { arr ->
                    for (i in 0 until arr.length()) {
                        arr.getJSONObject(i).optString("ytId")
                            .takeIf { it.isNotBlank() }?.let { return@use it }
                    }
                }
                meta.optJSONArray("trailers")?.let { arr ->
                    for (i in 0 until arr.length()) {
                        arr.getJSONObject(i).optString("source")
                            .takeIf { it.isNotBlank() }?.let { return@use it }
                    }
                }
                null
            }
        }.getOrNull().also {
            Log.i(TAG, "trailer '$title' -> ${it ?: "none"}")
        }
    }

    /** Optional download-and-cache — kept for callers that want a stable
     *  local file:// (e.g. offline). Not used by the direct-URL path. */
    suspend fun cache(sub: Sub, keyHint: String): String? =
        withContext(Dispatchers.IO) {
            val dir = File(App.instance.filesDir, "subs").apply { mkdirs() }
            val f = File(dir, "${keyHint}_${sub.code}.srt")
            if (f.exists() && f.length() > 0) return@withContext "file://${f.absolutePath}"
            val req = Request.Builder().url(sub.url).header("User-Agent", UA).get().build()
            runCatching {
                http.newCall(req).execute().use { r ->
                    val bytes = r.body?.bytes() ?: return@use null
                    if (bytes.size < 32) return@use null
                    f.writeBytes(bytes)
                    "file://${f.absolutePath}"
                }
            }.getOrNull()
        }

    private fun cleanTitle(raw: String): String =
        raw.replace(Regex("\\bS\\d+\\s*-\\s*S\\d+\\b", RegexOption.IGNORE_CASE), "")
            .replace(Regex("\\bSeason\\s*\\d+.*$", RegexOption.IGNORE_CASE), "")
            .replace(Regex("\\(\\d{4}\\)"), "")
            .replace(Regex("\\s{2,}"), " ")
            .trim()

    // ISO-639-2/B (OpenSubtitles' codes) → ISO-639-1 for ExoPlayer.
    private val LANG_2 = mapOf(
        "eng" to "en", "spa" to "es", "fre" to "fr", "ger" to "de",
        "ita" to "it", "por" to "pt", "pob" to "pt", "rus" to "ru",
        "kor" to "ko", "jpn" to "ja", "chi" to "zh", "zht" to "zh",
        "ara" to "ar", "hin" to "hi", "tur" to "tr", "dut" to "nl",
        "pol" to "pl", "swe" to "sv", "dan" to "da", "fin" to "fi",
        "nor" to "no", "gre" to "el", "ell" to "el", "heb" to "he",
        "tha" to "th", "vie" to "vi", "ind" to "id", "cze" to "cs",
        "hun" to "hu", "rum" to "ro", "ron" to "ro", "ukr" to "uk",
        "hrv" to "hr", "srp" to "sr", "slv" to "sl", "bul" to "bg",
        "cat" to "ca", "ben" to "bn", "est" to "et", "slo" to "sk",
        "may" to "ms", "fil" to "tl", "tgl" to "tl", "per" to "fa",
        // Extra code spellings the addon occasionally emits.
        "nld" to "nl", "spn" to "es", "sp" to "es", "hbs-srp" to "sr",
        "hbs" to "sr", "scc" to "sr", "scr" to "hr", "cze-ces" to "cs",
        "ge" to "de", "chs" to "zh", "cht" to "zh",
    )
    private val LANG_NAMES = mapOf(
        "eng" to "English", "spa" to "Spanish", "fre" to "French",
        "ger" to "German", "ita" to "Italian", "por" to "Portuguese",
        "pob" to "Portuguese (BR)", "rus" to "Russian", "kor" to "Korean",
        "jpn" to "Japanese", "chi" to "Chinese", "zht" to "Chinese (Trad)",
        "ara" to "Arabic", "hin" to "Hindi", "tur" to "Turkish",
        "dut" to "Dutch", "pol" to "Polish", "swe" to "Swedish",
        "dan" to "Danish", "fin" to "Finnish", "nor" to "Norwegian",
        "gre" to "Greek", "ell" to "Greek", "heb" to "Hebrew",
        "tha" to "Thai", "vie" to "Vietnamese", "ind" to "Indonesian",
        "cze" to "Czech", "hun" to "Hungarian", "rum" to "Romanian",
        "ron" to "Romanian", "ukr" to "Ukrainian", "hrv" to "Croatian",
        "srp" to "Serbian", "slv" to "Slovenian", "bul" to "Bulgarian",
        "cat" to "Catalan", "ben" to "Bengali", "est" to "Estonian",
        "slo" to "Slovak", "may" to "Malay", "fil" to "Filipino",
        "tgl" to "Filipino", "per" to "Persian",
        "nld" to "Dutch", "spn" to "Spanish", "sp" to "Spanish",
        "hbs-srp" to "Serbian", "hbs" to "Serbian", "scc" to "Serbian",
        "scr" to "Croatian", "ge" to "German", "chs" to "Chinese",
        "cht" to "Chinese (Trad)",
    )
}
