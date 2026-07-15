package com.moviebox.tv.net

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
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import kotlin.coroutines.resume

/**
 * Headless WebView resolver for movie playback.
 *
 * The themoviebox.org proxy refuses to return streams for any title the
 * client hasn't reached via a full browser session — a raw HTTP request to
 * the subject/play endpoint gets `hasResource:false` regardless of cookies or
 * tokens. The unlock is the page's Nuxt JS executing in a real browser
 * engine, which mints per-(IP, subjectId) server-side session state, and
 * THEN the proxy's play call returns the signed stream URLs.
 *
 * Replicating that with raw HTTP doesn't work. So we use the platform's own
 * WebView as an embedded headless browser: load the movie page off-screen,
 * intercept the `/wefeed-h5api-bff/subject/play` response, parse its streams,
 * and hand the highest-quality URL back via a suspending function. The
 * WebView is torn down as soon as we have a hit (or the timeout fires).
 */
object H5PlayResolver {

    private const val TAG = "H5Resolver"
    // TV-show episodes can take ~12-18s to trigger the play call (the SPA
    // resolves season/episode metadata first); movies typically fire it in
    // <4s. 25s covers both with headroom.
    private const val MAX_WAIT_MS = 25_000L
    private const val BROWSER_UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/149.0.0.0 Safari/537.36"

    /** Resolve playable streams for [subjectId] by driving a headless WebView
     *  through themoviebox.org's movie page. Returns the parsed streams from
     *  the first `subject/play` response that carries any. Empty list if the
     *  page never produced a hit before [MAX_WAIT_MS]. */
    suspend fun resolve(
        subjectId: String,
        detailPath: String,
        season: Int = 0,
        episode: Int = 0,
    ): List<H5Api.PlayStream> = suspendCancellableCoroutine { cont ->
        val main = Handler(Looper.getMainLooper())
        main.post {
            try {
                runResolve(subjectId, detailPath, season, episode) { result ->
                    if (cont.isActive) cont.resume(result)
                }
            } catch (e: Throwable) {
                Log.w(TAG, "resolve crash: ${e.message}", e)
                if (cont.isActive) cont.resume(emptyList())
            }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun runResolve(
        subjectId: String,
        detailPath: String,
        season: Int,
        episode: Int,
        onDone: (List<H5Api.PlayStream>) -> Unit,
    ) {
        val ctx = App.instance
        var finished = false
        var webView: WebView? = null
        val main = Handler(Looper.getMainLooper())

        fun finish(streams: List<H5Api.PlayStream>) {
            if (finished) return
            finished = true
            main.post {
                // Sync the WebView's cookies into OkHttp BEFORE destroying —
                // this is what makes the next direct play call (no WebView)
                // work. Android's CookieManager is a separate store from our
                // OkHttp cookie jar; without the bridge, every play re-runs
                // the WebView (slow, defeats the cache).
                runCatching {
                    val cm = android.webkit.CookieManager.getInstance()
                    // Include both moviebox.ph (the new main domain) and
                    // themoviebox.org (legacy fallback) so any cookies the
                    // WebView picked up from either origin get pushed into
                    // OkHttp's jar for subsequent direct-play calls.
                    for (host in listOf(
                        "moviebox.ph", "themoviebox.org", "h5-api.aoneroom.com",
                    )) {
                        val raw = cm.getCookie("https://$host/") ?: continue
                        H5Client.pushCookies(host, raw)
                    }
                }
                runCatching {
                    webView?.stopLoading()
                    webView?.loadUrl("about:blank")
                    webView?.destroy()
                }
                onDone(streams)
            }
        }

        // Timeout safety net.
        main.postDelayed({ finish(emptyList()) }, MAX_WAIT_MS)

        val ok = OkHttpClient.Builder()
            .followRedirects(true)
            .build()

        val wv = WebView(ctx).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.userAgentString = BROWSER_UA
            settings.mediaPlaybackRequiresUserGesture = false
            // No layout — never attached. We only need its JS engine + cookie jar.
        }
        webView = wv

        wv.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(
                view: WebView, request: WebResourceRequest,
            ): WebResourceResponse? {
                val url = request.url.toString()
                // Watch only for the play call themoviebox.org's JS makes after
                // page render. Refetch it on our own OkHttp so the body is ours
                // to read (WebView doesn't expose response bodies via this
                // hook — we re-issue the same GET, which the proxy answers
                // identically while the session state is fresh).
                // Match the play call regardless of which mirror the
                // WebView happens to be on (moviebox.ph is canonical but
                // themoviebox.org still works as a legacy fallback).
                if ("/wefeed-h5api-bff/subject/play" in url && (
                        "moviebox.ph" in url || "themoviebox.org" in url
                    )) {
                    Thread {
                        runCatching {
                            val refetch = Request.Builder()
                                .url(url)
                                .header("Accept", "application/json")
                                .header("User-Agent", BROWSER_UA)
                                .header("Referer", PAGE_URL(detailPath, subjectId))
                                .header("X-Client-Info", """{"timezone":"Africa/Nairobi"}""")
                                .header("X-Source", "")
                                .get().build()
                            ok.newCall(refetch).execute().use { r ->
                                val raw = r.body?.string().orEmpty()
                                val data = JSONObject(raw).optJSONObject("data")
                                val streams = parseStreams(data)
                                Log.i(TAG, "play intercept $subjectId -> ${streams.size} streams")
                                // Dump the raw streams array once so we can
                                // see whether the API is offering multiple
                                // resolutions (720/1080/2160) or a single URL
                                // — the current parseStreams only reads the
                                // top-level url per stream, which loses lower
                                // resolutions that would be a viable fallback
                                // when the top-quality file has a
                                // channelCount=0 track the TV can't decode.
                                Log.i(TAG, "raw streams JSON: " +
                                    (data?.optJSONArray("streams")?.toString()?.take(800)
                                        ?: "(no streams array)"))
                                if (streams.isNotEmpty()) finish(streams)
                            }
                        }.onFailure { Log.w(TAG, "refetch failed: ${it.message}") }
                    }.start()
                }
                return super.shouldInterceptRequest(view, request)
            }

            override fun onReceivedError(
                view: WebView?, request: WebResourceRequest?,
                error: android.webkit.WebResourceError?,
            ) {
                Log.w(TAG, "page error: ${error?.errorCode} ${error?.description}")
                super.onReceivedError(view, request, error)
            }
        }

        val pageUrl = PAGE_URL(detailPath, subjectId, season, episode)
        Log.i(TAG, "loading $pageUrl")
        wv.loadUrl(pageUrl)

        // Nudge the page after a few seconds: some titles (esp. TV episodes)
        // wait for a user gesture before firing subject/play. Programmatically
        // click likely play targets + force any <video> to play().
        main.postDelayed({
            if (finished) return@postDelayed
            runCatching {
                wv.evaluateJavascript(
                    """
                    (function(){
                      try {
                        document.querySelectorAll('video').forEach(v=>{try{v.muted=true; v.play()}catch(e){}});
                        var sels = ['.video-play','.play-btn','[class*="play-icon"]','[class*="poster"]','button[aria-label*="play" i]'];
                        for (var s of sels) {
                          var els = document.querySelectorAll(s);
                          for (var e of els) { try { e.click() } catch(_){} }
                        }
                      } catch(e){}
                    })();
                    """.trimIndent(),
                    null,
                )
            }
        }, 5_000)
    }

    @Suppress("FunctionName")
    private fun PAGE_URL(
        detailPath: String, subjectId: String, season: Int = 0, episode: Int = 0,
    ): String {
        // moviebox.ph is the canonical main domain (was themoviebox.org until
        // 2026-07-15 when the user reported themoviebox.org's play proxy was
        // handing back a 4K master with broken audio for Scary Movie while
        // moviebox.ph served the correct file with working audio). BOTH
        // domains serve movies and series under /movies/ with the same URL
        // shape (type=/movie/detail, detailSe/detailEp for episode selection);
        // only the file-mirror routing differs behind the /wefeed-h5api-bff
        // play proxy. See H5Client.PROXY_BASE for the full rationale.
        val seParam = "&detailSe=${if (season > 0) season else ""}" +
            "&detailEp=${if (episode > 0) episode else ""}"
        return "${H5Client.PROXY_BASE}/movies/$detailPath?id=$subjectId&type=/movie/detail$seParam&lang=en"
    }

    private fun parseStreams(data: JSONObject?): List<H5Api.PlayStream> {
        if (data == null) return emptyList()
        val arr = data.optJSONArray("streams") ?: return emptyList()
        val out = mutableListOf<H5Api.PlayStream>()
        for (i in 0 until arr.length()) {
            val s = arr.getJSONObject(i)
            val u = s.optString("url").takeIf { it.isNotBlank() } ?: continue
            val res = s.optString("resolutions").filter { it.isDigit() }.toIntOrNull() ?: 0
            out.add(
                H5Api.PlayStream(
                    url = u,
                    resolution = res,
                    format = s.optString("format"),
                    id = s.optString("id"),
                    durationSec = s.optInt("duration"),
                )
            )
        }
        return out
    }
}
