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

    /**
     * Per-channel inner-fetch state across multiple HTTP requests. Lets us:
     *   - Avoid yanking a sibling on the first transient 5xx (count
     *     consecutive failures before deciding the sibling is dead).
     *   - Mark "the next /inner/ response needs an EXT-X-DISCONTINUITY"
     *     after a rotation, so ExoPlayer treats the splice as a clean
     *     boundary instead of a confusing media-sequence time-warp.
     */
    private data class InnerState(
        var consecutive5xx: Int = 0,
        var rotationPending: Boolean = false,
        /** URL we successfully served from on the previous inner request.
         *  If it differs from the URL we serve from now, we've crossed an
         *  upstream boundary (background refresh, rotation, etc.) and need
         *  to inject an EXT-X-DISCONTINUITY. */
        var lastServedFrom: String? = null,
        /** Most recent successful inner-playlist body and when it was
         *  fetched. Lets us absorb a brief upstream 5xx by returning the
         *  same body again so ExoPlayer keeps consuming the segments it
         *  already saw (they're hosted on a separate CDN node and usually
         *  stay alive across nginx hiccups). Without this, every transient
         *  upstream blip surfaces as a 503 → ExoPlayer pauses → user sees
         *  "stop and play". */
        var lastGoodBody: String? = null,
        var lastGoodAtMs: Long = 0L,
    )
    private val innerState = ConcurrentHashMap<String, InnerState>()

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

        val state = innerState.getOrPut(channelId) { InnerState() }
        val tStart = System.currentTimeMillis()

        // Try the cached upstream URL up to MAX_INNER_RETRIES times. The
        // rotation policy is intentionally conservative now:
        //   - 4xx auth codes (401/403/410) mean the signed token is dead —
        //     rotate immediately, retrying the same URL is pointless.
        //   - 5xx codes mean origin/CDN trouble. We retry the SAME upstream
        //     a few times across THIS request, and only commit to a sibling
        //     rotation after [ROTATE_AFTER_5XX_RUN] **consecutive** request
        //     boundaries have failed. The previous policy rotated on the
        //     first 5xx, which during normal phantemlis hiccups would yank
        //     ExoPlayer onto a different sibling whose playlist has its OWN
        //     EXT-X-MEDIA-SEQUENCE numbering — surfaced as "the stream
        //     plays then jumps backward and repeats some content," which
        //     is what the user has been reporting.
        var lastResponseCode: Int? = null
        var lastWasAuthDead = false
        for (attempt in 1..MAX_INNER_RETRIES) {
            val r = runCatching { fetchUpstreamInner(entry.innerUrl) }
            val resp = r.getOrNull()
            if (resp != null && resp.first.isSuccessful) {
                state.consecutive5xx = 0
                val sourceChanged = state.lastServedFrom != null &&
                    state.lastServedFrom != entry.innerUrl
                val needsDisc = state.rotationPending || sourceChanged
                val body = if (needsDisc) injectDiscontinuity(resp.second) else resp.second
                state.rotationPending = false
                state.lastServedFrom = entry.innerUrl
                state.lastGoodBody = body
                state.lastGoodAtMs = System.currentTimeMillis()
                val dt = System.currentTimeMillis() - tStart
                Log.i(DIAG, "PROXY ch=$channelId inner OK attempt=$attempt " +
                    "dt=${dt}ms disc=$needsDisc host=${hostOf(entry.innerUrl)} " +
                    "mediaSeq=${extractMediaSeq(resp.second)}")
                return NanoHTTPD.newFixedLengthResponse(
                    NanoHTTPD.Response.Status.OK,
                    "application/vnd.apple.mpegurl",
                    body,
                )
            }
            if (resp != null) {
                lastResponseCode = resp.first.code
                com.moviebox.tv.debug.Telemetry.onHttpError(resp.first.code, entry.innerUrl)
                lastWasAuthDead = resp.first.code in listOf(401, 403, 410)
                if (lastWasAuthDead) break  // token death — go rotate now
            } else {
                Log.w(TAG, "inner attempt $attempt for $channelId: " +
                    "${r.exceptionOrNull()?.message}")
            }
            if (attempt < MAX_INNER_RETRIES) {
                runCatching { Thread.sleep((200L shl (attempt - 1)).coerceAtMost(1_000)) }
            }
        }

        // Decide whether this request justifies a sibling rotation.
        val transientServerErr = lastResponseCode != null &&
            (lastResponseCode in 500..504 || lastResponseCode in 520..524)
        if (transientServerErr) {
            state.consecutive5xx += 1
        }
        val shouldRotate = lastWasAuthDead ||
            (transientServerErr && state.consecutive5xx >= ROTATE_AFTER_5XX_RUN)

        if (shouldRotate) {
            Log.i(DIAG, "PROXY ch=$channelId ROTATE code=$lastResponseCode " +
                "authDead=$lastWasAuthDead 5xxRun=${state.consecutive5xx} " +
                "fromHost=${hostOf(entry.innerUrl)}")
            cache.remove(channelId)
            val refreshed = runBlocking { refreshCache(channelId) }
            if (refreshed != null && refreshed.innerUrl != entry.innerUrl) {
                runCatching { fetchUpstreamInner(refreshed.innerUrl) }
                    .getOrNull()
                    ?.takeIf { it.first.isSuccessful }
                    ?.let { ok ->
                        state.consecutive5xx = 0
                        state.rotationPending = false
                        state.lastServedFrom = refreshed.innerUrl
                        state.lastGoodBody = ok.second
                        state.lastGoodAtMs = System.currentTimeMillis()
                        val body = injectDiscontinuity(ok.second)
                        Log.i(DIAG, "PROXY ch=$channelId ROTATE OK " +
                            "newHost=${hostOf(refreshed.innerUrl)} " +
                            "mediaSeq=${extractMediaSeq(ok.second)}")
                        return NanoHTTPD.newFixedLengthResponse(
                            NanoHTTPD.Response.Status.OK,
                            "application/vnd.apple.mpegurl", body,
                        )
                    }
                // Resolver swap happened but the new URL also failed right
                // now. Mark the rotation as pending so the *next* successful
                // /inner/ response carries the discontinuity tag.
                state.rotationPending = true
            }
        }

        // Last-good cache: if we have a successful body that's still
        // fresh, return it. ExoPlayer keeps reading the segments inside
        // (which usually still work — they live on a separate CDN node
        // than the playlist endpoint that just 5xx'd). This is the most
        // impactful smoothing knob: hides upstream nginx hiccups from
        // the user entirely instead of surfacing them as "stop and play".
        val cached = state.lastGoodBody
        val cacheAge = System.currentTimeMillis() - state.lastGoodAtMs
        if (cached != null && cacheAge < LAST_GOOD_TTL_MS) {
            Log.i(DIAG, "PROXY ch=$channelId CACHE_HIT age=${cacheAge}ms " +
                "code=$lastResponseCode")
            return NanoHTTPD.newFixedLengthResponse(
                NanoHTTPD.Response.Status.OK,
                "application/vnd.apple.mpegurl", cached,
            )
        }
        Log.w(DIAG, "PROXY ch=$channelId GIVE_UP code=$lastResponseCode " +
            "cacheAge=${cacheAge}ms (returning 503)")
        // Out of retries AND no fresh cache. 503 = "retry me" rather than
        // "give up" so ExoPlayer doesn't burn its own retry budget on
        // this one.
        return NanoHTTPD.newFixedLengthResponse(
            NanoHTTPD.Response.Status.SERVICE_UNAVAILABLE,
            "text/plain", "upstream busy (${lastResponseCode ?: "timeout"})",
        )
    }

    /**
     * Insert an `#EXT-X-DISCONTINUITY` tag before the first segment in the
     * playlist body. Tells ExoPlayer "the stream you're about to read is
     * NOT the same continuous timeline as what came before" — required by
     * HLS spec when stitching across upstreams that may have unrelated
     * media-sequence numbering. Without this, the player either time-warps
     * (segments at a wildly different sequence number are treated as
     * gap-filling and re-rendered) or throws a behind-live-window error
     * and snaps back, which is what the user described as "repeats some
     * parts when reconnecting".
     */
    private fun hostOf(url: String): String =
        runCatching { url.substringAfter("//").substringBefore('/') }
            .getOrDefault("?")

    private fun extractMediaSeq(body: String): String {
        for (line in body.lineSequence()) {
            val l = line.trim()
            if (l.startsWith("#EXT-X-MEDIA-SEQUENCE:")) {
                return l.substringAfter(':')
            }
        }
        return "?"
    }

    private fun injectDiscontinuity(body: String): String {
        val lines = body.lines().toMutableList()
        // Find the first non-comment, non-blank line — that's the first
        // segment URI. Insert a discontinuity tag just above it.
        var firstSegIdx = -1
        for ((i, raw) in lines.withIndex()) {
            val line = raw.trim()
            if (line.isNotEmpty() && !line.startsWith("#")) {
                firstSegIdx = i; break
            }
        }
        if (firstSegIdx < 0) return body
        // The EXTINF for that segment is on the line directly above (or a
        // few lines above if EXT-X-KEY/EXT-X-PROGRAM-DATE-TIME tags exist).
        // Walk back to find the EXTINF and put EXT-X-DISCONTINUITY above it.
        var insertAt = firstSegIdx
        while (insertAt > 0 && !lines[insertAt - 1].trim().startsWith("#EXTINF")) {
            insertAt -= 1
            if (firstSegIdx - insertAt > 5) break
        }
        if (insertAt > 0 && lines[insertAt - 1].trim().startsWith("#EXTINF")) {
            insertAt -= 1
        }
        lines.add(insertAt, "#EXT-X-DISCONTINUITY")
        return lines.joinToString("\n")
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
        /** Single-stream diagnostic tag. Grep `adb logcat | grep LiveDiag`
         *  to see every recovery event in chronological order across the
         *  proxy, the ViewModel, and the player. */
        private const val DIAG = "LiveDiag"
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
        private const val MAX_INNER_RETRIES: Int = 5
        /** Number of consecutive **request-level** 5xx failures before we
         *  commit to swapping to a different sibling. Each individual request
         *  already burns up to [MAX_INNER_RETRIES] attempts internally, so a
         *  value of 2 means ~10 fetches across 2 ExoPlayer polls (~12 s of
         *  wall-clock) before we rotate. Transient origin hiccups recover
         *  inside that window; only sustained failure crosses the line. */
        private const val ROTATE_AFTER_5XX_RUN: Int = 2
        /** How fresh the last-good cached body has to be before we'll
         *  reuse it instead of surfacing 503. 12 s = three segment-worth
         *  of grace, comfortably under the live-edge window so ExoPlayer
         *  never falls behind the playable range. */
        private const val LAST_GOOD_TTL_MS: Long = 12_000L
        private const val CHROME_UA =
            "Mozilla/5.0 (Linux; Android 12) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"
    }
}
