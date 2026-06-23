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

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
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

            val desc = state.details?.description?.takeIf { it.isNotBlank() }
                ?: item.title
            Text("Summary", fontWeight = FontWeight.Bold, fontSize = 16.sp)
            Text(desc, color = TextMuted, fontSize = 13.sp)

            if (item.isSeries) {
                when {
                    state.detailLoading ->
                        CircularProgressIndicator(Modifier.size(28.dp))
                    state.details != null -> {
                        // Don't render the episode picker if the source has
                        // no seasons OR no episodes inside any season. TMDB
                        // sometimes classifies a single-release film as
                        // mediaType=tv and aoneroom answers with an empty
                        // seasons array — the old code rendered the picker
                        // anyway, so the user tapped an episode chip and
                        // got the "episode not available" error. Show
                        // an honest "no episodes available yet" line
                        // instead so the user knows it's not them.
                        val seasons = state.details?.seasons.orEmpty()
                        val hasEpisodes = seasons.any { it.episodes > 0 }
                        if (hasEpisodes) {
                            SeriesEpisodes(state, vm)
                        } else {
                            Text(
                                "No episodes available yet — the source hasn't " +
                                    "indexed this series. Try again later.",
                                color = TextMuted, fontSize = 13.sp,
                                modifier = Modifier.padding(top = 8.dp),
                            )
                        }
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SeriesEpisodes(state: UiState, vm: MainViewModel) {
    val seasons = state.details?.seasons ?: return
    if (seasons.isEmpty()) return
    val subjectId = state.details.subjectId
    var season by remember { mutableIntStateOf(seasons.first().season) }
    val current = seasons.firstOrNull { it.season == season }
    var open by remember { mutableStateOf(false) }

    // Reactive: phantom episodes (marked missing after a failed play) and
    // watched episodes both update the grid live.
    val missing by com.moviebox.tv.data.MissingEpisodeCatalog.flow.collectAsState()
    val watched by vm.watchedKeys.collectAsState()

    val rawCount = current?.episodes ?: 0
    // Episodes aoneroom CLAIMS exist (1..maxEp) minus the ones we've
    // already discovered are phantom. This is what hides Family Guy
    // S1E8/E9 once they've each failed to resolve once.
    val episodes = remember(season, rawCount, missing) {
        (1..rawCount).filter {
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
