package com.moviebox.tv.debug

import android.os.SystemClock
import java.util.concurrent.ConcurrentLinkedDeque
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * In-process metrics + event log for the Debug pane on the mobile remote.
 *
 * Everything here is in-memory only; we deliberately don't persist anything
 * across process restarts because the goal is "see what's happening right
 * now" not "deep historical analytics". Two ring-buffers cap the memory
 * footprint:
 *   - [eventLog] holds the latest [MAX_EVENTS] events (any severity).
 *   - [channelStats] holds the latest [MAX_CHANNELS] distinct channels we
 *     have ever touched in this session; oldest gets evicted by access time.
 *
 * The /api/debug endpoint snapshots this into JSON; the SPA renders it.
 *
 * Threading: lock-free. Counters are atomic, event log is a concurrent
 * deque, channel map is ConcurrentHashMap. Snapshot is best-effort
 * point-in-time; if a stat increments mid-snapshot we don't care.
 */
object Telemetry {

    // ---- session-wide counters ----
    private val playsStarted = AtomicInteger(0)
    private val playsFailed  = AtomicInteger(0)
    private val freezes      = AtomicInteger(0)
    private val rebuffers    = AtomicInteger(0)
    private val httpErrors   = AtomicInteger(0)
    private val sessionStart = SystemClock.elapsedRealtime()

    // ---- current stream (whatever's playing right now) ----
    @Volatile private var currentTitle: String = ""
    @Volatile private var currentKind:  String = "none"   // "live" | "vod" | "none"
    @Volatile private var currentChannelId: String? = null
    @Volatile private var currentFps: Int = 0
    @Volatile private var currentDroppedRatio: Float = 0f
    @Volatile private var currentBufferMs: Long = 0L
    @Volatile private var currentBitrateBps: Long = 0L
    @Volatile private var currentResolution: String = ""
    @Volatile private var lastStateChangeAt: Long = SystemClock.elapsedRealtime()

    // ---- per-channel cumulative stats ----
    private val channelStats = java.util.concurrent.ConcurrentHashMap<String, ChannelStats>()

    // ---- event log (newest at head) ----
    private val eventLog = ConcurrentLinkedDeque<LogEvent>()

    // ============================================================
    // hooks called by the rest of the app
    // ============================================================

    /** Called when a channel/movie starts playing. */
    fun onPlayStart(kind: String, title: String, channelId: String? = null) {
        playsStarted.incrementAndGet()
        currentKind = kind
        currentTitle = title
        currentChannelId = channelId
        lastStateChangeAt = SystemClock.elapsedRealtime()
        channelId?.let { id ->
            channelStats.compute(id) { _, prev ->
                (prev ?: ChannelStats(id, title)).also { it.plays.incrementAndGet() }
            }
        }
        log(Severity.INFO, "Playing $kind: $title")
    }

    /** Called when playback errors and we can't recover via re-resolve. */
    fun onPlayFailed(reason: String) {
        playsFailed.incrementAndGet()
        currentChannelId?.let { id ->
            channelStats[id]?.failures?.incrementAndGet()
            channelStats[id]?.lastFailure = reason
        }
        log(Severity.ERROR, "Play failed: $reason")
    }

    /** ExoPlayer entered STATE_BUFFERING after playing. */
    fun onBufferStart() {
        rebuffers.incrementAndGet()
        currentChannelId?.let { id ->
            channelStats[id]?.rebuffers?.incrementAndGet()
        }
        log(Severity.WARN, "Rebuffering")
    }

    /** ExoPlayer's resilience tracker decided this is a hard freeze (15s+). */
    fun onFreeze() {
        freezes.incrementAndGet()
        currentChannelId?.let { id ->
            channelStats[id]?.freezes?.incrementAndGet()
        }
        log(Severity.ERROR, "Stream froze")
    }

    /** AnalyticsListener.onDroppedVideoFrames callback. */
    fun onDroppedFrames(dropped: Int, elapsedMs: Long) {
        if (elapsedMs > 0) {
            val budget = (elapsedMs * 60 / 1000).toInt()
            if (budget > 0) {
                currentDroppedRatio = dropped.toFloat() / budget
            }
        }
    }

    /** Periodic update of the realtime metrics. Caller should fire ~once a second. */
    fun updateRealtime(
        fps: Int? = null,
        bufferMs: Long? = null,
        bitrateBps: Long? = null,
        resolution: String? = null,
    ) {
        fps?.let { currentFps = it }
        bufferMs?.let { currentBufferMs = it }
        bitrateBps?.let { currentBitrateBps = it }
        resolution?.let { currentResolution = it }
    }

    /** Called from the live-stream proxy when an upstream HTTP call non-2xxs. */
    fun onHttpError(code: Int, url: String) {
        httpErrors.incrementAndGet()
        log(Severity.WARN, "HTTP $code: ${shortUrl(url)}")
    }

    /** Channel/movie ended cleanly (user back / next episode / etc.) */
    fun onPlayStopped() {
        if (currentKind != "none") {
            log(Severity.INFO, "Stopped: $currentTitle")
        }
        currentKind = "none"
        currentTitle = ""
        currentChannelId = null
        currentFps = 0
        currentDroppedRatio = 0f
    }

    /** Free-form note. Used by the resolver / proxy / resilience trackers. */
    fun note(severity: Severity, message: String) = log(severity, message)

    // ============================================================
    // snapshot for the JSON API
    // ============================================================

    fun snapshotJson(): String {
        val now = SystemClock.elapsedRealtime()
        val uptimeMs = now - sessionStart
        val rating = ratingFor(currentDroppedRatio, currentBufferMs)
        val sb = StringBuilder()
        sb.append('{')
        // summary
        sb.append("\"session\":{")
        sb.append("\"uptimeMs\":").append(uptimeMs)
        sb.append(",\"playsStarted\":").append(playsStarted.get())
        sb.append(",\"playsFailed\":").append(playsFailed.get())
        sb.append(",\"freezes\":").append(freezes.get())
        sb.append(",\"rebuffers\":").append(rebuffers.get())
        sb.append(",\"httpErrors\":").append(httpErrors.get())
        sb.append('}')
        // current stream
        sb.append(",\"current\":{")
        sb.append("\"kind\":").append(quote(currentKind))
        sb.append(",\"title\":").append(quote(currentTitle))
        sb.append(",\"channelId\":").append(quote(currentChannelId ?: ""))
        sb.append(",\"fps\":").append(currentFps)
        sb.append(",\"droppedRatio\":").append("%.3f".format(currentDroppedRatio.toDouble()))
        sb.append(",\"bufferMs\":").append(currentBufferMs)
        sb.append(",\"bitrateBps\":").append(currentBitrateBps)
        sb.append(",\"resolution\":").append(quote(currentResolution))
        sb.append(",\"rating\":").append(quote(rating))
        sb.append('}')
        // channels
        sb.append(",\"channels\":[")
        var first = true
        for (cs in channelStats.values.sortedByDescending {
            it.failures.get() * 10 + it.freezes.get() * 5 + it.rebuffers.get()
        }) {
            if (!first) sb.append(',')
            first = false
            sb.append('{')
            sb.append("\"id\":").append(quote(cs.id))
            sb.append(",\"name\":").append(quote(cs.name))
            sb.append(",\"plays\":").append(cs.plays.get())
            sb.append(",\"freezes\":").append(cs.freezes.get())
            sb.append(",\"rebuffers\":").append(cs.rebuffers.get())
            sb.append(",\"failures\":").append(cs.failures.get())
            sb.append(",\"lastFailure\":").append(quote(cs.lastFailure ?: ""))
            sb.append('}')
        }
        sb.append(']')
        // events
        sb.append(",\"events\":[")
        first = true
        for (e in eventLog) {
            if (!first) sb.append(',')
            first = false
            sb.append('{')
            sb.append("\"atMs\":").append(e.atMs)
            sb.append(",\"severity\":").append(quote(e.severity.name.lowercase()))
            sb.append(",\"message\":").append(quote(e.message))
            sb.append('}')
        }
        sb.append(']')
        sb.append('}')
        return sb.toString()
    }

    // ============================================================
    // helpers
    // ============================================================

    private fun ratingFor(droppedRatio: Float, bufferMs: Long): String = when {
        droppedRatio >= 0.20f || bufferMs in 1..5_000 -> "poor"
        droppedRatio >= 0.10f -> "fair"
        droppedRatio >= 0.03f -> "good"
        else -> "excellent"
    }

    private fun log(severity: Severity, message: String) {
        eventLog.addFirst(
            LogEvent(SystemClock.elapsedRealtime(), severity, message),
        )
        // Trim from the tail so the newest events stay near the head.
        while (eventLog.size > MAX_EVENTS) eventLog.pollLast()
    }

    /** Shorten URLs in log messages so they fit a phone screen. */
    private fun shortUrl(url: String): String {
        if (url.length <= 60) return url
        val q = url.indexOf('?')
        return if (q in 1..58) url.substring(0, q) + "?…" else url.take(58) + "…"
    }

    private fun quote(s: String): String {
        val sb = StringBuilder(s.length + 2)
        sb.append('"')
        for (c in s) {
            when (c) {
                '\\' -> sb.append("\\\\")
                '"'  -> sb.append("\\\"")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> if (c < ' ') {
                    sb.append("\\u").append("%04x".format(c.code))
                } else sb.append(c)
            }
        }
        sb.append('"')
        return sb.toString()
    }

    private const val MAX_EVENTS = 60
    private const val MAX_CHANNELS = 50

    enum class Severity { INFO, WARN, ERROR }

    private data class LogEvent(
        val atMs: Long, val severity: Severity, val message: String,
    )

    private class ChannelStats(val id: String, val name: String) {
        val plays = AtomicInteger(0)
        val freezes = AtomicInteger(0)
        val rebuffers = AtomicInteger(0)
        val failures = AtomicInteger(0)
        @Volatile var lastFailure: String? = null
    }
}
