package com.moviebox.tv.data.live

import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
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
        // Try every known resolver host (cheap-first), and within each host
        // probe ALL daddyN endpoints CONCURRENTLY, taking the lowest-numbered
        // one that returns a playable manifest. Serial probing fell apart once
        // the CDN got slow: ~8 s per endpoint × 5 = ~40 s, which blew past the
        // call timeout, rejected the valid-but-slow stream, and handed the dead
        // daddy.php fallback to ExoPlayer — the "stuck loading / reconnecting"
        // the user hit on FOX and most channels. Parallel = one slow probe, not
        // the sum of them.
        for (host in resolverHosts(channelId)) {
            val winner = coroutineScope {
                DADDY_ENDPOINTS.mapIndexed { idx, suffix ->
                    async {
                        idx to runCatching { tryEndpoint(host, channelId, suffix) }.getOrNull()
                    }
                }.awaitAll()
                    .filter { it.second != null }
                    .minByOrNull { it.first }
                    ?.second
            }
            if (winner != null) {
                com.moviebox.tv.debug.ProviderHealth.success("donis@$host")
                rememberWorkingHost(host)
                return@withContext winner
            }
        }
        com.moviebox.tv.debug.ProviderHealth.failure(
            "donis", "resolve failed (all hosts × all daddy endpoints)")
        null
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
        // Only scrape if neither cache nor known hosts have given us a
        // result yet. Limited to one scrape attempt per resolveStream
        // call to keep cold-start latency bounded.
        if (out.isNotEmpty()) {
            runCatching { discoverHostFromDlhd(channelId) }
                .getOrNull()?.let { out.add(it) }
        }
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
        val resolved = client.newCall(req).execute().use { r ->
            if (!r.isSuccessful) return null
            val body = r.body?.string() ?: return null
            val b64 = B64_RE.find(body)?.groupValues?.get(1) ?: return null
            runCatching {
                Base64.decode(b64, Base64.DEFAULT)
                    .toString(Charsets.UTF_8)
                    .takeIf { ".m3u8" in it }
            }.getOrNull()
        } ?: return null

        // Validate that the resolved URL actually serves a manifest. daddy.php
        // returning 200 with a blob is necessary but not sufficient — the
        // pontos/vomos sibling the URL points at may itself be 500-ing
        // ("Error fetching index playlist"). When that happens we want a
        // sibling that gives a real manifest, not to hand a dead URL to
        // ExoPlayer. Validate with a GET that reads the response head: HEAD is
        // unreliable on these CDNs (some 200 a HEAD but 403/500 the real GET),
        // and a true #EXTM3U master is exactly what ExoPlayer needs next.
        return runCatching {
            val probe = Request.Builder().url(resolved).get()
                .header("User-Agent", CHROME_ANDROID_UA)
                .build()
            client.newCall(probe).execute().use { r ->
                val head = r.body?.string().orEmpty()
                if (r.isSuccessful && head.trimStart().startsWith("#EXTM3U")) resolved else null
            }
        }.getOrNull()
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
            // Timeouts must clear ONE slow CDN response, not be a tight cap on
            // the whole resolve. The endpoints are now probed in parallel
            // (see resolveStream), so total resolve ≈ the slowest single probe
            // rather than the sum. phantemlis has been answering the validating
            // GET in ~8 s under load (during live games) — the old 6 s call
            // timeout rejected those valid streams and dropped to the dead
            // fallback. 14 s clears them with headroom while still bailing on a
            // genuinely hung endpoint.
            .connectTimeout(6, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
            .callTimeout(14, java.util.concurrent.TimeUnit.SECONDS)
            // Follow donis -> Cloudflare redirects but not endlessly.
            .followRedirects(true)
            .followSslRedirects(true)
            .build()
    }
}
