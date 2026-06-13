package com.moviebox.tv.data.live

import android.util.Log
import fi.iki.elonen.NanoHTTPD
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Local HTTP proxy that gives ExoPlayer a stable URL to the live stream
 * while we re-resolve the donis token transparently in the background.
 *
 * **Why this exists.** The catalog's signed stream URLs expire after ~60
 * minutes. The previous approach (re-resolve and swap `play.mediaUrl`)
 * forces ExoPlayer to call `setMediaSource + prepare()`, which **throws
 * away the existing buffer** — visible as a freeze/rebuffer at each
 * token refresh. From the user's seat, that's the "channel froze at 10
 * min and tried to recover" symptom; it now happens *every refresh*,
 * which over a week of continuous viewing is hundreds of blips.
 *
 * With this proxy, ExoPlayer plays `http://127.0.0.1:PORT/master/<id>`
 * once. That URL never changes. The proxy serves:
 *
 *   - **GET /master/<id>** — a hand-rewritten master playlist whose
 *     stream-inf entry points at `/inner/<id>` on the same proxy.
 *   - **GET /inner/<id>** — fetches the upstream inner playlist (per
 *     the currently-cached resolved URL), returns its body verbatim.
 *     The segment URLs inside the inner playlist are absolute upstream
 *     CDN URLs — ExoPlayer fetches those directly, no further proxying.
 *
 * The cache is refreshed in the background after [REFRESH_AT_MS] of age.
 * The refresh runs in a coroutine; while it's in flight, requests serve
 * the stale-but-still-valid cached URLs. When the refresh completes, the
 * next `/inner/<id>` fetch transparently picks up the new token — HLS's
 * media-sequence numbers carry the user across the boundary, ExoPlayer
 * doesn't know anything changed.
 *
 * **Lifetime.** Single instance held by `MainViewModel`. Started on the
 * first live channel tap, never stopped within a session. The
 * `127.0.0.1` bind + OS-assigned port means it's not reachable from
 * outside the device.
 */
class LiveStreamProxy(
    private val resolver: LiveResolver,
) {

    private val server = ProxyServer()
    private val cache = ConcurrentHashMap<String, CacheEntry>()
    private val refreshLocks = ConcurrentHashMap<String, Mutex>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    /**
     * Bind the socket. Idempotent — repeated calls are no-ops. Returns true
     * if the proxy is alive after the call, false if bind failed.
     *
     * The user reported "failed to connect to localhost" on the mobile
     * build, which means ExoPlayer couldn't reach 127.0.0.1:PORT.
     * Previously [start] would silently swallow a bind IOException and
     * leave [server.isAlive] = false; callers would then hit the
     * `check(server.isAlive)` in [proxyUrl] and crash. Now bind failure
     * is logged + reported to telemetry, and [proxyUrl] returns null
     * instead of throwing so callers can fall through to the direct
     * stream URL.
     */
    fun start(): Boolean {
        if (server.isAlive) return true
        return runCatching {
            server.start(NanoHTTPD.SOCKET_READ_TIMEOUT, /* daemon = */ true)
            // NanoHTTPD.start() can return before the accept thread is
            // ready on slow devices; spin briefly until listeningPort is
            // valid OR a short deadline expires.
            val deadline = System.currentTimeMillis() + 1_500
            while (System.currentTimeMillis() < deadline &&
                (!server.isAlive || server.listeningPort <= 0)
            ) {
                runCatching { Thread.sleep(20) }
            }
            if (!server.isAlive || server.listeningPort <= 0) {
                Log.e(TAG, "Proxy bind succeeded but socket isn't usable")
                com.moviebox.tv.debug.Telemetry.note(
                    com.moviebox.tv.debug.Telemetry.Severity.ERROR,
                    "Live proxy bind unusable",
                )
                return@runCatching false
            }
            Log.i(TAG, "Proxy listening on 127.0.0.1:${server.listeningPort}")
            true
        }.getOrElse { e ->
            Log.e(TAG, "Proxy bind failed: ${e.message}", e)
            com.moviebox.tv.debug.Telemetry.note(
                com.moviebox.tv.debug.Telemetry.Severity.ERROR,
                "Live proxy bind failed: ${e.message?.take(120)}",
            )
            false
        }
    }

    /** Stop the socket. Safe to call repeatedly. */
    fun stop() {
        if (server.isAlive) server.stop()
    }

    /**
     * Stable URL ExoPlayer should play, or null if the proxy isn't running.
     * Caller is expected to handle null by falling back to the direct
     * stream URL (which works for ~60 min on a fresh resolve).
     */
    fun proxyUrl(channelId: String): String? {
        if (!server.isAlive || server.listeningPort <= 0) return null
        return "http://127.0.0.1:${server.listeningPort}/master/$channelId"
    }

    // ---- internals ----

    private inner class ProxyServer : NanoHTTPD("127.0.0.1", /* port = */ 0) {
        override fun serve(session: IHTTPSession): Response {
            val uri = session.uri ?: return notFound()
            return when {
                uri.startsWith("/master/") -> handleMaster(uri.removePrefix("/master/"))
                uri.startsWith("/inner/")  -> handleInner(uri.removePrefix("/inner/"))
                else -> notFound()
            }
        }

        private fun notFound() = newFixedLengthResponse(
            Response.Status.NOT_FOUND, "text/plain", "not found",
        )
    }

    /** Serve a master playlist pointing at our own /inner/<id> endpoint. */
    private fun handleMaster(channelId: String): NanoHTTPD.Response {
        // Make sure we've at least resolved once, so the upstream master body
        // is available to learn the codecs/resolution headers from. If we
        // can't resolve right now, return 503 and let ExoPlayer retry.
        val entry = ensureCached(channelId)
            ?: return NanoHTTPD.newFixedLengthResponse(
                NanoHTTPD.Response.Status.SERVICE_UNAVAILABLE,
                "text/plain", "resolver unavailable",
            )

        // Use the codecs/bandwidth line from the real master if we have it,
        // otherwise a sensible 720p60 H.264 default. ExoPlayer just needs
        // *some* STREAM-INF entry to pick the variant.
        val streamInf = entry.streamInfLine
            ?: """#EXT-X-STREAM-INF:BANDWIDTH=9000000,RESOLUTION=1280x720,""" +
                """FRAME-RATE=59.940,CODECS="avc1.640020,mp4a.40.2""""
        val innerUrl = "http://127.0.0.1:${server.listeningPort}/inner/$channelId"
        val body = buildString {
            appendLine("#EXTM3U")
            appendLine(streamInf)
            appendLine(innerUrl)
        }
        return NanoHTTPD.newFixedLengthResponse(
            NanoHTTPD.Response.Status.OK,
            "application/vnd.apple.mpegurl",
            body,
        )
    }

    /**
     * Serve the upstream inner playlist body, re-resolving lazily if our
     * cache is stale. We always fetch the upstream playlist live (never
     * cache its body) because the segment list is rolling.
     */
    private fun handleInner(channelId: String): NanoHTTPD.Response {
        val entry = ensureCached(channelId)
            ?: return NanoHTTPD.newFixedLengthResponse(
                NanoHTTPD.Response.Status.SERVICE_UNAVAILABLE,
                "text/plain", "resolver unavailable",
            )
        // Background refresh if we're past the soft TTL — keeps the next
        // fetch landing on a still-fresh token.
        if (System.currentTimeMillis() - entry.resolvedAtMs > REFRESH_AT_MS) {
            scope.launch { refreshCache(channelId) }
        }

        // Try the cached upstream URL up to MAX_INNER_RETRIES times before
        // giving up. A single failure used to bubble through as HTTP 500 to
        // ExoPlayer, which counted that against its (also-thin) retry budget
        // and threw a Source error after a couple of attempts — visible to
        // the user as "Reconnecting…" → freeze → bounce. Retrying here
        // catches transient Wi-Fi blips before they ever reach ExoPlayer.
        var lastResponseCode: Int? = null
        for (attempt in 1..MAX_INNER_RETRIES) {
            val r = runCatching { fetchUpstreamInner(entry.innerUrl) }
            val resp = r.getOrNull()
            if (resp != null && resp.first.isSuccessful) {
                val body = resp.second
                return NanoHTTPD.newFixedLengthResponse(
                    NanoHTTPD.Response.Status.OK,
                    "application/vnd.apple.mpegurl",
                    body,
                )
            }
            if (resp != null) {
                lastResponseCode = resp.first.code
                com.moviebox.tv.debug.Telemetry.onHttpError(resp.first.code, entry.innerUrl)
                // If upstream told us the token died, force a fresh resolve
                // immediately and retry with the new URL.
                if (resp.first.code in listOf(401, 403, 410)) {
                    val refreshed = runBlocking { refreshCache(channelId) }
                    if (refreshed != null) {
                        runCatching {
                            return fetchUpstreamInner(refreshed.innerUrl).let {
                                NanoHTTPD.newFixedLengthResponse(
                                    NanoHTTPD.Response.Status.OK,
                                    "application/vnd.apple.mpegurl", it.second,
                                )
                            }
                        }
                    }
                }
            } else {
                // Throw (e.g. SocketTimeout). Log and keep trying.
                Log.w(TAG, "inner attempt $attempt for $channelId: " +
                    "${r.exceptionOrNull()?.message}")
            }
            if (attempt < MAX_INNER_RETRIES) {
                // Exponential backoff between attempts: 200ms, 400ms, 800ms.
                runCatching { Thread.sleep((200L shl (attempt - 1)).coerceAtMost(1_000)) }
            }
        }
        // Out of retries. Tell ExoPlayer it was upstream's fault so it doesn't
        // count this against its own retry budget — 503 is "retry me" rather
        // than "give up".
        return NanoHTTPD.newFixedLengthResponse(
            NanoHTTPD.Response.Status.SERVICE_UNAVAILABLE,
            "text/plain", "upstream busy (${lastResponseCode ?: "timeout"})",
        )
    }

    private fun fetchUpstreamInner(url: String): Pair<okhttp3.Response, String> {
        val req = Request.Builder().url(url).header("User-Agent", CHROME_UA).build()
        return httpClient.newCall(req).execute().use { resp ->
            val body = resp.body?.string() ?: ""
            // Note: we have to capture body inside the .use block because
            // .body is closed when use exits. Returning a closed Response is
            // safe — we only read .code / .isSuccessful afterwards.
            resp to body
        }
    }


    /** Returns a cached entry, resolving synchronously if necessary. */
    private fun ensureCached(channelId: String): CacheEntry? {
        cache[channelId]?.let { return it }
        return runBlocking { refreshCache(channelId) }
    }

    /** Re-resolve donis for this channel; populate the cache. Serialized
     *  per-channel via a Mutex so two concurrent refreshes don't double-fire
     *  donis. */
    private suspend fun refreshCache(channelId: String): CacheEntry? {
        val lock = refreshLocks.getOrPut(channelId) { Mutex() }
        return lock.withLock {
            val current = cache[channelId]
            if (current != null &&
                System.currentTimeMillis() - current.resolvedAtMs < MIN_RESOLVE_INTERVAL_MS
            ) {
                // Another thread refreshed inside the lock; reuse that.
                return@withLock current
            }
            val masterUrl = resolver.resolveStream(channelId) ?: return@withLock null
            // Fetch master once to learn the inner URL + STREAM-INF line.
            val (innerUrl, streamInf) = parseMaster(masterUrl) ?: return@withLock null
            val entry = CacheEntry(
                masterUrl = masterUrl,
                innerUrl = innerUrl,
                streamInfLine = streamInf,
                resolvedAtMs = System.currentTimeMillis(),
            )
            cache[channelId] = entry
            entry
        }
    }

    private fun parseMaster(masterUrl: String): Pair<String, String?>? {
        val req = Request.Builder().url(masterUrl)
            .header("User-Agent", CHROME_UA)
            .build()
        return runCatching {
            httpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return null
                val body = resp.body?.string() ?: return null
                var streamInf: String? = null
                var rel: String? = null
                for (rawLine in body.lineSequence()) {
                    val line = rawLine.trim()
                    if (line.startsWith("#EXT-X-STREAM-INF")) streamInf = line
                    else if (line.isNotEmpty() && !line.startsWith("#")) {
                        rel = line; break
                    }
                }
                if (rel == null) return null
                val absolute = if (rel.startsWith("http")) rel
                else {
                    val base = masterUrl.substringBeforeLast('/')
                    "$base/$rel"
                }
                absolute to streamInf
            }
        }.getOrNull()
    }

    private data class CacheEntry(
        val masterUrl: String,
        val innerUrl: String,
        val streamInfLine: String?,
        val resolvedAtMs: Long,
    )

    companion object {
        private const val TAG = "LiveStreamProxy"
        /** Background-refresh the cached resolution after this much time.
         *  Donis tokens live ~60 min; refreshing at 40 min keeps a safe
         *  20-minute window of validity on every cache update. */
        private const val REFRESH_AT_MS: Long = 40L * 60 * 1000
        /** Minimum spacing between consecutive resolves for the same
         *  channel. Stops us from hammering donis if multiple requests
         *  race on a stale cache. */
        private const val MIN_RESOLVE_INTERVAL_MS: Long = 5_000
        /** Inner-playlist fetch retries before we return SERVICE_UNAVAILABLE.
         *  At 200/400/800 ms backoff the worst case takes ~1.4 s and we've
         *  given the upstream three chances. Stops a single Wi-Fi blip from
         *  cascading into a Source error on ExoPlayer's side. */
        private const val MAX_INNER_RETRIES: Int = 3
        private const val CHROME_UA =
            "Mozilla/5.0 (Linux; Android 12) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"
    }
}
