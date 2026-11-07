package com.moviebox.tv.data.live

import android.annotation.SuppressLint
import android.util.Log
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import com.moviebox.tv.App
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Learns how a live source wants to be read by watching its own player.
 *
 * Every live outage so far was the source changing how its player gets the
 * stream: a token call (2026-10-18), chunk lists sealed with a key from that
 * call (2026-11-07), new header or parameter demands. Each stayed broken
 * until someone read the player's code and shipped an update. The player has
 * to do it all in every viewer's browser, so the answer is on the page.
 *
 * When a source stops making sense, this loads its player page in a hidden
 * WebView, inside a frame on the page that embeds it (the player refuses to
 * be opened on its own), and watches the requests it makes from outside the
 * page: script on the page can't hide those, where it could (and does, in a
 * srcdoc frame) hide its own fetch and crypto calls. Token, API and playlist
 * requests are made here instead and their answers read on the way through;
 * chunk, picture, ad and peer-to-peer requests are refused, so it stays
 * light. From that:
 *
 *  - the token call: its address, the field with the token, the parameter
 *    that carries it on the playlist request;
 *  - sealed chunk lines: [Seal.search] against the token answer the player
 *    got, checked where possible against the chunk addresses the player then
 *    asked for;
 *  - the Origin/Referer on the player's playlist requests.
 *
 * The page runs once, for at most [MAX_RUN_MS], only after the source failed
 * in a way the app could not explain. What it can't learn (a key that never
 * crosses the network, a page that waits for a click) still needs an update;
 * the report it logs says what it saw.
 */
object SourceTeacher {
    private const val TAG = "LiveDiag"
    private const val MAX_RUN_MS = 45_000L
    /** After the first playlist, how long to keep listening. */
    private const val SETTLE_MS = 4_000L
    private const val BODY_MAX = 64 * 1024

    private const val UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
        "(KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"

    data class Result(val lesson: Lesson?, val report: String)

    private val running = AtomicBoolean(false)

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .followRedirects(true)
            .addInterceptor(HostGate)
            .build()
    }

    private class Net(val url: String, val status: Int, val body: String, val sent: Map<String, String>)

    private class Capture {
        val nets = mutableListOf<Net>()
        val chunks = mutableListOf<String>()
        val blockedHosts = mutableSetOf<String>()
        var firstPlaylistAt = 0L

        @Synchronized fun addNet(n: Net) {
            if (nets.size >= 60) return
            nets += n
            if (firstPlaylistAt == 0L && isPlaylist(n)) firstPlaylistAt = System.currentTimeMillis()
        }

        @Synchronized fun enough(): Boolean =
            firstPlaylistAt != 0L && System.currentTimeMillis() - firstPlaylistAt > SETTLE_MS
    }

    private fun isPlaylist(n: Net) =
        n.status in 200..299 && n.body.trimStart().startsWith("#EXTM3U") && "#EXTINF" in n.body

    /**
     * Load the player at [pageUrl] as [referer] embeds it, and learn how
     * [host] wants to be read. One at a time.
     */
    suspend fun learn(pageUrl: String, referer: String, host: String): Result {
        if (!running.compareAndSet(false, true)) return Result(null, "another lesson is running")
        val capture = Capture()
        var webView: WebView? = null
        val t0 = System.currentTimeMillis()
        try {
            Log.i(TAG, "LEARN $host: watching its player at ${pageUrl.take(80)}")
            webView = withContext(Dispatchers.Main) { open(pageUrl, referer, capture) }
            while (System.currentTimeMillis() - t0 < MAX_RUN_MS && !capture.enough()) delay(500)
        } finally {
            webView?.let { wv ->
                withContext(Dispatchers.Main) {
                    runCatching {
                        wv.stopLoading()
                        wv.loadUrl("about:blank")
                        wv.destroy()
                    }
                }
            }
            running.set(false)
        }
        val result = withContext(Dispatchers.Default) { synchronized(capture) { infer(capture, host) } }
        val took = System.currentTimeMillis() - t0
        Log.w(TAG, "LEARN $host: ${if (result.lesson != null) "learned" else "nothing learned"} " +
            "in ${took}ms — ${result.report}")
        return result
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun open(pageUrl: String, referer: String, capture: Capture): WebView {
        val wv = WebView(App.instance)
        wv.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            userAgentString = UA
            mediaPlaybackRequiresUserGesture = false
            blockNetworkImage = true
        }
        wv.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                val url = request.url.toString()
                val host = request.url.host.orEmpty()
                if (blocked(url, host)) {
                    synchronized(capture) {
                        capture.blockedHosts += host
                        if (CHUNKISH.containsMatchIn(url) || "tiktokcdn" in host) {
                            if (capture.chunks.size < 40) capture.chunks += url
                        }
                    }
                    return empty()
                }
                if (request.method == "GET" && (".m3u8" in url || "token" in url.lowercase() || "/api/" in url)) {
                    return passThrough(url, request.requestHeaders.orEmpty(), capture)
                }
                return null
            }
        }
        // The player only opens inside a frame on the page that embeds it.
        val shell = "<!doctype html><html><body style=\"margin:0;background:#000\">" +
            "<iframe src=\"${pageUrl.replace("\"", "%22")}\" allow=\"autoplay; encrypted-media\" " +
            "style=\"width:640px;height:360px;border:0\"></iframe></body></html>"
        wv.loadDataWithBaseURL(referer, shell, "text/html", "utf-8", null)
        return wv
    }

    /** Make the request here, note the answer, hand it to the page as is. */
    private fun passThrough(url: String, sent: Map<String, String>, capture: Capture): WebResourceResponse? =
        runCatching {
            val req = okhttp3.Request.Builder().url(url).apply {
                sent.forEach { (k, v) ->
                    if (k.lowercase() !in setOf("accept-encoding", "host", "content-length", "connection")) {
                        header(k, v)
                    }
                }
                if (sent.keys.none { it.equals("User-Agent", true) }) header("User-Agent", UA)
            }.build()
            client.newCall(req).execute().use { r ->
                val bytes = r.body?.bytes() ?: ByteArray(0)
                val ct = r.header("Content-Type").orEmpty()
                val text = if (bytes.size <= BODY_MAX) String(bytes, Charsets.UTF_8) else ""
                capture.addNet(Net(url, r.code, text, sent))
                val headers = r.headers.toMultimap()
                    .filterKeys { it.lowercase() !in setOf("content-encoding", "content-length", "transfer-encoding") }
                    .mapValues { it.value.joinToString(", ") }
                WebResourceResponse(
                    ct.substringBefore(';').trim().ifEmpty { "text/plain" },
                    ct.substringAfter("charset=", "utf-8").trim(),
                    r.code, r.message.ifEmpty { "OK" }, headers,
                    java.io.ByteArrayInputStream(bytes),
                )
            }
        }.getOrNull()

    private fun empty() = WebResourceResponse(
        "text/plain", "utf-8", 204, "No Content",
        mapOf("Access-Control-Allow-Origin" to "*"), java.io.ByteArrayInputStream(ByteArray(0)),
    )

    private val CHUNKISH = Regex("""\.(ts|m4s|mp4|aac|image)(\?|$)""", RegexOption.IGNORE_CASE)
    private val MEDIA = Regex(
        """\.(ts|m4s|mp4|aac|m4a|png|jpe?g|gif|webp|svg|ico|woff2?|ttf|image)(\?|$)""",
        RegexOption.IGNORE_CASE,
    )

    /** Ads, trackers, peer-to-peer and every picture or chunk: the lesson
     *  needs the player's requests, nothing it would show. */
    private val NOT_NEEDED = listOf(
        "tiktokcdn", "p2p-engine", "p2p-media-loader", "doubleclick", "googlesyndication",
        "googletagmanager", "google-analytics", "adservice", "adsrvr", "pubmatic", "rubicon",
        "connatix", "histats", "adcash", "rapidadexchange", "amazon-adsystem", "adform",
        "criteo", "taboola", "outbrain", "popads", "propeller", "chatango", "id5-sync",
        "smartadserver", "loopme", "yellowblue", "demdex", "rlcdn", "bidr.io", "lijit",
        "sharethrough", "adnxs", "turn.com", "1rx.io", "crwdcntrl", "semasio", "zeotap",
        "trustedstack", "pmbmonetize", "usrpubtrck", "jwpltx", "qpon", "browserpro",
    )

    private fun blocked(url: String, host: String): Boolean =
        MEDIA.containsMatchIn(url.substringBefore('#')) || NOT_NEEDED.any { it in host || it in url }

    // ---- working the rule out ------------------------------------------------

    private fun infer(c: Capture, host: String): Result {
        val playlists = c.nets.filter(::isPlaylist)
        val jsons = c.nets.mapNotNull { n ->
            if (n.status !in 200..299) null
            else runCatching { JSONObject(n.body) }.getOrNull()?.let { n to it }
        }
        val seen = "seen ${c.nets.size} answers, ${playlists.size} playlists, ${jsons.size} JSON, " +
            "${c.chunks.size} chunk requests, ${c.blockedHosts.size} hosts refused"
        if (playlists.isEmpty()) {
            val refused = c.nets.filter { it.status !in 200..299 }.take(3)
                .joinToString { "${it.status} ${it.url.substringBefore('?').takeLast(50)}" }
            return Result(null, "no playlist reached the player ($seen${if (refused.isNotEmpty()) "; refused: $refused" else ""})")
        }
        val playlistHost = hostOf(playlists.first().url)

        // The token: a JSON answer whose string field rides on a playlist request.
        var recipe: TokenRecipe? = null
        val jsonFor = HashMap<Net, JSONObject>()
        for (p in playlists) {
            val query = queryOf(p.url)
            for ((n, j) in jsons) {
                for (field in j.keys()) {
                    val v = j.opt(field) as? String ?: continue
                    if (v.length < 8) continue
                    val param = query.entries.firstOrNull { it.value == v }?.key ?: continue
                    jsonFor[p] = j
                    if (recipe == null) {
                        recipe = TokenRecipe(
                            urlTemplate = template(n.url, slugOf(p.url)),
                            tokenField = field,
                            param = param,
                            ttlField = j.keys().asSequence().firstOrNull { k ->
                                k.lowercase() in setOf("ttl", "expires_in", "expiresin") && j.opt(k) is Number
                            },
                        )
                    }
                }
            }
        }

        // Sealed lines: worked out against the token answer that came with them.
        var seal: SealRule? = null
        var sealNote = "no sealing"
        val sealed = playlists.filter { p -> p.body.lineSequence().any { Seal.looksSealed(it.trim()) } }
        if (sealed.isNotEmpty()) {
            sealNote = "lines are sealed and no rule opens them with what the token call returned"
            loop@ for (p in sealed) {
                val candidates = listOfNotNull(jsonFor[p]) + jsons.map { it.second }
                for (j in candidates) {
                    val rule = Seal.search(p.body, j) ?: continue
                    seal = rule
                    // The player's own chunk requests, when it got that far.
                    val opened = openAll(rule, p.body, j)
                    val agreed = opened.count { it in c.chunks }
                    sealNote = "seal $rule" + if (c.chunks.isNotEmpty()) ", $agreed of ${opened.size} match the player's chunk requests" else ""
                    break@loop
                }
            }
        }

        val headers = playlists.first().sent
            .filterKeys { it.equals("Origin", true) || it.equals("Referer", true) }
            .mapKeys { if (it.key.equals("origin", true)) "Origin" else "Referer" }

        if (recipe == null && seal == null && headers.isEmpty()) {
            return Result(null, "nothing to take from it ($seen; $sealNote)")
        }
        if (sealed.isNotEmpty() && seal == null) {
            return Result(null, "$sealNote ($seen)")
        }
        val lesson = Lesson(playlistHost, System.currentTimeMillis(), recipe, seal, headers)
        return Result(
            lesson,
            "token=${recipe?.let { "${it.tokenField} as ${it.param} from ${it.urlTemplate.take(70)}" } ?: "none"}; " +
                "$sealNote; headers=${headers.keys}" +
                (if (playlistHost != host) "; the player reads $playlistHost, not $host" else "") +
                " ($seen)",
        )
    }

    private fun openAll(rule: SealRule, body: String, token: JSONObject): List<String> {
        var seq = 0L
        val out = ArrayList<String>()
        for (raw in body.lineSequence()) {
            val line = raw.trim()
            if (line.startsWith("#EXT-X-MEDIA-SEQUENCE:")) {
                line.substringAfter(':').trim().toLongOrNull()?.let { seq = it }
                continue
            }
            if (line.isEmpty() || line.startsWith("#")) continue
            Seal.open(rule, line, seq, token)?.let { out += it }
            seq++
        }
        return out
    }

    private fun queryOf(url: String): Map<String, String> =
        url.substringAfter('?', "").substringBefore('#').split('&')
            .filter { '=' in it }
            .associate {
                it.substringBefore('=') to
                    (runCatching { java.net.URLDecoder.decode(it.substringAfter('='), "UTF-8") }
                        .getOrDefault(it.substringAfter('=')))
            }

    /** "https://edge.../premium343/index.m3u8" -> "premium343", as tokenized() does. */
    private fun slugOf(url: String): String =
        url.substringAfter("//").substringAfter('/', "").substringBefore('/').substringBefore('?')

    /** [url] with the channel's slug as {slug} and cache-busting times as {now}. */
    private fun template(url: String, slug: String): String {
        var t = url
        if (slug.isNotEmpty()) {
            t = t.replace(java.net.URLEncoder.encode(slug, "UTF-8"), "{slug}").replace(slug, "{slug}")
        }
        return t.replace(Regex("""(?<==)\d{13}(?=&|$)"""), "{now}")
    }

    private fun hostOf(url: String): String =
        url.substringAfter("//").substringBefore('/').substringBefore(':')
}
