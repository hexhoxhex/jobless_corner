package com.moviebox.tv.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.os.SystemClock
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.LiveTv
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SettingsRemote
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.moviebox.tv.ui.theme.Accent
import com.moviebox.tv.ui.theme.Bg
import com.moviebox.tv.ui.theme.SurfaceElevated
import com.moviebox.tv.ui.theme.TextMuted
import com.moviebox.tv.ui.theme.TextPrimary
import kotlinx.coroutines.delay
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.runtime.CompositionLocalProvider

/**
 * TV navigation: a slim rail down the left edge instead of the phone's top
 * bar and bottom bar.
 *
 * On a TV the effective screen is 960x540 dp, and those two bars took 60 dp
 * and 80 dp of it — over a quarter of the height — so the first row of cards
 * sat half under the bottom bar and the home screen never showed a whole row.
 * The rail costs 72 dp of WIDTH, which a 16:9 screen has to spare, and
 * expands over the content (never pushing it) to show labels while it holds
 * focus.
 *
 * BACK, which used to close the app from Home outright:
 *  - from the content, it moves focus to the rail;
 *  - on the rail, off the Home tab, it goes to Home;
 *  - on the rail at Home, it asks for a second press within 2.5 s to exit.
 *
 * Phones keep the old Scaffold layout; this is only composed when the device
 * is a TV.
 */
private val RailCollapsed = 72.dp
private val RailExpanded = 236.dp
private const val EXIT_WINDOW_MS = 2_500L

/**
 * How lists scroll to bring the focused item into view, on TV.
 *
 * Reported as "the tv app feels like its flickering up and down, its like
 * its shaking". Compose's default on a TV keeps the focused item pinned about
 * 30% down the screen, so every move between the banner and the first row
 * swung the whole page by half a screen — down onto the row, the banner gone;
 * back up, the banner back — measured on the TV as ~250 dp per press.
 *
 * This scrolls only as far as needed: an item already comfortably on screen
 * does not move the page at all (the banner stays put while you browse the
 * first row), anything off-screen comes in just far enough to be fully
 * visible with a small margin so the focus ring and the 1.06 scale are not
 * clipped, and the motion is a calm, non-bouncy spring. Applies to
 * rows (horizontal) and the page (vertical) alike.
 */
@OptIn(ExperimentalFoundationApi::class)
internal val CalmScroll = object : BringIntoViewSpec {
    // A spring, not a tween. The scroll is re-aimed every frame as the
    // remaining distance shrinks; a spring carries its velocity through each
    // re-aim, a fixed-duration tween restarts — measured on the TV, a 300 ms
    // tween turned into a page that kept crawling for 1-3 seconds after each
    // press (100+ frames where ~25 were needed). No bounce, so nothing
    // overshoots and settles back: that would read as a wobble.
    override val scrollAnimationSpec: AnimationSpec<Float> =
        androidx.compose.animation.core.spring(
            dampingRatio = androidx.compose.animation.core.Spring.DampingRatioNoBouncy,
            stiffness = 700f,
        )

    override fun calculateScrollDistance(
        offset: Float, size: Float, containerSize: Float,
    ): Float {
        // MUST converge. This is re-asked every frame while a scroll runs, so
        // any answer that can never be satisfied keeps the animation alive
        // forever. The first version used a margin unconditionally: the first
        // card of a row sits 64 px in, inside a 71 px margin, so it asked to
        // scroll before the start of the list — impossible — on every frame,
        // restarting the page's own scroll each time. Logged on the TV: the
        // page crept ~1 px per frame, which is a shake of its own.
        //
        // So: anything already fully on screen answers 0, and the margin only
        // adds travel while bringing in something that is partly hidden. Once
        // it is visible the next answer is 0 and the scroll ends.
        val trailing = offset + size
        if (size >= containerSize) {
            // Too big to show whole: if it already covers the view, leave it;
            // otherwise line its start up with the edge.
            return if (offset <= 0f && trailing >= containerSize) 0f else offset
        }
        if (offset >= 0f && trailing <= containerSize) return 0f
        // 9%: enough, moving up, to bring the row's title back into view above
        // its cards; moving sideways, the neighbouring card peeks in.
        val margin = minOf(containerSize * 0.09f, (containerSize - size) / 2f)
        return if (offset < 0f) offset - margin else trailing - (containerSize - margin)
    }
}

/**
 * [CalmScroll] for one horizontal row of cards whose content padding keeps
 * [start] and [end] clear of the screen edges. CalmScroll counts a card as
 * visible once it is anywhere on screen, so a card could stop flush against
 * the right edge with its ring and 1.08 scale cut off — on a TV that crops
 * the picture's edges it was half gone. Here "visible" means clear of the
 * edges by the row's own padding.
 *
 * Still converges (see CalmScroll): every card can reach that zone, since the
 * padding is exactly what the first and last cards sit in, and a scroll
 * aims past it (the 9% margin), so the next answer is 0. Not used for the
 * page or for lists without that padding, where the zone is unreachable.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun EdgeSafeRow(
    start: androidx.compose.ui.unit.Dp,
    end: androidx.compose.ui.unit.Dp,
    content: @Composable () -> Unit,
) {
    val density = androidx.compose.ui.platform.LocalDensity.current
    val spec = remember(density, start, end) {
        // 2 px of slack so float rounding at the ends of the row never
        // leaves a card "just outside" a zone it can get no further into.
        val lead = with(density) { start.toPx() } - 2f
        val trail = with(density) { end.toPx() } - 2f
        object : BringIntoViewSpec {
            override val scrollAnimationSpec: AnimationSpec<Float> = CalmScroll.scrollAnimationSpec

            override fun calculateScrollDistance(
                offset: Float, size: Float, containerSize: Float,
            ): Float {
                if (size >= containerSize - lead - trail) {
                    return CalmScroll.calculateScrollDistance(offset, size, containerSize)
                }
                val trailing = offset + size
                if (offset >= lead && trailing <= containerSize - trail) return 0f
                val margin = minOf(containerSize * 0.09f, (containerSize - size) / 2f)
                    .coerceAtLeast(maxOf(lead, trail) + 2f)
                return if (offset < lead) offset - margin else trailing - (containerSize - margin)
            }
        }
    }
    androidx.compose.runtime.CompositionLocalProvider(
        LocalBringIntoViewSpec provides spec,
        content = content,
    )
}

@Composable
fun TvTabs(state: UiState, vm: MainViewModel) {
    val context = LocalContext.current
    var railFocused by remember { mutableStateOf(false) }
    val requesters = remember { Tab.values().associateWith { FocusRequester() } }
    val content = remember { FocusRequester() }
    var exitArmedAt by remember { mutableLongStateOf(0L) }
    var exitHint by remember { mutableStateOf(false) }

    // Registered after AppRoot's own handler, so it wins while enabled; the
    // overlays (remote, settings, the QR tip) keep theirs by disabling this.
    BackHandler(enabled = !state.showRemote && !state.showSettings && !state.suggestRemote) {
        when {
            !railFocused -> runCatching { requesters.getValue(state.tab).requestFocus() }
            state.tab != Tab.HOME -> {
                vm.selectTab(Tab.HOME)
                runCatching { requesters.getValue(Tab.HOME).requestFocus() }
            }
            SystemClock.elapsedRealtime() - exitArmedAt < EXIT_WINDOW_MS ->
                context.findActivity()?.finish()
            else -> {
                exitArmedAt = SystemClock.elapsedRealtime()
                exitHint = true
            }
        }
    }
    LaunchedEffect(exitArmedAt) {
        if (exitHint) {
            delay(EXIT_WINDOW_MS)
            exitHint = false
        }
    }
    // Start in the content, not on the menu — the first press should move
    // through what's on screen, the way it did before the rail existed.
    //
    // A single request is not enough: Home is usually still loading when
    // this runs, so the content has nothing focusable yet — the request
    // "succeeds" and does nothing, and Compose then hands initial focus to
    // the top-left focusable, which is the rail. Keep asking until focus is
    // really inside the content, for a few seconds at most, and stop the
    // moment the viewer presses anything so this never fights them.
    var contentFocused by remember { mutableStateOf(false) }
    var userKeyed by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        val until = SystemClock.elapsedRealtime() + 8_000
        while (!contentFocused && !userKeyed && SystemClock.elapsedRealtime() < until) {
            // A screen placing its own first focus (the TV home: Play, or
            // the card BACK returns to) gets a head start. Racing it, this
            // landed on whatever sat top-left and scrolled the page there.
            if (!ContentFocusClaim.active) runCatching { content.requestFocus() }
            delay(250)
        }
    }

    // Settings and the phone-remote QR draw ON TOP of these tabs and take
    // focus while open. Closing one destroyed the focused control and nothing
    // handed focus back: the rail sat there with no item lit and the D-pad
    // did nothing at all ("the remote pointer is not visible"). Put it back
    // where it came from — the rail item that opened the overlay, or the
    // content if it was opened from there (the QR tip's "Show").
    val remoteItem = remember { FocusRequester() }
    val settingsItem = remember { FocusRequester() }
    var returnTo by remember { mutableStateOf<FocusRequester?>(null) }
    var restoring by remember { mutableStateOf<FocusRequester?>(null) }
    val overlayOpen = state.showSettings || state.showRemote
    LaunchedEffect(overlayOpen) {
        if (overlayOpen) {
            returnTo = when {
                !railFocused -> content
                state.showSettings -> settingsItem
                else -> remoteItem
            }
            return@LaunchedEffect
        }
        val target = returnTo ?: return@LaunchedEffect
        returnTo = null
        // The rail's enter rule ("land on the current tab") would otherwise
        // turn a return to Settings into a jump to Home.
        restoring = target
        // Not "stop once anything has focus": the moment the overlay goes,
        // Compose hands focus to the rail's default entry (Home) by itself,
        // which is exactly what this corrects. A few tries inside ~0.5 s,
        // too soon for anyone to have moved yet.
        try {
            repeat(4) {
                delay(100)
                runCatching { target.requestFocus() }
            }
        } finally {
            restoring = null
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Bg)
            .onPreviewKeyEvent { userKeyed = true; false },
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .padding(start = RailCollapsed)
                .onFocusChanged { contentFocused = it.hasFocus }
                .focusRequester(content)
                .focusGroup(),
        ) {
            @OptIn(ExperimentalFoundationApi::class)
            CompositionLocalProvider(LocalBringIntoViewSpec provides CalmScroll) {
                when (state.tab) {
                    Tab.HOME -> HomeScreen(state, vm)
                    Tab.LIVE -> LiveTvScreen(state, vm)
                    Tab.SEARCH -> SearchScreen(state, vm)
                    Tab.DOWNLOADS -> DownloadsScreen(vm)
                    Tab.FAVOURITES -> FavouritesScreen(vm)
                }
            }
        }
        Rail(
            state = state,
            vm = vm,
            expanded = railFocused,
            requesters = requesters,
            remoteItem = remoteItem,
            settingsItem = settingsItem,
            restoring = { restoring },
            onFocusChange = { railFocused = it },
        )
        if (exitHint) ExitHint()
    }
}

/** Lets a tab's screen place its own first focus without the rail's generic
 *  "focus the content" racing it. Time-limited, so a screen that never
 *  manages it still ends up with the rail's fallback. */
internal object ContentFocusClaim {
    @Volatile private var until = 0L
    fun claim(ms: Long) { until = SystemClock.elapsedRealtime() + ms }
    fun release() { until = 0L }
    val active: Boolean get() = SystemClock.elapsedRealtime() < until
}

@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
private fun BoxScope.Rail(
    state: UiState,
    vm: MainViewModel,
    expanded: Boolean,
    requesters: Map<Tab, FocusRequester>,
    remoteItem: FocusRequester,
    settingsItem: FocusRequester,
    restoring: () -> FocusRequester?,
    onFocusChange: (Boolean) -> Unit,
) {
    val width by animateDpAsState(
        if (expanded) RailExpanded else RailCollapsed,
        animationSpec = tween(170),
        label = "rail-width",
    )
    Column(
        Modifier
            .align(Alignment.CenterStart)
            .fillMaxHeight()
            .width(width)
            .background(if (expanded) Color(0xF70D0F13) else Bg)
            .onFocusChanged { onFocusChange(it.hasFocus) }
            // Entering the rail lands on the CURRENT tab, not whichever item
            // happens to be level with the card you came from — pressing
            // LEFT from the first row used to open the rail on "Phone remote".
            .focusProperties {
                enter = { if (restoring() != null) FocusRequester.Default else requesters.getValue(state.tab) }
            }
            .focusGroup()
            .padding(vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(Modifier.width(RailCollapsed).height(44.dp), contentAlignment = Alignment.Center) {
            Box(
                Modifier.size(36.dp).clip(RoundedCornerShape(10.dp)).background(Accent),
                contentAlignment = Alignment.Center,
            ) {
                Text("VB", color = Color(0xFF06120B), fontWeight = FontWeight.Black, fontSize = 13.sp)
            }
        }
        Spacer(Modifier.height(14.dp))
        RailItem("Home", Icons.Filled.Home, state.tab == Tab.HOME, expanded,
            requesters.getValue(Tab.HOME)) { vm.selectTab(Tab.HOME) }
        RailItem("Live TV", Icons.Filled.LiveTv, state.tab == Tab.LIVE, expanded,
            requesters.getValue(Tab.LIVE)) { vm.selectTab(Tab.LIVE) }
        RailItem("Search", Icons.Filled.Search, state.tab == Tab.SEARCH, expanded,
            requesters.getValue(Tab.SEARCH)) { vm.selectTab(Tab.SEARCH) }
        RailItem("Downloads", Icons.Filled.Download, state.tab == Tab.DOWNLOADS, expanded,
            requesters.getValue(Tab.DOWNLOADS)) { vm.selectTab(Tab.DOWNLOADS) }
        RailItem("Favourites", Icons.Filled.Favorite, state.tab == Tab.FAVOURITES, expanded,
            requesters.getValue(Tab.FAVOURITES)) { vm.selectTab(Tab.FAVOURITES) }
        Spacer(Modifier.weight(1f))
        RailItem("Phone remote", Icons.Filled.SettingsRemote, false, expanded, remoteItem) {
            vm.openRemote()
        }
        RailItem("Settings", Icons.Filled.Settings, false, expanded, settingsItem) {
            vm.openSettings()
        }
    }
}

/**
 * One rail entry. The FOCUS TARGET is only the 72 dp icon cell, even while
 * the rail is expanded and the highlight spans the label too: if the focus
 * bounds grew with the panel, "right" from the rail would skip every card
 * hidden underneath it and land on the second or third one.
 */
@Composable
private fun RailItem(
    label: String,
    icon: ImageVector,
    selected: Boolean,
    expanded: Boolean,
    requester: FocusRequester?,
    onClick: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val tint = when {
        focused -> Color(0xFF0B0E13)
        selected -> Accent
        else -> TextMuted
    }
    Row(
        Modifier
            .padding(horizontal = 10.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(if (focused) TextPrimary else Color.Transparent),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(width = RailCollapsed - 20.dp, height = 48.dp)
                .let { if (requester != null) it.focusRequester(requester) else it }
                .onFocusChanged { focused = it.isFocused }
                .focusable()
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onClick,
                ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = label, tint = tint, modifier = Modifier.size(24.dp))
            if (selected && !focused) {
                Box(
                    Modifier
                        .align(Alignment.CenterStart)
                        .width(3.dp)
                        .height(20.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(Accent),
                )
            }
        }
        if (expanded) {
            Text(
                label,
                color = if (focused) Color(0xFF0B0E13) else if (selected) TextPrimary else TextMuted,
                fontSize = 15.sp,
                fontWeight = if (selected || focused) FontWeight.Bold else FontWeight.Medium,
                maxLines = 1,
                modifier = Modifier.padding(end = 18.dp),
            )
        }
    }
}

@Composable
private fun BoxScope.ExitHint() {
    Box(
        Modifier
            .align(Alignment.BottomCenter)
            .padding(bottom = 28.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(SurfaceElevated)
            .padding(horizontal = 18.dp, vertical = 10.dp),
    ) {
        Text("Press BACK again to exit", color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
