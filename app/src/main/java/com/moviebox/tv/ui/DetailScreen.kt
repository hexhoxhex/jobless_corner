package com.moviebox.tv.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.runtime.collectAsState
import coil.compose.AsyncImage
import com.moviebox.tv.data.local.WatchHistoryEntity
import com.moviebox.tv.ui.theme.Accent
import com.moviebox.tv.ui.theme.Danger
import com.moviebox.tv.ui.theme.Gold
import com.moviebox.tv.ui.theme.SurfaceElevated
import com.moviebox.tv.ui.theme.TextMuted

@Composable
fun DetailScreen(state: UiState, vm: MainViewModel) {
    val item = state.detailItem ?: return
    val favIds by vm.favouriteIds.collectAsState()
    val isFav = favIds.contains(item.subjectId)

    // Self-heal: if the user landed here from the player's back arrow on a
    // Continue Watching resume (where details were never loaded — we went
    // straight to the player), lazily fetch them now so the episode picker
    // can render. Cheap no-op if details are already there or loading.
    androidx.compose.runtime.LaunchedEffect(item.subjectId) {
        if (state.details == null && !state.detailLoading) {
            vm.ensureDetails(item)
        }
    }

    Box(Modifier.fillMaxSize()) {
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            // Leave room at the bottom for the sticky CTA bar so the last
            // scrolling content (For You row) isn't hidden behind it.
            .padding(bottom = 96.dp),
    ) {
        Box(Modifier.fillMaxWidth().height(360.dp)) {
            AsyncImage(
                model = item.coverUrl,
                contentDescription = item.title,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
            Box(
                Modifier.fillMaxSize().background(
                    Brush.verticalGradient(
                        0f to Color(0x660B0D12),
                        0.6f to Color(0xBB0B0D12),
                        1f to Color(0xFF0B0D12),
                    )
                )
            )
            CircleIcon(
                Icons.AutoMirrored.Filled.ArrowBack,
                Modifier.align(Alignment.TopStart).statusBarsPadding().padding(12.dp),
            ) { vm.back() }
            CircleIcon(
                if (isFav) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                Modifier.align(Alignment.TopEnd).statusBarsPadding().padding(12.dp),
                tint = if (isFav) Danger else Color.White,
            ) { vm.toggleFavourite(item) }

            Column(Modifier.align(Alignment.BottomStart).padding(16.dp)) {
                Text(item.title, fontSize = 26.sp, fontWeight = FontWeight.Bold,
                    color = Color.White)
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    item.year?.let { Text("$it", color = TextMuted, fontSize = 13.sp) }
                    item.rating?.takeIf { it > 0 }?.let {
                        Text("★ %.1f".format(it), color = Gold, fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold)
                    }
                    Text(if (item.isSeries) "TV Series" else "Movie",
                        color = TextMuted, fontSize = 13.sp)
                }
            }
        }

        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            if (item.genres.isNotEmpty()) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    item.genres.forEach { Chip(it) }
                }
            }

            if (!item.isSeries) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    when (state.availability) {
                        Availability.CHECKING -> Button(
                            onClick = { /* wait for verdict */ },
                            modifier = Modifier.weight(1f).height(50.dp),
                            shape = RoundedCornerShape(12.dp),
                            enabled = false,
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp), strokeWidth = 2.dp,
                                color = Color.White,
                            )
                            Text("  Checking availability…",
                                fontWeight = FontWeight.SemiBold)
                        }
                        Availability.UNAVAILABLE -> OutlinedButton(
                            onClick = { vm.pickFromSearch() },
                            modifier = Modifier.weight(1f).height(50.dp),
                            shape = RoundedCornerShape(12.dp),
                        ) {
                            Text(
                                "Not available — pick from search",
                                color = TextMuted, fontWeight = FontWeight.SemiBold,
                            )
                        }
                        // UNKNOWN or AVAILABLE: allow play (UNKNOWN means we
                        // never checked, e.g. resumed item — keep the original
                        // behaviour rather than blocking).
                        else -> Button(
                            onClick = { vm.playMovie() },
                            modifier = Modifier.weight(1f).height(50.dp),
                            shape = RoundedCornerShape(12.dp),
                        ) {
                            Icon(Icons.Filled.PlayArrow, null)
                            Text("  Play", fontWeight = FontWeight.Bold)
                        }
                    }
                    OutlinedButton(
                        onClick = { vm.downloadMovie(item) },
                        modifier = Modifier.height(50.dp),
                        shape = RoundedCornerShape(12.dp),
                    ) { Icon(Icons.Filled.Download, "Download") }
                }
            }

            // Only show the Summary section if the H5 detail actually
            // returned a description. The old fallback (`?: item.title`)
            // printed the show's name as its own summary — visible as
            // "Summary: House of the Dragon" on HBO-tier titles where
            // aoneroom's H5 detail returns description="".
            val desc = state.details?.description?.takeIf { it.isNotBlank() }
            if (desc != null) {
                Text("Summary", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                Text(desc, color = TextMuted, fontSize = 13.sp)
            }

            if (item.isSeries) {
                when {
                    state.detailLoading ->
                        com.moviebox.tv.ui.components.LottieLoader(size = 48.dp)
                    state.details != null -> {
                        // Don't render the episode picker if the source has
                        // no seasons OR no episodes inside any season. TMDB
                        // sometimes classifies a single-release film as
                        // mediaType=tv and aoneroom answers with an empty
                        // seasons array — the old code rendered the picker
                        // anyway, so the user tapped an episode chip and
                        // got the "episode not available" error.
                        //
                        // BUT: if availability is AVAILABLE the show IS
                        // playable via the subject-level fallback added in
                        // v0.1.90 (HBO-tier titles like House of the Dragon
                        // play as a single resource even though aoneroom
                        // returns seasons=[]). Don't tell the user "no
                        // episodes available" when they're about to tap
                        // a green "Play" button that works.
                        val seasons = state.details?.seasons.orEmpty()
                        val hasEpisodes = seasons.any { it.episodes > 0 }
                        when {
                            hasEpisodes -> SeriesEpisodes(state, vm)
                            // Only show the "no episodes" warning when the
                            // precheck has POSITIVELY confirmed the show is
                            // unavailable. CHECKING (in-flight) and AVAILABLE
                            // (subject-level fallback worked) stay silent —
                            // the sticky Play CTA does the talking, and
                            // contradicting it with "no episodes" body text
                            // confuses the user.
                            state.availability == Availability.UNAVAILABLE -> Text(
                                "No episodes available yet — the source hasn't " +
                                    "indexed this series. Try again later.",
                                color = TextMuted, fontSize = 13.sp,
                                modifier = Modifier.padding(top = 8.dp),
                            )
                            else -> { /* CHECKING or AVAILABLE — silent */ }
                        }
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
        }
    }
    // Sticky bottom CTA — MovieWay's iconic green Play/Download button
    // anchored at the bottom edge, always visible while the user scrolls
    // through the rest of the detail page. Same gradient scrim as their
    // app so the button reads cleanly over any content behind it.
    Box(
        Modifier
            .align(Alignment.BottomCenter)
            .fillMaxWidth()
            .background(
                androidx.compose.ui.graphics.Brush.verticalGradient(
                    0f to androidx.compose.ui.graphics.Color.Transparent,
                    0.4f to com.moviebox.tv.ui.theme.Bg.copy(alpha = 0.8f),
                    1f to com.moviebox.tv.ui.theme.Bg,
                )
            )
            .padding(horizontal = 16.dp, vertical = 14.dp),
    ) {
        Button(
            onClick = {
                if (item.isSeries) {
                    val s1 = state.details?.seasons?.firstOrNull()?.season ?: 1
                    vm.playEpisode(s1, 1, restoreResume = true)
                } else vm.playMovie()
            },
            modifier = Modifier.fillMaxWidth().height(54.dp),
            shape = RoundedCornerShape(28.dp),
            colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                containerColor = com.moviebox.tv.ui.theme.Accent,
                contentColor = androidx.compose.ui.graphics.Color.Black,
            ),
        ) {
            Icon(Icons.Filled.PlayArrow, null)
            val label = when {
                state.availability == Availability.UNAVAILABLE -> "Not available — pick from search"
                item.isSeries -> "Play S${state.details?.seasons?.firstOrNull()?.season ?: 1}E1"
                else -> "Play"
            }
            Text("  $label", fontWeight = FontWeight.Bold, fontSize = 16.sp)
        }
    }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SeriesEpisodes(state: UiState, vm: MainViewModel) {
    val allSeasons = state.details?.seasons ?: return
    if (allSeasons.isEmpty()) return
    val subjectId = state.details.subjectId
    // Once the background enumeration has run, a season with a non-null
    // but EMPTY realEpisodes is entirely phantom (Family Guy S11/S20/S21
    // — maxEp lied, aoneroom has no files). Drop those from the picker.
    // While enumeration is still in flight (realEpisodes == null) we keep
    // every season so the user isn't staring at a shrinking list.
    val seasons = allSeasons.filter { it.realEpisodes == null || it.realEpisodes.isNotEmpty() }
    if (seasons.isEmpty()) return
    var season by remember { mutableIntStateOf(seasons.first().season) }
    // If the currently-selected season just got dropped as phantom, snap
    // back to the first surviving season.
    if (seasons.none { it.season == season }) season = seasons.first().season
    val current = seasons.firstOrNull { it.season == season }
    var open by remember { mutableStateOf(false) }

    // Reactive: phantom episodes (marked missing after a failed play) and
    // watched episodes both update the grid live.
    val missing by com.moviebox.tv.data.MissingEpisodeCatalog.flow.collectAsState()
    val watched by vm.watchedKeys.collectAsState()

    val rawCount = current?.episodes ?: 0
    // Episode list, most-authoritative source first:
    //   1. current.realEpisodes — the actual file listing aoneroom
    //      returned (populated by the background enumeration walk).
    //      Phantom trailing episodes are already absent here.
    //   2. fallback 1..maxEp while the walk is still running or if it
    //      couldn't complete.
    // Either way we ALSO subtract anything in MissingEpisodeCatalog
    // (episodes that passed enumeration but failed at play time) — belt
    // and suspenders.
    val episodes = remember(season, rawCount, current?.realEpisodes, missing) {
        val base = current?.realEpisodes ?: (1..rawCount).toList()
        base.filter {
            com.moviebox.tv.data.MissingEpisodeCatalog.isPresent(subjectId, season, it)
        }
    }
    val count = episodes.size

    // Season is "complete" when every present episode is finished.
    val seasonComplete = remember(season, episodes, watched) {
        episodes.isNotEmpty() && episodes.all { ep ->
            WatchHistoryEntity.keyOf(subjectId, season, ep) in watched
        }
    }

    Row(verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Box {
            Chip("Season $season ▾") { open = true }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                seasons.forEach { s ->
                    DropdownMenuItem(text = { Text("Season ${s.season}") }, onClick = {
                        season = s.season; open = false
                    })
                }
            }
        }
        Text("$count episodes", color = TextMuted, fontSize = 12.sp)
        if (seasonComplete) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.CheckCircle, null, Modifier.size(14.dp),
                    tint = Color(0xFF36C26B))
                Text(" Watched", color = Color(0xFF36C26B), fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold)
            }
        }
    }

    state.detailItem?.let { dItem ->
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            OutlinedButton(onClick = { vm.downloadSeason(dItem, season, rawCount) }) {
                Icon(Icons.Filled.Download, null, Modifier.size(18.dp))
                Text(" Download season", fontSize = 12.sp)
            }
            Text("long-press to download an episode", color = TextMuted,
                fontSize = 11.sp)
        }
    }
    LazyVerticalGrid(
        columns = GridCells.Fixed(5),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.height(((count / 5 + 1) * 52).dp.coerceAtMost(360.dp)),
    ) {
        items(episodes) { ep ->
            val dItem = state.detailItem
            val isWatched = WatchHistoryEntity.keyOf(subjectId, season, ep) in watched
            Box(
                Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(if (isWatched) Color(0xFF1B3326) else SurfaceElevated)
                    .combinedClickable(
                        // restoreResume=true — from the episode list inside
                        // details, tapping an episode means "continue where
                        // I left off". Different intent from the Next ⏭
                        // button which restarts the next episode at 00:00.
                        onClick = { vm.playEpisode(season, ep, restoreResume = true) },
                        onLongClick = {
                            if (dItem != null) vm.downloadEpisode(dItem, season, ep)
                        },
                    )
                    .padding(vertical = 12.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text("$ep", fontWeight = FontWeight.SemiBold,
                    color = if (isWatched) Color(0xFF8FE3B0) else Color.Unspecified)
                if (isWatched) {
                    Icon(
                        Icons.Filled.CheckCircle, "watched",
                        Modifier.size(13.dp).align(Alignment.TopEnd),
                        tint = Color(0xFF36C26B),
                    )
                }
            }
        }
    }
}

@Composable
fun Chip(label: String, onClick: (() -> Unit)? = null) {
    Box(
        Modifier
            .clip(RoundedCornerShape(20.dp))
            .background(SurfaceElevated)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 14.dp, vertical = 7.dp),
    ) {
        Text(label, fontSize = 12.sp, fontWeight = FontWeight.Medium, color = Color.White)
    }
}

@Composable
private fun CircleIcon(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    modifier: Modifier = Modifier,
    tint: Color = Color.White,
    onClick: () -> Unit,
) {
    Box(
        modifier
            .size(40.dp)
            .clip(CircleShape)
            .background(Color(0x88000000))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, null, tint = tint, modifier = Modifier.size(22.dp)) }
}
