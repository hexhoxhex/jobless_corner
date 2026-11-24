package com.moviebox.tv.data.live

import android.annotation.SuppressLint
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import com.moviebox.tv.App
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * Headless WebView resolver for iframe hosts we can't decode with regex.
 *
 * DADDY LIVE's alternate PLAYER paths route to a mix of resolver hosts.
 * PLAYER 1/3 use hamis.romponalis.st which embeds the m3u8 URL as a
 * simple `window.atob('<base64>')` call — [LiveResolver.tryPlayerPath]
 * grabs it with a regex. PLAYER 2/4/5/6 route to more JS-heavy players
 * (welovetocare.shop, api.cdnlivetv.tv, ksohls.ru, ritzembeds when it
 * doesn't embed the URL literally) whose m3u8 URLs are only visible
 * once the page's JS actually runs and asks Clappr/hls.js to fetch the
 * manifest.
 *
 * This resolver loads such an iframe in an off-screen WebView, watches
 * every outbound network request via [WebViewClient.shouldInterceptRequest],
 * and returns the first URL matching `.m3u8` we see. WebView is torn
 * down as soon as we have a hit (or [MAX_WAIT_MS] fires).
 *
 * Only invoked as a FALLBACK when the fast regex path returns null,
 * because a WebView load costs ~1-3s and holds ~10MB of memory. Common
 * channels never touch this path.
 */
object JsIframeResolver {

    private const val TAG = "JsIframeResolver"

    /** Mute anything this off-screen page decides to play, and keep muting:
     *  players replace their media element during setup, and ads inject
     *  their own. Same-origin only — a cross-origin ad iframe is beyond our
     *  reach, which is why the VOD resolver blocks autoplay outright. */
    private const val MUTE_JS = """
        (function () {
          var mute = function () {
            var m = document.querySelectorAll('video,audio');
            for (var i = 0; i < m.length; i++) { m[i].muted = true; m[i].volume = 0; }
          };
          mute();
          setInterval(mute, 400);
          if (window.MutationObserver) {
            new MutationObserver(mute).observe(
              document.documentElement, { childList: true, subtree: true });
          }
        })();
    """

    // 10 s (bumped from 6 s): welovetocare, cdnlivetv and ksohls all
    // load ~15-40 external resources (analytics, ad SDK, fingerprinting)
    // before Clappr fires the m3u8 request — 6 s was catching only the
    // fastest paths, timing out on the slower iframe hosts even though
    // they'd have worked given another 2-3 seconds. Tap-to-picture on
    // channels that only have JS players now caps at 10 s instead of
    // failing entirely. Channels with a working atob path are unaffected
    // (they short-circuit before JsIframeResolver is ever called).
    private const val MAX_WAIT_MS = 10_000L

    /** A desktop Chrome of the same major version as this WebView's engine.
     *  It was fixed at Chrome 131, and on 2026-11-24 waterera.net (the cast
     *  route: CNN and many more) refused that with a 403 while accepting 140
     *  and later; a fixed version goes stale as the engine updates. Desktop,
     *  not Android: an ad network shows its "not a robot" pop-up to
     *  non-desktop identities. */
    private val BROWSER_UA: String by lazy {
        val major = runCatching {
            Regex("""Chrome/(\d+)""").find(android.webkit.WebSettings.getDefaultUserAgent(App.instance))
                ?.groupValues?.get(1)
        }.getOrNull() ?: "157"
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/$major.0.0.0 Safari/537.36"
    }

    /** Per-host recent-timeout memory. A JS iframe host that failed to
     *  emit any m3u8 within [MAX_WAIT_MS] is recorded here; subsequent
     *  [resolve] calls for the same host return null IMMEDIATELY (no
     *  WebView) until the record ages out. This is the live-TV bottleneck
     *  fix: FOX USA's resolver race includes JS hosts (wikisport.info,
     *  ksohls.ru, dollardescent.net) that never resolve for that channel,
     *  and without this each resolve cycle spawned a fresh ~10 MB WebView
     *  per host that just burned 10 s + CPU before timing out. During a
     *  403-storm (repeated re-resolves) that piled up dozens of concurrent
     *  WebViews and starved the video decoder (erratic QIB). Keyed by
     *  host, not full URL, because the URL's signed token rotates every
     *  resolve while the host stays constant. */
    private val deadHosts =
        java.util.concurrent.ConcurrentHashMap<String, Long>()  // host -> skipped until

    private const val DEAD_HOST_TTL_MS = 120_000L  // 2 min, then retry

    /** A player whose own address doesn't resolve or connect (ksohls.ru,
     *  2026-11-24: the domain is gone) is skipped this long; it used to cost
     *  the full [MAX_WAIT_MS] every time, on every channel that lists it. */
    private const val UNREACHABLE_TTL_MS = 30 * 60_000L

    private fun hostRecentlyDead(host: String): Boolean {
        val until = deadHosts[host] ?: return false
        if (System.currentTimeMillis() > until) {
            deadHosts.remove(host)
            return false
        }
        return true
    }

    private fun htmlAttr(s: String): String =
        s.replace("&", "&amp;").replace("\"", "&quot;").replace("<", "&lt;").replace(">", "&gt;")

    /** How a player is shown to the hidden WebView. */
    private enum class Mode {
        /** In a frame of a page that has the wrapper's address: the site's
         *  own page isn't fetched. */
        EMBED,

        /** The site's wrapper page itself, player and all, as the desktop
         *  app's hidden window does it. */
        PAGE,
    }

    /** Player hosts that showed a stream only inside the site's own page,
     *  and when: they go straight there next time. */
    private val pageHosts = java.util.concurrent.ConcurrentHashMap<String, Long>()
    private const val PAGE_HOST_TTL_MS = 24 * 3600_000L

    /** The wrapper page is 650 KB and the player starts after it: more time. */
    private const val PAGE_WAIT_MS = 30_000L

    private class Outcome(val m3u8: String?, val unreachable: Boolean = false)

    /** A resolve waiting for its playlist: the player's host, and what to call. */
    private class Waiting(val playerHost: String?, val onM3u8: (String) -> Unit)
    private val waiting = java.util.concurrent.CopyOnWriteArrayList<Waiting>()
    private val swWatching = java.util.concurrent.atomic.AtomicBoolean(false)

    /**
     * Players that install a service worker fetch their playlist through it
     * from the second visit on, and those fetches never reach
     * [WebViewClient.shouldInterceptRequest]. 2026-11-24: waterera.net's
     * player (Clappr with a peer-to-peer add-on, `tryRegisterServiceWorker`,
     * sw.js) gave CNN's playlist in 3 s on its first visit, then nothing on
     * every visit after, the player itself reporting a network error. The
     * service-worker hook sees those requests; each goes to the resolve
     * whose player asked for it (by Referer/Origin), else to all running.
     */
    private fun watchServiceWorkers() {
        if (!swWatching.compareAndSet(false, true)) return
        runCatching {
            if (!androidx.webkit.WebViewFeature.isFeatureSupported(androidx.webkit.WebViewFeature.SERVICE_WORKER_BASIC_USAGE) ||
                !androidx.webkit.WebViewFeature.isFeatureSupported(
                    androidx.webkit.WebViewFeature.SERVICE_WORKER_SHOULD_INTERCEPT_REQUEST,
                )
            ) {
                Log.w(TAG, "this WebView can't watch service workers")
                return
            }
            androidx.webkit.ServiceWorkerControllerCompat.getInstance().setServiceWorkerClient(
                object : androidx.webkit.ServiceWorkerClientCompat() {
                    override fun shouldInterceptRequest(request: WebResourceRequest): WebResourceResponse? {
                        val url = request.url.toString()
                        if (".m3u8" !in url) return null
                        val from = request.requestHeaders?.entries
                            ?.firstOrNull { it.key.equals("Referer", true) || it.key.equals("Origin", true) }?.value
                        val fromHost = from?.let { runCatching { android.net.Uri.parse(it).host }.getOrNull() }
                        val running = waiting.toList()
                        val targets = running.filter { fromHost != null && it.playerHost == fromHost }.ifEmpty { running }
                        Log.i(TAG, "captured m3u8 through a service worker (from ${fromHost ?: "?"}): ${url.take(160)}")
                        // Keyed like the proxy looks it up (host:port), and
                        // with the player's page as Origin/Referer when the
                        // worker's request carries none: the stream server
                        // refuses anything else (403 to "dlhd.st", 2026-11-24).
                        val hostKey = hostOf(url)
                        val page = targets.firstOrNull()?.playerHost
                        if (hostKey.isNotEmpty() && page != null) {
                            val h = request.requestHeaders.orEmpty().toMutableMap()
                            fun missing(name: String) = h.keys.none { it.equals(name, true) }
                            if (missing("Origin")) h["Origin"] = "https://$page"
                            if (missing("Referer")) h["Referer"] = "https://$page/"
                            // The page's own name, not the worker's: the
                            // playlist address is signed for the browser name
                            // that fetched the player page (the same address
                            // 200s with it and 403s with anything else).
                            h.keys.filter { it.equals("User-Agent", true) }.forEach { h.remove(it) }
                            h["User-Agent"] = BROWSER_UA
                            CapturedHeaders.record(hostKey, h)
                        }
                        targets.forEach { it.onM3u8(url) }
                        return null
                    }
                },
            )
        }.onFailure { Log.w(TAG, "service worker watch: ${it.message}") }
    }

    private val client: okhttp3.OkHttpClient by lazy {
        okhttp3.OkHttpClient.Builder()
            .connectTimeout(8, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
            .followRedirects(true)
            .addInterceptor(HostGate)
            .build()
    }

    /** Headers the WebView's network stack may add after [WebViewClient.shouldInterceptRequest]
     *  has seen the request, or that okhttp sets itself. */
    private val NOT_COPIED = setOf("accept-encoding", "host", "content-length", "connection", "x-requested-with")

    /**
     * The player's own server, asked here instead of by the WebView, with
     * only the headers a browser sends. 2026-11-24: waterera.net answered the
     * TV's WebView with a 403 for its player page whatever we set (a current
     * Chrome name, X-Requested-With switched off), while the very same
     * request from anything else got the page: the WebView's network stack
     * marks its requests after the app has seen them. Returning the answer
     * ourselves leaves nothing to mark. Same as [SourceTeacher.passThrough].
     */
    private fun passThrough(request: WebResourceRequest, trace: Boolean, isPlayerPage: Boolean): WebResourceResponse? =
        runCatching {
            val url = request.url.toString()
            val req = okhttp3.Request.Builder().url(url).apply {
                request.requestHeaders.orEmpty().forEach { (k, v) ->
                    if (k.lowercase() !in NOT_COPIED) header(k, v)
                }
                header("User-Agent", BROWSER_UA)
                android.webkit.CookieManager.getInstance().getCookie(url)
                    ?.takeIf { it.isNotEmpty() }?.let { header("Cookie", it) }
            }.build()
            client.newCall(req).execute().use { r ->
                r.headers("Set-Cookie").forEach { android.webkit.CookieManager.getInstance().setCookie(url, it) }
                val bytes = r.body?.bytes() ?: ByteArray(0)
                if (trace || isPlayerPage) {
                    Log.i(TAG, "${if (isPlayerPage) "player page" else "trace passed through"} asked directly: " +
                        "HTTP ${r.code} ${bytes.size}B ${url.take(120)}")
                }
                val ct = r.header("Content-Type").orEmpty()
                val headers = r.headers.toMultimap()
                    .filterKeys { it.lowercase() !in setOf("content-encoding", "content-length", "transfer-encoding", "set-cookie") }
                    .mapValues { it.value.joinToString(", ") }
                WebResourceResponse(
                    ct.substringBefore(';').trim().ifEmpty { "text/html" },
                    ct.substringAfter("charset=", "utf-8").trim(),
                    r.code, r.message.ifEmpty { "OK" }, headers,
                    java.io.ByteArrayInputStream(bytes),
                )
            }
        }.onFailure {
            if (trace || isPlayerPage) Log.w(TAG, "asking ${request.url.host} directly failed: ${it.javaClass.simpleName} ${it.message}")
        }.getOrNull()

    /** Load [iframeUrl]'s player in a headless WebView, [referer] being the
     *  site's wrapper page that embeds it, and return the first `.m3u8` URL
     *  its JS requests. Null if none came, or if this host failed recently
     *  (fast-skip, no WebView spawned).
     *
     *  2026-11-24: waterera.net (the cast route: CNN and many more) never
     *  asked for a stream when opened on its own with the wrapper as Referer
     *  ("timeout: no m3u8 seen from waterera.net" on every channel), nor
     *  inside a frame of a page with the site's address; the desktop app,
     *  which loads the site's page itself, played CNN. So: the frame first
     *  (cheap: the site's page isn't fetched, and every fetch counts toward
     *  the limit that gets this address refused), then the site's page; and
     *  a player that only worked in the page goes there directly next time.
     *  [only] ("embed"/"page") and [waitMs] are for the debug hook. */
    suspend fun resolve(iframeUrl: String, referer: String, only: String? = null, waitMs: Long? = null): String? {
        val host = hostOf(iframeUrl)
        if (only == null && host.isNotEmpty() && hostRecentlyDead(host)) {
            // Fast-skip — don't spawn a WebView for a host we know is
            // dead for now. Saves ~10 s + ~10 MB per skipped host.
            return null
        }
        val now = System.currentTimeMillis()
        val pageFirst = now - (pageHosts[host] ?: 0L) < PAGE_HOST_TTL_MS
        val modes = when (only) {
            "embed" -> listOf(Mode.EMBED)
            "page" -> listOf(Mode.PAGE)
            else -> if (pageFirst) listOf(Mode.PAGE, Mode.EMBED) else listOf(Mode.EMBED, Mode.PAGE)
        }
        for (mode in modes) {
            // The page mode needs a real wrapper address to open.
            if (mode == Mode.PAGE && !referer.startsWith("http")) continue
            val wait = waitMs ?: if (mode == Mode.PAGE) PAGE_WAIT_MS else MAX_WAIT_MS
            val out = runOnce(iframeUrl, referer, mode, wait, trace = only != null)
            if (out.m3u8 != null) {
                if (mode == Mode.PAGE) pageHosts[host] = System.currentTimeMillis() else pageHosts.remove(host)
                deadHosts.remove(host)
                return out.m3u8
            }
            // The player's own address is gone: the site's page can't help.
            if (out.unreachable) return null
            Log.w(TAG, "timeout ($mode, ${wait / 1000}s): no m3u8 seen from $iframeUrl")
        }
        if (only == null && host.isNotEmpty()) deadHosts[host] = System.currentTimeMillis() + DEAD_HOST_TTL_MS
        return null
    }

    private suspend fun runOnce(iframeUrl: String, referer: String, mode: Mode, waitMs: Long, trace: Boolean): Outcome =
        suspendCancellableCoroutine { cont ->
            Handler(Looper.getMainLooper()).post {
                try {
                    runResolve(iframeUrl, referer, mode, waitMs, trace) { out ->
                        if (cont.isActive) cont.resume(out)
                    }
                } catch (e: Throwable) {
                    Log.w(TAG, "resolve crash: ${e.message}", e)
                    if (cont.isActive) cont.resume(Outcome(null))
                }
            }
        }

    @SuppressLint("SetJavaScriptEnabled")
    private fun runResolve(
        iframeUrl: String,
        referer: String,
        mode: Mode,
        waitMs: Long,
        trace: Boolean,
        onDone: (Outcome) -> Unit,
    ) {
        val main = Handler(Looper.getMainLooper())
        var finished = false
        var webView: WebView? = null
        val siteHost = hostOf(referer)
        val playerHost = android.net.Uri.parse(iframeUrl).host
        watchServiceWorkers()

        var me: Waiting? = null

        fun finish(out: Outcome) {
            if (finished) return
            finished = true
            me?.let { waiting.remove(it) }
            main.post {
                runCatching {
                    webView?.stopLoading()
                    webView?.loadUrl("about:blank")
                    webView?.destroy()
                }
                onDone(out)
            }
        }

        // Timeout safety net.
        main.postDelayed({ finish(Outcome(null)) }, waitMs)
        // A playlist fetched through the player's service worker.
        me = Waiting(playerHost) { url -> finish(Outcome(url)) }.also { waiting.add(it) }

        val wv = WebView(App.instance).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.userAgentString = BROWSER_UA
            settings.mediaPlaybackRequiresUserGesture = false
            // No "X-Requested-With: <app id>" on its requests: it marks them
            // as an app's WebView, and waterera.net answers those with a 403
            // (2026-11-24, same request with and without it).
            if (androidx.webkit.WebViewFeature.isFeatureSupported(
                    androidx.webkit.WebViewFeature.REQUESTED_WITH_HEADER_ALLOW_LIST,
                )
            ) {
                androidx.webkit.WebSettingsCompat.setRequestedWithHeaderOriginAllowList(settings, emptySet())
            }
            // Pop-ups stay closed: with multiple windows "supported" and no
            // onCreateWindow, a window.open() is dropped (unsupported, it
            // would replace this page instead).
            settings.setSupportMultipleWindows(true)
            // Also matters for CORS-preflighted fetches inside Clappr —
            // some iframe players use `credentials: 'include'` for the
            // m3u8 GET and refuse to start playback if cookies are
            // blocked wholesale.
            android.webkit.CookieManager.getInstance().setAcceptCookie(true)
        }
        webView = wv
        // Debug runs (the /api/debug/jsiframe hook) log what the page does.
        if (trace) {
            Log.i(TAG, "trace UA=$BROWSER_UA; X-Requested-With switch supported: " +
                androidx.webkit.WebViewFeature.isFeatureSupported(androidx.webkit.WebViewFeature.REQUESTED_WITH_HEADER_ALLOW_LIST))
            wv.webChromeClient = object : android.webkit.WebChromeClient() {
                override fun onConsoleMessage(m: android.webkit.ConsoleMessage?): Boolean {
                    Log.i(TAG, "trace console: ${m?.message()?.take(200)}")
                    return true
                }
            }
        }

        wv.webViewClient = object : WebViewClient() {
            override fun onReceivedHttpError(
                view: WebView?, request: WebResourceRequest?, errorResponse: WebResourceResponse?,
            ) {
                if (trace) Log.i(TAG, "trace HTTP ${errorResponse?.statusCode} ${request?.url.toString().take(140)}")
            }

            // Autoplay stays ON here, unlike the VOD resolver: these iframe
            // players only fetch the m3u8 once playback starts, and that
            // fetch is the whole point of this WebView. So mute instead —
            // the page may play, it may not be heard. Injected on start AND
            // finish because the player often attaches before load completes,
            // and re-applied on a timer since these pages swap their media
            // element as they initialise.
            override fun onPageStarted(
                view: WebView?, url: String?, favicon: android.graphics.Bitmap?,
            ) {
                super.onPageStarted(view, url, favicon)
                view?.evaluateJavascript(MUTE_JS, null)
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                view?.evaluateJavascript(MUTE_JS, null)
                // The site's page behind a bot check: never answered here.
                if (mode == Mode.PAGE) {
                    view?.evaluateJavascript("document.title") { t ->
                        if (Regex("just a moment|attention required|verify you are human|security check", RegexOption.IGNORE_CASE)
                                .containsMatchIn(t.orEmpty())
                        ) {
                            Log.w(TAG, "the site's page shows a bot check; leaving it")
                            finish(Outcome(null))
                        }
                    }
                }
            }

            // The page stays on the site it was opened on: ads try to take
            // the top page elsewhere.
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                if (request?.isForMainFrame != true) return false
                return mode != Mode.PAGE || hostOf(request.url.toString()) != siteHost
            }

            override fun shouldInterceptRequest(
                view: WebView, request: WebResourceRequest,
            ): WebResourceResponse? {
                val url = request.url.toString()
                if (trace) {
                    Log.i(TAG, "trace ${if (request.isForMainFrame) "MAIN " else ""}${request.method} ${url.take(140)}" +
                        (request.requestHeaders?.get("X-Requested-With")?.let { " XRW=$it" } ?: ""))
                }
                // The player's own server: asked here (see [passThrough]).
                if (request.method == "GET" && ".m3u8" !in url && request.url.host == playerHost) {
                    passThrough(request, trace, isPlayerPage = request.url.toString() == iframeUrl)?.let { return it }
                }
                if (".m3u8" in url) {
                    // Log EVERY header WebView planned to send. This is the
                    // ground truth for what upstream expects — we later
                    // replay these on the LiveStreamProxy's OkHttp calls
                    // when it fetches segments for the same channel. That
                    // way the proxy's per-URL Referer/Origin/Cookie mix
                    // matches whatever the JS iframe would have sent.
                    val hdrs = request.requestHeaders
                        ?.entries?.joinToString(" | ") { "${it.key}=${it.value.take(80)}" }
                        ?: "(no headers)"
                    Log.i(TAG, "captured m3u8 ($mode) from ${hostOf(iframeUrl)}: " +
                        url.take(160))
                    Log.i(TAG, "captured headers: $hdrs")
                    // Record the URL → headers mapping so LiveStreamProxy can
                    // clone the WebView's header set on subsequent OkHttp
                    // fetches for the same host. Keyed by host so segments
                    // (which sit on the same CDN host) inherit the right
                    // headers automatically.
                    val hostKey = hostOf(url)
                    if (hostKey.isNotEmpty()) {
                        val enriched = request.requestHeaders.orEmpty().toMutableMap()
                        // WebView doesn't include Cookie in requestHeaders —
                        // pull it separately from CookieManager, which IS
                        // what it will send on the actual request.
                        android.webkit.CookieManager.getInstance()
                            .getCookie("https://$hostKey/")
                            ?.takeIf { it.isNotEmpty() }
                            ?.let { enriched["Cookie"] = it }
                        CapturedHeaders.record(hostKey, enriched)
                    }
                    finish(Outcome(url))
                }
                return null  // let the request proceed normally
            }

            override fun onReceivedError(
                view: WebView?, request: WebResourceRequest?,
                error: android.webkit.WebResourceError?,
            ) {
                // Most iframe pages log errors for missing analytics
                // endpoints etc.; those don't matter. The player's own page
                // failing to load at all does: no m3u8 can follow, so stop
                // now instead of waiting out the timeout.
                if (request?.url?.toString() != iframeUrl) return
                val code = error?.errorCode ?: return
                val unreachable = code == WebViewClient.ERROR_HOST_LOOKUP || code == WebViewClient.ERROR_CONNECT ||
                    code == WebViewClient.ERROR_FAILED_SSL_HANDSHAKE || code == WebViewClient.ERROR_TIMEOUT
                if (!unreachable) return
                val host = hostOf(iframeUrl)
                Log.w(TAG, "player page unreachable ($code ${error.description}): $iframeUrl")
                if (host.isNotEmpty()) deadHosts[host] = System.currentTimeMillis() + UNREACHABLE_TTL_MS
                finish(Outcome(null, unreachable = true))
            }
        }

        if (mode == Mode.PAGE) {
            wv.loadUrl(referer)
            return
        }
        val page = "<!doctype html><html><head>" +
            "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">" +
            "<style>html,body{margin:0;height:100%;background:#000}" +
            "iframe{border:0;width:100%;height:100%}</style></head><body>" +
            "<iframe src=\"${htmlAttr(iframeUrl)}\" allow=\"autoplay; encrypted-media; fullscreen\" " +
            "allowfullscreen></iframe></body></html>"
        wv.loadDataWithBaseURL(referer, page, "text/html", "utf-8", null)
    }

    private fun hostOf(url: String): String =
        runCatching { url.substringAfter("//").substringBefore('/') }
            .getOrDefault("?")
}
