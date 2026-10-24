package com.moviebox.tv.data.live

import android.util.Log

/**
 * How fast a live feed's SOURCE is delivering the match: seconds of video
 * added to its playlist per second of real time, and how much of the match
 * it skipped between pieces.
 *
 * The speed probe can't see this. It measures how fast a chunk downloads,
 * and a feed can download fast while being fed slowly: Hub Premier on
 * 2026-10-17 (Everton vs Chelsea) added a new 6 s chunk only every ~9 s —
 * 68% of real time — while each chunk downloaded in under a second. The TV's
 * buffer ran dry about once a minute (six freezes of 5-15 s in four
 * minutes) until the feed was finally caught re-sending parts of the match.
 * TNT Sports 3 on 10-15 did the same at ~85% for 3.5 minutes. Measured
 * from the PC the same day, healthy mirrors of that match ran at 1.03-1.06.
 *
 * Fed with every fresh upstream playlist the proxy fetches for the playing
 * channel. Counts only chunks that appear while we watch (the first
 * playlist's window is history), over a sliding [WINDOW_MS], and starts
 * again whenever observation is interrupted or the server changes.
 */
object SourceRate {

    private const val TAG = "LiveDiag"

    /** Observation window, and the shortest observation judged for speed.
     *  Was 90 s: one 26 s pause of an otherwise steady feed (TNT Sports 1 UK,
     *  2026-10-24, the best of the match's feeds) read as "77% of real time"
     *  and the app moved the viewer to the worst one. Over 180 s that pause
     *  reads 86%; a feed slow all the time still reads under 80%. */
    private const val WINDOW_MS = 180_000L

    /** A playlist that adds nothing for this long is a pause long enough to
     *  eat most of the player's ~25 s cushion. */
    private const val LONG_PAUSE_MS = 20_000L
    /** This many long pauses within [PAUSES_WINDOW_MS] and the match freezes
     *  again and again: Hub Premier 1 stopped for 27 s and 22 s within 21 s
     *  of each other (measured from the PC the same afternoon). One is a
     *  server hiccup the cushion rides out. */
    private const val LONG_PAUSES_TO_BREAK = 2
    private const val PAUSES_WINDOW_MS = 180_000L

    /** Below this share of real time the feed cannot be watched without
     *  freezing; healthy feeds measure 0.95-1.1 over 90 s. */
    private const val SLOW_RATE = 0.8

    /** Skipped match time within [HOLES_WINDOW_MS] that marks a feed as
     *  dropping parts of the match. */
    private const val HOLES_LIMIT_S = 15.0
    private const val HOLES_WINDOW_MS = 120_000L

    /** A gap in observation longer than this restarts the measurement. */
    private const val MAX_SILENCE_MS = 20_000L

    private const val LOG_EVERY_MS = 30_000L

    private class Track(val host: String, val startedMs: Long) {
        /** seq -> (duration s, first seen ms) for chunks that appeared while watching. */
        val added = LinkedHashMap<Long, Pair<Double, Long>>()
        /** Every seq seen, with its PROGRAM-DATE-TIME (s) and duration, to spot skips. */
        val pdt = HashMap<Long, Pair<Double, Double>>()
        /** Every seq seen, stamped or not. New chunks used to be found
         *  through [pdt] alone, so a feed without PROGRAM-DATE-TIME (Hub
         *  Premier 3 on hls.lshls.st, 2026-10-24) never had any, read
         *  "0% of real time" and was given up on. */
        val seen = HashSet<Long>()
        val holes = ArrayDeque<Pair<Long, Double>>()
        /** The newest chunk seen, when it appeared, and the long pauses
         *  between new chunks (by sequence number: not every feed stamps
         *  PROGRAM-DATE-TIME). */
        var newestSeq = -1L
        var lastAdvanceMs = startedMs
        val pauses = ArrayDeque<Pair<Long, Long>>()
        var lastNoteMs = startedMs
        var lastLogMs = startedMs
        var reported = false
    }

    private val tracks = java.util.concurrent.ConcurrentHashMap<String, Track>()

    data class Reading(val rate: Double, val holesS: Double, val windowS: Long)

    /**
     * Record a fresh upstream playlist for [channelId] served from [host].
     * Returns why the feed should be given up on, once, or null.
     */
    fun note(channelId: String, host: String, body: String): String? {
        val now = System.currentTimeMillis()
        var t = tracks[channelId]
        if (t == null || t.host != host || now - t.lastNoteMs > MAX_SILENCE_MS) {
            t = Track(host, now)
            tracks[channelId] = t
        }
        val first = t.seen.isEmpty()
        t.lastNoteMs = now

        var seq: Long? = null
        var pdt: Double? = null
        var extinf = 0.0
        for (raw in body.lineSequence()) {
            val line = raw.trim()
            when {
                line.startsWith("#EXT-X-MEDIA-SEQUENCE:") ->
                    seq = line.substringAfter(':').trim().toLongOrNull()
                line.startsWith("#EXT-X-PROGRAM-DATE-TIME:") ->
                    pdt = parsePdt(line.substringAfter(':'))
                line.startsWith("#EXTINF:") ->
                    extinf = line.substringAfter(':').substringBefore(',').toDoubleOrNull() ?: 0.0
                line.isNotEmpty() && !line.startsWith("#") -> {
                    val s = seq ?: return null
                    if (t.seen.add(s)) {
                        if (!first) t.added[s] = extinf to now
                        // A chunk whose start is well after where the previous
                        // one ended: that stretch of the match never arrives.
                        val prev = t.pdt[s - 1]
                        if (pdt != null && prev != null) {
                            val gap = pdt - (prev.first + prev.second)
                            if (gap > 1.0 && gap < 600.0) t.holes.addLast(now to gap)
                        }
                        if (pdt != null) t.pdt[s] = pdt to extinf
                    }
                    pdt = pdt?.plus(extinf)
                    seq = s + 1
                    extinf = 0.0
                }
            }
        }
        // A new chunk appeared: was the wait for it a long pause?
        val newest = seq?.minus(1)
        if (newest != null) {
            if (t.newestSeq < 0) {
                t.newestSeq = newest
                t.lastAdvanceMs = now
            } else if (newest > t.newestSeq) {
                val waited = now - t.lastAdvanceMs
                if (waited >= LONG_PAUSE_MS) {
                    t.pauses.addLast(now to waited)
                    Log.i(TAG, "SRCRATE ch=$channelId source paused ${waited / 1000}s")
                }
                t.newestSeq = newest
                t.lastAdvanceMs = now
            }
        }
        while (t.pauses.isNotEmpty() && now - t.pauses.first().first > PAUSES_WINDOW_MS) t.pauses.removeFirst()
        // Keep the bookkeeping bounded to the window.
        t.added.entries.removeIf { now - it.value.second > WINDOW_MS }
        while (t.holes.isNotEmpty() && now - t.holes.first().first > HOLES_WINDOW_MS) t.holes.removeFirst()
        if (t.pdt.size > 200) {
            val keep = t.pdt.keys.sorted().takeLast(100).toSet()
            t.pdt.keys.retainAll(keep)
        }
        if (t.seen.size > 200) {
            val keep = t.seen.sorted().takeLast(100).toSet()
            t.seen.retainAll(keep)
        }

        val watched = now - t.startedMs
        val reading = reading(t, now)
        if (now - t.lastLogMs >= LOG_EVERY_MS && watched >= LOG_EVERY_MS) {
            t.lastLogMs = now
            Log.i(TAG, "SRCRATE ch=$channelId rate=${"%.2f".format(reading.rate)} " +
                "skipped=${"%.0f".format(reading.holesS)}s over ${reading.windowS}s host=$host")
        }
        if (t.reported) return null
        val why = when {
            t.pauses.size >= LONG_PAUSES_TO_BREAK ->
                "its source keeps stopping for ${t.pauses.maxOf { it.second } / 1000} s " +
                    "at a time, so the match freezes"
            watched < WINDOW_MS -> null
            reading.rate < SLOW_RATE ->
                "its source sends only ${(reading.rate * 100).toInt()}% of real time, " +
                    "so the match freezes"
            reading.holesS >= HOLES_LIMIT_S ->
                "it keeps skipping parts of the match " +
                    "(${"%.0f".format(reading.holesS)} s in two minutes)"
            else -> null
        }
        if (why != null) {
            t.reported = true
            Log.w(TAG, "SRCRATE ch=$channelId giving up on this feed: $why")
        }
        return why
    }

    /** The latest reading for [channelId], or null while it is too short. */
    fun current(channelId: String): Reading? {
        val t = tracks[channelId] ?: return null
        val now = System.currentTimeMillis()
        if (now - t.startedMs < WINDOW_MS || now - t.lastNoteMs > MAX_SILENCE_MS) return null
        return reading(t, now)
    }

    private fun reading(t: Track, now: Long): Reading {
        val span = minOf(WINDOW_MS, now - t.startedMs).coerceAtLeast(1L)
        val content = t.added.values.sumOf { it.first }
        return Reading(
            rate = content / (span / 1000.0),
            holesS = t.holes.sumOf { it.second },
            windowS = span / 1000,
        )
    }

    /** Seconds since the epoch for an ISO-8601 PROGRAM-DATE-TIME. */
    fun parsePdt(value: String): Double? = runCatching {
        java.time.OffsetDateTime.parse(
            value.trim().replace(Regex("""([+-]\d{2})(\d{2})$"""), "$1:$2"),
        ).toInstant().toEpochMilli() / 1000.0
    }.getOrNull()
}
