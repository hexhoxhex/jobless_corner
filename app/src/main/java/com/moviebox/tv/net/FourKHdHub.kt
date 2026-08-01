package com.moviebox.tv.net

import com.moviebox.tv.data.ApiException
import com.moviebox.tv.data.Details
import com.moviebox.tv.data.Item
import com.moviebox.tv.data.PlayInfo
import com.moviebox.tv.data.Quality
import com.moviebox.tv.data.SeasonInfo
import com.moviebox.tv.data.SubjectType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import java.util.concurrent.TimeUnit

/**
 * 4KHDHub VOD provider — fills catalog gaps the aoneroom backend lacks
 * (older seasons/films: e.g. Big Bang Theory S1-4, classic Spider-Man).
 *
 * Technique ported from mesamirh/MovieBox-TUI (MIT/Apache): scrape
 * `4khdhub.one` search + detail pages for per-episode download items, each
 * carrying `hubcloud` / `hubdrive` mirror links; resolve a mirror through the
 * hubcloud → sportverse chain to a **direct** MP4/MKV file (Google storage,
 * Cloudflare R2, pixeldrain), which ExoPlayer plays like any VOD URL.
 *
 * MEMORY NOTE: a full-series detail page is ~1.5 MB with 800+ episode items.
 * jsoup-parsing that into a DOM OOM'd the app on low-heap TVs (192 MB cap),
 * so the heavy detail page is parsed with targeted REGEX, not jsoup. Only the
 * small search / hubcloud / sportverse pages use jsoup.
 *
 * Items are tagged with the [PREFIX] subjectId so [com.moviebox.tv.data.Repository]
 * routes search/details/resolvePlay here, mirroring the existing `tmdb:` bridge.
 * HTML scraping is inherently fragile — selectors track the live site as of
 * 2026-08 and may need updates if 4khdhub redesigns.
 */
object FourKHdHub {

    /** subjectId marker: `4k:<site-path>` (e.g. `4k:/the-big-bang-theory-series-6663/`). */
    const val PREFIX = "4k:"

    private const val BASE = "https://4khdhub.one"
    private const val UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/149.0.0.0 Safari/537.36"
    private const val TAG = "FourKHdHub"

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    // ---- public API (called by Repository for `4k:` subjectIds) ----------

    /** Search is a small (~20 KB) page — jsoup is fine here. */
    suspend fun search(query: String): List<Item> = withContext(Dispatchers.IO) {
        val html = get("$BASE/?s=${urlEncode(query)}") ?: return@withContext emptyList()
        val doc = Jsoup.parse(html, BASE)
        val out = ArrayList<Item>()
        for (card in doc.select("a.movie-card")) {
            val href = card.attr("href")
            if (href.isBlank()) continue
            val path = sameHostPath(href) ?: continue
            val title = card.selectFirst(".movie-card-title")?.text()?.trim().orEmpty()
            if (title.isEmpty()) continue
            val meta = card.selectFirst(".movie-card-meta")?.text().orEmpty()
            val isSeries = href.contains("-series-")
            out.add(
                Item(
                    subjectId = PREFIX + path,
                    title = title,
                    type = if (isSeries) SubjectType.TV_SERIES else SubjectType.MOVIE,
                    year = firstYear(meta),
                    rating = null,
                    coverUrl = card.selectFirst("img")?.absUrl("src")
                        ?.ifBlank { card.selectFirst("img")?.attr("src") },
                    seasonCount = seasonCount(meta) ?: 0,
                ),
            )
        }
        android.util.Log.i(TAG, "search($query) -> ${out.size} items")
        out
    }

    /** Detail page is huge — enumerate seasons/episodes by REGEX over the raw
     *  HTML (no jsoup DOM) to keep memory tiny. */
    suspend fun details(subjectId: String): Details = withContext(Dispatchers.IO) {
        val path = subjectId.removePrefix(PREFIX)
        val html = get(BASE + path) ?: throw ApiException("4KHDHub page unavailable")
        val isSeries = path.contains("-series-")
        val title = stripTrailingYear(
            Regex("<h1[^>]*>\\s*([^<]+?)\\s*</h1>").find(html)?.groupValues?.get(1)
                ?: metaContent(html, "og:title").orEmpty(),
        )
        val description = metaContent(html, "description").orEmpty()
        val year = Regex("(19|20)\\d{2}").find(title)?.value?.toIntOrNull()

        val seasons: List<SeasonInfo> = if (isSeries) {
            val map = sortedMapOf<Int, java.util.TreeSet<Int>>()
            for (m in Regex("[Ss](\\d{1,2})[Ee](\\d{1,3})").findAll(html)) {
                val s = m.groupValues[1].toIntOrNull() ?: continue
                val e = m.groupValues[2].toIntOrNull() ?: continue
                if (s in 1..99 && e in 1..999) map.getOrPut(s) { java.util.TreeSet() }.add(e)
            }
            map.map { (s, eps) -> SeasonInfo(s, eps.size, DEFAULT_RES, eps.toList()) }
        } else {
            listOf(SeasonInfo(0, 1, DEFAULT_RES, listOf(0)))
        }

        Details(
            subjectId = subjectId,
            title = title,
            type = if (isSeries) SubjectType.TV_SERIES else SubjectType.MOVIE,
            description = description,
            year = year,
            isSeries = isSeries,
            seasons = seasons,
            dubs = emptyList(),
        )
    }

    /** Resolve a playable direct URL. [resolution] is a quality label from a
     *  prior [PlayInfo.qualities] entry (or "best"/blank for the streamable
     *  default). Movies use season/episode 0. */
    suspend fun resolvePlay(
        subjectId: String,
        season: Int,
        episode: Int,
        resolution: String = "best",
    ): PlayInfo = withContext(Dispatchers.IO) {
        val path = subjectId.removePrefix(PREFIX)
        val html = get(BASE + path) ?: throw ApiException("4KHDHub page unavailable")
        val all = parseReleases(html, season, episode)
        if (all.isEmpty()) throw ApiException("This title isn't available right now.")

        // Prefer the requested label; else the most streamable (first).
        val ordered = buildList {
            all.firstOrNull { it.label.equals(resolution, true) }?.let(::add)
            addAll(all.filter { !it.label.equals(resolution, true) })
        }
        val qualities = all.map { Quality(it.label, null) }

        for (release in ordered) {
            for (mirror in release.mirrors) {
                val candidates = runCatching { resolveMirror(mirror) }.getOrDefault(emptyList())
                for (cand in candidates) {
                    val playable = runCatching { preflight(cand) }.getOrNull() ?: continue
                    android.util.Log.i(TAG, "resolved [${release.label}] -> ${playable.take(80)}")
                    return@withContext PlayInfo(
                        title = release.filename,
                        mediaUrl = playable,
                        selected = release.label,
                        qualities = qualities.map {
                            if (it.label == release.label) Quality(it.label, playable) else it
                        },
                        captions = emptyList(),
                        dubs = emptyList(),
                        selectedDub = "Original",
                        season = season,
                        episode = episode,
                        episodeTitle = release.filename,
                        durationSec = 0,
                    )
                }
            }
        }
        throw ApiException("This title isn't available right now.")
    }

    // ---- release parsing (REGEX over the raw HTML — memory-light) ---------

    private data class Mirror(val url: String)
    private data class ReleaseInfo(
        val filename: String,
        val label: String,      // user-facing quality label + stable key
        val streamScore: Int,   // lower = more streamable (default pick)
        val mirrors: List<Mirror>,
        val sizeBytes: Long?,   // null when the page omits a size badge
    )

    // Match the download-item class TOKEN wherever it sits in the class list.
    // Series items are class="episode-download-item" (exact); MOVIE items are
    // class="download-item border rounded-lg overflow-hidden" (extra classes),
    // so an exact-attr match missed every movie → "not available". `\b` before
    // "download-item" also matches inside "episode-download-item", so one
    // pattern splits both page shapes.
    private val ITEM_SPLIT = Regex("class=\"[^\"]*\\bdownload-item\\b[^\"]*\"")
    private val TITLE_RE = Regex("(?:episode-)?file-title[^>]*>\\s*([^<]+?)\\s*<")
    private val SIZE_RE = Regex("badge-size[^>]*>\\s*([^<]+?)\\s*<")
    private val HREF_RE = Regex("href=\"(https://[^\"]+)\"")

    /** Split the giant page into per-item chunks and pull filename/size/mirrors
     *  from each with small regexes. Excludes REMUX (download-only, ~3 GB —
     *  the OOM/buffering culprit) when any streamable release exists. */
    private fun parseReleases(html: String, season: Int, episode: Int): List<ReleaseInfo> {
        val chunks = ITEM_SPLIT.split(html)
        val parsed = ArrayList<ReleaseInfo>()
        val isEpisode = season > 0
        // chunks[0] is the pre-first-item preamble — skip it.
        for (i in 1 until chunks.size) {
            val chunk = chunks[i]
            val filename = TITLE_RE.find(chunk)?.groupValues?.get(1)?.trim().orEmpty()
            if (filename.isEmpty() || isArchive(filename)) continue
            if (season > 0 && parseSeasonEpisode(filename) != (season to episode)) continue
            val mirrors = HREF_RE.findAll(chunk)
                .map { it.groupValues[1] }
                .filter { !it.contains("logout") && (it.contains("hubcloud.") || it.contains("hubdrive.") || isValidPlayback(it)) }
                .distinct().map { Mirror(it) }.toList()
            if (mirrors.isEmpty()) continue
            val res = detectQuality(filename)
            val codec = detectCodec(filename)
            val sizeText = SIZE_RE.find(chunk)?.groupValues?.get(1)?.trim()
            val label = buildString {
                append(res ?: "SD")
                codec?.let { append(" ").append(it) }
                sizeText?.let { append(" · ").append(it) }
            }
            val sizeBytes = parseSizeBytes(sizeText ?: "")
            parsed.add(
                ReleaseInfo(
                    filename, label,
                    streamScore(res, codec, sizeBytes),
                    mirrors,
                    sizeBytes,
                ),
            )
        }
        // Keep only releases whose bitrate these mirrors can actually sustain.
        // This provider hosts DOWNLOAD-oriented BluRay rips (10-50 GB) behind
        // free Cloudflare workers measured at ~5-16 Mbps — a 2160p rip needs
        // 25-50 Mbps, so it buffer-starves into a one-frame-at-a-time
        // slideshow and then dies ("something went wrong"). Filtering here,
        // rather than only scoring, is what stops an infeasible file from
        // being picked when the good mirror fails to resolve.
        val feasible = parsed.filter {
            !it.label.contains("REMUX", true) &&
                // 2160p/4K is never streamable over these mirrors: a UHD rip
                // wants 25-50 Mbps and they sustain ~5-16. Filter on the
                // RESOLUTION, which is always in the release name — the
                // size-badge check below is inert when the page omits a size
                // (sizeBytes null), which is how a 4K file still got picked
                // and froze playback.
                !it.label.contains("2160p", true) &&
                isStreamable(it.sizeBytes, isEpisode)
        }
        // Nothing streamable → return empty so resolvePlay fails FAST with
        // "not available" instead of playing a file that can't keep up.
        return feasible.sortedBy { it.streamScore }.distinctBy { it.label }
    }

    /** True if a file of [sizeBytes] can stream over this provider's mirrors.
     *  Budget is [SUSTAINABLE_BPS] against a typical runtime (movie ~2h,
     *  episode ~30min); unknown size is allowed through (the preflight and
     *  the player still guard it). Measured 2026-08-01: worker mirrors deliver
     *  ~5-16 Mbps, so ~6 Mbps of video is the safe ceiling. */
    private fun isStreamable(sizeBytes: Long?, isEpisode: Boolean): Boolean {
        val size = sizeBytes ?: return true
        val runtimeSec = if (isEpisode) 30 * 60L else 2 * 60 * 60L
        return size <= SUSTAINABLE_BPS / 8 * runtimeSec
    }

    /** Video bitrate the 4KHDHub mirror chain can hold without starving. */
    private const val SUSTAINABLE_BPS = 6_000_000L

    /** Lower = better default for streaming. Codec COMPATIBILITY dominates:
     *  H.264/x264 decodes on every TV (verified playing here), whereas
     *  HEVC 10-bit MKV stalls this TCL/Realtek decoder — so x264 is the
     *  default and HEVC stays a selectable option. Then a ~1080p sweet spot,
     *  then smaller files. REMUX is excluded upstream (unstreamable). */
    private fun streamScore(res: String?, codec: String?, sizeBytes: Long?): Int {
        val codecScore = when (codec) { "x264" -> 0; "HEVC" -> 1; "REMUX" -> 3; else -> 2 }
        val resScore = when (res) { "1080p" -> 0; "720p" -> 1; "480p" -> 2; "2160p" -> 3; else -> 2 }
        val sizeGb = ((sizeBytes ?: 0L) / (1024L * 1024 * 1024)).toInt().coerceIn(0, 9)
        return codecScore * 100 + resScore * 10 + sizeGb
    }

    // ---- mirror resolver chain (small pages — jsoup ok) -------------------

    private fun resolveMirror(mirror: Mirror): List<String> {
        val url = mirror.url
        return when {
            url.contains("hubcloud.") -> resolveHubcloud(url)
            url.contains("hubdrive.") -> resolveHubdrive(url)
            else -> if (isValidPlayback(url)) listOf(url) else emptyList()
        }
    }

    private fun resolveHubcloud(driveUrl: String): List<String> {
        val driveHtml = get(driveUrl) ?: return emptyList()
        val sportverse = Jsoup.parse(driveHtml, driveUrl)
            .select("a#download")
            .map { it.attr("href") }
            .firstOrNull { it.startsWith("https://sportverse.") }
            ?: return emptyList()
        val page = get(sportverse) ?: return emptyList()

        val scored = ArrayList<Pair<Int, String>>()
        for (u in extractPixeldrain(page)) scored.add(0 to u)
        for (a in Jsoup.parse(page, sportverse).select("a[href]")) {
            val href = a.attr("href")
            if (!isValidPlayback(href)) continue
            val u = pixeldrainApiUrl(href) ?: href
            scored.add(score(u, a.text()) to u)
        }
        return scored.sortedBy { it.first }.map { it.second }.distinct()
    }

    private fun resolveHubdrive(driveUrl: String): List<String> {
        val html = get(driveUrl) ?: return emptyList()
        val hub = Jsoup.parse(html, driveUrl).select("a[href]")
            .map { it.attr("href") }
            .firstOrNull { it.contains("hubcloud.") && it.contains("/drive/") }
            ?: return emptyList()
        return resolveHubcloud(hub)
    }

    private fun preflight(rawUrl: String): String? {
        if (!isValidPlayback(rawUrl)) return null
        // Encode spaces/braces before the request — OkHttp's .url() throws on
        // them, and ExoPlayer needs the encoded form to fetch the file too.
        val url = canonicalize(rawUrl)
        val resp = runCatching {
            client.newCall(
                Request.Builder().url(url).header("User-Agent", UA)
                    .header("Range", "bytes=0-0").get().build(),
            ).execute()
        }.getOrNull() ?: return null
        resp.use {
            if (!it.isSuccessful) return null
            val finalUrl = it.request.url.toString()
            val ct = it.header("Content-Type").orEmpty().lowercase()
            if (ct.contains("text/html") || ct.contains("application/zip") ||
                ct.contains("text/plain")
            ) {
                val wrapped = it.request.url.queryParameter("link")
                    ?.takeIf { w -> w.startsWith("https://") } ?: return null
                return preflight(wrapped)
            }
            // Reject Google-backed "video-downloads" CDN links: they're
            // single-use / session-bound (HTTP 400 on ExoPlayer's follow-up
            // request), so a movie whose mirror resolves here would hang on a
            // black screen. Returning null makes resolvePlay fall through to
            // the next candidate (Cloudflare workers.dev / pixeldrain, which
            // stream fine); if none qualify it fails fast ("not available")
            // instead of hanging. Series already resolve to pixeldrain, so
            // they're unaffected.
            if (finalUrl.contains("googleusercontent") ||
                finalUrl.contains("video-downloads")
            ) {
                return null
            }
            return finalUrl
        }
    }

    // ---- helpers ----------------------------------------------------------

    private val DEFAULT_RES = listOf(1080, 720, 480)

    private fun get(url: String): String? = runCatching {
        client.newCall(
            Request.Builder().url(url).header("User-Agent", UA).get().build(),
        ).execute().use { if (it.isSuccessful) it.body?.string() else null }
    }.getOrNull()

    private fun urlEncode(s: String): String =
        java.net.URLEncoder.encode(s, "UTF-8").replace("+", "%20")

    private fun sameHostPath(href: String): String? {
        if (href.startsWith("/")) return href
        val u = runCatching { java.net.URI(href) }.getOrNull() ?: return null
        if (u.host != null && u.host != "4khdhub.one") return null
        return u.rawPath
    }

    private fun firstYear(s: String): Int? =
        Regex("(19|20)\\d{2}").find(s)?.value?.toIntOrNull()

    private fun seasonCount(meta: String): Int? {
        val marker = meta.indexOf('S')
        if (marker < 0) return null
        return meta.substring(marker).split('-', ' ', '•')
            .mapNotNull { it.trimStart('S').toIntOrNull() }.maxOrNull()
    }

    private fun stripTrailingYear(s: String): String =
        s.trim().replace(Regex("\\s*\\((19|20)\\d{2}\\)\\s*$"), "").trim()

    /** Cheap meta-tag content pull without a DOM (property= or name=). */
    private fun metaContent(html: String, key: String): String? {
        val re = Regex(
            "<meta[^>]*(?:property|name)=\"(?:og:)?$key\"[^>]*content=\"([^\"]*)\"",
            RegexOption.IGNORE_CASE,
        )
        return re.find(html)?.groupValues?.get(1)?.takeIf { it.isNotBlank() }
            ?: Regex(
                "<meta[^>]*content=\"([^\"]*)\"[^>]*(?:property|name)=\"(?:og:)?$key\"",
                RegexOption.IGNORE_CASE,
            ).find(html)?.groupValues?.get(1)?.takeIf { it.isNotBlank() }
    }

    private fun parseSeasonEpisode(value: String): Pair<Int, Int>? {
        val m = Regex("[Ss](\\d{1,2})[Ee](\\d{1,3})").find(value) ?: return null
        val s = m.groupValues[1].toIntOrNull() ?: return null
        val e = m.groupValues[2].toIntOrNull() ?: return null
        return s to e
    }

    private fun detectQuality(v: String): String? =
        listOf("2160p", "1080p", "720p", "480p").firstOrNull { v.contains(it, true) }

    private fun detectCodec(v: String): String? {
        val l = v.lowercase()
        return when {
            l.contains("remux") -> "REMUX"
            l.contains("x265") || l.contains("h265") || l.contains("h.265") ||
                l.contains("hevc") -> "HEVC"
            l.contains("x264") || l.contains("h264") || l.contains("h.264") ||
                l.contains("avc") -> "x264"
            else -> null
        }
    }

    private fun isArchive(v: String): Boolean {
        val l = v.lowercase()
        return l.endsWith(".zip") || l.contains("complete season") || l.contains("season pack")
    }

    private fun parseSizeBytes(v: String): Long? {
        val m = Regex("([0-9.]+)\\s*(GB|MB|KB)", RegexOption.IGNORE_CASE).find(v) ?: return null
        val n = m.groupValues[1].toDoubleOrNull() ?: return null
        val mult = when (m.groupValues[2].uppercase()) {
            "GB" -> 1024.0 * 1024 * 1024; "MB" -> 1024.0 * 1024; else -> 1024.0
        }
        return (n * mult).toLong()
    }

    private fun extractPixeldrain(html: String): List<String> {
        val out = ArrayList<String>()
        Regex("https://pixeldrain\\.dev/u/([A-Za-z0-9_-]+)").findAll(html).forEach {
            pixeldrainApiUrl(it.value)?.let { u -> if (u !in out) out.add(u) }
        }
        return out
    }

    private fun pixeldrainApiUrl(raw: String): String? {
        val u = runCatching { java.net.URI(raw) }.getOrNull() ?: return null
        if (u.host != "pixeldrain.dev") return null
        val id = u.rawPath?.removePrefix("/u/")?.trim('/').orEmpty()
        if (id.isEmpty() || !id.all { it.isLetterOrDigit() || it == '-' || it == '_' }) return null
        return "https://pixeldrain.dev/api/file/$id?download"
    }

    /** Percent-encode the RFC-3986 characters that appear UNescaped in
     *  4KHDHub's scene filenames — spaces, braces, brackets, etc. Both
     *  java.net.URI(raw) and OkHttp's HttpUrl THROW on these, so a worker
     *  link like ".../Spider-Man 2 (2004) {Dual Audio} ByHammer.mkv" was
     *  silently dropped by isValidPlayback — losing the one Cloudflare mirror
     *  that actually streams (verified: that link returns HTTP 206). Idempotent:
     *  never touches '%', so an already-encoded URL passes through unchanged. */
    private fun canonicalize(raw: String): String =
        raw.replace(" ", "%20").replace("{", "%7B").replace("}", "%7D")
            .replace("[", "%5B").replace("]", "%5D").replace("|", "%7C")
            .replace("`", "%60").replace("^", "%5E")
            .replace("\"", "%22").replace("<", "%3C").replace(">", "%3E")

    private fun isValidPlayback(raw: String): Boolean {
        val u = runCatching { java.net.URI(canonicalize(raw)) }.getOrNull() ?: return false
        if (u.scheme != "https" || u.host.isNullOrBlank()) return false
        val host = u.host.lowercase()
        val path = u.rawPath.orEmpty().lowercase()
        if (host == "localhost" || host.endsWith(".local")) return false
        if (path.endsWith(".zip") || path.contains("login.php") || path.contains("logout")) return false
        return true
    }

    private fun score(url: String, label: String): Int {
        val v = "$url $label".lowercase()
        return when {
            v.contains("pixeldrain") || v.contains("pixel.hubcloud") -> 0
            v.contains("gpdl.") || v.contains("googleusercontent") ||
                v.contains("storage.googleapis") -> 1
            v.contains("workers.dev") || v.contains("r2.dev") ||
                v.contains("r2.cloudflarestorage") -> 2
            v.contains("latent.click") || v.contains("fsl") -> 3
            else -> 4
        }
    }
}
