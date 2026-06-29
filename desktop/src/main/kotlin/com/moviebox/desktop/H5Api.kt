package com.moviebox.desktop

import com.squareup.moshi.JsonClass
import com.squareup.moshi.Moshi
import com.squareup.moshi.adapter
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.security.MessageDigest
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Minimal H5 client for the desktop app. Mirrors the signing + bearer
 * flow used by :app/.../net/H5Client.kt. Pure JVM — no Android deps.
 * Re-implements rather than depending on :app so the desktop module
 * stays standalone until we extract a :shared module.
 *
 * Endpoint base + signing constants taken from the Android client
 * verified against live aoneroom traffic (see `_movieway/` notes).
 */
object H5Api {
    private const val H5_BASE = "https://h5-api.aoneroom.com"
    private const val PAGE_REFERER = "https://themoviebox.org/"
    private const val BROWSER_UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
        "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/149.0.0.0 Safari/537.36"

    private const val SIGNING_KEY_B64 =
        "76iRl07s0xSN9jqmEWAt79EBJZulIQIsV64FZr2O"

    /** Same X-Client-Info shape Android uses — Nairobi timezone is what
     *  the H5 backend returned the highest-tier (`atp:3`) bearer for
     *  during reverse engineering. */
    private const val X_CLIENT_INFO =
        """{"timezone":"Africa/Nairobi"}"""

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
                    .header("X-Client-Token", clientToken(ts))
                    .header("x-tr-signature", trSignature("POST", "application/json", ctype, "$H5_BASE$path", body, ts))
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
     *  Returns the list of row buckets the site uses ("Continue Watching",
     *  "Popular Series", "Trending Now", "Premium VIP HD", etc.). Each
     *  row carries its own items. Title comes from the row's name. */
    fun home(): List<H5Row> {
        ensureWarm()
        val ctype = "application/json; charset=utf-8"
        val body = """{"host":"themoviebox.org"}"""
        val raw = signedPost("/wefeed-h5api-bff/home", body, ctype)
        return parseRows(raw)
    }

    private fun signedPost(path: String, body: String, ctype: String): String {
        val ts = System.currentTimeMillis()
        val req = Request.Builder()
            .url("$H5_BASE$path")
            .header("User-Agent", BROWSER_UA)
            .header("Accept", "application/json")
            .header("Content-Type", ctype)
            .header("Referer", PAGE_REFERER)
            .header("X-Client-Token", clientToken(ts))
            .header("x-tr-signature", trSignature("POST", "application/json", ctype, "$H5_BASE$path", body, ts))
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

    private fun parseItems(raw: String): List<H5Item> = runCatching {
        val data = JSONObject(raw).optJSONObject("data") ?: return@runCatching emptyList()
        val items = data.optJSONArray("items") ?: return@runCatching emptyList()
        (0 until items.length()).mapNotNull { idx ->
            val o = items.optJSONObject(idx) ?: return@mapNotNull null
            H5Item(
                subjectId = o.optString("subjectId"),
                title = o.optString("title"),
                type = o.optInt("type"),
                year = o.optString("releaseDate").take(4).toIntOrNull(),
                coverUrl = o.optString("imageUrl"),
                rating = o.optDouble("imdbRatingValue", 0.0).takeIf { it > 0 },
            )
        }
    }.getOrDefault(emptyList())

    private fun parseRows(raw: String): List<H5Row> = runCatching {
        val data = JSONObject(raw).optJSONObject("data") ?: return@runCatching emptyList()
        val rows = data.optJSONArray("operatingList")
            ?: data.optJSONArray("homeList")
            ?: return@runCatching emptyList()
        (0 until rows.length()).mapNotNull { rIdx ->
            val r = rows.optJSONObject(rIdx) ?: return@mapNotNull null
            val title = r.optString("name").ifBlank { r.optString("title") }
            val items = r.optJSONArray("subjectList")
                ?: r.optJSONArray("items")
                ?: return@mapNotNull null
            val parsed = (0 until items.length()).mapNotNull { idx ->
                val o = items.optJSONObject(idx) ?: return@mapNotNull null
                H5Item(
                    subjectId = o.optString("subjectId"),
                    title = o.optString("title"),
                    type = o.optInt("type"),
                    year = o.optString("releaseDate").take(4).toIntOrNull(),
                    coverUrl = o.optString("imageUrl"),
                    rating = o.optDouble("imdbRatingValue", 0.0).takeIf { it > 0 },
                )
            }
            if (parsed.isEmpty()) null else H5Row(title = title, items = parsed)
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

    private fun jsonStr(s: String): String =
        "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

    private fun md5Hex(b: ByteArray): String {
        val d = MessageDigest.getInstance("MD5").digest(b)
        return d.joinToString("") { "%02x".format(it) }
    }

    private fun clientToken(ts: Long): String {
        val reversed = ts.toString().reversed()
        return "$ts,${md5Hex(reversed.toByteArray())}"
    }

    private fun trSignature(
        method: String, accept: String, contentType: String,
        url: String, body: String?, timestampMs: Long,
    ): String {
        val bodyLen = (body?.toByteArray()?.size ?: 0).toString()
        val bodyHash = if (body == null) "" else md5Hex(body.toByteArray())
        val canonical = listOf(method, accept, contentType, bodyLen, timestampMs.toString(), bodyHash, url)
            .joinToString("\n")
        val key = Base64.getDecoder().decode(SIGNING_KEY_B64)
        val mac = Mac.getInstance("HmacMD5").apply { init(SecretKeySpec(key, "HmacMD5")) }
        val signed = mac.doFinal(canonical.toByteArray())
        return "$timestampMs|2|${Base64.getEncoder().encodeToString(signed)}"
    }
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
