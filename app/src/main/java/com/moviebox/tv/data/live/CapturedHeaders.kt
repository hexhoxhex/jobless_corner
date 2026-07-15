package com.moviebox.tv.data.live

import java.util.concurrent.ConcurrentHashMap

/**
 * Cross-component memory of the exact request-header set a real browser
 * (via [JsIframeResolver]'s headless WebView) sent when it fetched an
 * m3u8 URL that Cloudflare-fronted CDNs then answered with 200.
 *
 * Motivation: our LiveStreamProxy's OkHttp calls consistently 403 on
 * xameleon.phantemlis.top and related CDNs even when Origin, Referer,
 * User-Agent, Sec-Ch-Ua, connection-pool-off, etc. all match what a
 * "typical Chrome tab" would send. What actually works is the SPECIFIC
 * header bundle Clappr/hls.js emits from inside the iframe's origin —
 * which depends on the iframe host, sometimes carries cookies set by
 * that iframe's own JS, and varies across (channel × player-path)
 * combinations enough that hardcoding a generic set fails.
 *
 * By recording the WebView-observed headers keyed by upstream host, the
 * proxy's next outbound request to that host can replay them verbatim,
 * getting the same 200 that the JS-captured m3u8 URL originally returned.
 * Records TTL after [ENTRY_TTL_MS] so a channel that switched iframes
 * (dlhd rotating the resolver) doesn't inherit dead cookies forever.
 *
 * Thread-safe. Values are shallow-copied on read so callers can mutate
 * without racing.
 */
object CapturedHeaders {

    private data class Entry(
        val headers: Map<String, String>,
        val capturedAtMs: Long,
    )

    private val byHost = ConcurrentHashMap<String, Entry>()

    /** Save the browser-observed header set for outbound requests to
     *  [host]. Filters out headers OkHttp will set itself (Content-Length,
     *  Accept-Encoding, Host) or that don't survive between contexts
     *  (Sec-Fetch-Site because the fetch context differs). */
    fun record(host: String, headers: Map<String, String>) {
        val kept = headers.filterKeys { name ->
            val n = name.lowercase()
            n !in FORBIDDEN
        }
        if (kept.isNotEmpty()) {
            byHost[host.lowercase()] = Entry(kept, System.currentTimeMillis())
        }
    }

    /** Retrieve the last-good header set for outbound requests to [host].
     *  Returns null if we've never seen this host or the record expired. */
    fun headersFor(host: String): Map<String, String>? {
        val key = host.lowercase()
        val entry = byHost[key] ?: return null
        if (System.currentTimeMillis() - entry.capturedAtMs > ENTRY_TTL_MS) {
            byHost.remove(key)
            return null
        }
        return entry.headers
    }

    /** OkHttp will set these itself (or refuses to let us override them).
     *  Filter them out of the WebView-captured set so we don't collide. */
    private val FORBIDDEN = setOf(
        "content-length", "host", "connection",
        // Accept-Encoding: OkHttp defaults to gzip; overriding to
        // "identity" (what WebView often sends for a video fetch) can
        // regress unrelated fetches. Let OkHttp own this.
        "accept-encoding",
    )

    /** 15 min. Signed URLs from these CDNs live ~1 h, but per-session
     *  bot-management state can rotate faster. 15 min balances "fresh
     *  enough to still be trusted" with "long enough that the WebView
     *  cost pays off across a whole channel-watching session". */
    private const val ENTRY_TTL_MS: Long = 15L * 60 * 1000
}
