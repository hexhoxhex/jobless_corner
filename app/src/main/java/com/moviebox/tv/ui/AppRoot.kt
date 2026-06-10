package com.moviebox.tv.ui

import android.app.UiModeManager
import android.content.Context
import android.content.res.Configuration
import android.media.AudioManager
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.LiveTv
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SettingsRemote
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.moviebox.tv.data.TastePrefs
import com.moviebox.tv.remote.RemoteAccess
import com.moviebox.tv.remote.RemoteController
import com.moviebox.tv.ui.theme.Accent
import com.moviebox.tv.ui.theme.Surface
import com.moviebox.tv.ui.theme.TextMuted

/** Read with `val isTv = LocalIsTv.current` from any Composable. */
val LocalIsTv = compositionLocalOf { false }

@Composable
fun AppRoot(vm: MainViewModel = viewModel()) {
    val state by vm.state.collectAsState()
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    val isTv = remember(configuration) { detectTv(context, configuration) }

    LaunchedEffect(Unit) {
        RemoteController.bind(
            vm, context.getSystemService(Context.AUDIO_SERVICE) as AudioManager,
        )
        RemoteAccess.init(context)
        TastePrefs.init(context)
    }

    BackHandler(
        enabled = state.showRemote || state.showSettings ||
            state.screen != Screen.TABS || state.tab != Tab.HOME,
    ) {
        when {
            state.showRemote -> vm.closeRemote()
            state.showSettings -> vm.closeSettings()
            state.screen != Screen.TABS -> vm.back()
            state.tab != Tab.HOME -> vm.selectTab(Tab.HOME)
        }
    }

    CompositionLocalProvider(LocalIsTv provides isTv) {
    Box(Modifier.fillMaxSize()) {
        when (state.screen) {
            Screen.DETAIL -> DetailScreen(state, vm)
            Screen.PLAYER -> PlayerScreen(state, vm)
            Screen.TABS -> Tabs(state, vm, isTv)
        }
        if (state.showSettings) SettingsScreen(onClose = vm::closeSettings)
        // Mobile-remote UI is only meaningful on big-screen devices.
        if (isTv) {
            if (state.showRemote) RemoteOverlay(onClose = vm::closeRemote)
            if (state.suggestRemote && !state.showRemote) {
                SuggestionPopup(
                    onShow = vm::openRemote, onDismiss = vm::dismissSuggestion,
                )
            }
        }
        // Floating error banner — surfaces failures (e.g. "title isn't
        // available") without trapping the user on a broken screen.
        val errorMsg = state.error
        if (state.screen != Screen.PLAYER && errorMsg != null) {
            ErrorBanner(errorMsg, onDismiss = vm::dismissError)
        }
    }
    }
}

@Composable
private fun BoxScope.ErrorBanner(message: String, onDismiss: () -> Unit) {
    LaunchedEffect(message) {
        kotlinx.coroutines.delay(4500)
        onDismiss()
    }
    Box(
        Modifier
            .align(Alignment.TopCenter)
            .statusBarsPadding()
            .padding(top = 70.dp, start = 16.dp, end = 16.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(Color(0xCCE5484D))
            .padding(horizontal = 14.dp, vertical = 10.dp),
    ) {
        Text(
            message,
            color = Color.White, fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

/**
 * True when running on Android TV / Google TV / Fire TV. The leanback system
 * feature is the most reliable signal (set by the OEM for TVs); we also accept
 * UI_MODE_TYPE_TELEVISION as a hint.
 */
private fun detectTv(context: Context, configuration: Configuration): Boolean {
    val pm = context.packageManager
    if (pm.hasSystemFeature("android.software.leanback") ||
        pm.hasSystemFeature("amazon.hardware.fire_tv")
    ) return true
    val typeBits = configuration.uiMode and Configuration.UI_MODE_TYPE_MASK
    if (typeBits == Configuration.UI_MODE_TYPE_TELEVISION) return true
    val uim = context.getSystemService(Context.UI_MODE_SERVICE) as? UiModeManager
    return uim?.currentModeType == Configuration.UI_MODE_TYPE_TELEVISION
}

@Composable
private fun Tabs(state: UiState, vm: MainViewModel, isTv: Boolean) {
    Scaffold(
        topBar = {
            Row(
                Modifier.fillMaxWidth().statusBarsPadding()
                    .padding(start = 16.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Vijana BaruBaru", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                Row {
                    if (isTv) {
                        IconButton(onClick = { vm.openRemote() }) {
                            Icon(
                                Icons.Filled.SettingsRemote, "Mobile Remote",
                                tint = Accent,
                            )
                        }
                    }
                    IconButton(onClick = { vm.openSettings() }) {
                        Icon(Icons.Filled.Settings, "Settings", tint = TextMuted)
                    }
                }
            }
        },
        bottomBar = {
            NavigationBar(containerColor = Surface) {
                NavItem("Home", Icons.Filled.Home, state.tab == Tab.HOME) {
                    vm.selectTab(Tab.HOME)
                }
                NavItem("Live TV", Icons.Filled.LiveTv, state.tab == Tab.LIVE) {
                    vm.selectTab(Tab.LIVE)
                }
                NavItem("Search", Icons.Filled.Search, state.tab == Tab.SEARCH) {
                    vm.selectTab(Tab.SEARCH)
                }
                NavItem("Downloads", Icons.Filled.Download, state.tab == Tab.DOWNLOADS) {
                    vm.selectTab(Tab.DOWNLOADS)
                }
                NavItem("Favourites", Icons.Filled.Favorite, state.tab == Tab.FAVOURITES) {
                    vm.selectTab(Tab.FAVOURITES)
                }
            }
        },
    ) { inner ->
        Box(Modifier.fillMaxSize().padding(inner)) {
            when (state.tab) {
                Tab.HOME -> HomeScreen(state, vm)
                Tab.LIVE -> LiveTvScreen(state, vm)
                Tab.SEARCH -> SearchScreen(state, vm)
                Tab.DOWNLOADS -> DownloadsScreen(vm)
                Tab.FAVOURITES -> FavouritesScreen(vm)
            }
        }
    }
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.NavItem(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    selected: Boolean,
    onClick: () -> Unit,
) {
    NavigationBarItem(
        selected = selected,
        onClick = onClick,
        icon = { Icon(icon, label) },
        label = { Text(label) },
        colors = NavigationBarItemDefaults.colors(
            selectedIconColor = Color.White,
            selectedTextColor = Accent,
            indicatorColor = Accent,
            unselectedIconColor = TextMuted,
            unselectedTextColor = TextMuted,
        ),
    )
}
