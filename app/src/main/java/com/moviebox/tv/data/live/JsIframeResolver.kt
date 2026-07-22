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

    // 10 s (bumped from 6 s): welovetocare, cdnlivetv and ksohls all
    // load ~15-40 external resources (analytics, ad SDK, fingerprinting)
    // before Clappr fires the m3u8 request — 6 s was catching only the
    // fastest paths, timing out on the slower iframe hosts even though
    // they'd have worked given another 2-3 seconds. Tap-to-picture on
    // channels that only have JS players now caps at 10 s instead of
    // failing entirely. Channels with a working atob path are unaffected
    // (they short-circuit before JsIframeResolver is ever called).
    private const val MAX_WAIT_MS = 10_000L

    private const val BROWSER_UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"

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
        java.util.concurrent.ConcurrentHashMap<String, Long>()

    private const val DEAD_HOST_TTL_MS = 120_000L  // 2 min, then retry

    private fun hostRecentlyDead(host: String): Boolean {
        val at = deadHosts[host] ?: return false
        if (System.currentTimeMillis() - at > DEAD_HOST_TTL_MS) {
            deadHosts.remove(host)
            return false
        }
        return true
    }

    /** Load [iframeUrl] in a headless WebView with the given [referer]
     *  and return the first `.m3u8` URL its JS requests. Null if nothing
     *  m3u8-like was requested inside [MAX_WAIT_MS], or if this host timed
     *  out recently (fast-skip, no WebView spawned). */
    suspend fun resolve(iframeUrl: String, referer: String): String? {
        val host = hostOf(iframeUrl)
        if (host.isNotEmpty() && hostRecentlyDead(host)) {
            // Fast-skip — don't spawn a WebView for a host we know is
            // dead for now. Saves ~10 s + ~10 MB per skipped host.
            return null
        }
        return suspendCancellableCoroutine { cont ->
            Handler(Looper.getMainLooper()).post {
                try {
                    runResolve(iframeUrl, referer) { m3u8 ->
                        if (cont.isActive) cont.resume(m3u8)
                    }
                } catch (e: Throwable) {
                    Log.w(TAG, "resolve crash: ${e.message}", e)
                    if (cont.isActive) cont.resume(null)
                }
            }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun runResolve(
        iframeUrl: String,
        referer: String,
        onDone: (String?) -> Unit,
    ) {
        val main = Handler(Looper.getMainLooper())
        var finished = false
        var webView: WebView? = null

        fun finish(m3u8: String?) {
            if (finished) return
            finished = true
            main.post {
                runCatching {
                    webView?.stopLoading()
                    webView?.loadUrl("about:blank")
                    webView?.destroy()
                }
                onDone(m3u8)
            }
        }

        // Timeout safety net. On timeout, mark this host dead so the next
        // resolve cycle fast-skips it instead of spawning another WebView.
        main.postDelayed({
            if (!finished) {
                Log.w(TAG, "timeout: no m3u8 seen from $iframeUrl")
                val host = hostOf(iframeUrl)
                if (host.isNotEmpty()) deadHosts[host] = System.currentTimeMillis()
            }
            finish(null)
        }, MAX_WAIT_MS)

        val wv = WebView(App.instance).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.userAgentString = BROWSER_UA
            settings.mediaPlaybackRequiresUserGesture = false
            // Also matters for CORS-preflighted fetches inside Clappr —
            // some iframe players use `credentials: 'include'` for the
            // m3u8 GET and refuse to start playback if cookies are
            // blocked wholesale.
            android.webkit.CookieManager.getInstance().setAcceptCookie(true)
        }
        webView = wv

        wv.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(
                view: WebView, request: WebResourceRequest,
            ): WebResourceResponse? {
                val url = request.url.toString()
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
                    Log.i(TAG, "captured m3u8 from ${hostOf(iframeUrl)}: " +
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
                    // This iframe host resolved — clear any stale
                    // dead-mark so we keep using it.
                    deadHosts.remove(hostOf(iframeUrl))
                    finish(url)
                }
                return null  // let the request proceed normally
            }

            override fun onReceivedError(
                view: WebView?, request: WebResourceRequest?,
                error: android.webkit.WebResourceError?,
            ) {
                // Ignore — most iframe pages log errors for missing
                // analytics endpoints etc. We only care whether an
                // m3u8 gets requested before the timeout.
            }
        }

        // Pass Referer as a load-time header so the iframe host's WAF
        // (welovetocare, cdnlivetv, etc.) sees the same referer chain
        // it would if the iframe were embedded in dlhd.st normally.
        val headers = mapOf("Referer" to referer)
        wv.loadUrl(iframeUrl, headers)
    }

    private fun hostOf(url: String): String =
        runCatching { url.substringAfter("//").substringBefore('/') }
            .getOrDefault("?")
}
