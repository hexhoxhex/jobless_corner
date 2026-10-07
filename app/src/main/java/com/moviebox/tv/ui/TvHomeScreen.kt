package com.moviebox.tv.ui

import android.os.SystemClock
import androidx.compose.animation.Crossfade
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
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
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.BiasAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.moviebox.tv.data.Hero
import com.moviebox.tv.data.Item
import com.moviebox.tv.data.local.WatchHistoryEntity
import com.moviebox.tv.ui.theme.Accent
import com.moviebox.tv.ui.theme.Bg
import com.moviebox.tv.ui.theme.Gold
import com.moviebox.tv.ui.theme.SurfaceElevated
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/*
 * The TV home (phase 3, mock-up "TV home").
 *
 * A billboard instead of a passive banner: the featured title's backdrop
 * with a cinematic scrim, its title, year, rating and synopsis, and three
 * actions — Play (starts it, resuming if watched before), More info (title
 * page) and My list. It rotates through the featured titles, but only while
 * the viewer is idle and the billboard is on screen: a slide never changes
 * under someone who is pressing keys (android-tv-compose skill, section 5).
 *
 * Rows below, rebuilt for the TV: Continue watching as 16:9 cards with the
 * title on the art, what is left and a progress bar; the other rows as
 * posters sized so ~6 fit across. Rings are white (green ones vanished on
 * green artwork), only the art scales, and entering a row brings the WHOLE
 * row — heading included — into view, which CalmScroll alone could not do.
 *
 * Under Continue watching, "Live now": the matches on right now, as in the
 * sports guide, watchable ones only. OK plays; BACK comes back here.
 *
 * Opening a card and coming BACK lands on that same card, scrolled where it
 * was (HomeMemory): the home leaves composition while a title page or the
 * player is up, and used to come back at the top with Play focused.
 *
 * The phone keeps HomeScreen.
 */

private const val START = 32
private const val ROTATE_IDLE_MS = 9_000L
private const val LIVE_ROW_MAX = 12

private val Meta = Color(0xFFC9D0DA)
private val Body = Color(0xFFD5DBE3)
private val Dim = Color(0xFFA7B0BE)
private val LiveRed = Color(0xFFC8313A)

/**
 * Where the viewer was when they opened something from the home, so BACK
 * returns to that card rather than the top. Everything Compose remembers
 * goes when the home leaves composition for the title page or the player;
 * this outlives it. [focus] is consumed by the first home shown after.
 */
private object HomeMemory {
    var columnIndex = 0
    var columnOffset = 0
    var heroIndex = 0
    val rows = HashMap<String, Pair<Int, Int>>()
    /** "row|item" of the card that was opened; null = open at the top. */
    var focus: String? = null
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun TvHomeScreen(state: UiState, vm: MainViewModel) {
    // The home places its own first focus (Play, or the card BACK returns
    // to); keep the rail's generic content focus out of the way until it
    // has. Released below once done, or when there is no billboard to
    // focus. Measured: the first start after an install takes ~4.5 s to
    // bring the home up, so a fixed short head start was not enough.
    remember { ContentFocusClaim.claim(8_000) }
    if (state.networkState == com.moviebox.tv.debug.NetworkMonitor.State.OfflineLong) {
        NetworkOfflinePage(onRetry = { vm.loadHome() })
        return
    }
    val home = state.home
    if (home == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            if (state.homeLoading) com.moviebox.tv.ui.components.LottieLoader()
            else ErrorView(state.error, onRetry = { vm.loadHome() })
        }
        return
    }
    val continueWatching by vm.continueWatching.collectAsState()
    val recs by vm.recommendations.collectAsState()
    val favIds by vm.favouriteIds.collectAsState()
    val history by vm.historyByKey.collectAsState()
    // Back from a title page or the player: start where the viewer left.
    val returning = remember { HomeMemory.focus }
    val listState = rememberLazyListState(
        if (returning != null) HomeMemory.columnIndex else 0,
        if (returning != null) HomeMemory.columnOffset else 0,
    )
    val rowStates = remember { HashMap<String, LazyListState>() }
    val restoreFocus = remember { FocusRequester() }
    val opened: (String, String) -> Unit = { row, item ->
        HomeMemory.columnIndex = listState.firstVisibleItemIndex
        HomeMemory.columnOffset = listState.firstVisibleItemScrollOffset
        HomeMemory.rows.clear()
        rowStates.forEach { (k, st) ->
            HomeMemory.rows[k] = st.firstVisibleItemIndex to st.firstVisibleItemScrollOffset
        }
        HomeMemory.focus = "$row|$item"
    }
    val rowState: @Composable (String) -> LazyListState = { key ->
        val saved = if (returning != null) HomeMemory.rows[key] else null
        rememberLazyListState(saved?.first ?: 0, saved?.second ?: 0).also { rowStates[key] = it }
    }
    val focusFor: (String, String) -> Modifier = { row, item ->
        if (returning == "$row|$item") Modifier.focusRequester(restoreFocus) else Modifier
    }
    var lastKeyAt by remember { mutableLongStateOf(SystemClock.uptimeMillis()) }
    val nowSec by produceState(System.currentTimeMillis() / 1000) {
        while (true) {
            delay(60_000)
            value = System.currentTimeMillis() / 1000
        }
    }
    val liveNow = remember(state.liveSchedule, state.liveChannels, nowSec) {
        liveNowOf(classifySchedule(state.liveSchedule, state.liveChannels), nowSec)
            .filter { it.primary != null }
            .take(LIVE_ROW_MAX)
    }
    val billboardOnScreen by remember {
        derivedStateOf { listState.layoutInfo.visibleItemsInfo.any { it.key == "billboard" } }
    }

    // Start on the billboard's Play — the page's primary action — rather than
    // whichever card the rail's generic "focus the content" lands on. Stops
    // for good once the billboard has focus or the viewer presses anything.
    val playFocus = remember { FocusRequester() }
    var billboardFocused by remember { mutableStateOf(false) }
    val openedAt = remember { SystemClock.uptimeMillis() }
    val hasHeroes = home.heroes.isNotEmpty()
    val backToCard = returning != null && !returning.startsWith("billboard|")
    LaunchedEffect(hasHeroes) {
        if (backToCard) return@LaunchedEffect
        if (!hasHeroes) {
            ContentFocusClaim.release()
            return@LaunchedEffect
        }
        repeat(20) {
            if (billboardFocused || lastKeyAt > openedAt) {
                ContentFocusClaim.release()
                return@LaunchedEffect
            }
            // The billboard's art often arrives a few seconds after the
            // rows. By then the rail has put focus on the first row and the
            // list has kept that row on top, leaving the billboard above the
            // screen where Play cannot be focused. Nobody has pressed
            // anything yet, so take them back to the top.
            if (listState.layoutInfo.visibleItemsInfo.none { it.key == "billboard" }) {
                runCatching { listState.scrollToItem(0) }
            }
            runCatching { playFocus.requestFocus() }
            delay(150)
        }
        ContentFocusClaim.release()
    }
    // Back on the card that was opened. Repeated briefly because the rail
    // hands focus to the content's first card on its own as the tabs come
    // back; stops the moment the viewer presses anything.
    LaunchedEffect(Unit) {
        if (!backToCard) {
            HomeMemory.focus = null
            return@LaunchedEffect
        }
        try {
            repeat(12) {
                if (lastKeyAt > openedAt) return@LaunchedEffect
                runCatching { restoreFocus.requestFocus() }
                delay(100)
            }
        } finally {
            HomeMemory.focus = null
            ContentFocusClaim.release()
        }
    }

    LazyColumn(
        state = listState,
        modifier = Modifier
            .fillMaxSize()
            .onPreviewKeyEvent { lastKeyAt = SystemClock.uptimeMillis(); false },
        contentPadding = PaddingValues(bottom = 40.dp),
        verticalArrangement = Arrangement.spacedBy(28.dp),
    ) {
        if (state.networkState == com.moviebox.tv.debug.NetworkMonitor.State.Checking) {
            item(key = "checking") { NetworkCheckingBanner() }
        }
        val u = state.updateAvailable
        if (u != null && state.updateDismissedFor != u.tag) {
            item(key = "update") {
                val ctx = LocalContext.current
                UpdateBanner(
                    update = u,
                    onUpdate = { vm.installUpdate(ctx) },
                    onDismiss = { vm.dismissUpdateBanner() },
                )
            }
        }
        if (home.heroes.isNotEmpty()) {
            item(key = "billboard") {
                Billboard(
                    heroes = home.heroes,
                    initialIndex = if (returning != null) HomeMemory.heroIndex else 0,
                    favIds = favIds,
                    history = history.values,
                    playFocus = playFocus,
                    onFocus = { billboardFocused = it },
                    onScreen = { billboardOnScreen },
                    lastKeyAt = { lastKeyAt },
                    onIndex = { HomeMemory.heroIndex = it },
                    onPlay = { opened("billboard", "play"); vm.playFromHome(it) },
                    onInfo = { opened("billboard", "info"); vm.openItem(it) },
                    onList = { vm.toggleFavourite(it) },
                )
            }
        }
        if (continueWatching.isNotEmpty()) {
            item(key = "continue") {
                TvRow("Continue watching") {
                    key(
                        continueWatching.size,
                        continueWatching.firstOrNull()?.key,
                        continueWatching.lastOrNull()?.key,
                    ) {
                        LazyRow(
                            Modifier.focusGroup().tvFocusRestorer(),
                            state = rowState("continue"),
                            contentPadding = PaddingValues(start = START.dp, end = 48.dp),
                            horizontalArrangement = Arrangement.spacedBy(18.dp),
                        ) {
                            items(continueWatching, key = { it.key }) { h ->
                                ContinueCardTv(h, focusFor("continue", h.key)) {
                                    opened("continue", h.key)
                                    vm.resumeFrom(h)
                                }
                            }
                        }
                    }
                }
            }
        }
        if (liveNow.isNotEmpty()) {
            item(key = "live") {
                LiveRow(liveNow.size, liveNow.map { it.sport }.distinct().filter { it != "Other" }) {
                    LazyRow(
                        Modifier.focusGroup().tvFocusRestorer(),
                        state = rowState("live"),
                        contentPadding = PaddingValues(start = START.dp, end = 48.dp),
                        horizontalArrangement = Arrangement.spacedBy(18.dp),
                    ) {
                        items(liveNow, key = { it.key() }) { c ->
                            LiveCard(c, nowSec, focusFor("live", c.key())) {
                                c.primary?.let {
                                    opened("live", c.key())
                                    vm.playLiveFromHome(it.id)
                                }
                            }
                        }
                    }
                }
            }
        }
        if (recs.isNotEmpty()) {
            item(key = "recs") {
                TvRow("For you") {
                    PosterRow(recs, ranked = false, rowState("recs"), { focusFor("recs", it) }) {
                        opened("recs", it.subjectId); vm.openItem(it)
                    }
                }
            }
        }
        items(home.rows, key = { "row-" + it.title }) { row ->
            val ranked = listOf("Trending", "Top", "Ranking", "Most")
                .any { row.title.contains(it, ignoreCase = true) }
            val rk = "row-" + row.title
            TvRow(row.title) {
                PosterRow(row.items, ranked, rowState(rk), { focusFor(rk, it) }) {
                    opened(rk, it.subjectId); vm.openItem(it)
                }
            }
        }
    }
}

/** A row heading and its cards. Entering the row brings the whole row into
 *  view, heading included: the focused card alone would leave the heading
 *  above the screen when moving UP. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TvRow(title: String, content: @Composable () -> Unit) {
    val whole = remember { BringIntoViewRequester() }
    val scope = rememberCoroutineScope()
    Column(
        Modifier
            .bringIntoViewRequester(whole)
            .onFocusChanged { if (it.hasFocus) scope.launch { whole.bringIntoView() } },
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            title, color = Color.White, fontSize = 21.sp, fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(start = START.dp),
        )
        EdgeSafeRow(START.dp, 48.dp, content)
    }
}

/** "Live now" heading: a LIVE count and the sports on, as in the guide. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun LiveRow(count: Int, sports: List<String>, content: @Composable () -> Unit) {
    val whole = remember { BringIntoViewRequester() }
    val scope = rememberCoroutineScope()
    Column(
        Modifier
            .bringIntoViewRequester(whole)
            .onFocusChanged { if (it.hasFocus) scope.launch { whole.bringIntoView() } },
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(Modifier.padding(start = START.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Live now", color = Color.White, fontSize = 21.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.width(12.dp))
            Text(
                "$count LIVE", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(LiveRed)
                    .padding(horizontal = 8.dp, vertical = 3.dp),
            )
            if (sports.isNotEmpty()) {
                Spacer(Modifier.width(12.dp))
                Text(
                    sports.take(4).joinToString("  ·  "), color = Dim, fontSize = 15.sp,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
        }
        EdgeSafeRow(START.dp, 48.dp, content)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PosterRow(
    items: List<Item>,
    ranked: Boolean,
    state: LazyListState,
    focusFor: (String) -> Modifier,
    onOpen: (Item) -> Unit,
) {
    key(items.size, items.firstOrNull()?.subjectId, items.lastOrNull()?.subjectId) {
        LazyRow(
            Modifier.focusGroup().tvFocusRestorer(),
            state = state,
            contentPadding = PaddingValues(start = START.dp, end = 48.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            itemsIndexed(items, key = { _, it -> it.subjectId }) { i, item ->
                PosterCardTv(item, rank = if (ranked) i + 1 else null, focusFor(item.subjectId)) {
                    onOpen(item)
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Billboard(
    heroes: List<Hero>,
    initialIndex: Int,
    favIds: Set<String>,
    history: Collection<WatchHistoryEntity>,
    playFocus: FocusRequester,
    onFocus: (Boolean) -> Unit,
    onScreen: () -> Boolean,
    lastKeyAt: () -> Long,
    onIndex: (Int) -> Unit,
    onPlay: (Item) -> Unit,
    onInfo: (Item) -> Unit,
    onList: (Item) -> Unit,
) {
    val screen = LocalConfiguration.current
    var index by rememberSaveable { mutableIntStateOf(initialIndex) }
    val hero = heroes[index % heroes.size]
    LaunchedEffect(index) { onIndex(index) }
    // Never rotate under a focused billboard: Play would start a different
    // title from the one the viewer is reading.
    var focusedHere by remember { mutableStateOf(false) }
    // Coming back UP from the rows, show the WHOLE billboard (title and
    // synopsis), not just enough of it to uncover the Play button.
    val whole = remember { BringIntoViewRequester() }
    val scope = rememberCoroutineScope()

    // Advance only when idle and visible; never while someone is pressing.
    LaunchedEffect(heroes.size) {
        var lastAdvance = SystemClock.uptimeMillis()
        while (heroes.size > 1) {
            delay(1_000)
            val now = SystemClock.uptimeMillis()
            if (onScreen() && !focusedHere && now - lastKeyAt() >= ROTATE_IDLE_MS &&
                now - lastAdvance >= ROTATE_IDLE_MS
            ) {
                index = (index + 1) % heroes.size
                lastAdvance = now
            }
        }
    }

    Box(
        Modifier
            .fillMaxWidth()
            .height((screen.screenHeightDp * 0.64f).dp)
            .bringIntoViewRequester(whole)
            .onFocusChanged {
                if (it.hasFocus && !focusedHere) scope.launch { whole.bringIntoView() }
                focusedHere = it.hasFocus
                onFocus(it.hasFocus)
            },
    ) {
        Crossfade(targetState = hero, animationSpec = tween(500), label = "billboard-art") { h ->
            BillboardArt(h)
        }
        Column(
            Modifier
                .align(Alignment.BottomStart)
                .padding(start = START.dp, end = 48.dp, bottom = 4.dp)
                .widthIn(max = 580.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // Fixed height, text anchored to the bottom: the buttons below must
            // not move when a slide with a longer synopsis comes in (a block
            // that resized per slide jumped the whole row of buttons). Old
            // text fades out before the new fades in — overlapping two
            // synopses mid-crossfade read as garbage.
            Box(Modifier.fillMaxWidth().height(232.dp), contentAlignment = Alignment.BottomStart) {
                androidx.compose.animation.AnimatedContent(
                    targetState = hero,
                    transitionSpec = {
                        androidx.compose.animation.fadeIn(tween(220, delayMillis = 160)) togetherWith
                            androidx.compose.animation.fadeOut(tween(150))
                    },
                    contentAlignment = Alignment.BottomStart,
                    label = "billboard-text",
                ) { h -> BillboardText(h) }
            }
            Row(
                // UP from the rows lands on Play, not on whichever button
                // happens to sit above the card (it was More info).
                Modifier
                    .focusProperties { enter = { playFocus } }
                    .focusGroup(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // Says what OK will do: the rule Play itself resumes by.
                val resume = remember(hero, history) {
                    resumeEntryFor(history, hero.item.subjectId, hero.item.title)
                }
                val playLabel = when {
                    resume == null -> "Play"
                    resume.season > 0 -> "Resume S${resume.season} E${resume.episode}"
                    else -> "Resume"
                }
                PillButton(
                    playLabel, Icons.Rounded.PlayArrow, primary = true,
                    modifier = Modifier.focusRequester(playFocus),
                ) { onPlay(hero.item) }
                PillButton("More info", Icons.Rounded.Info) { onInfo(hero.item) }
                val inList = hero.item.subjectId in favIds
                IconAction(
                    if (inList) Icons.Rounded.Check else Icons.Rounded.Add,
                    if (inList) "Remove from my list" else "Add to my list",
                ) { onList(hero.item) }
            }
            if (heroes.size > 1) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.padding(top = 4.dp),
                ) {
                    heroes.indices.forEach { i ->
                        val on = i == index % heroes.size
                        Box(
                            Modifier
                                .width(if (on) 18.dp else 6.dp)
                                .height(4.dp)
                                .clip(RoundedCornerShape(2.dp))
                                .background(if (on) Color(0xFFF2F5F8) else Color(0x59F2F5F8)),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun BillboardArt(h: Hero) {
    val ctx = LocalContext.current
    Box(Modifier.fillMaxSize()) {
        val url = h.backdropUrl ?: h.item.backdropUrl ?: h.item.coverUrl
        if (url != null) {
            AsyncImage(
                model = remember(url) {
                    ImageRequest.Builder(ctx).data(url).size(1280, 720).crossfade(250).build()
                },
                contentDescription = h.item.title,
                contentScale = ContentScale.Crop,
                alignment = BiasAlignment(0.4f, -0.3f),
                modifier = Modifier.fillMaxSize(),
            )
        }
        Box(
            Modifier.fillMaxSize().background(
                Brush.horizontalGradient(
                    0f to Bg,
                    0.22f to Bg.copy(alpha = 0.92f),
                    0.48f to Bg.copy(alpha = 0.55f),
                    0.75f to Bg.copy(alpha = 0.05f),
                    1f to Color.Transparent,
                ),
            ),
        )
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(0.50f to Color.Transparent, 1f to Bg),
            ),
        )
    }
}

@Composable
private fun BillboardText(h: Hero) {
    val item = h.item
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        val overline = buildList {
            add(if (item.isSeries) "Series" else "Movie")
            addAll(item.genres.take(2))
        }.joinToString("  ·  ")
        Text(overline, color = Meta, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        Text(
            item.title, color = Color.White, fontSize = 44.sp, lineHeight = 46.sp,
            fontWeight = FontWeight.Black, maxLines = 2, overflow = TextOverflow.Ellipsis,
        )
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item.year?.let { Text("$it", color = Meta, fontSize = 15.sp, fontWeight = FontWeight.Medium) }
            item.rating?.takeIf { it > 0 }?.let { r ->
                if (item.year != null) Text("•", color = Color(0xFF5D6675), fontSize = 15.sp)
                Icon(Icons.Rounded.Star, null, tint = Gold, modifier = Modifier.size(17.dp))
                Text("%.1f".format(r), color = Gold, fontSize = 15.sp, fontWeight = FontWeight.Bold)
            }
        }
        val blurb = item.overview?.takeIf { it.isNotBlank() } ?: h.tagline.takeIf { it.isNotBlank() }
        if (blurb != null) {
            Text(
                blurb, color = Body, fontSize = 15.sp, lineHeight = 22.sp,
                maxLines = 3, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = 520.dp),
            )
        }
    }
}

@Composable
private fun ContinueCardTv(h: WatchHistoryEntity, focus: Modifier, onClick: () -> Unit) {
    val shape = RoundedCornerShape(10.dp)
    Column(Modifier.width(228.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .then(focus)
                .tvFocusable(shape = shape, scaleOnFocus = 1.08f, borderColor = Color.White, onClick = onClick)
                .clip(shape)
                .background(SurfaceElevated),
        ) {
            PosterImage(h.coverUrl, h.title, Modifier.fillMaxSize())
            Box(
                Modifier.fillMaxSize().background(
                    Brush.verticalGradient(0.35f to Color.Transparent, 1f to Color(0xD9000000)),
                ),
            )
            Text(
                h.title, color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Bold,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.align(Alignment.BottomStart)
                    .padding(start = 12.dp, end = 12.dp, bottom = 14.dp),
            )
            Box(
                Modifier.align(Alignment.BottomStart).fillMaxWidth().height(4.dp)
                    .background(Color(0x38F2F5F8)),
            ) {
                Box(
                    Modifier.fillMaxHeight()
                        .fillMaxWidth(h.progress.coerceIn(0.03f, 1f))
                        .background(Accent),
                )
            }
        }
        val left = if (h.durationMs > 0) {
            val m = ((h.durationMs - h.positionMs) / 60_000L).coerceAtLeast(1)
            if (m >= 60) "${m / 60} h ${"%02d".format(m % 60)} min left" else "$m min left"
        } else null
        val what = if (h.season > 0) "S${h.season} · E${h.episode}" else "Movie"
        Text(
            listOfNotNull(what, left).joinToString("  ·  "),
            color = Dim, fontSize = 14.sp, maxLines = 1,
        )
    }
}

@Composable
private fun PosterCardTv(item: Item, rank: Int?, focus: Modifier, onClick: () -> Unit) {
    val shape = RoundedCornerShape(10.dp)
    Column(Modifier.width(132.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(2f / 3f)
                .then(focus)
                .tvFocusable(shape = shape, scaleOnFocus = 1.08f, borderColor = Color.White, onClick = onClick)
                .clip(shape),
        ) {
            PosterImage(item.coverUrl, item.title, Modifier.fillMaxSize())
            if (rank != null) {
                Text(
                    "$rank", color = Color(0xFF06120B), fontSize = 16.sp, fontWeight = FontWeight.Black,
                    modifier = Modifier.align(Alignment.TopStart)
                        .padding(6.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(Accent)
                        .padding(horizontal = 8.dp, vertical = 1.dp),
                )
            } else {
                RatingPill(item.rating, Modifier.align(Alignment.TopStart).padding(6.dp))
            }
            if (item.subjectId.startsWith(com.moviebox.tv.net.FourKHdHub.PREFIX)) {
                Text(
                    "4K", color = Color(0xFF06120B), fontSize = 11.sp, fontWeight = FontWeight.Black,
                    modifier = Modifier.align(Alignment.TopEnd)
                        .padding(6.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(Accent)
                        .padding(horizontal = 5.dp, vertical = 1.dp),
                )
            }
        }
        Text(
            item.title, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Medium,
            maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
        Text(
            item.year?.toString() ?: if (item.isSeries) "Series" else "Movie",
            color = Dim, fontSize = 13.sp,
        )
    }
}
