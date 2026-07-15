package com.moviebox.tv.data.live

import android.util.Base64
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * Device-side stream URL resolver. Calls donis directly at playback time so
 * the signed-URL token is bound to *this device's* WAN egress IP — fixes the
 * IP-binding 403 the static catalog hits when the scraper and the playback
 * device sit on different IPs. See `LIVE_TV_NOTES.md` and the IP-binding
 * confirmation thread in `LIVE_TV_BUG_REPORT.md` §0.
 *
 * Notes from the data agent (do not change without consulting):
 *  - `daddy.php` is the canonical endpoint; `daddy2..daddy5.php` are CDN
 *    alternates that sometimes route to dead pontos siblings. We rotate
 *    through them on 500-class responses.
 *  - The `Referer` header is mandatory — without it Cloudflare in front
 *    of donis serves a 403 instead of the player HTML. Must be
 *    `https://dlhd.pk/stream/stream-{id}.php` (channel-specific, not a
 *    generic root).
 *  - The base64 blob is the first `window.atob('...')` in the page. Other
 *    `atob()` calls (ad-related) come later; always take the first.
 *  - ExoPlayer's default UA gets 403'd; use a real Chrome UA. The resolver
 *    therefore needs its own OkHttp client, separate from ExoPlayer's
 *    DataSource.
 *  - Use only the `stream` wrapper path for the direct resolver. `cast`,
 *    `watch`, `plus`, `casting`, `player` (the other entries in
 *    `Channel.players`) all route to anti-bot inner backends; those are
 *    reserved for the WebView fallback.
 */
class LiveResolver(
    private val client: OkHttpClient = defaultClient(),
) {

    /** Per-channel × per-CDN 4xx counter. When a specific CDN keeps
     *  handing back tokens that die on the very-next-hop request
     *  (typical for xameleon.phantemlis.top on some East African ISPs +
     *  some channels — the master m3u8 is 200 but the inner playlist is
     *  403), we bias future [resolveStream] calls for THAT channel to
     *  reject probes returning that CDN. Self-healing: after
     *  [FAILURE_TTL_MS] with no fresh failures, the count is cleared and
     *  the channel goes back to racing all CDNs equally. */
    private val cdnFailures =
        java.util.concurrent.ConcurrentHashMap<String, ChannelHealth>()

    private data class ChannelHealth(
        val host: String,
        var count: Int,
        var lastFailAtMs: Long,
    )

    /** LiveStreamProxy calls this when it observes a 401/403/410 on an
     *  inner-playlist or segment fetch. Recording the failing CDN host
     *  lets us avoid handing the same channel the same doomed CDN on
     *  the next resolve. */
    fun reportAuthFailure(channelId: String, cdnHost: String) {
        val now = System.currentTimeMillis()
        cdnFailures.compute("$channelId|$cdnHost") { _, prev ->
            val stale = prev != null && now - prev.lastFailAtMs > FAILURE_TTL_MS
            if (prev == null || stale) ChannelHealth(cdnHost, 1, now)
            else prev.also { it.count += 1; it.lastFailAtMs = now }
        }
    }

    /** LiveStreamProxy calls this when a channel plays cleanly for more
     *  than a segment — resets the per-channel bad-CDN memory. */
    fun reportSuccess(channelId: String) {
        cdnFailures.keys.removeAll { it.startsWith("$channelId|") }
    }

    /** CDN hosts to avoid for this channel because they've handed us
     *  dead tokens recently. Returns empty if the channel has no fresh
     *  failures on record. */
    private fun avoidCdnsFor(channelId: String): Set<String> {
        val now = System.currentTimeMillis()
        val prefix = "$channelId|"
        return cdnFailures.entries
            .asSequence()
            .filter { (k, v) ->
                k.startsWith(prefix) &&
                    v.count >= AVOID_AFTER_FAILURES &&
                    now - v.lastFailAtMs <= FAILURE_TTL_MS
            }
            .map { it.value.host }
            .toSet()
    }

    /**
     * Resolve a fresh, IP-signed master `.m3u8` URL for [channelId]. Tries
     * each daddy CDN endpoint in [DADDY_ENDPOINTS] in order, returning the
     * first one that yields a base64 blob decoding to a `.m3u8` URL.
     *
     * Returns null if every endpoint fails — caller should fall back to the
     * catalog's `stream_url` (which may itself 403 if the IP-bind hypothesis
     * holds, but it's still the right next step before giving up).
     */
    suspend fun resolveStream(channelId: String): String? = withContext(Dispatchers.IO) {
        val t0 = System.currentTimeMillis()
        // Race every known resolver strategy IN PARALLEL and take the FIRST
        // one that hands back a validated master manifest — the health check
        // inside each probe (GET + `#EXTM3U` prefix) means only URLs the
        // device can actually stream from win the race. Two strategies:
        //   (A) daddy1..5.php on hamis (historically primary), and
        //   (B) the 6 PLAYER paths on dlhd.st (/stream, /cast, /watch,
        //       /plus, /casting, /player).
        // In practice most (A) attempts route to xameleon.phantemlis.top,
        // which during peak load returns 5xx on the very-next-hop manifest
        // fetch even though the initial validation passed. Meanwhile (B)
        // covers the CDN diversity DADDY LIVE actually exposes — most
        // importantly PLAYER 4 (/plus/) which routes via ritzembeds ->
        // vinix.inproviszon.st on Cloudflare's Nairobi PoP (~5 ms from Kenyan
        // ISPs). "First validated wins" means: users on a fast well-provisioned
        // route to xameleon get that (its probe finishes first); Kenya users
        // whose xameleon path is slow/5xx get the vinix probe finishing first
        // — automatically, without a region hint.
        val winner = coroutineScope {
            val probes = mutableListOf<Deferred<Pair<String, String>?>>()
            for (host in resolverHosts(channelId)) {
                for (suffix in DADDY_ENDPOINTS) {
                    probes += async {
                        runCatching { tryEndpoint(host, channelId, suffix) }.getOrNull()
                            ?.let { it to "donis@$host" }
                    }
                }
            }
            for (path in PLAYER_PATHS) {
                probes += async {
                    runCatching { tryPlayerPath(channelId, path) }.getOrNull()
                        ?.let { it to "dlhd:$path" }
                }
            }
            // Per-channel CDN blacklist: any CDN host that's returned 401/403/410
            // for this channel N times in the last few minutes gets filtered
            // out of this race. Runs BEFORE the vinix-preference step so a
            // channel with recent xameleon failures skips the xameleon
            // answers entirely, waiting for a vinix / other alternate to
            // succeed instead of immediately handing back a token that will
            // 403 on the very next hop.
            val avoid = avoidCdnsFor(channelId)
            if (avoid.isNotEmpty()) {
                android.util.Log.w(
                    "LiveDiag",
                    "RESOLVER ch=$channelId avoid-cdns=$avoid (past 4xx failures)",
                )
            }
            // Prefer any non-xameleon winner if one arrives within
            // PREFER_ALTERNATE_WINDOW_MS. xameleon.phantemlis.top is the
            // fast-but-flaky CDN; any alternate (vinix on Cloudflare
            // Nairobi, api.cdnlivetv.tv captured by JsIframeResolver,
            // welovetocare, etc.) is more reliable EVEN IF slower to
            // resolve. Under the per-channel blacklist, acceptFallback
            // also rejects the xameleon URL as a fallback — so on
            // blacklisted channels we keep waiting indefinitely for a
            // non-xameleon probe to succeed instead of returning null.
            fun notAvoided(host: String): Boolean =
                avoid.none { bad -> host.endsWith(bad) }
            val first = awaitPreferredThenAny(
                deferreds = probes,
                preferWindowMs = PREFER_ALTERNATE_WINDOW_MS,
                cdnHostOf = { pair -> pair.first.let(::hostFromUrl) },
                isPreferred = { host ->
                    // Preferred = anything that ISN'T the xameleon family.
                    // Includes vinix, api.cdnlivetv.tv, welovetocare,
                    // ksohls, ritzembeds — whatever WebView captures from
                    // the various JS players.
                    host.isNotEmpty() && !host.endsWith("phantemlis.top")
                },
                acceptFallback = { pair ->
                    val host = pair.first.let(::hostFromUrl).orEmpty()
                    host.isNotEmpty() && notAvoided(host)
                },
            )
            probes.forEach { if (it.isActive) it.cancel() }
            first
        }
        if (winner != null) {
            com.moviebox.tv.debug.ProviderHealth.success(winner.second)
            // Log the winner so we can tell in logcat WHICH strategy won —
            // essential when debugging why a specific ISP still sees the
            // "wrong" CDN. Format: strategy → cdn-host. `dt` measures how
            // long the race took start-to-finish; tap-to-picture latency
            // has three big chunks — resolver dt (this) + parseMaster (in
            // LiveStreamProxy) + first-segment fetch + codec init.
            val cdn = runCatching {
                java.net.URI(winner.first).host
            }.getOrNull() ?: "?"
            android.util.Log.w(
                "LiveDiag",
                "RESOLVER ch=$channelId won=${winner.second} cdn=$cdn " +
                    "dt=${System.currentTimeMillis() - t0}ms",
            )
            if (winner.second.startsWith("donis@")) {
                rememberWorkingHost(winner.second.removePrefix("donis@"))
            }
            return@withContext winner.first
        }
        android.util.Log.w(
            "LiveDiag",
            "RESOLVER ch=$channelId FAILED — all daddy × all player paths",
        )
        com.moviebox.tv.debug.ProviderHealth.failure(
            "donis", "resolve failed (all daddy endpoints × all player paths)")
        null
    }

    /** Await the first [Deferred] whose result is non-null. Returns null if
     *  every deferred resolves to null. Semantically the "any non-null" of
     *  a parallel probe race — cheaper and more robust than [select] since
     *  we don't need to identify which specific probe won.
     *
     *  Implementation: each probe drops its result onto a shared channel;
     *  we consume until we see a non-null or the channel empties. Cheap
     *  and doesn't hit the [select]-in-a-loop closure-binding gotcha. */
    private suspend fun <T> awaitFirstNonNull(deferreds: List<Deferred<T?>>): T? =
        kotlinx.coroutines.coroutineScope {
            if (deferreds.isEmpty()) return@coroutineScope null
            val channel =
                kotlinx.coroutines.channels.Channel<T?>(deferreds.size)
            val relays = deferreds.map { d ->
                launch { channel.send(d.await()) }
            }
            try {
                repeat(deferreds.size) {
                    val r = channel.receive()
                    if (r != null) return@coroutineScope r
                }
                null
            } finally {
                relays.forEach { it.cancel() }
                channel.close()
            }
        }

    /** Race the deferreds. Take the first probe whose CDN is "preferred"
     *  (per [isPreferred]) if one arrives within [preferWindowMs]. Otherwise
     *  fall back to the first non-preferred probe (usually the fast daddyN
     *  → xameleon answer). Rationale: xameleon.phantemlis.top has been
     *  observed to intermittently 403 or connection-fail on Kenyan ISPs
     *  (per-channel and per-edge basis). Every OTHER CDN we see (vinix,
     *  api.cdnlivetv.tv captured by JsIframeResolver, welovetocare, etc.)
     *  is considered preferred so a slower-but-working alternate can beat
     *  a fast-but-dying xameleon. Waiting up to a few seconds for the
     *  alternate is cheaper than the multi-second recovery loop that
     *  fires when xameleon 403s mid-stream. */
    private suspend fun <T> awaitPreferredThenAny(
        deferreds: List<Deferred<T?>>,
        preferWindowMs: Long,
        cdnHostOf: (T) -> String?,
        /** Predicate: does this CDN host count as preferred? Return true
         *  to immediately win the race; false to be considered a fallback. */
        isPreferred: (String) -> Boolean,
        /** Optional filter: only save a probe as fallback if this returns
         *  true. Defaults to accept-everything. Used to reject probes that
         *  target a CDN we've blacklisted for this channel — so we don't
         *  hand back a URL we already know will 403. When [acceptFallback]
         *  rejects all incoming probes, the loop keeps waiting past
         *  [preferWindowMs] until a preferred probe DOES arrive — which
         *  is exactly what we want for channels stuck on xameleon 403s:
         *  wait for a slower JS-captured alternate rather than returning
         *  null and forcing a retry. */
        acceptFallback: (T) -> Boolean = { true },
    ): T? = kotlinx.coroutines.coroutineScope {
        if (deferreds.isEmpty()) return@coroutineScope null
        val channel = kotlinx.coroutines.channels.Channel<T?>(deferreds.size)
        val relays = deferreds.map { d ->
            launch { channel.send(d.await()) }
        }
        try {
            val started = System.currentTimeMillis()
            var fallback: T? = null
            var received = 0
            while (received < deferreds.size) {
                val timeLeft = preferWindowMs - (System.currentTimeMillis() - started)
                // Wrap the receive in a "did we time out?" wrapper so we
                // can distinguish window-expiry (stop waiting for a
                // preferred winner) from a probe that returned null (keep
                // waiting for the next probe). This bug caused RESOLVER
                // FAILED — a probe returning null on channel was being
                // read as "window expired, give up" and we bailed with
                // fallback=null before any successful probe was seen.
                val timedOut = kotlinx.coroutines.Job()
                val r: T? = if (fallback != null && timeLeft > 0) {
                    kotlinx.coroutines.withTimeoutOrNull(timeLeft) {
                        channel.receive()
                    }.also { if (it == null) timedOut.complete() }
                } else {
                    channel.receive()
                }
                if (timedOut.isCompleted) break  // window expired
                received++  // counts a probe result, even if null
                if (r != null) {
                    val host = cdnHostOf(r).orEmpty()
                    when {
                        isPreferred(host) -> return@coroutineScope r
                        fallback == null && acceptFallback(r) -> fallback = r
                    }
                }
            }
            fallback
        } finally {
            relays.forEach { it.cancel() }
            channel.close()
        }
    }

    /** Ordered list of resolver hosts to try for [channelId]. Cached host
     *  first (free), known-good hardcodes next, then a one-shot dynamic
     *  discovery scrape of dlhd.pk as the safety net. Dynamic discovery
     *  is what makes this resolver self-healing across infrastructure
     *  changes — when dlhd rotates from donis → hamis → next, the app
     *  finds the new host on its own without a code change. */
    private fun resolverHosts(channelId: String): List<String> {
        val out = LinkedHashSet<String>()
        cachedHost?.let { out.add(it) }
        out.addAll(KNOWN_HOSTS)
        // The dynamic dlhd.pk scrape (discoverHostFromDlhd) used to run
        // here as a fallback "self-heal on host rotation" safety net.
        // Problem: it's a SYNCHRONOUS HTTP GET that takes up to 3 s
        // (matching the client callTimeout) BEFORE any async probes are
        // launched. Combined with the 3 s parallel race that followed,
        // resolveStream was routinely taking 6+ seconds even when the
        // very-first known host answered in < 1 s — the scrape was pure
        // startup latency.
        //
        // Removed here because [tryPlayerPath] already scrapes dlhd.st
        // ON EVERY CALL as part of its wrapper walk — the diverse player
        // paths cover the same "which resolver-host is dlhd advertising
        // today" question WITHOUT costing an extra synchronous roundtrip.
        // If both KNOWN_HOSTS AND every player path fail, the race
        // returns null, LiveStreamProxy propagates 503, ExoPlayer
        // retries, and the same code re-runs — self-healing without the
        // upfront cost.
        return out.toList()
    }

    /** Fetch `https://dlhd.pk/stream/stream-{id}.php` and pull the
     *  current resolver host out of the embedded
     *  `…/premiumtv/daddyN.php?id=…` URL that always appears in the
     *  page's HTML. dlhd writes this in plaintext (it's just the link
     *  to the page's own backend), so no JS decoding required.
     *  Returns null if scrape failed or no URL matched. */
    private fun discoverHostFromDlhd(channelId: String): String? {
        val req = Request.Builder()
            .url("https://dlhd.pk/stream/stream-$channelId.php")
            .header("User-Agent", CHROME_ANDROID_UA)
            .header("Referer", "https://dlhd.pk/watch.php?id=$channelId")
            .build()
        return runCatching {
            client.newCall(req).execute().use { r ->
                if (!r.isSuccessful) return@use null
                val body = r.body?.string() ?: return@use null
                HOST_DISCOVERY_RE.find(body)?.groupValues?.get(1)
            }
        }.getOrNull()
    }

    private fun rememberWorkingHost(host: String) { cachedHost = host }

    /**
     * Drop the remembered "last-good" host so the next [resolveStream]
     * call has to re-discover via [discoverHostFromDlhd]. Called by
     * [LiveStreamProxy.invalidate] when the player requested a re-resolve —
     * the previously-good host may itself have died, and we don't want
     * to loop on it. Cheap: the discovery scrape adds ~300 ms one-shot.
     */
    fun resetCachedHost() { cachedHost = null }

    /** Walk a specific PLAYER path on dlhd.st, follow its iframe, and pull
     *  out an m3u8 URL if one is embedded. Two resolver shapes exist inside
     *  the iframe body:
     *    - atob-encoded (PLAYER 1/3 → hamis): `window.atob('<base64>')`.
     *    - literal (PLAYER 4 → ritzembeds): the m3u8 URL sits as a plain
     *      https://<cdn>/... string in the page HTML.
     *  Only the atob shape has been seen for PLAYER 2/5/6 in most channels,
     *  but their iframe hosts (merithotdog, wikisport, ksohls) sometimes
     *  serve JS-heavy players we can't decode headlessly — those return
     *  null and get skipped. The primary win is the /plus/ path, which
     *  routes Kenya-reachable via vinix.inproviszon.st. */
    private suspend fun tryPlayerPath(channelId: String, path: String): String? {
        val wrapper = "https://dlhd.st/$path/stream-$channelId.php"
        val referer = "https://dlhd.st/watch.php?id=$channelId"
        val iframeUrl = runCatching {
            val req = Request.Builder().url(wrapper)
                .header("Referer", referer)
                .header("User-Agent", CHROME_ANDROID_UA)
                .build()
            client.newCall(req).execute().use { r ->
                if (!r.isSuccessful) return@use null
                val body = r.body?.string().orEmpty()
                IFRAME_RE.find(body)?.groupValues?.get(1)
                    ?.takeIf { "dlhd.st" !in it && "dlhd.pk" !in it }
            }
        }.getOrNull() ?: return null

        // Extract the m3u8 URL from the iframe body. Three resolver shapes:
        //   - atob-encoded (hamis on PLAYER 1/3): `window.atob('<b64>')`
        //   - literal (ritzembeds on PLAYER 4): the m3u8 URL sits as a plain
        //     https://<cdn>/... string somewhere in the page HTML
        //   - JS-obfuscated (welovetocare, cdnlivetv, ksohls on PLAYER 2/5/6):
        //     the m3u8 URL only exists in a Clappr/hls.js runtime call, not
        //     in the source HTML — fall through to [JsIframeResolver] which
        //     loads the page in a headless WebView and captures the first
        //     `.m3u8` network request. Costs ~1-3s and ~10MB memory per hit
        //     so we only take this path when both regex extractors miss.
        //
        // Note: we DELIBERATELY skip the streaming-side validation probe here
        // (an earlier version did a full GET + `#EXTM3U` check on the extracted
        // URL). That probe added ~10s worst-case per candidate; with 11 candidates
        // racing in parallel it pushed resolveStream past ExoPlayer's socket
        // timeout on the localhost proxy — playback died before the winner even
        // returned. LiveStreamProxy will fetch + parse the master manifest itself
        // in parseMaster(), and its 5xx handling will trigger [invalidate] +
        // re-resolve on failure. Trusting the atob is safe: hamis/ritzembeds only
        // emit valid signed URLs; a bad one gets caught within seconds by the
        // proxy's own probe, then the resolver runs again.
        val fromRegex = runCatching {
            val req = Request.Builder().url(iframeUrl)
                .header("Referer", wrapper)
                .header("User-Agent", CHROME_ANDROID_UA)
                .build()
            client.newCall(req).execute().use { r ->
                if (!r.isSuccessful) return@use null
                val body = r.body?.string().orEmpty()
                B64_RE.find(body)?.groupValues?.get(1)
                    ?.let {
                        runCatching {
                            Base64.decode(it, Base64.DEFAULT)
                                .toString(Charsets.UTF_8)
                                .takeIf { url -> ".m3u8" in url }
                        }.getOrNull()
                    }
                    ?: M3U8_DIRECT_RE.find(body)?.value
            }
        }.getOrNull()
        if (fromRegex != null) return fromRegex

        // Regex path missed — this iframe is JS-obfuscated. Fall back to
        // the WebView resolver. Only fires for the ~4 known JS-heavy
        // iframe hosts, and only when they're actually raced (i.e. this
        // channel's other player paths didn't win first). The WebView
        // has its own 6 s timeout so this can't exceed the resolver's
        // 3 s callTimeout by much — the awaitFirstNonNull race just
        // means we might see a slower answer arrive from JS iframes
        // after a faster daddyN answer has already resolved.
        return JsIframeResolver.resolve(iframeUrl, wrapper)
    }

    private fun tryEndpoint(
        host: String, channelId: String, daddySuffix: String,
    ): String? {
        val url = "https://$host/premiumtv/daddy$daddySuffix.php?id=$channelId"
        val referer = "https://dlhd.pk/stream/stream-$channelId.php"
        val req = Request.Builder()
            .url(url)
            .header("Referer", referer)
            .header("User-Agent", CHROME_ANDROID_UA)
            .header("Accept",
                "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
            .header("Accept-Language", "en-US,en;q=0.5")
            .build()
        // Skip validation probe — see comment in tryPlayerPath. Even one extra
        // GET here, multiplied by 5 daddyN suffixes × 6 player paths, exceeded
        // ExoPlayer's socket timeout on the localhost proxy and caused the
        // very-first /master fetch to fail with SocketTimeoutException before
        // any URL could be returned. LiveStreamProxy validates + re-resolves
        // on parse failure, which is the correct place for the check.
        return client.newCall(req).execute().use { r ->
            if (!r.isSuccessful) return null
            val body = r.body?.string() ?: return null
            val b64 = B64_RE.find(body)?.groupValues?.get(1) ?: return null
            runCatching {
                Base64.decode(b64, Base64.DEFAULT)
                    .toString(Charsets.UTF_8)
                    .takeIf { ".m3u8" in it }
            }.getOrNull()
        }
    }

    /** Discovered-good resolver host across the resolveStream() call.
     *  Promoted to the head of [resolverHosts] for subsequent calls so
     *  we don't pay the discovery cost more than once per
     *  infrastructure rotation. Process-local; lost on app restart and
     *  re-discovered cheaply on first play. */
    @Volatile private var cachedHost: String? = null

    companion object {
        /** Daddy CDN endpoint suffixes, canonical first. Empty = `daddy.php`. */
        private val DADDY_ENDPOINTS = listOf("", "2", "3", "4", "5")

        /** After this many 4xx failures on a given (channel, cdn) pair,
         *  future [resolveStream] calls for that channel filter that CDN
         *  out of the race. Set to 2 so a single flaky-then-recovered
         *  edge doesn't get permanently blacklisted but a sustained
         *  problem shifts us to alternates fast. */
        private const val AVOID_AFTER_FAILURES: Int = 2

        /** Per-channel failure records age out after this window. Prevents
         *  stale blacklist entries from following a channel around after
         *  the underlying CDN edge has healed. 5 minutes is short enough
         *  to notice recovery but long enough to bridge a typical burst
         *  of failures during a live event's peak traffic. */
        private const val FAILURE_TTL_MS: Long = 5L * 60 * 1000

        /** How long [resolveStream] will wait for a non-xameleon alternate
         *  probe to win before falling back to whichever xameleon result
         *  arrived first. Bumped from 1.2 s to 3 s so JS-captured URLs
         *  (via [JsIframeResolver], typically 2-6 s wall-clock) have a
         *  real chance to beat the fast daddyN → xameleon answer. On
         *  channels where xameleon is blacklisted this window is
         *  effectively unbounded — we keep waiting for any non-xameleon
         *  probe (see acceptFallback + notAvoided in [resolveStream]).
         *  Users where xameleon is healthy see a 3 s ceiling on cold-
         *  start latency but usually much less because vinix (when
         *  available) beats xameleon by ~1 s. */
        private const val PREFER_ALTERNATE_WINDOW_MS: Long = 3_000L

        /** Extract the host from a URL using string parsing — matches
         *  [LiveStreamProxy.hostOf]'s approach and avoids `java.net.URI`'s
         *  known-quirky `.host` behavior on some URL patterns. */
        private fun hostFromUrl(url: String): String? =
            url.substringAfter("//", "").substringBefore('/')
                .substringBefore(':').lowercase().takeIf { it.isNotEmpty() }

        /** The 6 alternate PLAYER wrapper paths dlhd.st exposes per channel
         *  (dlhd.st/{path}/stream-{id}.php). Each routes to a different iframe
         *  host and — critically — a different upstream CDN. `stream` and
         *  `watch` share the hamis→xameleon chain, matching daddyN. `plus`
         *  routes via ritzembeds → vinix.inproviszon.st (Cloudflare Nairobi
         *  PoP, ~5 ms from Kenyan ISPs), which is the escape hatch when
         *  xameleon is IP-blocked. The others (`cast`, `casting`, `player`)
         *  use JS-heavy players we can't decode headlessly today but their
         *  target hosts are recorded so a future resolver upgrade can cover
         *  more CDN diversity without touching this file. */
        private val PLAYER_PATHS = listOf(
            "stream", "cast", "watch", "plus", "casting", "player",
        )

        /** `<iframe src="...">` src attribute — first match only. Used both
         *  to pull the resolver iframe out of a dlhd.st player wrapper AND
         *  as a sanity guard: an iframe pointing back at dlhd.st itself is
         *  a placeholder, not a real backend. */
        private val IFRAME_RE = Regex(
            """<iframe[^>]+src="(https?://[^"]+)"""",
            RegexOption.IGNORE_CASE,
        )

        /** Literal `.m3u8` URL in an iframe body. Some resolvers (notably
         *  ritzembeds.pages.dev serving the /plus/ path) embed the master
         *  manifest URL as a plain https string instead of base64-encoding
         *  it, so the atob path misses them. Regex is deliberately greedy
         *  to capture the whole URL including its query string / signed
         *  token. */
        private val M3U8_DIRECT_RE = Regex(
            """https?://[a-z0-9.-]+/[^\s"']*\.m3u8[^\s"']*""",
            RegexOption.IGNORE_CASE,
        )

        /** Hardcoded fallback list, most-recently-confirmed first. dlhd
         *  rotates this domain periodically (each entry was the
         *  authoritative resolver until it was replaced):
         *    - hamis.romponalis.st            (current, captured 2026-06-23)
         *    - donis.jimpenopisonline.online  (NXDOMAIN since 2026-06)
         *  Older entries kept as last-ditch fallbacks. The dynamic
         *  scrape in discoverHostFromDlhd() is what handles future
         *  rotations without a code change. */
        private val KNOWN_HOSTS = listOf(
            "hamis.romponalis.st",
            "donis.jimpenopisonline.online",
        )

        /** Captures the resolver host from the daddyN URL dlhd writes
         *  into stream-X.php's HTML. Expects a hostname (letters,
         *  digits, dots, hyphens) followed by /premiumtv/daddyN.php. */
        private val HOST_DISCOVERY_RE = Regex(
            """https?://([a-z0-9.-]+)/premiumtv/daddy\d*\.php""",
            RegexOption.IGNORE_CASE,
        )

        /** First `window.atob('...')` in the donis player HTML. Multiple
         *  atob() calls exist (ad-related); always take the first one. */
        private val B64_RE =
            Regex("""window\.atob\(\s*['"]([A-Za-z0-9+/=]+)['"]\s*\)""")

        /** Real Chrome-Android UA. ExoPlayer's default UA gets 403'd; donis
         *  is sensitive to this even though the catalog CDN isn't. */
        private const val CHROME_ANDROID_UA =
            "Mozilla/5.0 (Linux; Android 12) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"

        private fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            // Tight timeouts: the resolver now races 11 candidates (5 daddyN
            // + 6 PLAYER paths) via [awaitFirstNonNull], so total resolve time
            // is bounded by the FASTEST successful probe — not the slowest.
            // ExoPlayer's socket timeout on the localhost proxy is ~10 s, so
            // resolveStream must finish well inside that. Previously the 14 s
            // callTimeout meant a single stuck endpoint could hold up the
            // whole race, and playback died before any URL was returned. A
            // 5 s call timeout leaves plenty of headroom for legitimate
            // signed-URL fetches (~1-3 s) while cutting off dead branches
            // fast; if all 11 probes hang for 5 s, we still return null in
            // time for ExoPlayer's own retry to kick in.
            // 3 s call ceiling per probe — dropped from 5 s because
            // the LiveStreamProxy's [ENSURE_CACHED_TIMEOUT_MS] is 7 s AND
            // has to also cover parseMaster (~0.5-1.5 s). With 5 s per
            // probe, a resolver race that had to fall through to a slow
            // player-path (6-7 s wall clock) would leave < 1 s for
            // parseMaster and the proxy would return 503 to ExoPlayer
            // before the cache was actually populated. 3 s is enough for
            // any HEALTHY probe (hamis daddyN responds in ~800 ms, /plus
            // paths in ~1.5-2 s); a probe that hasn't completed in 3 s
            // was going to fail anyway.
            .connectTimeout(2, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(3, java.util.concurrent.TimeUnit.SECONDS)
            .callTimeout(3, java.util.concurrent.TimeUnit.SECONDS)
            // Follow donis -> Cloudflare redirects but not endlessly.
            .followRedirects(true)
            .followSslRedirects(true)
            .build()
    }
}
