package com.moviebox.tv.net

import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * HTTP client for the H5 + themoviebox.org proxy flow.
 *
 * Why this exists. The aoneroom *mobile* API (`api*.aoneroom.com`) now rejects
 * guest signing — search returns `code=441 "miss token"` and even with a freshly
 * minted token, mobile `subject/play` answers `code=401 "signature invalid"`. The
 * H5 surface (`h5-api.aoneroom.com`) still serves search/detail to guests, and
 * themoviebox.org runs its own `/wefeed-h5api-bff/subject/play` proxy that
 * unlocks resources for a warmed cookie session. Reproduced end-to-end with
 * Playwright: load the movie page → call `country-code` + `detail` (which set a
 * `token` cookie) → call the play proxy and get back `streams[]` with signed
 * `bcdnxw.hakunaymatata.com` MP4 URLs. No login. No signing on the play call.
 *
 * The legacy ApiClient stays untouched: this client only handles VOD search /
 * detail / play. Live TV runs through its own independent stack.
 */
object H5Client {

    const val H5_BASE = "https://h5-api.aoneroom.com"
    const val PROXY_BASE = "https://themoviebox.org"

    /** Detail-path slugs in our catalog don't exist, so we synthesise one. The
     *  proxy's `/subject/play` ignores it as long as it's present and matches
     *  the slug format `*-XXXXXXXX` it validates loosely. A real slug from the
     *  detail call is plugged in when we have one. */
    private const val SYNTHETIC_DETAIL_PATH_SUFFIX = "-aXXxxXXxxXX"

    private const val PAGE_REFERER = "$PROXY_BASE/"
    /** Browser User-Agent — themoviebox.org's edge inspects this. */
    private const val BROWSER_UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/149.0.0.0 Safari/537.36"
    /** Minimal X-Client-Info that the browser app sends; expanding this to the
     *  app-style payload (region/gaid/etc) triggered geo gates in testing. */
    private const val X_CLIENT_INFO = """{"timezone":"Africa/Nairobi"}"""

    private val cookieJar = SimpleCookieJar()

    private val client: OkHttpClient = OkHttpClient.Builder()
        .cookieJar(cookieJar)
        .followRedirects(true)
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .callTimeout(25, TimeUnit.SECONDS)
        .build()

    @Volatile private var warmed = false
    @Volatile private var bearer: String? = null
    private val warmLock = Any()

    /** One-shot session warm. Two steps:
     *   1. `country-code` — establishes the H5 `token` cookie our cookie jar
     *      then carries to the themoviebox.org play proxy.
     *   2. `subject/search-suggest` — its response includes an `x-user` header
     *      with a guest JWT; without that JWT as `Authorization: Bearer`,
     *      `/subject/search` 400s with "invalid token" (reproduced on-device).
     *  Repeating is harmless; each call rotates the JWT but we keep the
     *  latest. Best-effort: search will still run if these fail and surface
     *  whatever error the server returns. */
    private fun ensureWarm() {
        if (warmed) return
        synchronized(warmLock) {
            if (warmed) return
            runCatching {
                client.newCall(
                    Request.Builder()
                        .url("$H5_BASE/wefeed-h5api-bff/country-code")
                        .header("Accept", "application/json")
                        .header("User-Agent", BROWSER_UA)
                        .header("Referer", PAGE_REFERER)
                        .header("X-Client-Info", X_CLIENT_INFO)
                        .get().build(),
                ).execute().use { r ->
                    android.util.Log.i("H5", "warm country-code ${r.code} cookies=${r.headers("set-cookie").size}")
                }
            }.onFailure { android.util.Log.w("H5", "warm country-code failed: ${it.message}") }
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
                    .header(
                        "x-tr-signature",
                        Crypto.trSignature(
                            method = "POST",
                            accept = "application/json",
                            contentType = ctype,
                            url = "$H5_BASE$path",
                            body = body,
                            timestampMs = ts,
                        ),
                    )
                    .header("X-Client-Info", X_CLIENT_INFO)
                    .header("X-Client-Status", "0")
                    .post(body.toRequestBody(ctype.toMediaType()))
                    .build()
                client.newCall(req).execute().use { r ->
                    val xUser = r.header("x-user")
                    if (!xUser.isNullOrBlank()) {
                        runCatching {
                            JSONObject(xUser).optString("token").takeIf { it.isNotBlank() }
                        }.getOrNull()?.let { bearer = it }
                    }
                    android.util.Log.i(
                        "H5",
                        "warm search-suggest ${r.code} x-user=${if (xUser.isNullOrBlank()) "NO" else "YES"} bearer=${if (bearer.isNullOrBlank()) "NO" else "len=${bearer!!.length}"}"
                    )
                }
            }.onFailure { android.util.Log.w("H5", "warm search-suggest failed: ${it.message}") }
            warmed = true
        }
    }

    /** Sign + POST a JSON body to the H5 origin. Used for search; the signing
     *  is identical to what we already do for the legacy mobile API and the
     *  H5 origin still accepts it. */
    fun signedPost(path: String, jsonBody: String): String {
        ensureWarm()
        val ts = System.currentTimeMillis()
        val contentType = "application/json; charset=utf-8"
        val sig = Crypto.trSignature(
            method = "POST",
            accept = "application/json",
            contentType = contentType,
            url = "$H5_BASE$path",
            body = jsonBody,
            timestampMs = ts,
        )
        val builder = Request.Builder()
            .url("$H5_BASE$path")
            .header("User-Agent", BROWSER_UA)
            .header("Accept", "application/json")
            .header("Content-Type", contentType)
            .header("Referer", PAGE_REFERER)
            .header("X-Client-Token", Crypto.clientToken(ts))
            .header("x-tr-signature", sig)
            .header("X-Client-Info", X_CLIENT_INFO)
            .header("X-Client-Status", "0")
            .post(jsonBody.toRequestBody(contentType.toMediaType()))
        effectiveBearer()?.let { builder.header("Authorization", "Bearer $it") }
        return client.newCall(builder.build()).execute().use { r ->
            absorbXUser(r.header("x-user"))
            android.util.Log.i("H5", "POST $path -> ${r.code} (bearer=${if (bearer.isNullOrBlank()) "NO" else "YES"})")
            if (!r.isSuccessful) error("H5 ${r.code} on $path")
            r.body?.string().orEmpty()
        }
    }

    /** Refresh the bearer when a response rotates the JWT. */
    private fun absorbXUser(xUser: String?) {
        if (xUser.isNullOrBlank()) return
        runCatching {
            JSONObject(xUser).optString("token").takeIf { it.isNotBlank() }
        }.getOrNull()?.let { bearer = it }
    }

    /** Fallback: pull the `token` cookie value out of our cookie jar. On some
     *  IPs/regions the H5 origin sets the JWT via a Set-Cookie header instead
     *  of `x-user`; without using it as a Bearer, `/subject/search` 400s. */
    private fun bearerFromCookies(): String? {
        val req = Request.Builder().url(H5_BASE).get().build()
        return cookieJar.loadForRequest(req.url)
            .firstOrNull { it.name == "token" }
            ?.value
            ?.takeIf { it.isNotBlank() }
    }

    private fun effectiveBearer(): String? = bearer ?: bearerFromCookies()

    /** GET the themoviebox.org proxy unsigned (browser flow). Caller provides a
     *  query string already encoded. Returns the response body. */
    fun proxyGet(path: String, query: String): String {
        ensureWarm()
        val builder = Request.Builder()
            .url("$PROXY_BASE$path?$query")
            .header("User-Agent", BROWSER_UA)
            .header("Accept", "application/json")
            .header("Referer", PAGE_REFERER)
            .header("X-Client-Info", X_CLIENT_INFO)
            .header("X-Source", "")
            .get()
        effectiveBearer()?.let { builder.header("Authorization", "Bearer $it") }
        return client.newCall(builder.build()).execute().use { r ->
            absorbXUser(r.header("x-user"))
            val bodyStr = r.body?.string().orEmpty()
            if (!r.isSuccessful) {
                android.util.Log.w("H5", "proxy ${r.code} body=${bodyStr.take(220)}")
                error("Proxy ${r.code} on $path")
            }
            bodyStr
        }
    }

    /** Suffix used to fabricate a detailPath when the catalog only has the
     *  subjectId. The proxy's slug validation is lenient. */
    fun syntheticDetailPath(title: String): String {
        val slug = title.lowercase()
            .replace(Regex("[^a-z0-9]+"), "-")
            .trim('-')
            .ifEmpty { "title" }
        return "$slug$SYNTHETIC_DETAIL_PATH_SUFFIX"
    }
}

/** Tiny in-memory cookie jar. Only one process / one user, so a plain map is
 *  enough — no persistence needed (the H5 token rotates on every call anyway). */
private class SimpleCookieJar : CookieJar {
    private val store = mutableMapOf<String, MutableList<Cookie>>()

    @Synchronized
    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        if (cookies.isEmpty()) return
        val list = store.getOrPut(url.host) { mutableListOf() }
        cookies.forEach { c ->
            list.removeAll { it.name == c.name }
            list.add(c)
        }
    }

    @Synchronized
    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        // Send cookies set by either H5 origin or the proxy itself — the proxy
        // accepts the H5 origin's `token` cookie when the host matches its
        // own session policy (verified on device + via the headless capture).
        val matches = mutableListOf<Cookie>()
        store.forEach { (_, list) ->
            list.forEach { c ->
                if (c.matches(url)) matches.add(c)
            }
        }
        return matches
    }
}
