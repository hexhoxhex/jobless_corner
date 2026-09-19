package com.moviebox.tv.data.live

/**
 * Chooses which channel to actually tune to for a given fixture.
 *
 * The naive choice — `event.channels.first()` — is what makes a reminder
 * open a channel that isn't showing the game. The catalog lists a channel
 * against every event it will carry that day, so a generic feed like
 * "Sky Sports Main Event" or "TSN5" appears under several fixtures at
 * once. Measured on the live schedule: a British GT event's first-listed
 * channel was Sky Sports F1 UK, which was carrying **2 concurrent
 * events**, and TSN5 was carrying 3. Tuning to one of those is a coin
 * flip on which match you get.
 *
 * A channel that carries exactly one event in the window is almost
 * certainly showing that event, so "fewest concurrent events" is the
 * strongest available signal for "this feed is actually on our game".
 */
object EventChannelPicker {

    /** Overlap window either side of a fixture. Matches the schedule's own
     *  3h on-air grace, so an event that started two hours ago still counts
     *  as competing for the channel. */
    private const val OVERLAP_SEC = 3 * 60 * 60L

    /**
     * How many events each channel is carrying around [aroundSec]. Built
     * once per pick so a 60-channel fixture doesn't rescan the schedule
     * per candidate.
     */
    fun concurrencyMap(schedule: List<ScheduleEvent>, aroundSec: Long): Map<String, Int> {
        val counts = HashMap<String, Int>()
        for (e in schedule) {
            val start = e.startUnix ?: continue
            if (start < aroundSec - OVERLAP_SEC || start > aroundSec + OVERLAP_SEC) continue
            for (c in e.channels) counts[c.id] = (counts[c.id] ?: 0) + 1
        }
        return counts
    }

    /**
     * Channels for [event], best first.
     *
     * Order of preference:
     *  1. fewest concurrent events — most likely to actually be on our game;
     *  2. English commentary, when we can tell;
     *  3. best measured headroom, when the feed has been probed;
     *  4. the catalog's own order, as a stable tiebreak.
     *
     * Note the deliberate ordering: being on the RIGHT MATCH beats being on
     * the sturdiest stream. A flawless feed of the wrong game is a failure.
     */
    /**
     * Does the fixture's label for this channel contradict the channel
     * directory?
     *
     * The source disagrees with itself. Measured 2026-09-19: its fixture
     * list attached id 125 to a Premier League match under the label
     * "Astro Premier League", while its OWN channel directory calls id 125
     * "Astro Supersport 3" — and that channel was showing a drama, not
     * football. Where the two names cannot be reconciled, the fixture's
     * claim about that feed has already been shown to be unreliable.
     *
     * Deliberately forgiving: one name containing the other ("USA Network"
     * vs "USA Network HD") is a spelling difference, not a contradiction.
     */
    fun conflictsWithCatalog(
        ref: ScheduleChannelRef,
        catalogNames: Map<String, String>,
    ): Boolean {
        val catalog = catalogNames[ref.id]?.let(LeagueCatalog::normalise)
            ?.takeIf { it.isNotBlank() } ?: return false
        val listed = LeagueCatalog.normalise(ref.name).takeIf { it.isNotBlank() }
            ?: return false
        return !catalog.contains(listed) && !listed.contains(catalog)
    }

    fun rank(
        event: ScheduleEvent,
        schedule: List<ScheduleEvent>,
        concurrency: Map<String, Int>? = null,
        /** id -> the channel directory's name. Empty = check skipped. */
        catalogNames: Map<String, String> = emptyMap(),
    ): List<ScheduleChannelRef> {
        val around = event.startUnix ?: return event.channels
        val conc = concurrency ?: concurrencyMap(schedule, around)
        val original = event.channels.withIndex().associate { (i, c) -> c.id to i }
        return event.channels.sortedWith(
            // A contradicted label comes FIRST in the ordering because it is
            // the strongest "this is not your game" signal measured so far —
            // stronger than concurrency, which only says a channel is busy.
            compareBy<ScheduleChannelRef> {
                if (conflictsWithCatalog(it, catalogNames)) 1 else 0
            }
                .thenBy { conc[it.id] ?: 1 }
                .thenBy { ChannelLanguage.preference(it.name) }
                .thenByDescending { FeedRanker.cached(it.id)?.result?.headroom ?: 0f }
                .thenBy { original[it.id] ?: 0 }
        )
    }

    /** The single channel a reminder should open. */
    fun best(
        event: ScheduleEvent,
        schedule: List<ScheduleEvent>,
        concurrency: Map<String, Int>? = null,
        catalogNames: Map<String, String> = emptyMap(),
    ): ScheduleChannelRef? =
        rank(event, schedule, concurrency, catalogNames).firstOrNull()
}
