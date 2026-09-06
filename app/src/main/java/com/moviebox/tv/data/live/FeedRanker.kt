package com.moviebox.tv.data.live

import android.util.Log
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Knows which feeds carry the same fixture, how healthy each one measured,
 * and which one to move to when the current feed can't keep up.
 *
 * A big match is mirrored across a lot of channels — Everton vs Man United
 * measured 40, and 52 were reachable from the one being watched — and the
 * feed the schedule lists first is regularly the worst of them. Measured on
 * that fixture: Sky Sports Premier League UK demanded 13380 kbps and failed
 * outright, while USA Network carried the same match at 4640 kbps with 2x
 * headroom. Picking the right mirror is the only quality lever that exists
 * here, because every master this catalog serves has a single variant.
 */
object FeedRanker {

    private const val TAG = "LiveDiag"

    data class Ranked(
        val result: LiveStreamProxy.ProbeResult,
        val atMs: Long,
    )

    private val results = ConcurrentHashMap<String, Ranked>()
    private val probing = AtomicBoolean(false)

    @Volatile private var probedSoFar = 0
    @Volatile private var probeTotal = 0

    /** Results older than this are stale — a feed that measured well an
     *  hour ago tells you nothing about now. */
    private const val FRESH_MS = 10 * 60 * 1000L

    /** Below this there is no margin: the feed will bleed its buffer on any
     *  hiccup. 1.3 was chosen from measurement — 0.98 and 0.85 feeds both
     *  stalled, 1.75 and 2.00 held a flat 20 s buffer. */
    const val MIN_HEADROOM = 1.3f

    fun cached(channelId: String): Ranked? =
        results[channelId]?.takeIf { System.currentTimeMillis() - it.atMs < FRESH_MS }

    fun snapshot(): Map<String, Ranked> = results.toMap()

    fun isProbing(): Boolean = probing.get()

    fun progress(): String =
        if (!probing.get()) "" else "$probedSoFar/$probeTotal"

    fun record(r: LiveStreamProxy.ProbeResult) {
        results[r.channelId] = Ranked(r, System.currentTimeMillis())
    }

    /**
     * Every channel carrying whatever [channelId] is carrying. Union across
     * the events that list it, so a channel showing two fixtures returns
     * the mirrors of both.
     */
    fun siblingsOf(channelId: String, schedule: List<ScheduleEvent>): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        schedule.filter { ev -> ev.channels.any { it.id == channelId } }
            .forEach { ev -> ev.channels.forEach { out[it.id] = it.name } }
        return out
    }

    /**
     * Probe every id in turn, recording as it goes so a caller can watch
     * partial results.
     *
     * Sequential on purpose. Probing in parallel would multiply the
     * bandwidth this is trying not to steal from live playback — the whole
     * point is that checking is cheap enough to run while someone watches.
     * Re-entrant calls are dropped rather than queued.
     */
    suspend fun probeAll(
        ids: List<String>,
        probe: suspend (String) -> LiveStreamProxy.ProbeResult,
    ) {
        if (!probing.compareAndSet(false, true)) {
            Log.i(TAG, "FEEDS probe already running — ignoring re-entry")
            return
        }
        probedSoFar = 0
        probeTotal = ids.size
        try {
            for (id in ids) {
                val r = runCatching { probe(id) }.getOrNull()
                if (r != null) record(r)
                probedSoFar++
            }
            val ok = results.values.count { it.result.ok }
            Log.i(TAG, "FEEDS probed ${ids.size}, $ok usable")
        } finally {
            probing.set(false)
        }
    }

    /**
     * Best alternative among [candidates], by measured headroom.
     *
     * Only considers results we actually measured and that cleared
     * [MIN_HEADROOM]; returns null rather than guessing when nothing
     * qualifies, so a caller never switches to a feed that is merely
     * untested.
     */
    fun best(
        candidates: Collection<String>,
        excluding: Set<String> = emptySet(),
        minHeadroom: Float = MIN_HEADROOM,
        /** id -> display name, for the language preference. */
        names: Map<String, String> = emptyMap(),
    ): LiveStreamProxy.ProbeResult? {
        val usable = candidates.asSequence()
            .filter { it !in excluding }
            .mapNotNull { cached(it)?.result }
            .filter { it.ok && it.headroom >= minHeadroom }
            .toList()
        if (usable.isEmpty()) return null
        // Every feed here already clears the headroom bar, so they are all
        // watchable. Order by LANGUAGE first: the healthiest mirror of a
        // Premier League match is routinely "DAZN1 Spain" or "Canal+ Extra
        // 1 Poland", and silently moving an English viewer onto Spanish
        // commentary is its own failure. Headroom breaks the tie inside a
        // language group, so we still take the sturdiest English feed.
        return usable.minWithOrNull(
            compareBy<LiveStreamProxy.ProbeResult> {
                ChannelLanguage.preference(names[it.channelId])
            }.thenByDescending { it.headroom }
        )
    }

    /** Forget everything — used when the user changes fixture entirely. */
    fun clear() {
        results.clear()
        probedSoFar = 0
        probeTotal = 0
    }
}
