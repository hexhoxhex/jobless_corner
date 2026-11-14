package com.moviebox.tv.net

import android.content.Context
import com.moviebox.tv.App
import com.moviebox.tv.data.PlayInfo
import com.moviebox.tv.data.Quality
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * VidNest — a TMDB-keyed adaptive-HLS provider (ported from cinepro-org/core
 * `providers/vidnest`).
 *
 * Chosen after live-testing all 16 cinepro providers: only this one and
 * [Icefy] still worked. Measured 2026-08-03: 12-16 Mbps, a 4-rendition ladder
 * up to 1080p, clean `video/mp2t` segments, hits for both movies and TV.
 *
 * 2026-11-14 the site changed how its player asks, and every title answered
 * 403 "missing or invalid nonce" ("VidNest failed for Banshee"). Its player
 * script (vidnest.fun, `withStreamNonceUrl`/`decryptCipherResponse`) now:
 *  - adds `_n=<16 random bytes, base64url>` to every API request; there is no
 *    secret in it, the server only wants one there;
 *  - may answer `{"v":2,"n":..,"d":..}`: AES-GCM, key = SHA-256 of
 *    "<n>|<a constant in the script>", the first 12 bytes of d the IV. The
 *    older `{"data": <base64 under a shuffled alphabet>}` still comes back
 *    from some servers;
 *  - picks from twelve servers, not the two we knew. Measured that day on
 *    Banshee S1E1, Inception and Breaking Bad S2E3: ophim answered all three
 *    with a stream that plays, allmovies two (502 on Banshee), flixhq,
 *    yflix and rogflix answer as a single `{url}` object, and hollymoviehd's
 *    goodstream links still refuse us (403).
 * If decryption stops working, the constant and the server list are read
 * again from the site's own scripts ([relearn]), at most every few hours.
 *
 * The CDN 404s without the exact Referer, and the payload TELLS US which one
 * to use (it differs per sub-server), so it travels back in
 * [PlayInfo.headers] rather than being hardcoded.
 */
object VidNest {

    const val PREFIX = "vn:"

    private const val BASE = "https://new.vidnest.fun"
    private const val SITE = "https://vidnest.fun"
    private const val TAG = "VidNest"
    private const val UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"

    /** Sub-servers in preference order (see the class note). */
    private val DEFAULT_SERVERS = listOf(
        "ophim", "allmovies", "flixhq", "yflix", "rogflix", "vidzee", "hollymoviehd",
    )

    /** The constant the v2 key is made from, as the site's script had it. */
    private const val DEFAULT_SECRET = "K3p8vQ2mX9rL5wZ0fN7bG1sH4tJ6dC9_y8S3wM1nQ5pL2"

    /** The older payloads: standard base64 with a shuffled alphabet. */
    private const val CUSTOM =
        "RB0fpH8ZEyVLkv7c2i6MAJ5u3IKFDxlS1NTsnGaqmXYdUrtzjwObCgQP94hoeW+/="
    private const val STANDARD =
        "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/="

    private const val PREFS = "vidnest"
    private const val RELEARN_EVERY_MS = 6 * 60 * 60_000L

    @Volatile private var secret: String = DEFAULT_SECRET
    @Volatile private var servers: List<String> = DEFAULT_SERVERS
    @Volatile private var loaded = false
    @Volatile private var lastRelearn = 0L

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    private class Entry(val url: String, val language: String, val headers: Map<String, String>, val type: String)

    /** Resolve a TMDB id to a playable HLS stream. season/episode of 0 means
     *  "movie". Returns null when no sub-server carries the title, so the
     *  caller falls through to the next provider. */
    suspend fun resolvePlay(
        tmdbId: Int,
        season: Int,
        episode: Int,
        title: String,
    ): PlayInfo? = withContext(Dispatchers.IO) {
        load()
        // A server that only carries a dub is a last resort, not an answer.
        // This used to take the first server that returned anything, so
        // Spider-Man came back from `allmovies` with its single Hindi track
        // and played in Hindi — with no way to change it. Keep looking for
        // an English track across the remaining servers first.
        var dubbedFallback: PlayInfo? = null
        val misses = ArrayList<String>()
        for (server in servers) {
            val path =
                if (season > 0) "/$server/tv/$tmdbId/$season/$episode"
                else "/$server/movie/$tmdbId"
            val (code, raw) = get(BASE + path)
            if (raw == null) {
                misses += "$server ${if (code > 0) "HTTP $code" else "no answer"}"
                continue
            }
            val payload = open(raw)
                ?: if (relearn()) open(raw) else null
            if (payload == null) {
                misses += "$server unreadable"
                continue
            }
            // Prefer an English track. The provider returns one stream entry
            // per audio language (English/Hindi/Tamil/Telugu), not per
            // quality — picking blind lands the viewer in a dub.
            val entries = entriesOf(payload)
            if (entries.isEmpty()) {
                misses += "$server none"
                continue
            }
            val ordered = entries.sortedBy { if (isOriginal(it.language)) 0 else 1 }
            for (chosen in ordered) {
                val kind = StreamProbe.check(chosen.url, chosen.headers)
                if (kind == null) {
                    android.util.Log.w(
                        TAG,
                        "$server ${chosen.language}: the stream refused us — skipping " +
                            "(${chosen.url.substringBefore('?').take(80)})",
                    )
                    continue
                }
                android.util.Log.i(
                    TAG,
                    "resolved tmdb=$tmdbId s=${season}e=$episode via $server " +
                        "(${entries.size} audio tracks, picked ${chosen.language}, $kind)",
                )
                val info = PlayInfo(
                    title = title,
                    // The master playlist — ExoPlayer adapts across its renditions.
                    mediaUrl = chosen.url,
                    selected = "Auto",
                    qualities = listOf(Quality("Auto", chosen.url)),
                    captions = emptyList(),
                    dubs = emptyList(),
                    selectedDub = chosen.language,
                    season = season,
                    episode = episode,
                    episodeTitle = title,
                    durationSec = 0,
                    headers = chosen.headers,
                    hls = kind == "hls" || chosen.type.equals("hls", true),
                )
                if (isOriginal(chosen.language)) return@withContext info
                if (dubbedFallback == null) dubbedFallback = info
                break
            }
            misses += "$server refused"
        }
        if (dubbedFallback == null) {
            android.util.Log.w(TAG, "nothing for tmdb=$tmdbId s=${season}e=$episode: ${misses.joinToString()}")
        }
        dubbedFallback
    }

    /** The original soundtrack: English, "Original (Audio)", no name, or a
     *  server label that isn't a language at all ("LS-25"). */
    private fun isOriginal(language: String): Boolean {
        val l = language.trim()
        if (l.isEmpty() || l.equals("auto", true)) return true
        if (l.contains("english", true) || l.contains("original", true) || l.equals("en", true)) return true
        return !l.all { it.isLetter() || it == ' ' }
    }

    /** Stream entries out of any of the payload shapes the servers send:
     *  `{streams:[..]}`, `{sources:[..]}` or one `{url, headers}`. */
    private fun entriesOf(p: JSONObject): List<Entry> {
        fun entry(o: JSONObject): Entry? {
            val url = o.optString("url").ifBlank { o.optString("file") }
            // A relative address needs a relay the page picks; not ours to guess.
            if (!url.startsWith("http")) return null
            val headers = o.optJSONObject("headers")?.let { h ->
                h.keys().asSequence().associateWith { k -> h.optString(k).trim() }
                    .filterValues { v -> v.isNotBlank() }
            }.orEmpty()
            val language = o.optString("language").ifBlank { o.optString("lang") }
                .ifBlank { o.optString("label") }.ifBlank { "Original" }
            return Entry(url, language, headers, o.optString("type"))
        }
        val list = p.optJSONArray("streams") ?: p.optJSONArray("sources")
        if (list != null) {
            return (0 until list.length()).mapNotNull { i ->
                list.optJSONObject(i)?.let(::entry)
            }
        }
        return listOfNotNull(entry(p))
    }

    /** A server's answer as JSON: v2 opened with AES-GCM, the older shuffled
     *  base64, or plain. Null when it can't be read. */
    private fun open(raw: String): JSONObject? = runCatching {
        val o = JSONObject(raw)
        when {
            o.optInt("v") == 2 && o.has("d") -> openV2(o.getString("d"), o.optString("n"))
            o.has("data") -> {
                val translated = buildString {
                    for (c in o.getString("data")) {
                        val i = CUSTOM.indexOf(c)
                        append(if (i >= 0) STANDARD[i] else c)
                    }
                }
                JSONObject(
                    String(android.util.Base64.decode(translated, android.util.Base64.DEFAULT), Charsets.UTF_8),
                )
            }
            else -> o
        }
    }.getOrNull()

    private fun openV2(d: String, n: String): JSONObject? = runCatching {
        val key = java.security.MessageDigest.getInstance("SHA-256")
            .digest("$n|$secret".toByteArray(Charsets.UTF_8))
        val bytes = android.util.Base64.decode(d, android.util.Base64.URL_SAFE)
        val cipher = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            javax.crypto.Cipher.DECRYPT_MODE,
            javax.crypto.spec.SecretKeySpec(key, "AES"),
            javax.crypto.spec.GCMParameterSpec(128, bytes, 0, 12),
        )
        val plain = String(cipher.doFinal(bytes, 12, bytes.size - 12), Charsets.UTF_8)
        // The script parses JSON when it can; a bare string is a URL answer.
        runCatching { JSONObject(plain) }.getOrElse { JSONObject().put("url", plain.trim('"')) }
    }.getOrNull()

    /**
     * Read the v2 constant and the server list again from the site's own
     * scripts, when an answer can't be opened. At most every
     * [RELEARN_EVERY_MS]; true when it found something new.
     */
    private fun relearn(): Boolean {
        val now = System.currentTimeMillis()
        if (now - lastRelearn < RELEARN_EVERY_MS) return false
        lastRelearn = now
        val page = get("$SITE/tv/1396/1/1", referer = "$SITE/").second ?: return false
        val scripts = Regex("""/_next/static/chunks/[A-Za-z0-9_-]+\.js""").findAll(page)
            .map { it.value }.distinct().take(30).toList()
        var newSecret: String? = null
        var newServers: List<String>? = null
        for (s in scripts) {
            val js = get(SITE + s, referer = "$SITE/").second ?: continue
            if (newSecret == null) {
                newSecret = Regex("""="([A-Za-z0-9_-]{32,64})",\w{1,3}="https://new\.vidnest\.fun"""")
                    .find(js)?.groupValues?.get(1)
            }
            if (newServers == null && "tvUrl" in js) {
                newServers = Regex("""tvUrl:`\${'$'}\{\w{1,3}\}/([a-z0-9]+)""").findAll(js)
                    .map { it.groupValues[1] }.distinct().toList().takeIf { it.size >= 2 }
            }
            if (newSecret != null && newServers != null) break
        }
        val changed = (newSecret != null && newSecret != secret) ||
            (newServers != null && newServers.toSet() != servers.toSet())
        newSecret?.let { secret = it }
        newServers?.let { found ->
            // Ours first, in our order; any new ones after.
            servers = DEFAULT_SERVERS.filter { it in found } + found.filter { it !in DEFAULT_SERVERS }
        }
        runCatching {
            App.instance.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString("secret", secret)
                .putString("servers", JSONArray(servers).toString())
                .apply()
        }
        android.util.Log.w(
            TAG,
            "read the site's scripts again: ${if (changed) "something changed" else "nothing new"} " +
                "(${servers.size} servers)",
        )
        return changed
    }

    private fun load() {
        if (loaded) return
        loaded = true
        runCatching {
            val prefs = App.instance.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            prefs.getString("secret", null)?.takeIf { it.isNotBlank() }?.let { secret = it }
            prefs.getString("servers", null)?.let { s ->
                val arr = JSONArray(s)
                (0 until arr.length()).map { arr.getString(it) }.takeIf { it.isNotEmpty() }
                    ?.let { servers = it }
            }
        }
    }

    /** A fresh `_n` for one request: 16 random bytes, base64url, as the
     *  site's player makes it. */
    private fun nonce(): String {
        val bytes = ByteArray(16).also { java.security.SecureRandom().nextBytes(it) }
        return android.util.Base64.encodeToString(
            bytes,
            android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP or android.util.Base64.NO_PADDING,
        )
    }

    /** GET [url] (API calls get their `_n`); the status and, when it was
     *  a success, the body. */
    private fun get(url: String, referer: String = "$SITE/"): Pair<Int, String?> = runCatching {
        val withNonce = if (url.startsWith(BASE)) {
            url + (if ('?' in url) "&" else "?") + "_n=" + nonce()
        } else url
        client.newCall(
            Request.Builder().url(withNonce)
                .header("User-Agent", UA)
                .header("Referer", referer)
                .header("Origin", SITE)
                .get().build(),
        ).execute().use { r -> r.code to (if (r.isSuccessful) r.body?.string() else null) }
    }.getOrDefault(0 to null)
}
