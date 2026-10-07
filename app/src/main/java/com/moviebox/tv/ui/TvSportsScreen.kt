package com.moviebox.tv.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.NotificationsActive
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.moviebox.tv.data.live.Channel
import com.moviebox.tv.data.live.FixtureParser
import com.moviebox.tv.data.live.LeagueCatalog
import com.moviebox.tv.data.live.ScheduleEvent
import com.moviebox.tv.data.live.SportCatalog
import com.moviebox.tv.ui.theme.Accent
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/*
 * The live sports guide on TV (phase 3, mock-up "Sports and live guide").
 *
 * The old schedule was a stack of collapsible category boxes, each event a
 * line of text over a wrap of up to 100 small green channel chips; finding
 * what was on meant scrolling through feed names. Now:
 *  - filter chips by sport (All, Football, Motor sports, ... as present);
 *  - "Live now": a row of big cards — competition, LIVE and how long it has
 *    been on, the two sides, the feed it will open and how many more exist.
 *    OK plays it; if that feed dies, the player's own auto-failover moves to
 *    the event's other feeds;
 *  - "Later today": one line per event — time, competition, fixture, feed —
 *    with a bell that sets a reminder for THAT match (20 min before, via the
 *    existing follows/alarm machinery).
 * Titles are shown without the feed's emoji (Glyphs.plain).
 */

private val CardBg = Color(0xFF161C26)
private val RowBg = Color(0xFF121720)
private val LiveRed = Color(0xFFC8313A)
private val Meta = Color(0xFFC9D0DA)
private val Dim = Color(0xFFA7B0BE)

private const val START = 32

private val GENERIC_CATEGORY = Regex("^\\s*(upcoming events?|other( sports| events)?|events?|all sports)\\s*$", RegexOption.IGNORE_CASE)

internal class Classified(
    val e: ScheduleEvent,
    val sport: String,
    val league: String,
    val sides: List<String>,
    val primary: Channel?,
    val feeds: Int,
)

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun TvSportsSchedule(state: UiState, vm: MainViewModel) {
    val followKeys by vm.followKeys.collectAsState()
    // Re-evaluate what is "live" as the clock moves.
    val nowSec by produceState(System.currentTimeMillis() / 1000) {
        while (true) {
            delay(60_000)
            value = System.currentTimeMillis() / 1000
        }
    }
    val all = remember(state.liveSchedule, state.liveChannels) {
        classifySchedule(state.liveSchedule, state.liveChannels)
    }
    val sports = remember(all) {
        val present = all.map { it.sport }.toSet()
        listOf("All") + SportCatalog.ORDER.filter { it in present } +
            (if ("Other" in present) listOf("Other") else emptyList())
    }
    var filter by rememberSaveable { mutableStateOf("All") }
    if (filter !in sports) filter = "All"
    val shown = all.filter { filter == "All" || it.sport == filter }
    // Sport before shows: under "All" the live row used to open on NCIS and
    // The Voice simply because they started first. Within a sport, by start.
    val liveNow = liveNowOf(shown, nowSec)
    val later = shown.filter { eventStatusAt(it.e, nowSec) == EventStatus.NEXT }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(top = 4.dp, bottom = 40.dp),
        verticalArrangement = Arrangement.spacedBy(22.dp),
    ) {
        item(key = "filters") {
            EdgeSafeRow(START.dp, 48.dp) {
                LazyRow(
                    Modifier.focusRestorer(),
                    contentPadding = PaddingValues(start = START.dp, end = 48.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    items(sports, key = { it }) { s ->
                        FilterPill(s, selected = s == filter) { filter = s }
                    }
                }
            }
        }
        if (liveNow.isNotEmpty()) {
            item(key = "live") {
                Section("Live now", badge = "${liveNow.size} LIVE") {
                    LazyRow(
                        Modifier.focusRestorer(),
                        contentPadding = PaddingValues(start = START.dp, end = 48.dp),
                        horizontalArrangement = Arrangement.spacedBy(18.dp),
                    ) {
                        items(liveNow, key = { it.key() }) { c ->
                            LiveCard(c, nowSec) { c.primary?.let { vm.playScheduleChannel(it.id) } }
                        }
                    }
                }
            }
        }
        if (later.isNotEmpty()) {
            item(key = "later-h") {
                Text(
                    "Later today", color = Color.White, fontSize = 21.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(start = START.dp),
                )
            }
            items(later, key = { "later-" + it.e.title + "|" + it.e.startUnix }) { c ->
                val key = FixtureParser.canonical(Glyphs.plain(c.e.title))
                LaterRow(
                    c = c,
                    reminded = key in followKeys,
                    onPlay = { c.primary?.let { vm.playScheduleChannel(it.id) } },
                    onBell = { vm.toggleEventReminder(c.e) },
                )
            }
        }
        if (liveNow.isEmpty() && later.isEmpty()) {
            item(key = "empty") {
                Text(
                    if (state.liveSchedule.isEmpty()) "Loading the schedule…"
                    else "Nothing scheduled in this sport right now.",
                    color = Dim, fontSize = 16.sp,
                    modifier = Modifier.padding(start = START.dp, top = 24.dp),
                )
            }
        }
    }
}

/** Every event that has not ended, with its sport, competition, sides and
 *  the feed OK would open. Shared by this guide and the home "Live now" row. */
internal fun classifySchedule(schedule: List<ScheduleEvent>, channels: List<Channel>): List<Classified> {
    val byId = channels.associateBy { it.id }
    val now = System.currentTimeMillis() / 1000
    return schedule
        .filter { e -> e.startUnix.let { s -> s == null || s + SCHEDULE_GRACE_SEC >= now } }
        // The feed lists some events twice, under two categories (MotoGP as
        // both "Upcoming events" and "Motorsport", each with its own
        // channels). As two cards they shared one list key and crashed the
        // guide. One card, every channel from both listings, under the
        // listing whose category says more than "upcoming"/"other".
        .groupBy { it.title.trim() to it.startUnix }
        .values
        .map { same ->
            if (same.size == 1) same[0] else {
                val best = same.firstOrNull { SportCatalog.sportFor(it.title, it.category) != null }
                    ?: same[0]
                best.copy(channels = same.flatMap { it.channels }.distinctBy { it.id })
            }
        }
        .sortedBy { it.startUnix ?: Long.MAX_VALUE }
        .map { e ->
            val sport = SportCatalog.sportFor(e.title, e.category) ?: "Other"
            val listed = Glyphs.plain(
                if (sport == SportCatalog.FOOTBALL) {
                    LeagueCatalog.groupFor(e.title, e.category).ifBlank { e.category }
                } else e.category,
            )
            // "Upcoming events" / "Other" are the feed's catch-alls, not a
            // competition; the sport says more (MotoGP read "UPCOMING EVENTS").
            val league = if (sport != "Other" && GENERIC_CATEGORY.containsMatchIn(listed)) sport else listed
            val fx = FixtureParser.parse(e.title)
            val available = e.channels.mapNotNull { byId[it.id] }
            Classified(
                e = e,
                sport = sport,
                league = league,
                sides = if (fx.isFixture) fx.sides.map { Glyphs.plain(it) } else emptyList(),
                primary = available.firstOrNull(),
                feeds = (available.size - 1).coerceAtLeast(0),
            )
        }
}

/** What is on now, in the order the guide shows it. */
internal fun liveNowOf(events: List<Classified>, nowSec: Long): List<Classified> =
    events.filter { eventStatusAt(it.e, nowSec) == EventStatus.LIVE }
        .sortedWith(
            // Watchable first: a card that says "No feed available" should
            // not be the first thing in the row.
            compareBy<Classified> { if (it.primary == null) 1 else 0 }
                .thenBy { if (it.sport == "Other") Int.MAX_VALUE else SportCatalog.rank(it.sport) }
                .thenBy { it.e.startUnix ?: Long.MAX_VALUE },
        )

/** Stable identity of an event for list keys and focus memory. */
internal fun Classified.key(): String = "live-" + e.title + "|" + e.startUnix

private fun eventStatusAt(e: ScheduleEvent, nowSec: Long): EventStatus {
    val start = e.startUnix ?: return EventStatus.NEXT
    return when {
        start > nowSec -> EventStatus.NEXT
        nowSec - start <= SCHEDULE_GRACE_SEC -> EventStatus.LIVE
        else -> EventStatus.ENDED
    }
}

/** Section heading + content; entering it brings heading and content into
 *  view together (the focused card alone would leave the heading above). */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Section(title: String, badge: String?, content: @Composable () -> Unit) {
    val whole = remember { BringIntoViewRequester() }
    val scope = rememberCoroutineScope()
    Column(
        Modifier
            .bringIntoViewRequester(whole)
            .onFocusChanged { if (it.hasFocus) scope.launch { whole.bringIntoView() } },
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(Modifier.padding(start = START.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(title, color = Color.White, fontSize = 21.sp, fontWeight = FontWeight.Bold)
            if (badge != null) {
                Spacer(Modifier.width(12.dp))
                Text(
                    badge, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(LiveRed)
                        .padding(horizontal = 8.dp, vertical = 3.dp),
                )
            }
        }
        EdgeSafeRow(START.dp, 48.dp, content)
    }
}

@Composable
private fun FilterPill(label: String, selected: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(20.dp)
    Box(
        Modifier
            .tvFocusRing(shape, scaleOnFocus = 1.08f)
            .height(38.dp)
            .clip(shape)
            .background(if (selected) Color(0xFFF2F5F8) else Color(0x1AF2F5F8))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = ownFocusIndication(),
                onClick = onClick,
            )
            .padding(horizontal = 18.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            color = if (selected) Color(0xFF0B0E13) else Color(0xFFD5DBE3),
            fontSize = 15.sp,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.SemiBold,
        )
    }
}

@Composable
internal fun LiveCard(
    c: Classified,
    nowSec: Long,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(14.dp)
    val mins = c.e.startUnix?.let { ((nowSec - it) / 60).coerceAtLeast(0) }
    Column(
        modifier
            .width(300.dp)
            .height(156.dp)
            .tvFocusable(shape = shape, scaleOnFocus = 1.06f, borderColor = Color.White, onClick = onClick)
            .clip(shape)
            .background(CardBg)
            .padding(16.dp),
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                c.league.uppercase(), color = Meta, fontSize = 12.sp, fontWeight = FontWeight.Bold,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                if (mins != null) "LIVE · ${mins}′" else "LIVE",
                color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.clip(RoundedCornerShape(5.dp)).background(LiveRed)
                    .padding(horizontal = 7.dp, vertical = 2.dp),
            )
        }
        if (c.sides.size == 2) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                c.sides.forEach {
                    Text(
                        it, color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        } else {
            Text(
                Glyphs.plain(c.e.title), color = Color.White, fontSize = 17.sp,
                fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis,
            )
        }
        FeedLine(c)
    }
}

@Composable
private fun FeedLine(c: Classified) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        val p = c.primary
        if (p == null) {
            Text("No feed available", color = Dim, fontSize = 13.sp)
            return@Row
        }
        Text(
            p.displayName, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
            maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(Color(0x1AF2F5F8))
                .padding(horizontal = 8.dp, vertical = 3.dp),
        )
        if (c.feeds > 0) {
            Spacer(Modifier.width(8.dp))
            Text(
                "+ ${c.feeds} more " + if (c.feeds == 1) "feed" else "feeds",
                color = Dim, fontSize = 13.sp, maxLines = 1,
            )
        }
    }
}

@Composable
private fun LaterRow(
    c: Classified,
    reminded: Boolean,
    onPlay: () -> Unit,
    onBell: () -> Unit,
) {
    val shape = RoundedCornerShape(12.dp)
    Row(
        Modifier.fillMaxWidth().padding(start = START.dp, end = 48.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(
            Modifier
                .weight(1f)
                .tvFocusRing(shape, scaleOnFocus = 1.0f, inside = true)
                .clip(shape)
                .background(RowBg)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = ownFocusIndication(),
                    onClick = onPlay,
                )
                .padding(horizontal = 18.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                formatEventTime(c.e), color = Color.White, fontSize = 18.sp,
                fontWeight = FontWeight.Bold, modifier = Modifier.width(96.dp),
            )
            Text(
                c.league.uppercase(), color = Meta, fontSize = 12.sp, fontWeight = FontWeight.Bold,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.width(190.dp),
            )
            Spacer(Modifier.width(12.dp))
            Text(
                if (c.sides.size == 2) "${c.sides[0]}  vs  ${c.sides[1]}" else Glyphs.plain(c.e.title),
                color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(12.dp))
            Box(Modifier.width(250.dp)) { FeedLine(c) }
        }
        if (c.e.startUnix != null) {
            val bellShape = RoundedCornerShape(12.dp)
            Box(
                Modifier
                    .tvFocusRing(bellShape)
                    .size(46.dp)
                    .clip(bellShape)
                    .background(if (reminded) Accent else Color(0x1AF2F5F8))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = ownFocusIndication(),
                        onClick = onBell,
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    if (reminded) Icons.Rounded.NotificationsActive else Icons.Rounded.Notifications,
                    if (reminded) "Reminder set" else "Remind me",
                    tint = if (reminded) Color(0xFF06120B) else Color.White,
                    modifier = Modifier.size(24.dp),
                )
            }
        }
    }
}
