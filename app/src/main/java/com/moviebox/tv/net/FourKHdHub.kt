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
import org.jsoup.nodes.Document
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
 * Items are tagged with the [PREFIX] subjectId so [com.moviebox.tv.data.Repository]
 * can route search/details/resolvePlay here, mirroring the existing `tmdb:`
 * bridge. HTML scraping is inherently fragile — selectors track the live site
 * as of 2026-08 and may need updates if 4khdhub redesigns.
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

    suspend fun details(subjectId: String): Details = withContext(Dispatchers.IO) {
        val path = subjectId.removePrefix(PREFIX)
        val html = get(BASE + path) ?: throw ApiException("4KHDHub page unavailable")
        val doc = Jsoup.parse(html, BASE)
        val isSeries = path.contains("-series-")
        val title = stripTrailingYear(
            doc.selectFirst("h1")?.text()?.trim()
                ?: metaContent(doc, "meta[property=og:title]").orEmpty(),
        )
        val description = doc.selectFirst(".content-section p.mt-4")?.text()
            ?: metaContent(doc, "meta[name=description]").orEmpty()
        val year = findMetadata(doc, "Release:")?.let(::firstYear)
            ?: findMetadata(doc, "Last Air:")?.let(::firstYear)

        val seasons: List<SeasonInfo> = if (isSeries) {
            val map = sortedMapOf<Int, java.util.TreeSet<Int>>()
            for (node in doc.select("#episodes .episode-download-item")) {
                val fn = node.selectFirst(".episode-file-title")?.text().orEmpty()
                val se = parseSeasonEpisode(fn) ?: continue
                map.getOrPut(se.first) { java.util.TreeSet() }.add(se.second)
            }
            map.map { (s, eps) ->
                SeasonInfo(s, eps.size, DEFAULT_RES, eps.toList())
            }
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

    /** Resolve a playable direct URL for [subjectId] (movie: season/episode 0). */
    suspend fun resolvePlay(
        subjectId: String,
        season: Int,
        episode: Int,
    ): PlayInfo = withContext(Dispatchers.IO) {
        val path = subjectId.removePrefix(PREFIX)
        val html = get(BASE + path) ?: throw ApiException("4KHDHub page unavailable")
        val doc = Jsoup.parse(html, BASE)
        val releases = parseReleases(doc, season, episode)
        if (releases.isEmpty()) throw ApiException("This title isn't available right now.")

        // Best quality first; try each release's mirrors until one resolves +
        // preflights to a real media URL.
        for (release in releases) {
            for (mirror in release.mirrors) {
                val candidates = runCatching { resolveMirror(mirror) }.getOrDefault(emptyList())
                for (cand in candidates) {
                    val playable = runCatching { preflight(cand) }.getOrNull() ?: continue
                    android.util.Log.i(TAG, "resolved ${release.filename} -> ${playable.take(90)}")
                    return@withContext PlayInfo(
                        title = release.filename,
                        mediaUrl = playable,
                        selected = release.quality ?: "best",
                        qualities = listOf(Quality(release.quality ?: "Original", playable)),
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

    // ---- release parsing --------------------------------------------------

    private data class Mirror(val url: String)
    private data class ReleaseInfo(
        val filename: String,
        val quality: String?,
        val mirrors: List<Mirror>,
    )

    private fun parseReleases(doc: Document, season: Int, episode: Int): List<ReleaseInfo> {
        val itemSel = if (season > 0) "#episodes .episode-download-item" else ".download-item"
        val titleSel = if (season > 0) ".episode-file-title" else ".file-title"
        val out = ArrayList<ReleaseInfo>()
        for (item in doc.select(itemSel)) {
            val filename = item.selectFirst(titleSel)?.text().orEmpty()
            if (filename.isEmpty() || isArchive(filename)) continue
            if (season > 0 && parseSeasonEpisode(filename) != (season to episode)) continue
            val mirrors = item.select("a[href]").mapNotNull { a ->
                val href = a.attr("href")
                if (!href.startsWith("https://") || href.contains("logout")) null else Mirror(href)
            }.distinctBy { it.url }
            if (mirrors.isEmpty()) continue
            out.add(ReleaseInfo(filename, detectQuality(filename), mirrors))
        }
        // Best quality first (2160 > 1080 > 720 > 480 > unknown).
        return out.sortedByDescending { qualityRank(it.quality) }
    }

    // ---- mirror resolver chain -------------------------------------------

    /** Turn a mirror link into candidate direct-file URLs (best first). */
    private fun resolveMirror(mirror: Mirror): List<String> {
        val url = mirror.url
        return when {
            url.contains("hubcloud.") -> resolveHubcloud(url)
            url.contains("hubdrive.") -> resolveHubdrive(url)
            else -> if (isValidPlayback(url)) listOf(url) else emptyList()
        }
    }

    /** hubcloud `/drive/…` → `a#download` (sportverse) → direct file candidates. */
    private fun resolveHubcloud(driveUrl: String): List<String> {
        val driveHtml = get(driveUrl) ?: return emptyList()
        val sportverse = Jsoup.parse(driveHtml, driveUrl)
            .select("a#download")
            .map { it.attr("href") }
            .firstOrNull { it.startsWith("https://sportverse.") }
            ?: return emptyList()
        val page = get(sportverse) ?: return emptyList()

        val scored = ArrayList<Pair<Int, String>>()
        // pixeldrain URLs embedded in `var pxl … https://pixeldrain.dev/u/<id>`
        for (u in extractPixeldrain(page)) scored.add(0 to u)
        // anchor hrefs
        for (a in Jsoup.parse(page, sportverse).select("a[href]")) {
            val href = a.attr("href")
            if (!isValidPlayback(href)) continue
            val u = pixeldrainApiUrl(href) ?: href
            scored.add(score(u, a.text()) to u)
        }
        return scored.sortedBy { it.first }.map { it.second }.distinct()
    }

    /** hubdrive `/file/…` → embedded hubcloud `/drive/…` → [resolveHubcloud]. */
    private fun resolveHubdrive(driveUrl: String): List<String> {
        val html = get(driveUrl) ?: return emptyList()
        val hub = Jsoup.parse(html, driveUrl).select("a[href]")
            .map { it.attr("href") }
            .firstOrNull { it.contains("hubcloud.") && it.contains("/drive/") }
            ?: return emptyList()
        return resolveHubcloud(hub)
    }

    /** Range-preflight a candidate: follow redirects, reject html/zip, unwrap
     *  a `?link=` wrapper if the CDN returns a landing page. Returns the final
     *  media URL or null. */
    private fun preflight(url: String): String? {
        if (!isValidPlayback(url)) return null
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
                // wrapped landing page: unwrap ?link=
                val wrapped = it.request.url.queryParameter("link")
                    ?.takeIf { w -> w.startsWith("https://") } ?: return null
                return preflight(wrapped)
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

    /** Keep only same-host relative paths (guards against off-site hrefs). */
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

    private fun metaContent(doc: Document, css: String): String? =
        doc.selectFirst(css)?.attr("content")?.takeIf { it.isNotBlank() }

    private fun findMetadata(doc: Document, label: String): String? {
        for (item in doc.select(".metadata-item")) {
            val l = item.selectFirst(".metadata-label")?.text()?.trim()
            if (l == label) return item.selectFirst(".metadata-value")?.text()?.trim()
        }
        return null
    }

    /** Parse `S12E01` → (12, 1) from a filename. */
    private fun parseSeasonEpisode(value: String): Pair<Int, Int>? {
        val m = Regex("[Ss](\\d{1,2})[Ee](\\d{1,3})").find(value) ?: return null
        val s = m.groupValues[1].toIntOrNull() ?: return null
        val e = m.groupValues[2].toIntOrNull() ?: return null
        return s to e
    }

    private fun detectQuality(v: String): String? =
        listOf("2160p", "1080p", "720p", "480p").firstOrNull { v.contains(it, true) }

    private fun qualityRank(q: String?): Int = when (q) {
        "2160p" -> 4; "1080p" -> 3; "720p" -> 2; "480p" -> 1; else -> 0
    }

    private fun isArchive(v: String): Boolean {
        val l = v.lowercase()
        return l.endsWith(".zip") || l.contains("complete season") || l.contains("season pack")
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

    /** Reject non-media targets (local hosts, login/logout, zip). */
    private fun isValidPlayback(raw: String): Boolean {
        val u = runCatching { java.net.URI(raw) }.getOrNull() ?: return false
        if (u.scheme != "https" || u.host.isNullOrBlank()) return false
        val host = u.host.lowercase()
        val path = u.rawPath.orEmpty().lowercase()
        if (host == "localhost" || host.endsWith(".local")) return false
        if (path.endsWith(".zip") || path.contains("login.php") || path.contains("logout")) return false
        return true
    }

    /** Prefer fast/direct hosts (pixeldrain > google > r2/workers > other). */
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
