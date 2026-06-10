package com.moviebox.tv.data.live

import android.util.Base64
import kotlinx.coroutines.Dispatchers
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
        for (suffix in DADDY_ENDPOINTS) {
            runCatching { tryEndpoint(channelId, suffix) }
                .getOrNull()
                ?.let { return@withContext it }
        }
        null
    }

    private fun tryEndpoint(channelId: String, daddySuffix: String): String? {
        val url = "https://donis.jimpenopisonline.online/premiumtv/daddy$daddySuffix.php?id=$channelId"
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
        // ("Error fetching index playlist"). When that happens we want to
        // rotate to the next daddyN.php which usually picks a healthier
        // sibling, not hand a dead URL to ExoPlayer.
        return runCatching {
            val probe = Request.Builder().url(resolved).head()
                .header("User-Agent", CHROME_ANDROID_UA)
                .build()
            client.newCall(probe).execute().use { r ->
                if (r.isSuccessful) resolved else null
            }
        }.getOrNull()
    }

    companion object {
        /** Daddy CDN endpoint suffixes, canonical first. Empty = `daddy.php`. */
        private val DADDY_ENDPOINTS = listOf("", "2", "3", "4", "5")

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
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(8, TimeUnit.SECONDS)
            // Follow donis -> Cloudflare redirects but not endlessly.
            .followRedirects(true)
            .followSslRedirects(true)
            .build()
    }
}
