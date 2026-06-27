package com.moviebox.tv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.border
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.runtime.key
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalConfiguration
import coil.compose.AsyncImage
import com.moviebox.tv.data.Hero
import com.moviebox.tv.data.local.WatchHistoryEntity
import com.moviebox.tv.ui.theme.Accent
import com.moviebox.tv.ui.theme.SurfaceElevated
import com.moviebox.tv.ui.theme.TextMuted

@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
fun HomeScreen(state: UiState, vm: MainViewModel) {
    // OfflineLong = network has been gone >3min. Take over the whole
    // screen with a clean explainer rather than letting the home try to
    // load forever. Soft "Checking…" banner is rendered inline as the
    // first LazyColumn item further down.
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
    val isTv = LocalIsTv.current

    LazyColumn(
        contentPadding = PaddingValues(bottom = if (isTv) 36.dp else 24.dp),
        verticalArrangement = Arrangement.spacedBy(if (isTv) 28.dp else 20.dp),
    ) {
        // Soft "Checking your connection…" banner while we're in the
        // 3-minute window. After that the full NetworkOfflinePage takes
        // over above. Solid pill rather than an animated spinner — the
        // user shouldn't be alarmed.
        if (state.networkState == com.moviebox.tv.debug.NetworkMonitor.State.Checking) {
            item { NetworkCheckingBanner() }
        }
        // Update-available banner. Dismissed-for-this-session check keeps
        // the banner from coming back after the user clicks the X.
        val u = state.updateAvailable
        if (u != null && state.updateDismissedFor != u.tag) {
            item {
                val ctx = androidx.compose.ui.platform.LocalContext.current
                UpdateBanner(
                    update = u,
                    onUpdate = { vm.installUpdate(ctx) },
                    onDismiss = { vm.dismissUpdateBanner() },
                )
            }
        }
        item {
            home.heroes.firstOrNull()?.let { HeroBanner(it) { vm.openItem(it.item) } }
        }
        item {
            val continueWatching by vm.continueWatching.collectAsState()
            if (continueWatching.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    SectionHeader("Continue Watching")
                    // CRASH FIX: wrap each focusRestorer LazyRow in `key()`
                    // tied to the items' identity. Compose Foundation
                    // throws IllegalStateException "Release should only be
                    // called once" when focusRestorer holds a reference to
                    // a pinned item that gets removed/replaced on data
                    // change. Keying on the items' boundary IDs forces a
                    // fresh LazyRow + focusRestorer whenever the row's
                    // contents materially shift — avoiding the stale
                    // pinned-item reference. Same root cause as the
                    // USA-filter crash on LiveTvScreen.
                    key(
                        continueWatching.size,
                        continueWatching.firstOrNull()?.key,
                        continueWatching.lastOrNull()?.key,
                    ) {
                        LazyRow(
                            modifier = Modifier
                                .focusGroup()
                                .focusRestorer(),
                            contentPadding = PaddingValues(horizontal = if (isTv) 32.dp else 16.dp),
                            horizontalArrangement = Arrangement.spacedBy(if (isTv) 16.dp else 12.dp),
                        ) {
                            items(continueWatching, key = { it.key }) { h ->
                                ContinueCard(h, onClick = { vm.resumeFrom(h) })
                            }
                        }
                    }
                }
            }
        }
        item {
            val recs by vm.recommendations.collectAsState()
            if (recs.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    SectionHeader("For You")
                    key(
                        recs.size,
                        recs.firstOrNull()?.subjectId,
                        recs.lastOrNull()?.subjectId,
                    ) {
                        LazyRow(
                            modifier = Modifier
                                .focusGroup()
                                .focusRestorer(),
                            contentPadding = PaddingValues(horizontal = if (isTv) 32.dp else 16.dp),
                            horizontalArrangement = Arrangement.spacedBy(if (isTv) 16.dp else 12.dp),
                        ) {
                            items(recs, key = { it.subjectId }) { item ->
                                PosterCard(item) { vm.openItem(item) }
                            }
                        }
                    }
                }
            }
        }
        items(home.rows, key = { it.title }) { row ->
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                SectionHeader(row.title)
                key(
                    row.items.size,
                    row.items.firstOrNull()?.subjectId,
                    row.items.lastOrNull()?.subjectId,
                ) {
                    LazyRow(
                        modifier = Modifier
                            .focusGroup()
                            .focusRestorer(),
                        contentPadding = PaddingValues(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        items(row.items, key = { it.subjectId }) { item ->
                            PosterCard(item) { vm.openItem(item) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ContinueCard(h: WatchHistoryEntity, onClick: () -> Unit) {
    val isTv = LocalIsTv.current
    val cardW = if (isTv) 240.dp else 150.dp
    var focused by remember { mutableStateOf(false) }
    val s = if (focused) 1.04f else 1f
    Column(
        Modifier
            .width(cardW)
            .scale(s)
            .onFocusChanged { focused = it.isFocused }
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            ),
    ) {
        Box(
            Modifier.fillMaxWidth().aspectRatio(16f / 10f)
                .clip(RoundedCornerShape(10.dp))
                .border(
                    width = if (focused) 3.dp else 0.dp,
                    color = if (focused) Accent else Color.Transparent,
                    shape = RoundedCornerShape(10.dp),
                ),
        ) {
            PosterImage(h.coverUrl, h.title, Modifier.fillMaxSize())
            // Play affordance scrim
            Box(
                Modifier.fillMaxSize().background(
                    Brush.verticalGradient(
                        0f to Color.Transparent, 1f to Color(0xAA000000),
                    )
                )
            )
            // Progress bar
            Box(
                Modifier.align(Alignment.BottomStart).fillMaxWidth().height(4.dp)
                    .background(Color(0x55FFFFFF)),
            ) {
                Box(
                    Modifier.fillMaxHeight().fillMaxWidth(h.progress)
                        .background(Accent),
                )
            }
        }
        Text(
            h.title,
            fontSize = if (isTv) 14.sp else 12.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 6.dp),
        )
        Text(
            if (h.season > 0) "S${h.season}E${h.episode}" else "Resume",
            fontSize = if (isTv) 12.sp else 11.sp, color = TextMuted,
        )
    }
}

@Composable
private fun HeroBanner(hero: Hero, onClick: () -> Unit) {
    val isTv = LocalIsTv.current
    val screen = LocalConfiguration.current
    // TV: ~55% of screen height — cinematic but leaves room for the first row
    // of cards below the fold. Phone: fixed 300dp.
    val heroHeight =
        if (isTv) (screen.screenHeightDp * 0.55f).dp else 300.dp
    var focused by remember { mutableStateOf(false) }
    Box(
        Modifier
            .fillMaxWidth()
            .height(heroHeight)
            .onFocusChanged { focused = it.isFocused }
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            )
            .let { if (isTv && focused) it.border(3.dp, Accent) else it },
    ) {
        PosterImage(
            url = hero.backdropUrl,
            title = hero.item.title,
            modifier = Modifier.fillMaxSize(),
        )
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(
                    0f to Color.Transparent,
                    0.55f to Color(0x660B0D12),
                    1f to Color(0xFF0B0D12),
                )
            )
        )
        Column(
            Modifier
                .align(Alignment.BottomStart)
                .padding(if (isTv) 36.dp else 16.dp),
        ) {
            Text(
                hero.item.title,
                fontSize = if (isTv) 42.sp else 26.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White,
            )
            Spacer(Modifier.height(if (isTv) 8.dp else 4.dp))
            Text(
                buildString {
                    hero.item.year?.let { append(it) }
                    if (hero.item.genres.isNotEmpty()) {
                        if (isNotEmpty()) append("  •  ")
                        append(hero.item.genres.take(2).joinToString(" / "))
                    }
                },
                fontSize = if (isTv) 16.sp else 13.sp,
                color = TextMuted,
            )
            // On TV, give the user a clear affordance — the hero is huge and
            // would otherwise feel passive.
            if (isTv) {
                Spacer(Modifier.height(12.dp))
                Text(
                    "▶  Play",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Accent,
                )
            }
        }
        RatingPill(
            hero.item.rating,
            Modifier.align(Alignment.TopEnd).padding(if (isTv) 24.dp else 16.dp),
        )
    }
}

/** Update-available banner shown above the Home hero. Polite, dismissable.
 *  Triggers a browser-intent to the release APK URL on the user's TV. */
@Composable
private fun UpdateBanner(
    update: com.moviebox.tv.debug.UpdateChecker.Result,
    onUpdate: () -> Unit,
    onDismiss: () -> Unit,
) {
    val isTv = LocalIsTv.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = if (isTv) 32.dp else 16.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(
                androidx.compose.ui.graphics.Brush.horizontalGradient(
                    listOf(Accent.copy(alpha = 0.32f), SurfaceElevated),
                ),
            )
            .border(1.dp, Accent.copy(alpha = 0.6f), RoundedCornerShape(14.dp))
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                "Update available — ${update.name}",
                fontWeight = FontWeight.SemiBold, fontSize = 14.sp,
                color = androidx.compose.ui.graphics.Color.White,
            )
            val one = update.notes
                .replace(Regex("[#*`]+"), "")
                .lineSequence()
                .map { it.trim() }
                .firstOrNull { it.isNotEmpty() }
                ?: "Tap Download to install."
            Text(
                one, fontSize = 12.sp, color = TextMuted,
                maxLines = 2,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(12.dp))
        // While a download is in flight, replace the button with a live
        // percent + MB readout and a fill bar so the user can see it working
        // (the APK is ~40 MB — the old silent "Downloading…" toast looked
        // frozen). UpdateInstaller.downloadProgress is null when idle.
        val dl by com.moviebox.tv.debug.UpdateInstaller.downloadProgress.collectAsState()
        val status = dl
        if (status != null) {
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    if (status.pct >= 0) "Downloading ${status.pct}%"
                    else "Downloading… ${"%.1f".format(status.mb)} MB",
                    color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 12.sp,
                )
                if (status.totalMb > 0f) {
                    Text(
                        "${"%.1f".format(status.mb)} / ${"%.1f".format(status.totalMb)} MB",
                        color = TextMuted, fontSize = 10.sp,
                    )
                }
                Spacer(Modifier.height(5.dp))
                Box(
                    Modifier
                        .width(130.dp).height(5.dp)
                        .clip(RoundedCornerShape(3.dp))
                        .background(Color.White.copy(alpha = 0.2f)),
                ) {
                    if (status.pct >= 0) {
                        Box(
                            Modifier
                                .fillMaxHeight()
                                .fillMaxWidth((status.pct / 100f).coerceIn(0f, 1f))
                                .clip(RoundedCornerShape(3.dp))
                                .background(Accent),
                        )
                    }
                }
            }
        } else {
            Box(
                Modifier
                    .clip(RoundedCornerShape(18.dp))
                    .background(Accent)
                    .clickable(onClick = onUpdate)
                    .padding(horizontal = 14.dp, vertical = 8.dp),
            ) {
                Text("Download",
                    color = Color.White,
                    fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
            }
            Spacer(Modifier.width(6.dp))
            Box(
                Modifier
                    .size(34.dp)
                    .clip(RoundedCornerShape(17.dp))
                    .clickable(onClick = onDismiss),
                contentAlignment = Alignment.Center,
            ) {
                Text("×", color = TextMuted, fontSize = 18.sp)
            }
        }
    }
}

/** Soft inline banner: shown for the first 3 minutes after we lose
 *  connectivity. Keeps the rest of the home visible (history, cached
 *  rows) so the user can still scroll through what's already loaded. */
@Composable
private fun NetworkCheckingBanner() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(androidx.compose.ui.graphics.Color(0xCC2D1F40))
            .border(1.dp, androidx.compose.ui.graphics.Color(0x66FFFFFF),
                RoundedCornerShape(14.dp))
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(8.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(androidx.compose.ui.graphics.Color(0xFFE8B341)),
        )
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text("Checking your connection…",
                color = androidx.compose.ui.graphics.Color.White,
                fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
            Text("We'll keep trying quietly. Hold tight.",
                color = TextMuted, fontSize = 12.sp)
        }
    }
}

/** Full-page replacement shown when we've been offline >3 minutes. */
@Composable
private fun NetworkOfflinePage(onRetry: () -> Unit) {
    val longGone = com.moviebox.tv.debug.NetworkMonitor.timeInStateMs() >=
        com.moviebox.tv.debug.NetworkMonitor.LONG_FAIL_MS
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(horizontal = 24.dp),
        ) {
            Text("📡",
                fontSize = 56.sp,
                color = androidx.compose.ui.graphics.Color.White)
            Spacer(Modifier.height(16.dp))
            Text("No internet right now",
                fontSize = 22.sp, fontWeight = FontWeight.Bold,
                color = androidx.compose.ui.graphics.Color.White)
            Spacer(Modifier.height(8.dp))
            Text(
                if (longGone)
                    "Still no connection — try moving closer to your router or restarting it."
                else
                    "We can't reach the network. We'll keep checking and pick up automatically when it's back.",
                fontSize = 14.sp, color = TextMuted,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
            Spacer(Modifier.height(24.dp))
            Box(
                Modifier.clip(RoundedCornerShape(22.dp))
                    .background(Accent)
                    .clickable(onClick = onRetry)
                    .padding(horizontal = 22.dp, vertical = 12.dp),
            ) {
                Text("Try again",
                    color = androidx.compose.ui.graphics.Color.White,
                    fontWeight = FontWeight.SemiBold)
            }
        }
    }
}
