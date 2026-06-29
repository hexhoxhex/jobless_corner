package com.moviebox.desktop

import com.moviebox.shared.net.Constants as SC
import com.moviebox.shared.net.Crypto
import com.squareup.moshi.JsonClass
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

/**
 * Minimal H5 client for the desktop app. Signing + endpoint constants
 * come from :shared/net/Crypto.kt and :shared/net/Constants.kt — patches
 * to those land in BOTH :app and :desktop at once.
 *
 * The bearer-mint + cookie-jar flow here is a JVM port of
 * :app/.../net/H5Client.kt. Will fully migrate to :shared in a later
 * commit once we abstract the cookie-store + logger glue.
 */
object H5Api {
    private val H5_BASE = SC.H5_BASE
    private val PAGE_REFERER = SC.PAGE_REFERER
    private val BROWSER_UA = SC.BROWSER_UA
    private val X_CLIENT_INFO = SC.X_CLIENT_INFO

    private val moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()

    @Volatile private var bearer: String? = null
    @Volatile private var warmed = false
    private val warmLock = Any()

    private val cookieJar = object : CookieJar {
        private val store = mutableMapOf<String, MutableList<Cookie>>()
        override fun loadForRequest(url: HttpUrl): List<Cookie> =
            store[url.host].orEmpty()
        override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
            store.getOrPut(url.host) { mutableListOf() }.apply {
                val keep = cookies.map { it.name }.toSet()
                removeAll { it.name in keep }
                addAll(cookies)
            }
        }
    }

    private val client = OkHttpClient.Builder()
        .cookieJar(cookieJar)
        .build()

    /** Two-step warm — same as Android. country-code mints the atp:3
     *  bearer in its x-user header (verified via Playwright capture);
     *  search-suggest then rotates inside the same session. We read
     *  x-user from BOTH calls so whichever returns one wins. */
    private fun ensureWarm() {
        if (warmed) return
        synchronized(warmLock) {
            if (warmed) return
            runCatching {
                val ts = System.currentTimeMillis()
                val path = "/wefeed-h5api-bff/country-code"
                val req = Request.Builder()
                    .url("$H5_BASE$path")
                    .header("Accept", "application/json")
                    .header("User-Agent", BROWSER_UA)
                    .header("Referer", PAGE_REFERER)
                    .header("X-Client-Info", X_CLIENT_INFO)
                    .header("x-request-lang", "en")
                    .get().build()
                client.newCall(req).execute().use { absorbXUser(it.header("x-user")) }
            }
            runCatching {
                val ts = System.currentTimeMillis()
                val ctype = "application/json; charset=utf-8"
                val body = """{"keyword":"avatar","perPage":0}"""
                val path = "/wefeed-h5api-bff/subject/search-suggest"
                val req = Request.Builder()
                    .url("$H5_BASE$path")
                    .header("User-Agent", BROWSER_UA)
                    .header("Accept", "application/json")
                    .header("Content-Type", ctype)
                    .header("Referer", PAGE_REFERER)
                    .header("X-Client-Token", Crypto.clientToken(ts))
                    .header("x-tr-signature", Crypto.trSignature("POST", "application/json", ctype, "$H5_BASE$path", body, ts))
                    .header("X-Client-Info", X_CLIENT_INFO)
                    .header("x-request-lang", "en")
                    .header("X-Client-Status", "0")
                    .post(body.toRequestBody(ctype.toMediaType()))
                    .build()
                client.newCall(req).execute().use { absorbXUser(it.header("x-user")) }
            }
            warmed = true
        }
    }

    /** Search the catalog. Returns a flat list of items (movies + series). */
    fun search(keyword: String, perPage: Int = 12): List<H5Item> {
        ensureWarm()
        val ctype = "application/json; charset=utf-8"
        val body = """{"keyword":${jsonStr(keyword)},"page":1,"perPage":$perPage,"subjectType":0}"""
        val raw = signedPost("/wefeed-h5api-bff/subject/search", body, ctype)
        return parseItems(raw)
    }

    /** Home rows — same call themoviebox.org's SPA makes on first load.
     *  This is a signed GET with `host` as a query param, NOT a POST
     *  with body (that endpoint returns 404). The actual response shape
     *  uses `operatingList` at the root with each entry carrying `title`
     *  and `subjects[]` (or a nested `banner.items[].subject` for the
     *  hero BANNER row). */
    fun home(): List<H5Row> {
        ensureWarm()
        val raw = signedGet("/wefeed-h5api-bff/home", "host=themoviebox.org")
        return parseRows(raw)
    }

    /** Detail for a single title. Returns description + first season's
     *  episode count (if series). Matches the Android Repository.details()
     *  return shape but lighter — desktop doesn't need the full episode
     *  enumeration walk yet. */
    fun detail(subjectId: String, titleHint: String): H5Detail {
        ensureWarm()
        val path = lookupDetailPath(subjectId, titleHint)
        val raw = signedGet(path)
        return parseDetail(raw, subjectId)
    }

    /** Play call — returns the first playable stream URL + a list of all
     *  qualities. Falls back to subject-level (se=0, ep=0) when the asked
     *  (se, ep) returns 0 streams, matching the Android v0.1.93 fix. */
    fun play(
        subjectId: String,
        season: Int = 0,
        episode: Int = 0,
        detailPath: String = "",
    ): H5Play {
        ensureWarm()
        val ctype = "application/json; charset=utf-8"
        val body = """{"subjectId":"$subjectId","se":$season,"ep":$episode,"detailPath":${jsonStr(detailPath)}}"""
        val raw = signedPost("/wefeed-h5api-bff/subject/play", body, ctype)
        val first = parsePlay(raw)
        if (first.streams.isNotEmpty() || (season == 0 && episode == 0)) return first
        // Subject-level fallback for HBO-tier titles (HotD, etc.).
        val fbBody = """{"subjectId":"$subjectId","se":0,"ep":0,"detailPath":${jsonStr(detailPath)}}"""
        val fbRaw = signedPost("/wefeed-h5api-bff/subject/play", fbBody, ctype)
        val fb = parsePlay(fbRaw)
        return if (fb.streams.isNotEmpty()) fb else first
    }

    /** Locate a detailPath for a subjectId via a title search. Mirrors
     *  Android H5Api.lookupDetailPath. The path is what /subject/detail
     *  needs; it isn't a clean URL slug, it's a server-side identifier. */
    private fun lookupDetailPath(subjectId: String, titleHint: String): String {
        val results = search(titleHint, perPage = 6)
        val match = results.firstOrNull { it.subjectId == subjectId }
            ?: return ""  // empty path is OK — server resolves by subjectId
        return match.subjectId  // detailPath happens to equal subjectId for the H5 detail endpoint
    }

    private fun signedGet(path: String, query: String = ""): String {
        ensureWarm()
        val ts = System.currentTimeMillis()
        val full = if (query.isBlank()) "$H5_BASE$path" else "$H5_BASE$path?$query"
        val req = Request.Builder()
            .url(full)
            .header("User-Agent", BROWSER_UA)
            .header("Accept", "application/json")
            .header("Referer", PAGE_REFERER)
            .header("X-Client-Token", Crypto.clientToken(ts))
            .header("x-tr-signature", Crypto.trSignature("GET", "application/json", "", full, null, ts))
            .header("X-Client-Info", X_CLIENT_INFO)
            .header("x-request-lang", "en")
            .header("X-Client-Status", "0")
            .also { b -> bearer?.let { b.header("Authorization", "Bearer $it") } }
            .get().build()
        return client.newCall(req).execute().use { r ->
            absorbXUser(r.header("x-user"))
            r.body?.string().orEmpty()
        }
    }

    private fun parseDetail(raw: String, subjectId: String): H5Detail = runCatching {
        val data = JSONObject(raw).optJSONObject("data") ?: return@runCatching H5Detail(subjectId, "", "", emptyList(), 0)
        val subject = data.optJSONObject("subject") ?: data
        val resourceObj = data.optJSONObject("resource") ?: subject.optJSONObject("resource") ?: JSONObject()
        val seasonsArr = resourceObj.optJSONArray("seasons") ?: JSONObject().optJSONArray("seasons")
        val seasons = if (seasonsArr == null) emptyList() else {
            (0 until seasonsArr.length()).mapNotNull { i ->
                val s = seasonsArr.optJSONObject(i) ?: return@mapNotNull null
                H5Season(season = s.optInt("season"), episodes = s.optInt("maxEp"))
            }
        }
        H5Detail(
            subjectId = subjectId,
            title = subject.optString("title"),
            description = subject.optString("description"),
            seasons = seasons,
            type = subject.optInt("subjectType"),
        )
    }.getOrDefault(H5Detail(subjectId, "", "", emptyList(), 0))

    private fun parsePlay(raw: String): H5Play = runCatching {
        val data = JSONObject(raw).optJSONObject("data") ?: return@runCatching H5Play(emptyList())
        val streamsArr = data.optJSONArray("streams") ?: data.optJSONArray("list")
            ?: return@runCatching H5Play(emptyList())
        val streams = (0 until streamsArr.length()).mapNotNull { i ->
            val s = streamsArr.optJSONObject(i) ?: return@mapNotNull null
            val url = s.optString("url")
            if (url.isBlank()) null else H5Stream(
                url = url,
                resolution = s.optInt("resolution"),
                format = s.optString("format"),
            )
        }
        H5Play(streams = streams)
    }.getOrDefault(H5Play(emptyList()))

    private fun signedPost(path: String, body: String, ctype: String): String {
        val ts = System.currentTimeMillis()
        val req = Request.Builder()
            .url("$H5_BASE$path")
            .header("User-Agent", BROWSER_UA)
            .header("Accept", "application/json")
            .header("Content-Type", ctype)
            .header("Referer", PAGE_REFERER)
            .header("X-Client-Token", Crypto.clientToken(ts))
            .header("x-tr-signature", Crypto.trSignature("POST", "application/json", ctype, "$H5_BASE$path", body, ts))
            .header("X-Client-Info", X_CLIENT_INFO)
            .header("x-request-lang", "en")
            .header("X-Client-Status", "0")
            .also { b -> bearer?.let { b.header("Authorization", "Bearer $it") } }
            .post(body.toRequestBody(ctype.toMediaType()))
            .build()
        return client.newCall(req).execute().use { r ->
            absorbXUser(r.header("x-user"))
            r.body?.string().orEmpty()
        }
    }

    /** Parse one item from the H5 subject shape. The fields the site
     *  uses don't match the names I guessed in v1; this is the
     *  verified-against-live-response decoder. */
    private fun parseSubject(o: JSONObject): H5Item? {
        // The "subject" inside banner.items has the same shape as the
        // entries in operatingList[i].subjects[j]. Both go through here.
        val subj = o.optJSONObject("subject") ?: o
        val id = subj.optString("subjectId")
        if (id.isBlank()) return null
        val cover = subj.optJSONObject("cover")
        val coverUrl = cover?.optString("url").orEmpty()
            .ifBlank { subj.optString("imageUrl") }
        // imdbRatingValue is a STRING in the API ("4.8"); optDouble
        // returns NaN for strings so we parse it manually.
        val ratingStr = subj.optString("imdbRatingValue")
        val rating = ratingStr.toDoubleOrNull()?.takeIf { it > 0 }
        return H5Item(
            subjectId = id,
            title = subj.optString("title"),
            type = subj.optInt("subjectType"),
            year = subj.optString("releaseDate").take(4).toIntOrNull(),
            coverUrl = coverUrl,
            rating = rating,
        )
    }

    private fun parseItems(raw: String): List<H5Item> = runCatching {
        val data = JSONObject(raw).optJSONObject("data") ?: return@runCatching emptyList()
        val items = data.optJSONArray("items") ?: return@runCatching emptyList()
        (0 until items.length()).mapNotNull { idx ->
            val o = items.optJSONObject(idx) ?: return@mapNotNull null
            parseSubject(o)
        }
    }.getOrDefault(emptyList())

    private fun parseRows(raw: String): List<H5Row> = runCatching {
        val data = JSONObject(raw).optJSONObject("data") ?: return@runCatching emptyList()
        val rows = data.optJSONArray("operatingList") ?: return@runCatching emptyList()
        (0 until rows.length()).mapNotNull { rIdx ->
            val r = rows.optJSONObject(rIdx) ?: return@mapNotNull null
            val title = r.optString("title")
            // Two layouts: regular rows have `subjects[]` directly;
            // BANNER rows wrap in `banner.items[].subject`.
            val direct = r.optJSONArray("subjects")
            val bannerItems = r.optJSONObject("banner")?.optJSONArray("items")
            val parsed = mutableListOf<H5Item>()
            if (direct != null) {
                for (i in 0 until direct.length()) {
                    direct.optJSONObject(i)?.let { parseSubject(it) }?.let { parsed += it }
                }
            }
            if (bannerItems != null) {
                for (i in 0 until bannerItems.length()) {
                    bannerItems.optJSONObject(i)?.let { parseSubject(it) }?.let { parsed += it }
                }
            }
            if (parsed.isEmpty() || title.isBlank()) null
            else H5Row(title = title, items = parsed)
        }
    }.getOrDefault(emptyList())

    private fun absorbXUser(xUser: String?) {
        if (xUser.isNullOrBlank()) return
        val token: String? = runCatching {
            JSONObject(xUser).optString("token").takeIf { it.isNotBlank() }
        }.getOrNull()
        if (token != null) bearer = token
    }

    // ---- signing ----

    // Tiny JSON-string escaper for inline body building. Kept inline
    // because the body builder is the only caller and it's not crypto-
    // adjacent — no sharing benefit.
    private fun jsonStr(s: String): String =
        "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

    // clientToken + trSignature live in :shared/net/Crypto.kt — see
    // Crypto.clientToken / Crypto.trSignature calls above.
}

@JsonClass(generateAdapter = true)
data class H5Item(
    val subjectId: String,
    val title: String,
    val type: Int,
    val year: Int?,
    val coverUrl: String,
    val rating: Double?,
)

@JsonClass(generateAdapter = true)
data class H5Row(
    val title: String,
    val items: List<H5Item>,
)

@JsonClass(generateAdapter = true)
data class H5Detail(
    val subjectId: String,
    val title: String,
    val description: String,
    val seasons: List<H5Season>,
    val type: Int,
) {
    val isSeries: Boolean get() = type == 1 || seasons.isNotEmpty()
}

@JsonClass(generateAdapter = true)
data class H5Season(val season: Int, val episodes: Int)

@JsonClass(generateAdapter = true)
data class H5Play(val streams: List<H5Stream>)

@JsonClass(generateAdapter = true)
data class H5Stream(
    val url: String,
    val resolution: Int,
    val format: String,
) {
    val label: String get() = if (resolution > 0) "${resolution}P" else format.ifBlank { "Auto" }
}
