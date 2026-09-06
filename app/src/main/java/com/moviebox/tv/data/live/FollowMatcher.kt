package com.moviebox.tv.data.live

import com.moviebox.tv.data.local.FollowEntity
import com.moviebox.tv.data.local.FollowKind

/**
 * Decides which schedule events a user's follows apply to.
 *
 * The matching rule is deliberately strict for [FollowKind.TEAM]: a follow
 * matches only when its canonical key equals a *parsed side* of the
 * fixture. Substring matching on the raw title is what makes a Manchester
 * United fan get pinged for Manchester City — measured on a real
 * 701-event payload, "Manchester" as a substring hit 2 events where the
 * side-equality rule hit exactly the 1 the club was actually playing in.
 *
 * Competitions and shows are looser (a contains test), because those
 * names are published with varying decoration — "England - Premier
 * League" vs "Premier League" — and a false positive there costs the user
 * an extra row in a list rather than a wrong alert.
 */
object FollowMatcher {

    data class Match(
        val follow: FollowEntity,
        val event: ScheduleEvent,
        /** The side that matched, for "Man United vs Arsenal" phrasing. */
        val opponent: String?,
    )

    /** True if [follow] applies to [event]. */
    fun matches(follow: FollowEntity, event: ScheduleEvent): Boolean =
        matchOf(follow, event) != null

    fun matchOf(follow: FollowEntity, event: ScheduleEvent): Match? {
        val fixture = FixtureParser.parse(event.title)
        return when (FollowKind.from(follow.kind)) {
            // Try the precise rule first and widen only if it misses, so a
            // typed name lands on the right thing without the user having
            // to tell us what kind of thing it is. Order matters: a side
            // match beats a competition match beats a title match.
            FollowKind.AUTO ->
                asTeam(follow, event, fixture)
                    ?: asCompetition(follow, event, fixture)
                    ?: asShow(follow, event)

            FollowKind.TEAM -> asTeam(follow, event, fixture)
            FollowKind.COMPETITION -> asCompetition(follow, event, fixture)
            FollowKind.SHOW -> asShow(follow, event)
        }
    }

    /** Strict: the follow must BE one of the sides, not merely appear in
     *  the title. This is what keeps a Manchester United follow off
     *  Manchester City fixtures. */
    private fun asTeam(
        follow: FollowEntity,
        event: ScheduleEvent,
        fixture: FixtureParser.Fixture,
    ): Match? {
        val sides = fixture.sides.map { it to FixtureParser.canonical(it) }
        val hitIndex = sides.indexOfFirst { it.second == follow.key }
        if (hitIndex < 0) return null
        return Match(
            follow = follow,
            event = event,
            // Only meaningful for a real two-sided fixture; a single-side
            // "fixture" is a standalone programme.
            opponent = if (fixture.isFixture) sides[1 - hitIndex].first else null,
        )
    }

    private fun asCompetition(
        follow: FollowEntity,
        event: ScheduleEvent,
        fixture: FixtureParser.Fixture,
    ): Match? {
        if (follow.key.isBlank()) return null
        val comp = FixtureParser.canonical(fixture.competition)
        val cat = FixtureParser.canonical(event.category)
        return if (comp.contains(follow.key) || cat.contains(follow.key)) {
            Match(follow, event, null)
        } else {
            null
        }
    }

    private fun asShow(follow: FollowEntity, event: ScheduleEvent): Match? {
        if (follow.key.isBlank()) return null
        val title = FixtureParser.canonical(event.title)
        return if (title.contains(follow.key)) Match(follow, event, null) else null
    }

    /**
     * Every (follow, event) pair in [events] that a reminder could be
     * built from, soonest kickoff first.
     *
     * Events without a [ScheduleEvent.startUnix] are skipped: there is no
     * trustworthy instant to count back from, and the raw "time" string is
     * UK-local, so scheduling off it would fire at the wrong moment for
     * anyone outside that timezone. They still show up in the followed
     * list — just without an alarm.
     */
    fun upcoming(
        follows: List<FollowEntity>,
        events: List<ScheduleEvent>,
        nowSec: Long,
        horizonSec: Long = DEFAULT_HORIZON_SEC,
    ): List<Match> {
        if (follows.isEmpty()) return emptyList()
        val out = ArrayList<Match>()
        for (e in events) {
            val start = e.startUnix ?: continue
            if (start < nowSec - LIVE_GRACE_SEC) continue      // long over
            if (start > nowSec + horizonSec) continue          // too far out
            for (f in follows) {
                matchOf(f, e)?.let { out.add(it) }
            }
        }
        return out.sortedBy { it.event.startUnix ?: Long.MAX_VALUE }
    }

    /** Stable per-follow-per-kickoff identity for the fired ledger. */
    fun eventKey(follow: FollowEntity, event: ScheduleEvent): String =
        follow.key + "|" + (event.startUnix ?: 0L)

    /** Matches that already kicked off stay listed this long (3h ≈ a
     *  match plus stoppage/overtime), matching the schedule pane's own
     *  on-air window so the two surfaces agree about what's still on. */
    const val LIVE_GRACE_SEC = 3 * 60 * 60L

    /** How far ahead to look. The catalog publishes roughly a day. */
    const val DEFAULT_HORIZON_SEC = 36 * 60 * 60L
}
