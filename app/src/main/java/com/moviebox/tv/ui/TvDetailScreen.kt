package com.moviebox.tv.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Replay
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.BiasAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import kotlinx.coroutines.launch
import com.moviebox.tv.data.Details
import com.moviebox.tv.data.EpisodeMeta
import com.moviebox.tv.data.Item
import com.moviebox.tv.data.local.WatchHistoryEntity
import com.moviebox.tv.ui.theme.Accent
import com.moviebox.tv.ui.theme.Bg
import com.moviebox.tv.ui.theme.Gold
import com.moviebox.tv.ui.theme.SurfaceElevated
import com.moviebox.tv.ui.theme.TextMuted

/*
 * The title page on TV (phase 3, mock-up "Series detail").
 *
 * Reported: "the designs on the movie when i click a movie also needs to be
 * worked on". The phone layout was being stretched onto the TV: a 360 dp
 * banner, the title at 26 sp, genres as chips, a five-column grid of bare
 * episode numbers, a second "Play" bar pinned to the bottom, and content
 * running to the screen edges.
 *
 * Built to the android-tv-compose skill:
 *  - full-bleed backdrop with a left + bottom cinematic scrim, text block
 *    inside the 48 dp safe area;
 *  - ONE primary action, focused when the page opens: Resume (with what is
 *    left) when there is a saved position, else Play / Play S1 E1;
 *  - secondary actions on one row, sentence case, white focus ring + 1.1x;
 *  - seasons as tabs that switch on focus, episodes as 16:9 cards with the
 *    TMDB still, name and runtime (plain "Episode N" when TMDB has none),
 *    watched / in-progress shown on the card;
 *  - CalmScroll so moving between rows never swings the page;
 *  - no on-screen back button (the remote's BACK does it).
 *
 * The phone keeps [DetailScreen] exactly as it was.
 */

private val Ink = Color(0xFF06120B)
private val Meta = Color(0xFFC9D0DA)
private val Body = Color(0xFFD5DBE3)
private val Dim = Color(0xFFA7B0BE)
private val Sep = Color(0xFF5D6675)
private val Glass = Color(0x24F2F5F8)

private const val SAFE_START = 56

/** Where "Resume" picks up: the newest unfinished history row for this
 *  title (matched by id OR title, since aoneroom rotates subject ids). */
private data class ResumePoint(
    val season: Int,
    val episode: Int,
    val minutesLeft: Int?,
)

private fun resumePoint(
    history: Collection<WatchHistoryEntity>,
    item: Item,
    details: Details?,
): ResumePoint? {
    val ids = setOfNotNull(item.subjectId, details?.subjectId)
    val norm = item.title.trim().lowercase().replace(Regex("[^a-z0-9]+"), "")
    val row = history.asSequence()
        .filter {
            it.subjectId in ids ||
                it.title.trim().lowercase().replace(Regex("[^a-z0-9]+"), "") == norm
        }
        .filter { !it.finished && it.positionMs > 30_000 }
        .filter { it.durationMs <= 0 || it.progress < 0.92f }
        .maxByOrNull { it.updatedAt }
        ?: return null
    val left = if (row.durationMs > 0) {
        ((row.durationMs - row.positionMs) / 60_000L).toInt().coerceAtLeast(1)
    } else null
    return ResumePoint(row.season, row.episode, left)
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun TvDetailScreen(state: UiState, vm: MainViewModel) {
    val item = state.detailItem ?: return
    val d = state.details
    val favIds by vm.favouriteIds.collectAsState()
    val isFav = item.subjectId in favIds
    val isSeries = d?.takeIf { it.seasons.isNotEmpty() }?.isSeries ?: item.isSeries
    val history by vm.historyByKey.collectAsState()
    val watched by vm.watchedKeys.collectAsState()
    val episodeMeta by vm.episodeMeta.collectAsState()
    val missing by com.moviebox.tv.data.MissingEpisodeCatalog.flow.collectAsState()
    var showTrailer by remember { mutableStateOf(false) }

    // Landed here from a Continue Watching resume with no details loaded.
    LaunchedEffect(item.subjectId) {
        if (state.details == null && !state.detailLoading) vm.ensureDetails(item)
    }

    val resume = remember(history, item.subjectId, d?.subjectId) {
        resumePoint(history.values, item, d)
    }

    // Seasons that really have files (phantom ones dropped once enumerated).
    val seasons = d?.seasons.orEmpty()
        .filter { s -> s.realEpisodes?.isNotEmpty() ?: (s.episodes > 0) }
    var season by remember(item.subjectId) {
        mutableIntStateOf(
            resume?.season?.takeIf { s -> s > 0 } ?: seasons.firstOrNull()?.season ?: 1,
        )
    }
    if (seasons.isNotEmpty() && seasons.none { it.season == season }) {
        season = seasons.first().season
    }
    val current = seasons.firstOrNull { it.season == season }
    val subjectId = d?.subjectId ?: item.subjectId
    val episodes = remember(season, current, missing) {
        val base = current?.realEpisodes ?: (1..(current?.episodes ?: 0)).toList()
        base.filter {
            com.moviebox.tv.data.MissingEpisodeCatalog.isPresent(subjectId, season, it)
        }
    }
    val tmdbId = d?.tmdbId
    LaunchedEffect(tmdbId, season, isSeries) {
        if (tmdbId != null && isSeries) vm.loadEpisodeMeta(tmdbId, season)
    }
    val metaByEp: Map<Int, EpisodeMeta> = remember(episodeMeta, tmdbId, season) {
        tmdbId?.let { episodeMeta["$it|$season"] }.orEmpty().associateBy { it.number }
    }

    val primaryFocus = remember { FocusRequester() }
    var hasFocus by remember { mutableStateOf(false) }
    TvInitialFocus(primaryFocus, key = item.subjectId) { hasFocus }

    val backdrop = d?.backdropUrl ?: item.backdropUrl ?: d?.posterUrl ?: item.coverUrl

    CompositionLocalProvider(LocalBringIntoViewSpec provides CalmScroll) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Bg)
                .onFocusChanged { hasFocus = it.hasFocus },
        ) {
            Backdrop(backdrop, item.title)

            LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(top = 44.dp, bottom = 40.dp),
                verticalArrangement = Arrangement.spacedBy(26.dp),
            ) {
                item(key = "header") {
                    Header(
                        state = state, vm = vm, item = item, d = d,
                        isSeries = isSeries, isFav = isFav, resume = resume,
                        firstSeason = seasons.firstOrNull()?.season,
                        firstEpisode = seasons.firstOrNull()
                            ?.let { it.realEpisodes?.firstOrNull() ?: 1 },
                        primaryFocus = primaryFocus,
                        onTrailer = { showTrailer = true },
                    )
                }
                if (isSeries && seasons.isNotEmpty()) {
                    item(key = "seasons") {
                        SeasonTabs(
                            seasons = seasons.map { it.season },
                            selected = season,
                            note = buildString {
                                append("${episodes.size} episodes")
                                current?.resolutions?.maxOrNull()?.let { append("  •  ${it}p") }
                            },
                            onSelect = { season = it },
                        )
                    }
                    item(key = "episodes") {
                        EpisodeRow(
                            episodes = episodes,
                            season = season,
                            subjectId = subjectId,
                            metaByEp = metaByEp,
                            history = history,
                            watched = watched,
                            fallbackImage = backdrop,
                            onPlay = { ep -> vm.playEpisode(season, ep, restoreResume = true) },
                            onDownload = { ep -> vm.downloadEpisode(item, season, ep) },
                        )
                    }
                } else if (isSeries && state.detailLoading) {
                    item(key = "eps-loading") {
                        Text(
                            "Loading episodes…", color = Dim, fontSize = 15.sp,
                            modifier = Modifier.padding(start = SAFE_START.dp),
                        )
                    }
                }
                val cast = d?.cast.orEmpty()
                if (cast.isNotEmpty()) {
                    item(key = "cast") {
                        CastRow(cast) { name -> vm.searchFor(name) }
                    }
                }
            }

            val trailerId = d?.trailerYouTubeId
            if (showTrailer && trailerId != null) {
                TrailerOverlay(youTubeId = trailerId, onClose = { showTrailer = false })
            }
        }
    }
}

@Composable
private fun Backdrop(url: String?, title: String) {
    val ctx = LocalContext.current
    Box(Modifier.fillMaxWidth().fillMaxHeight(0.68f)) {
        if (url != null) {
            AsyncImage(
                model = remember(url) {
                    ImageRequest.Builder(ctx).data(url).size(1280, 720).crossfade(300).build()
                },
                contentDescription = title,
                contentScale = ContentScale.Crop,
                // Keep the subject (usually upper right of a backdrop) in
                // frame; the text block covers the left.
                alignment = BiasAlignment(0.4f, -0.4f),
                modifier = Modifier.fillMaxSize(),
            )
        }
        Box(
            Modifier.fillMaxSize().background(
                Brush.horizontalGradient(
                    0f to Bg,
                    0.30f to Bg.copy(alpha = 0.93f),
                    0.58f to Bg.copy(alpha = 0.50f),
                    0.80f to Bg.copy(alpha = 0.08f),
                    1f to Color.Transparent,
                ),
            ),
        )
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(0.55f to Color.Transparent, 1f to Bg),
            ),
        )
    }
}

@Composable
private fun Header(
    state: UiState,
    vm: MainViewModel,
    item: Item,
    d: Details?,
    isSeries: Boolean,
    isFav: Boolean,
    resume: ResumePoint?,
    firstSeason: Int?,
    firstEpisode: Int?,
    primaryFocus: FocusRequester,
    onTrailer: () -> Unit,
) {
    Column(
        Modifier.padding(start = SAFE_START.dp, end = 48.dp).widthIn(max = 600.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            item.title,
            fontSize = 40.sp, lineHeight = 44.sp,
            fontWeight = FontWeight.Black, color = Color.White,
            maxLines = 2, overflow = TextOverflow.Ellipsis,
        )
        MetaLine(item, d, isSeries)

        val desc = d?.description?.takeIf { it.isNotBlank() } ?: item.overview
        when {
            desc != null -> Text(
                desc, color = Body, fontSize = 15.sp, lineHeight = 22.sp,
                maxLines = 4, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = 520.dp),
            )
            state.detailLoading -> SkeletonLines()
        }

        InfoLine(d)

        Spacer(Modifier.height(2.dp))
        Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val primary = primaryAction(state, vm, item, isSeries, resume, firstSeason, firstEpisode)
            PillButton(
                label = primary.label,
                icon = primary.icon,
                primary = true,
                busy = primary.busy,
                modifier = Modifier.focusRequester(primaryFocus),
                onClick = primary.onClick,
            )
            if (resume != null && state.availability != Availability.UNAVAILABLE) {
                PillButton("Start over", Icons.Rounded.Replay) {
                    if (isSeries) vm.playEpisode(resume.season, resume.episode, restoreResume = false)
                    else vm.playMovieFromStart()
                }
            }
            if (d?.trailerYouTubeId != null) {
                PillButton("Trailer", Icons.Rounded.Movie, onClick = onTrailer)
            }
            if (!isSeries) {
                IconAction(Icons.Rounded.Download, "Download") { vm.downloadMovie(item) }
            }
            IconAction(
                if (isFav) Icons.Rounded.Check else Icons.Rounded.Add,
                if (isFav) "Remove from my list" else "Add to my list",
            ) { vm.toggleFavourite(item) }
        }
    }
}

private class PrimaryAction(
    val label: String,
    val icon: ImageVector,
    val busy: Boolean,
    val onClick: () -> Unit,
)

private fun primaryAction(
    state: UiState,
    vm: MainViewModel,
    item: Item,
    isSeries: Boolean,
    resume: ResumePoint?,
    firstSeason: Int?,
    firstEpisode: Int?,
): PrimaryAction {
    val left = resume?.minutesLeft?.let { " · $it min left" }.orEmpty()
    return when {
        state.availability == Availability.UNAVAILABLE ->
            PrimaryAction("Not available — pick from search", Icons.Rounded.Search, false) {
                vm.pickFromSearch()
            }
        resume != null && isSeries && resume.season > 0 ->
            PrimaryAction("Resume S${resume.season} E${resume.episode}$left", Icons.Rounded.PlayArrow, false) {
                vm.playEpisode(resume.season, resume.episode, restoreResume = true)
            }
        resume != null && !isSeries ->
            PrimaryAction("Resume$left", Icons.Rounded.PlayArrow, false) { vm.playMovie() }
        // While the probe runs, say so: the button used to promise "Play"
        // for titles no source carries ("why doesn't the button show the
        // movie is available?"). It still starts playback if pressed.
        state.availability == Availability.CHECKING ->
            PrimaryAction(
                if (isSeries) "Checking…" else "Checking this plays…",
                Icons.Rounded.PlayArrow, true,
            ) {
                if (isSeries) vm.playEpisode(firstSeason ?: 1, firstEpisode ?: 1, restoreResume = true)
                else vm.playMovie()
            }
        isSeries ->
            PrimaryAction("Play S${firstSeason ?: 1} E${firstEpisode ?: 1}", Icons.Rounded.PlayArrow, false) {
                vm.playEpisode(firstSeason ?: 1, firstEpisode ?: 1, restoreResume = true)
            }
        else -> PrimaryAction("Play", Icons.Rounded.PlayArrow, false) { vm.playMovie() }
    }
}

@Composable
private fun MetaLine(item: Item, d: Details?, isSeries: Boolean) {
    val parts = buildList {
        (d?.year ?: item.year)?.let { add("$it") }
        item.genres.take(2).takeIf { it.isNotEmpty() }?.let { add(it.joinToString(" · ")) }
        if (isSeries) {
            val n = d?.seasons?.count { s -> s.realEpisodes?.isNotEmpty() ?: (s.episodes > 0) } ?: 0
            if (n > 0) add(if (n == 1) "1 Season" else "$n Seasons") else add("Series")
        } else add("Movie")
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        parts.forEachIndexed { i, p ->
            if (i > 0) Text("•", color = Sep, fontSize = 15.sp)
            Text(p, color = Meta, fontSize = 15.sp, fontWeight = FontWeight.Medium)
        }
        (d?.rating ?: item.rating)?.takeIf { it > 0 }?.let { r ->
            Text("•", color = Sep, fontSize = 15.sp)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.Star, null, tint = Gold, modifier = Modifier.size(17.dp))
                Text(
                    " %.1f".format(r), color = Gold, fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
    }
}

/** Audio and quality, when the source told us. */
@Composable
private fun InfoLine(d: Details?) {
    val dubs = d?.dubs.orEmpty()
    val audio = (dubs.firstOrNull { it.original } ?: dubs.firstOrNull())?.name
    val more = (dubs.size - 1).takeIf { it > 0 }
    val quality = d?.seasons?.flatMap { it.resolutions }?.maxOrNull()
    if (audio == null && quality == null) return
    Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
        audio?.let {
            Row {
                Text("Audio ", color = Dim, fontSize = 14.sp)
                Text(
                    it + (more?.let { m -> " + $m more" } ?: ""),
                    color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                )
            }
        }
        quality?.let {
            Row {
                Text("Quality ", color = Dim, fontSize = 14.sp)
                Text("${it}p", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

@Composable
private fun SkeletonLines() {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf(500, 470, 300).forEach { w ->
            Box(
                Modifier.width(w.dp).height(14.dp)
                    .clip(RoundedCornerShape(7.dp))
                    .background(Color(0x1FFFFFFF)),
            )
        }
    }
}

@Composable
internal fun PillButton(
    label: String,
    icon: ImageVector?,
    primary: Boolean = false,
    busy: Boolean = false,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(12.dp)
    Row(
        modifier
            .tvFocusRing(shape, scaleOnFocus = if (primary) 1.06f else 1.1f)
            .height(46.dp)
            .clip(shape)
            .background(if (primary) Accent else Glass)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = ownFocusIndication(),
                onClick = onClick,
            )
            .padding(start = if (icon != null) 18.dp else 22.dp, end = 22.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val ink = if (primary) Ink else Color.White
        if (busy) {
            CircularProgressIndicator(
                modifier = Modifier.size(18.dp), strokeWidth = 2.dp, color = ink,
            )
            Spacer(Modifier.width(10.dp))
        } else if (icon != null) {
            Icon(icon, null, tint = ink, modifier = Modifier.size(24.dp))
            Spacer(Modifier.width(8.dp))
        }
        Text(
            label, color = ink, fontSize = 16.sp,
            fontWeight = if (primary) FontWeight.Bold else FontWeight.SemiBold,
            maxLines = 1,
        )
    }
}

@Composable
internal fun IconAction(icon: ImageVector, description: String, onClick: () -> Unit) {
    val shape = RoundedCornerShape(12.dp)
    Box(
        Modifier
            .tvFocusRing(shape)
            .size(46.dp)
            .clip(shape)
            .background(Glass)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = ownFocusIndication(),
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, description, tint = Color.White, modifier = Modifier.size(24.dp))
    }
}

/** Seasons as tabs: focus switches the season (TV tab behaviour), so moving
 *  along the tabs previews each season's episodes below. */
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
private fun SeasonTabs(
    seasons: List<Int>,
    selected: Int,
    note: String,
    onSelect: (Int) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(
            Modifier.padding(start = SAFE_START.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            Text("Episodes", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.width(14.dp))
            Text(note, color = Dim, fontSize = 15.sp)
        }
        // Full width: it was capped next to the count and cut "Season 6"
        // in half on an 11-season show.
        LazyRow(
            Modifier.tvFocusRestorer(),
            contentPadding = PaddingValues(start = SAFE_START.dp, end = 48.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            items(seasons, key = { it }) { s ->
                val on = s == selected
                val shape = RoundedCornerShape(20.dp)
                Box(
                    Modifier
                        .tvFocusRing(shape, scaleOnFocus = 1.08f)
                        .onFocusChanged { if (it.isFocused) onSelect(s) }
                        .height(38.dp)
                        .clip(shape)
                        .background(if (on) Color(0xFFF2F5F8) else Color(0x1AF2F5F8))
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = ownFocusIndication(),
                        ) { onSelect(s) }
                        .padding(horizontal = 18.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        "Season $s",
                        color = if (on) Bg else Body,
                        fontSize = 15.sp,
                        fontWeight = if (on) FontWeight.Bold else FontWeight.SemiBold,
                    )
                }
            }
        }
    }
}

@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
private fun EpisodeRow(
    episodes: List<Int>,
    season: Int,
    subjectId: String,
    metaByEp: Map<Int, EpisodeMeta>,
    history: Map<String, WatchHistoryEntity>,
    watched: Set<String>,
    fallbackImage: String?,
    onPlay: (Int) -> Unit,
    onDownload: (Int) -> Unit,
) {
    LazyRow(
        Modifier.tvFocusRestorer(),
        contentPadding = PaddingValues(start = SAFE_START.dp, end = 48.dp),
        horizontalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        items(episodes, key = { "$season-$it" }) { ep ->
            val key = WatchHistoryEntity.keyOf(subjectId, season, ep)
            EpisodeCard(
                ep = ep,
                meta = metaByEp[ep],
                row = history[key],
                isWatched = key in watched,
                fallbackImage = fallbackImage,
                onPlay = { onPlay(ep) },
                onDownload = { onDownload(ep) },
            )
        }
    }
}

@Composable
private fun EpisodeCard(
    ep: Int,
    meta: EpisodeMeta?,
    row: WatchHistoryEntity?,
    isWatched: Boolean,
    fallbackImage: String?,
    onPlay: () -> Unit,
    onDownload: () -> Unit,
) {
    val ctx = LocalContext.current
    val shape = RoundedCornerShape(10.dp)
    val still = meta?.stillUrl
    val inProgress = !isWatched && row != null && row.positionMs > 30_000 && row.durationMs > 0
    // The focus target is the still; without this the page only scrolled far
    // enough to show the still, leaving the episode name below the screen.
    val whole = remember { BringIntoViewRequester() }
    val scope = rememberCoroutineScope()
    Column(
        Modifier
            .width(220.dp)
            .bringIntoViewRequester(whole)
            .onFocusChanged { if (it.hasFocus) scope.launch { whole.bringIntoView() } },
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .tvFocusable(
                    shape = shape,
                    scaleOnFocus = 1.08f,
                    borderColor = Color.White,
                    onClick = onPlay,
                    onLongClick = onDownload,
                )
                .clip(shape)
                .background(SurfaceElevated),
        ) {
            val img = still ?: fallbackImage
            if (img != null) {
                AsyncImage(
                    model = remember(img) {
                        ImageRequest.Builder(ctx).data(img).size(440, 248).crossfade(200).build()
                    },
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            // No still: the shared backdrop would make every card look the
            // same, so dim it and lead with the number.
            if (still == null) {
                Box(Modifier.fillMaxSize().background(Color(0x99000000)))
                Text(
                    "E$ep", color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.Black,
                    modifier = Modifier.align(Alignment.BottomStart).padding(12.dp),
                )
            }
            if (isWatched) {
                Text(
                    "Watched", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.align(Alignment.TopEnd).padding(8.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(Color(0x99000000))
                        .padding(horizontal = 7.dp, vertical = 2.dp),
                )
            }
            if (isWatched || inProgress) {
                Box(
                    Modifier.align(Alignment.BottomStart).fillMaxWidth().height(4.dp)
                        .background(Color(0x38F2F5F8)),
                ) {
                    Box(
                        Modifier.fillMaxHeight()
                            .fillMaxWidth(if (isWatched) 1f else row!!.progress.coerceIn(0.03f, 1f))
                            .background(Accent),
                    )
                }
            }
        }
        Text(
            meta?.name?.let { "E$ep · $it" } ?: "Episode $ep",
            color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
            maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
        val sub = when {
            inProgress -> "${((row!!.durationMs - row.positionMs) / 60_000L).coerceAtLeast(1)} min left"
            isWatched -> "Watched"
            meta?.runtimeMin != null -> "${meta.runtimeMin} min"
            else -> null
        }
        if (sub != null) {
            Text(sub, color = if (inProgress) Color.White else Dim, fontSize = 13.sp)
        }
    }
}

@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
private fun CastRow(cast: List<com.moviebox.tv.data.CastMember>, onPick: (String) -> Unit) {
    val ctx = LocalContext.current
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            "Cast", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(start = SAFE_START.dp),
        )
        LazyRow(
            Modifier.tvFocusRestorer(),
            contentPadding = PaddingValues(start = SAFE_START.dp, end = 48.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            items(cast, key = { it.name }) { m ->
                val whole = remember { BringIntoViewRequester() }
                val scope = rememberCoroutineScope()
                Column(
                    Modifier
                        .width(96.dp)
                        .bringIntoViewRequester(whole)
                        .onFocusChanged { if (it.hasFocus) scope.launch { whole.bringIntoView() } },
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Box(
                        Modifier
                            .size(76.dp)
                            .tvFocusable(
                                shape = CircleShape,
                                scaleOnFocus = 1.1f,
                                borderColor = Color.White,
                                onClick = { onPick(m.name) },
                            )
                            .clip(CircleShape)
                            .background(SurfaceElevated),
                    ) {
                        if (m.profileUrl != null) {
                            AsyncImage(
                                model = remember(m.profileUrl) {
                                    ImageRequest.Builder(ctx).data(m.profileUrl)
                                        .size(152, 152).crossfade(200).build()
                                },
                                contentDescription = m.name,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize(),
                            )
                        }
                    }
                    Text(
                        m.name, color = Color.White, fontSize = 13.sp,
                        fontWeight = FontWeight.Medium, maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    m.character?.let {
                        Text(
                            it, color = Dim, fontSize = 12.sp, maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}
