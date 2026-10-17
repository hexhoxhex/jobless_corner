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

    /**
     * Fallback bar when nothing clears [MIN_HEADROOM].
     *
     * 1.3 is the right target when the day is healthy. On a big fixture
     * every mirror is congested at once, and insisting on 1.3 meant staying
     * on a feed stalling 37% of the time rather than moving to the best of a
     * bad set. Anything above 1.0 can at least sustain real time.
     */
    const val RELAXED_HEADROOM = 1.05f

    /** A feed whose newest chunk ends further behind real time than this
     *  is not offered as a replacement. Healthy feeds measure 0-15 s; the
     *  margin allows for a source clock that runs a little slow. */
    const val MAX_SOURCE_LAG_S = 60

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
     * Feeds seen broken while being played, and why.
     *
     * A speed probe can't tell a healthy feed from one that plays badly:
     * TNT Sports 4 UK (Roma vs Real Madrid, 2026-10-14) measured 4.9x
     * headroom — one of the best — while it re-sent chunks of the match it
     * had already sent and carried audio whose timestamps ran a third short,
     * so the player re-aligned its clock every 0.4 s (stutter, bad sound).
     * The proxy and the player report what they see; a broken feed is left
     * out of every automatic pick for [BROKEN_FOR_MS].
     */
    private val broken = ConcurrentHashMap<String, Pair<Long, String>>()
    const val BROKEN_FOR_MS = 15 * 60 * 1000L

    /** Called with (channelId, reason) when a feed is first marked broken. */
    @Volatile var onBroken: ((String, String) -> Unit)? = null

    fun markBroken(channelId: String, why: String) {
        val fresh = brokenReason(channelId) == null
        broken[channelId] = System.currentTimeMillis() to why
        if (fresh) {
            Log.w(TAG, "FEEDS ch=$channelId marked broken: $why")
            runCatching { onBroken?.invoke(channelId, why) }
        }
    }

    /** Why [channelId] was marked broken, or null when it isn't (any more). */
    fun brokenReason(channelId: String): String? =
        broken[channelId]?.takeIf { System.currentTimeMillis() - it.first < BROKEN_FOR_MS }?.second

    /** A fixture that kicked off this long ago may still be on air. */
    private const val ON_AIR_BEFORE_SEC = 3 * 60 * 60L

    /** ...and one starting this soon is close enough to count. */
    private const val ON_AIR_AFTER_SEC = 30 * 60L

    /**
     * Channels carrying what [channelId] is showing RIGHT NOW.
     *
     * This used to union every event that listed the channel, across the
     * whole day. A sports channel carries four or five different things
     * between breakfast and midnight, so "siblings" came back as the feeds
     * of unrelated fixtures — and auto-switch moved a viewer watching
     * football onto a motorsport feed, while the prober answered questions
     * about the wrong match entirely (measured 2026-09-19: probing TNT
     * Sports 1 UK during a Premier League game returned the mirrors of a
     * motorcycle race, and a viewer ended up on sailing). Only events on
     * air now can tell us anything about what the viewer is watching; when
     * none is, we know nothing and say so with an empty map.
     */
    fun siblingsOf(
        channelId: String,
        schedule: List<ScheduleEvent>,
        nowSec: Long = System.currentTimeMillis() / 1000,
    ): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        schedule.asSequence()
            .filter { ev -> ev.channels.any { it.id == channelId } }
            .filter { ev ->
                val start = ev.startUnix ?: return@filter false
                start <= nowSec + ON_AIR_AFTER_SEC &&
                    start >= nowSec - ON_AIR_BEFORE_SEC
            }
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
            .filter { brokenReason(it) == null }
            .mapNotNull { cached(it)?.result }
            .filter { it.ok && it.headroom >= minHeadroom }
            // Downloads fast but the source has stalled or fallen far behind
            // (a minute-plus behind live by its own clock): not a rescue.
            .filter { it.sourceLagSec < 0 || it.sourceLagSec <= MAX_SOURCE_LAG_S }
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
