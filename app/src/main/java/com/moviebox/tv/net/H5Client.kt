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

    /** Force the next H5/proxy call to re-warm and pick up a fresh session.
     *  Called when a play attempt returns zero streams despite the WebView
     *  resolver running — likely the cached `token` cookie has been
     *  invalidated server-side. */
    fun resetSession() {
        synchronized(warmLock) {
            warmed = false
            bearer = null
            cookieJar.clear()
        }
    }

    /** Push raw cookies from elsewhere (e.g. Android's WebView CookieManager
     *  after the headless play resolver loads a page) into our OkHttp jar.
     *  This is what bridges the WebView session — which mints the
     *  resource-capable `mb_token` + `token` cookies — into the OkHttp client
     *  that subsequent direct play calls use. Without this sync the WebView
     *  unlocked the proxy session but our OkHttp never benefited; every play
     *  re-ran the WebView. After syncing, direct H5/proxy calls reuse the
     *  same cookies and skip the WebView round trip entirely. */
    fun pushCookies(host: String, rawCookies: String) {
        if (rawCookies.isBlank()) return
        val httpUrl = HttpUrl.Builder().scheme("https").host(host).build()
        val parsed = rawCookies.split(";").mapNotNull { part ->
            val trimmed = part.trim()
            if (trimmed.isEmpty()) return@mapNotNull null
            val eq = trimmed.indexOf('=')
            if (eq <= 0) return@mapNotNull null
            val name = trimmed.substring(0, eq).trim()
            val value = trimmed.substring(eq + 1).trim()
            runCatching {
                Cookie.Builder().name(name).value(value).domain(host).path("/")
                    .expiresAt(System.currentTimeMillis() + 30L * 24 * 3600_000)
                    .build()
            }.getOrNull()
        }
        if (parsed.isNotEmpty()) {
            cookieJar.saveFromResponse(httpUrl, parsed)
            val tok = parsed.firstOrNull { it.name == "token" }?.value
            if (!tok.isNullOrBlank()) bearer = tok
            android.util.Log.i("H5", "pushCookies($host) +${parsed.size} cookies, bearer=${if (bearer.isNullOrBlank()) "NO" else "YES"}")
        }
    }

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
            .header("x-request-lang", "en")
                        .get().build(),
                ).execute().use { r ->
                    // CRITICAL: country-code's x-user header carries the
                    // atp:3 anonymous-premium JWT — the same bearer
                    // themoviebox.org's SPA uses for subject/search. Reading
                    // it here is what unlocks the FULL catalog (House of the
                    // Dragon, Wednesday, Barbie, Breaking Bad, etc.). Before
                    // this fix, only the second warm call (search-suggest)
                    // populated the bearer — and search-suggest's x-user is a
                    // lower-tier guest JWT that filters those titles out
                    // server-side. Verified against a live themoviebox.org
                    // page-load capture: country-code mints uid with atp:3,
                    // search-suggest then rotates inside the same session
                    // but the *first* mint is what flips the catalog tier.
                    val xUser = r.header("x-user")
                    if (!xUser.isNullOrBlank()) {
                        runCatching {
                            JSONObject(xUser).optString("token")
                                .takeIf { it.isNotBlank() }
                        }.getOrNull()?.let { bearer = it }
                    }
                    android.util.Log.i(
                        "H5",
                        "warm country-code ${r.code} cookies=${r.headers("set-cookie").size} " +
                            "x-user=${if (xUser.isNullOrBlank()) "NO" else "YES"} " +
                            "bearer=${if (bearer.isNullOrBlank()) "NO" else "len=${bearer!!.length}"}"
                    )
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
            .header("x-request-lang", "en")
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

    /** Signed GET to the H5 origin. Used for /detail and other read endpoints
     *  that need the same auth as POST search. */
    fun signedGet(path: String, query: String = ""): String {
        ensureWarm()
        val ts = System.currentTimeMillis()
        val accept = "application/json"
        val canonical = if (query.isNotEmpty()) "$path?$query" else path
        val sig = Crypto.trSignature(
            method = "GET",
            accept = accept,
            contentType = accept,
            url = "$H5_BASE$canonical",
            body = null,
            timestampMs = ts,
        )
        val builder = Request.Builder()
            .url("$H5_BASE$canonical")
            .header("User-Agent", BROWSER_UA)
            .header("Accept", accept)
            .header("Referer", PAGE_REFERER)
            .header("X-Client-Token", Crypto.clientToken(ts))
            .header("x-tr-signature", sig)
            .header("X-Client-Info", X_CLIENT_INFO)
            .header("X-Client-Status", "0")
            .header("x-request-lang", "en")
            .get()
        effectiveBearer()?.let { builder.header("Authorization", "Bearer $it") }
        return client.newCall(builder.build()).execute().use { r ->
            absorbXUser(r.header("x-user"))
            if (!r.isSuccessful) error("H5 ${r.code} on $path")
            r.body?.string().orEmpty()
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
            .header("x-request-lang", "en")
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
            .header("x-request-lang", "en")
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

    /** Visit the movie page on themoviebox.org. This is what *actually*
     *  unlocks playback for a guest: the page load mints a resource-capable
     *  `token` cookie on the proxy origin. The bootstrap token from
     *  h5-api.search-suggest has a different uid and the proxy rejects it
     *  with `hasResource:false`. Reproduced end-to-end via a Playwright
     *  capture — only the cookie set by the page visit returned streams. */
    fun primeProxyForPlay(detailPath: String) {
        runCatching {
            val req = Request.Builder()
                .url("$PROXY_BASE/movies/$detailPath")
                .header("User-Agent", BROWSER_UA)
                .header(
                    "Accept",
                    "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
                )
                .header("Accept-Language", "en-US,en;q=0.9")
                .get().build()
            client.newCall(req).execute().close()
        }
    }
}

/** Cookie jar that persists across process restarts. The proxy's session
 *  state lives partly in its `token` cookie — saving it skips the warm calls
 *  on subsequent app launches, and means a recently-played title can be
 *  re-played without re-running the WebView resolver. */
internal class SimpleCookieJar : CookieJar {
    private val store = mutableMapOf<String, MutableList<Cookie>>()
    private val prefs: android.content.SharedPreferences? = runCatching {
        com.moviebox.tv.App.instance.getSharedPreferences("h5_cookies", android.content.Context.MODE_PRIVATE)
    }.getOrNull()
    @Volatile private var loaded = false

    @Synchronized
    private fun ensureLoaded() {
        if (loaded) return
        loaded = true
        val p = prefs ?: return
        runCatching {
            val arr = org.json.JSONArray(p.getString("jar", "[]") ?: "[]")
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val expires = o.optLong("expires", 0L)
                if (expires in 1..System.currentTimeMillis()) continue
                val b = Cookie.Builder().name(o.getString("name")).value(o.getString("value"))
                    .domain(o.getString("domain")).path(o.optString("path", "/"))
                if (expires > 0) b.expiresAt(expires)
                if (o.optBoolean("secure")) b.secure()
                if (o.optBoolean("httpOnly")) b.httpOnly()
                val c = b.build()
                store.getOrPut(o.getString("domain")) { mutableListOf() }.add(c)
            }
        }
    }

    @Synchronized
    private fun persist() {
        val p = prefs ?: return
        runCatching {
            val arr = org.json.JSONArray()
            store.forEach { (host, list) ->
                list.forEach { c ->
                    arr.put(
                        org.json.JSONObject()
                            .put("name", c.name).put("value", c.value)
                            .put("domain", host).put("path", c.path)
                            .put("expires", c.expiresAt)
                            .put("secure", c.secure).put("httpOnly", c.httpOnly)
                    )
                }
            }
            p.edit().putString("jar", arr.toString()).apply()
        }
    }

    @Synchronized
    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        ensureLoaded()
        if (cookies.isEmpty()) return
        val list = store.getOrPut(url.host) { mutableListOf() }
        cookies.forEach { c ->
            list.removeAll { it.name == c.name }
            list.add(c)
        }
        persist()
    }

    @Synchronized
    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        ensureLoaded()
        val matches = mutableListOf<Cookie>()
        store.forEach { (_, list) ->
            list.forEach { c -> if (c.matches(url)) matches.add(c) }
        }
        return matches
    }

    /** Wipe the jar — used when a play attempt fails with empty streams to
     *  force a fresh proxy session next time. */
    @Synchronized
    fun clear() {
        store.clear()
        prefs?.edit()?.remove("jar")?.apply()
    }
}
