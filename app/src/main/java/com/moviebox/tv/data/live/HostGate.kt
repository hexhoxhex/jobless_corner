package com.moviebox.tv.data.live

import android.util.Log
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * Keeps us off a server that has asked us to stay away.
 *
 * 2026-11-07: the live edge changed its playlist format, every chunk came back
 * 404, and the player's reloads (one every ~3.5 s, plus a failover bouncing
 * between two channels and the background checks) got this address banned:
 * every request, token calls included, answered `403 forbidden` with
 * `Retry-After: 3600`. We kept asking all the same, which can only stretch a
 * ban like that.
 *
 * A server that names a wait gets it. Until it is over, requests to that host
 * are answered here with a 503 that never touches the network, except one
 * every [PROBE_EVERY_MS], so a ban lifted early is noticed. Every live client
 * carries this interceptor, so playback, probes and background checks all
 * obey the same wait.
 */
object HostGate : Interceptor {
    /** Set on the answers made up here. */
    const val HELD_HEADER = "X-Held-Back"

    private const val TAG = "LiveDiag"
    private const val PROBE_EVERY_MS = 5 * 60_000L
    private const val MIN_HOLD_MS = 15_000L
    private const val MAX_HOLD_MS = 60 * 60_000L
    private val REFUSALS = setOf(403, 429, 503)

    private val heldUntil = ConcurrentHashMap<String, Long>()
    private val lastProbe = ConcurrentHashMap<String, Long>()

    /** How much longer [host] asked us to stay away; 0 when it didn't. */
    fun heldForMs(host: String): Long {
        val until = heldUntil[host] ?: return 0L
        return (until - System.currentTimeMillis()).coerceAtLeast(0L)
    }

    /** Test hook (/api/debug/gate): act as if [host] asked us to wait [ms];
     *  0 releases it. */
    fun holdForTest(host: String, ms: Long) {
        if (ms <= 0) {
            heldUntil.remove(host)
            lastProbe.remove(host)
            Log.i(TAG, "GATE $host released for a test")
        } else {
            hold(host, ms, 0)
        }
    }

    /** True while any server has us waiting. */
    fun anyHeld(): Boolean {
        val now = System.currentTimeMillis()
        return heldUntil.values.any { it > now }
    }

    /** True when the next request to held [host] would go through as the
     *  check on whether it lets us back in. */
    fun probeDue(host: String): Boolean =
        System.currentTimeMillis() - (lastProbe[host] ?: 0L) >= PROBE_EVERY_MS

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val host = request.url.host
        val now = System.currentTimeMillis()
        val until = heldUntil[host]
        var probing = false
        if (until != null) {
            if (now >= until) {
                heldUntil.remove(host, until)
            } else {
                // One request at a time goes through as the probe.
                val last = lastProbe[host]
                val claimed = now - (last ?: 0L) >= PROBE_EVERY_MS && (
                    if (last == null) lastProbe.putIfAbsent(host, now) == null
                    else lastProbe.replace(host, last, now)
                    )
                if (!claimed) return held(request, host, until - now)
                probing = true
            }
        }
        val response = chain.proceed(request)
        val wait = retryAfterMs(response)
        if (response.code in REFUSALS && wait != null) {
            hold(host, wait, response.code)
        } else if (probing && response.code !in REFUSALS) {
            heldUntil.remove(host)
            Log.i(TAG, "GATE $host answers again (HTTP ${response.code}) — released")
        }
        return response
    }

    private fun hold(host: String, waitMs: Long, code: Int) {
        val ms = waitMs.coerceIn(MIN_HOLD_MS, MAX_HOLD_MS)
        val until = System.currentTimeMillis() + ms
        val before = heldUntil.put(host, until)
        lastProbe[host] = System.currentTimeMillis()
        if (before == null) {
            Log.w(TAG, "GATE $host asked us to wait ${ms / 1000}s (HTTP $code) — " +
                "holding every request to it until then")
        }
    }

    private fun held(request: okhttp3.Request, host: String, leftMs: Long): Response =
        Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(503)
            .message("Held back")
            .header(HELD_HEADER, "1")
            .header("Retry-After", ((leftMs + 999) / 1000).toString())
            .body("held back: $host asked us to wait".toResponseBody("text/plain".toMediaType()))
            .build()

    /** Retry-After in ms: seconds, or an HTTP date. */
    private fun retryAfterMs(response: Response): Long? {
        val v = response.header("Retry-After")?.trim() ?: return null
        v.toLongOrNull()?.let { return it * 1000 }
        return runCatching {
            val fmt = java.text.SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US)
            fmt.parse(v)!!.time - System.currentTimeMillis()
        }.getOrNull()
    }
}
