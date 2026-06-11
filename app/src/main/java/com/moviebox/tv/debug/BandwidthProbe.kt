package com.moviebox.tv.debug

import android.os.SystemClock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * Measures the TV's actual download throughput from a real CDN-hosted file,
 * so the Debug pane can show the user something more concrete than "rebuffers
 * are happening" — specifically, a Mbps number and a channel-by-channel
 * health verdict.
 *
 * We deliberately target a file that's hosted on the same kind of upstream
 * the live streams use rather than a "speed test" service. The point isn't
 * to measure peak link bandwidth — it's to measure **what you actually get
 * to phantemlis-style hosts**, which is the relevant number for live TV.
 *
 * The chosen target is the data agent's catalog JSON on raw.githubusercontent.com:
 *  - Always reachable (no auth, no expiry)
 *  - Hits the same CGNAT → Africa-egress → upstream path the streams use
 *  - ~2 MB body is large enough to give a stable throughput measurement
 *    but small enough that the test takes ~1-3 s on a healthy link
 *
 * Result is reported in Mbps + a string verdict the SPA renders without
 * extra logic ("excellent" / "good" / "fair" / "poor"). 'fair' is the
 * default threshold below which we'd expect a 5 Mbps live channel to
 * rebuffer; "poor" is below the threshold even for 2 Mbps SD channels.
 */
class BandwidthProbe {

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    suspend fun measure(): Result = withContext(Dispatchers.IO) {
        val req = Request.Builder()
            .url(TARGET_URL)
            .header("User-Agent", USER_AGENT)
            .header("Cache-Control", "no-cache")
            .build()
        val startMs = SystemClock.elapsedRealtime()
        runCatching {
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    return@runCatching Result(
                        mbps = 0.0,
                        bytes = 0,
                        elapsedMs = SystemClock.elapsedRealtime() - startMs,
                        verdict = "error",
                        error = "HTTP ${resp.code}",
                    )
                }
                // Stream bytes through a counting input rather than
                // resp.body.string() — we want elapsed wall-clock time
                // from headers-received to last byte, NOT JSON parsing.
                val stream = resp.body?.byteStream() ?: return@runCatching Result(
                    mbps = 0.0, bytes = 0,
                    elapsedMs = SystemClock.elapsedRealtime() - startMs,
                    verdict = "error", error = "no body",
                )
                val buf = ByteArray(8 * 1024)
                var total = 0L
                while (true) {
                    val n = stream.read(buf)
                    if (n < 0) break
                    total += n
                }
                val elapsedMs = SystemClock.elapsedRealtime() - startMs
                val mbps = if (elapsedMs > 0) {
                    (total * 8.0 / 1_000_000.0) / (elapsedMs / 1000.0)
                } else 0.0
                Result(
                    mbps = mbps,
                    bytes = total,
                    elapsedMs = elapsedMs,
                    verdict = verdictFor(mbps),
                    error = null,
                )
            }
        }.getOrElse { e ->
            Result(
                mbps = 0.0, bytes = 0,
                elapsedMs = SystemClock.elapsedRealtime() - startMs,
                verdict = "error", error = e.message ?: "unknown",
            )
        }
    }

    private fun verdictFor(mbps: Double): String = when {
        mbps >= 20.0 -> "excellent"   // 4K-comfortable; live HD trivial
        mbps >= 8.0  -> "good"        // 1080p live channels comfortable
        mbps >= 4.0  -> "fair"        // 720p live works; HD rebuffers
        else         -> "poor"        // even SD live channels will rebuffer
    }

    data class Result(
        val mbps: Double,
        val bytes: Long,
        val elapsedMs: Long,
        val verdict: String,
        val error: String?,
    ) {
        fun toJson(): String {
            val sb = StringBuilder()
            sb.append('{')
            sb.append("\"mbps\":").append("%.2f".format(mbps))
            sb.append(",\"bytes\":").append(bytes)
            sb.append(",\"elapsedMs\":").append(elapsedMs)
            sb.append(",\"verdict\":\"").append(verdict).append('"')
            sb.append(",\"error\":")
            if (error == null) sb.append("null")
            else {
                sb.append('"')
                for (c in error) {
                    if (c == '"' || c == '\\') sb.append('\\')
                    sb.append(c)
                }
                sb.append('"')
            }
            sb.append('}')
            return sb.toString()
        }
    }

    companion object {
        /** Catalog JSON on raw.githubusercontent.com. ~2 MB, no auth, takes the
         *  same long-haul path the live streams do. */
        private const val TARGET_URL =
            "https://raw.githubusercontent.com/hexhoxhex/mkurugenzi_viewer/main/data/channels.json"
        private const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 12) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"
    }
}
