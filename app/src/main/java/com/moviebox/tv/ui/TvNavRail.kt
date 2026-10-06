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
            runCatching { content.requestFocus() }
            delay(250)
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
            when (state.tab) {
                Tab.HOME -> HomeScreen(state, vm)
                Tab.LIVE -> LiveTvScreen(state, vm)
                Tab.SEARCH -> SearchScreen(state, vm)
                Tab.DOWNLOADS -> DownloadsScreen(vm)
                Tab.FAVOURITES -> FavouritesScreen(vm)
            }
        }
        Rail(
            state = state,
            vm = vm,
            expanded = railFocused,
            requesters = requesters,
            onFocusChange = { railFocused = it },
        )
        if (exitHint) ExitHint()
    }
}

@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
private fun BoxScope.Rail(
    state: UiState,
    vm: MainViewModel,
    expanded: Boolean,
    requesters: Map<Tab, FocusRequester>,
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
            .focusProperties { enter = { requesters.getValue(state.tab) } }
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
        RailItem("Phone remote", Icons.Filled.SettingsRemote, false, expanded, null) {
            vm.openRemote()
        }
        RailItem("Settings", Icons.Filled.Settings, false, expanded, null) {
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
