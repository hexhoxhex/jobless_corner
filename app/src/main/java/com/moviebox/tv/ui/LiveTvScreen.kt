package com.moviebox.tv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Star
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.moviebox.tv.data.live.Channel
import com.moviebox.tv.data.live.ScheduleEvent
import com.moviebox.tv.data.local.ChannelHealthEntity
import com.moviebox.tv.ui.theme.Accent
import com.moviebox.tv.ui.theme.Surface
import com.moviebox.tv.ui.theme.SurfaceElevated
import com.moviebox.tv.ui.theme.TextMuted
import com.moviebox.tv.ui.theme.TextPrimary

@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
fun LiveTvScreen(state: UiState, vm: MainViewModel) {
    val isTv = LocalIsTv.current

    Column(Modifier.fillMaxSize()) {
        // Sub-tab switcher (Channels | Schedule), styled like a pill bar.
        Row(
            Modifier.fillMaxWidth().padding(
                horizontal = if (isTv) 32.dp else 16.dp, vertical = 10.dp,
            ),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            SubTabPill(
                "Channels", state.liveSubTab == LiveSubTab.CHANNELS,
            ) { vm.selectLiveSubTab(LiveSubTab.CHANNELS) }
            SubTabPill(
                "Schedule", state.liveSubTab == LiveSubTab.SCHEDULE,
            ) { vm.selectLiveSubTab(LiveSubTab.SCHEDULE) }
        }

        when {
            state.liveLoading && state.liveChannels.isEmpty() ->
                Box(Modifier.fillMaxSize(), Alignment.Center) {
                    CircularProgressIndicator(color = Accent)
                }
            state.liveError != null && state.liveChannels.isEmpty() ->
                Box(Modifier.fillMaxSize(), Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("Could not load Live TV",
                            color = TextPrimary, fontWeight = FontWeight.SemiBold)
                        Text(state.liveError, color = TextMuted, fontSize = 12.sp)
                        Box(
                            Modifier.padding(top = 12.dp).clip(RoundedCornerShape(10.dp))
                                .background(Accent).clickable { vm.loadLive(force = true) }
                                .padding(horizontal = 18.dp, vertical = 8.dp),
                        ) { Text("Retry", color = Color.White, fontWeight = FontWeight.SemiBold) }
                    }
                }
            state.liveSubTab == LiveSubTab.CHANNELS -> ChannelsView(state, vm, isTv)
            else -> ScheduleView(state, vm, isTv)
        }
    }
}

// ---------------- Channels grid ----------------

@Composable
private fun ChannelsView(state: UiState, vm: MainViewModel, isTv: Boolean) {
    val q = state.liveQuery.trim().lowercase()
    val group = state.liveGroup
    val filtered = state.liveChannels.filter { c ->
        (group == null || c.group == group) &&
        (q.isEmpty() || c.name.lowercase().contains(q))
    }
    val groups = remember(state.liveChannels) {
        state.liveChannels.mapNotNull { it.group }.distinct()
    }

    // On TV, D-pad focus on a BasicTextField pops the on-screen keyboard
    // immediately, which the user hits before they even reach the channel
    // grid below. Two-state pattern: a focusable preview chip that just
    // shows the current query, and a separate edit mode entered only on OK
    // press. BackHandler exits edit mode without leaving the LIVE tab.
    var liveSearchEditing by remember { mutableStateOf(false) }
    val searchFocus = remember { FocusRequester() }
    if (liveSearchEditing) {
        BackHandler(enabled = true) { liveSearchEditing = false }
        // When we enter edit mode, request focus into the text field — this
        // is what triggers the IME on TV.
        LaunchedEffect(Unit) { runCatching { searchFocus.requestFocus() } }
    }

    Column(Modifier.fillMaxSize()) {
        // Search bar — preview-vs-edit state.
        Box(
            Modifier.fillMaxWidth()
                .padding(horizontal = if (isTv) 32.dp else 16.dp)
                .padding(bottom = 8.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(SurfaceElevated)
                .tvFocusable(
                    shape = RoundedCornerShape(12.dp),
                    onClick = { liveSearchEditing = true },
                )
                .padding(horizontal = 12.dp, vertical = 10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Search, null, tint = TextMuted,
                    modifier = Modifier.size(18.dp))
                if (liveSearchEditing) {
                    BasicTextField(
                        value = state.liveQuery,
                        onValueChange = vm::onLiveQuery,
                        singleLine = true,
                        textStyle = TextStyle(color = TextPrimary, fontSize = 14.sp),
                        cursorBrush = androidx.compose.ui.graphics.SolidColor(Accent),
                        modifier = Modifier
                            .fillMaxWidth().padding(start = 8.dp)
                            .focusRequester(searchFocus),
                        decorationBox = { inner ->
                            if (state.liveQuery.isEmpty()) {
                                Text("Type to search…",
                                    color = TextMuted, fontSize = 14.sp)
                            }
                            inner()
                        },
                    )
                } else {
                    // Read-only preview: shows current query (or placeholder)
                    // without claiming D-pad focus or popping the IME.
                    Text(
                        text = state.liveQuery.ifEmpty { "Search channels — press OK" },
                        color = if (state.liveQuery.isEmpty()) TextMuted else TextPrimary,
                        fontSize = 14.sp,
                        modifier = Modifier.fillMaxWidth().padding(start = 8.dp),
                    )
                }
            }
        }

        // Group filter chips
        LazyRow(
            contentPadding = PaddingValues(
                horizontal = if (isTv) 32.dp else 16.dp,
            ),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(bottom = 10.dp),
        ) {
            item { GroupChip("All", group == null) { vm.selectLiveGroup(null) } }
            items(groups) { g ->
                GroupChip(g.shortGroupLabel(), group == g) { vm.selectLiveGroup(g) }
            }
        }

        // Channel grid - cards with logo + name + live dot
        val columns = if (isTv) 6 else 3
        // CRASH FIX: tapping a group chip (e.g. "USA") shrinks `filtered` and
        // crashed the app with IllegalStateException "Release should only be
        // called once" from LazyLayoutPinnableItem. The cause is focusRestorer
        // holding a reference to a pinned item that disappears when the list
        // shrinks. Wrapping the grid in key(group, q) forces a fresh
        // LazyVerticalGrid (and a fresh focusRestorer state) whenever the
        // filter changes — the buggy pinnable item gets released cleanly
        // with the old composition instead of being touched twice.
        key(group, q) {
            LazyVerticalGrid(
                // focusRestorer so D-pad up from a row lands back at the same
                // column it was on. Without this, vertical navigation on TV
                // makes the cursor jump to the leftmost tile of each row
                // which feels broken.
                modifier = Modifier
                    .focusGroup()
                    .focusRestorer(),
                columns = GridCells.Fixed(columns),
                contentPadding = PaddingValues(
                    start = if (isTv) 32.dp else 16.dp,
                    end = if (isTv) 32.dp else 16.dp,
                    bottom = if (isTv) 32.dp else 24.dp,
                ),
                horizontalArrangement = Arrangement.spacedBy(if (isTv) 14.dp else 10.dp),
                verticalArrangement = Arrangement.spacedBy(if (isTv) 14.dp else 10.dp),
            ) {
                items(filtered, key = { it.id }) { ch ->
                    ChannelCard(
                        ch = ch,
                        health = state.channelHealth[ch.id],
                        sweep = state.channelSweep[ch.id],
                        isFavourite = ch.id in state.liveFavouriteIds,
                        onClick = { vm.playChannel(ch) },
                        onLongClick = { vm.toggleLiveFavourite(ch) },
                    )
                }
            }
        }
    }
}

@Composable
private fun ChannelCard(
    ch: Channel,
    health: ChannelHealthEntity?,
    /** Last CI-side deep-sweep result for this channel. Null = no signal
     *  (haven't been swept yet, or fetch failed). Status "down" gets a
     *  grey "Often offline" overlay but the card stays clickable — the
     *  sweep runs from a different network than the user's device and
     *  false positives are common, so we never block playback on it. */
    sweep: com.moviebox.tv.data.live.HealthEntry? = null,
    isFavourite: Boolean = false,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
) {
    Column(
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .background(Surface)
            // Brighter, thicker focus ring so the indicator reads from across
            // a TV room. The default 3 dp Accent (purple) was lost on channel
            // logos with dark backgrounds — users reported "can't see where
            // the remote is at" when scrolling the grid.
            .tvFocusable(
                shape = RoundedCornerShape(12.dp),
                scaleOnFocus = 1.10f,
                borderWidth = 4.dp,
                borderColor = Color.White,
                onClick = onClick,
                onLongClick = onLongClick,
            )
            .padding(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier.fillMaxWidth().aspectRatio(1.5f)
                .clip(RoundedCornerShape(8.dp))
                .then(
                    if (!ch.logo.isNullOrBlank()) Modifier.background(Color(0xFF0A0C12))
                    else Modifier.background(initialsGradient(ch.displayName))
                ),
            contentAlignment = Alignment.Center,
        ) {
            if (!ch.logo.isNullOrBlank()) {
                AsyncImage(
                    model = ch.logo, contentDescription = ch.name,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize().padding(10.dp),
                )
            } else {
                Text(
                    initialsOf(ch.displayName),
                    color = Color.White,
                    fontWeight = FontWeight.Black,
                    fontSize = 20.sp,
                )
            }
            // Tiny "LIVE" pill in the corner.
            Box(
                Modifier.align(Alignment.TopEnd).padding(6.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(Color(0xCCE5484D))
                    .padding(horizontal = 5.dp, vertical = 1.dp),
            ) {
                Text("LIVE",
                    color = Color.White, fontSize = 9.sp, fontWeight = FontWeight.Bold)
            }
            // "Often offline" badge — the CI sweep saw this channel fail
            // its full playback chain on the most recent run. Bottom-end
            // corner so it sits opposite the existing UNSTABLE badge
            // (bottom-start) and below the LIVE pill. Channel stays
            // clickable — this is an advisory not a block, because the
            // sweep runs from a different network and false positives
            // are common.
            if (sweep?.status == "down") {
                Box(
                    Modifier.align(Alignment.BottomEnd).padding(6.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(Color(0xCC6E6E6E))
                        .padding(horizontal = 5.dp, vertical = 1.dp),
                ) {
                    Text(
                        "OFTEN OFFLINE",
                        color = Color.White, fontSize = 9.sp,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
            // Star overlay top-left when this channel is in the user's
            // favourites. Long-press anywhere on the card toggles it.
            if (isFavourite) {
                Icon(
                    Icons.Filled.Star,
                    contentDescription = "Favourite",
                    tint = Color(0xFFFFC857),
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(6.dp)
                        .size(16.dp),
                )
            }
            // Bottom-left "Often unstable" badge when this channel has
            // bounced through the recovery cascade multiple times.
            // Amber so it reads as "warning, not blocking" — the channel
            // is still tappable; user just won't be surprised by 110 s
            // of "Reconnecting…" before it bounces back to the grid.
            if (health?.unstableHint == true) {
                Box(
                    Modifier.align(Alignment.BottomStart).padding(6.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(Color(0xCCD97706))
                        .padding(horizontal = 5.dp, vertical = 1.dp),
                ) {
                    Text("UNSTABLE",
                        color = Color.White, fontSize = 9.sp,
                        fontWeight = FontWeight.Bold)
                }
            }
        }
        Text(
            ch.displayName,
            color = TextPrimary, fontSize = 12.sp, fontWeight = FontWeight.Medium,
            maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 6.dp),
        )
    }
}

@Composable
private fun GroupChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.clip(RoundedCornerShape(20.dp))
            .background(if (selected) Accent else SurfaceElevated)
            .tvFocusable(
                shape = RoundedCornerShape(20.dp),
                onClick = onClick,
            )
            .padding(horizontal = 14.dp, vertical = 7.dp),
    ) {
        Text(
            label,
            color = if (selected) Color.White else TextMuted,
            fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
private fun SubTabPill(label: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.clip(RoundedCornerShape(20.dp))
            .background(if (selected) Accent else SurfaceElevated)
            .tvFocusable(
                shape = RoundedCornerShape(20.dp),
                onClick = onClick,
            )
            .padding(horizontal = 18.dp, vertical = 9.dp),
    ) {
        Text(
            label,
            color = if (selected) Color.White else TextMuted,
            fontWeight = FontWeight.SemiBold, fontSize = 13.sp,
        )
    }
}

// ---------------- Schedule list ----------------

@Composable
private fun ScheduleView(state: UiState, vm: MainViewModel, isTv: Boolean) {
    val byId = remember(state.liveChannels) { state.liveChannels.associateBy { it.id } }
    val collapsed = remember { mutableStateMapOf<String, Boolean>() }
    val grouped = remember(state.liveSchedule) {
        // Drop events that have already ended (best estimate: ~1h after
        // start). dlhd doesn't publish runtimes, so we treat the next-hour
        // mark as the cutoff — anything older than that has definitely
        // aired. Without this filter the schedule shows yesterday's morning
        // shows alongside tonight's events because the scraper still hands
        // them up while they're inside the 3 h grace window.
        val nowSec = System.currentTimeMillis() / 1000
        val live = state.liveSchedule.filter {
            val s = it.startUnix
            s == null || s + 3_600 >= nowSec
        }
        val buckets = LinkedHashMap<String, MutableList<ScheduleEvent>>()
        for (e in live) buckets.getOrPut(e.category) { mutableListOf() }.add(e)
        // Sort inside each bucket by the authoritative startUnix when set;
        // unparseable entries sink to the end.
        buckets.map { (k, v) -> k to v.sortedBy { it.startUnix ?: Long.MAX_VALUE } }
    }

    LazyColumn(
        contentPadding = PaddingValues(
            start = if (isTv) 32.dp else 16.dp,
            end = if (isTv) 32.dp else 16.dp,
            bottom = if (isTv) 32.dp else 24.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(grouped) { (cat, events) ->
            val isCollapsed = collapsed[cat] == true
            Column(
                Modifier.clip(RoundedCornerShape(12.dp)).background(Surface),
            ) {
                Row(
                    Modifier.fillMaxWidth()
                        .clickable { collapsed[cat] = !isCollapsed }
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(cat, color = TextPrimary, fontWeight = FontWeight.SemiBold,
                        fontSize = 14.sp, modifier = Modifier.weight(1f))
                    Box(
                        Modifier.clip(RoundedCornerShape(8.dp))
                            .background(SurfaceElevated)
                            .padding(horizontal = 8.dp, vertical = 2.dp),
                    ) {
                        Text("${events.size}", color = TextMuted, fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold)
                    }
                }
                if (!isCollapsed) {
                    events.forEach { e -> EventRow(e, byId, vm) }
                }
            }
        }
    }
}

@Composable
private fun EventRow(
    e: ScheduleEvent,
    byId: Map<String, Channel>,
    vm: MainViewModel,
) {
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(formatEventTime(e), color = Color(0xFF9CC8FF), fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.padding(end = 10.dp))
            Text(e.title, color = TextPrimary, fontSize = 13.sp,
                fontWeight = FontWeight.Medium, maxLines = 2,
                overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        }
        FlowRow(
            Modifier.fillMaxWidth().padding(top = 6.dp, start = 60.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            // Was take(6). A FIFA World Cup row in the schedule can list 100+
            // mirror channels (dlhd.pk dumps every country feed under one
            // event) — clipping to 6 left users staring at "+98 hidden"
            // worth of regional broadcasts. Show them all; FlowRow wraps
            // onto additional lines and the lazy schedule list scrolls.
            e.channels.forEach { ref ->
                val real = byId[ref.id]
                val available = real != null
                Box(
                    Modifier.clip(RoundedCornerShape(6.dp))
                        .background(if (available) Accent else Color(0x33FFFFFF))
                        .clickable(enabled = available) {
                            if (available) vm.playScheduleChannel(ref.id)
                        }
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (available) {
                            Icon(Icons.Filled.PlayArrow, null, tint = Color.White,
                                modifier = Modifier.size(11.dp))
                        }
                        Text(ref.name,
                            color = if (available) Color.White else TextMuted,
                            fontSize = 10.sp, fontWeight = FontWeight.SemiBold,
                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(start = if (available) 4.dp else 0.dp),
                        )
                    }
                }
            }
        }
    }
}

/** Render an event's time in the viewer's local timezone. dlhd publishes
 *  the raw `time` field in UK time, so a Kenyan viewer would otherwise see
 *  a 2-hour-off label. When [ScheduleEvent.startUnix] is available we
 *  format it through `java.text.DateFormat` so the device's locale and TZ
 *  do the right thing (e.g. "21:00" in EAT vs "19:00" in UK). The raw
 *  [ScheduleEvent.time] is kept as a fallback for old catalog dumps that
 *  pre-date the start_unix field. If the event is tomorrow in the local
 *  TZ (common for late-evening UK shows that wrap past midnight in EAT),
 *  we prefix with "Tom" so the user isn't confused about same-day-vs-next.
 */
private fun formatEventTime(e: ScheduleEvent): String {
    val s = e.startUnix ?: return e.time
    val ms = s * 1000L
    val now = java.util.Calendar.getInstance().apply { timeInMillis = System.currentTimeMillis() }
    val evt = java.util.Calendar.getInstance().apply { timeInMillis = ms }
    val sameDay = now.get(java.util.Calendar.YEAR) == evt.get(java.util.Calendar.YEAR) &&
        now.get(java.util.Calendar.DAY_OF_YEAR) == evt.get(java.util.Calendar.DAY_OF_YEAR)
    val fmt = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault())
    val label = fmt.format(java.util.Date(ms))
    return if (sameDay) label else "Tom $label"
}

/** Shortens "USA (DADDY LIVE)" -> "USA", "Soccer Coverage" -> "Soccer Coverage". */
private fun String.shortGroupLabel(): String {
    val idx = indexOf(" (")
    return if (idx > 0) substring(0, idx) else this
}

/** Acronym up to 3 chars from the channel name. "Arena Sport 2 Serbia" -> "AS2",
 *  "ABC" -> "ABC", "Eurosport" -> "EUR". Beats `name.take(3)` which produced
 *  "ARE", "AST", "EUR" for unrelated names — looked broken. */
private fun initialsOf(name: String): String {
    val words = name.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
    return when {
        words.isEmpty() -> "?"
        words.size == 1 -> words[0].take(3).uppercase()
        else -> words.take(3)
            .map { it.first() }
            .joinToString("")
            .uppercase()
    }
}

/** Deterministic two-color diagonal gradient seeded by the channel name.
 *  Same channel always picks the same palette so card backgrounds stay
 *  stable across recompositions. Hues are saturated but not garish. */
private fun initialsGradient(seed: String): Brush {
    val hash = seed.hashCode().toUInt().toLong()
    val palette = listOf(
        0xFF1f3a5f to 0xFF2d5a87,  // navy
        0xFF3a1f5f to 0xFF6d3a87,  // purple
        0xFF1f5f3a to 0xFF3a8758,  // teal-green
        0xFF5f1f3a to 0xFF87385c,  // crimson
        0xFF5f4a1f to 0xFF87683a,  // amber
        0xFF1f4a5f to 0xFF3a6878,  // steel-blue
        0xFF4a1f5f to 0xFF6a3a87,  // violet
        0xFF5f1f1f to 0xFF873a3a,  // brick
    )
    val (a, b) = palette[(hash % palette.size).toInt()]
    return Brush.linearGradient(
        listOf(Color(a), Color(b)),
    )
}
