package com.moviebox.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import coil3.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Vijana BaruBaru — Desktop edition. Phase 1 milestones tonight:
 *  - Compose Multiplatform Desktop window
 *  - MovieWay-dark palette (matches Android v0.1.86+)
 *  - Real H5 home rows from aoneroom
 *  - Coil 3 multiplatform poster images
 *  - Click into detail (title + summary + Play button)
 *  - VLCJ playback for movies (real video in the same window)
 *
 * Navigation state is intentionally tiny — a sealed-ish enum + the
 * currently-open H5Item. No Compose Navigation library yet; once we
 * add Live TV or Search a real nav stack will be worth it.
 */
sealed interface Screen {
    data object Home : Screen
    data class Detail(val item: H5Item) : Screen
    data class Player(val item: H5Item, val mediaUrl: String) : Screen
}

fun main() = application {
    val state = rememberWindowState(width = 1280.dp, height = 800.dp)
    Window(
        onCloseRequest = ::exitApplication,
        title = "Vijana BaruBaru",
        state = state,
    ) {
        MaterialTheme(colorScheme = movieWayDark()) {
            App()
        }
    }
}

@Composable
private fun App() {
    var screen by remember { mutableStateOf<Screen>(Screen.Home) }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        when (val s = screen) {
            Screen.Home -> HomeScreen(onItemClick = { screen = Screen.Detail(it) })
            is Screen.Detail -> DetailScreen(
                item = s.item,
                onBack = { screen = Screen.Home },
                onPlay = { mediaUrl ->
                    screen = Screen.Player(item = s.item, mediaUrl = mediaUrl)
                },
            )
            is Screen.Player -> PlayerScreen(
                item = s.item, mediaUrl = s.mediaUrl,
                onBack = { screen = Screen.Detail(s.item) },
            )
        }
    }
}

private fun movieWayDark() = darkColorScheme(
    background = Color(0xFF101114),
    surface = Color(0xFF191F2B),
    primary = Color(0xFF07B84E),       // brand green
    secondary = Color(0xFF2FF58B),
    onBackground = Color.White,
    onSurface = Color.White,
    onPrimary = Color.Black,
)

// ---- HOME --------------------------------------------------------------

@Composable
private fun HomeScreen(onItemClick: (H5Item) -> Unit) {
    var rows by remember { mutableStateOf<List<H5Row>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        runCatching { withContext(Dispatchers.IO) { H5Api.home() } }
            .onSuccess { rows = it; loading = false }
            .onFailure { error = it.message ?: "Failed to load"; loading = false }
    }

    when {
        loading -> CenterStatus("Loading catalog…")
        error != null -> CenterStatus("Couldn't load: $error")
        rows.isEmpty() -> CenterStatus("Empty catalog.")
        else -> Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            Text(
                "Vijana BaruBaru",
                fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Color.White,
                modifier = Modifier.padding(start = 24.dp, top = 16.dp, bottom = 8.dp),
            )
            rows.forEach { RowSection(it, onItemClick) }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun RowSection(row: H5Row, onItemClick: (H5Item) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(vertical = 12.dp)) {
        Text(
            row.title,
            fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = Color.White,
            modifier = Modifier.padding(horizontal = 24.dp),
        )
        LazyRow(
            contentPadding = PaddingValues(horizontal = 24.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(row.items) { item -> PosterCard(item, onClick = { onItemClick(item) }) }
        }
    }
}

@Composable
private fun PosterCard(item: H5Item, onClick: () -> Unit) {
    Column(
        modifier = Modifier.width(140.dp).clickable { onClick() },
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(
            modifier = Modifier
                .width(140.dp).height(210.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(Color(0xFF28292E)),
        ) {
            AsyncImage(
                model = item.coverUrl,
                contentDescription = item.title,
                modifier = Modifier.fillMaxSize(),
            )
        }
        Text(
            item.title,
            fontSize = 13.sp, fontWeight = FontWeight.Medium,
            color = Color.White, maxLines = 1,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            item.year?.let { Text("$it", fontSize = 11.sp, color = Color(0xFF9BA0A8)) }
            item.rating?.let {
                Text("★ %.1f".format(it), fontSize = 11.sp, color = Color(0xFFF5C518), fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

// ---- DETAIL ------------------------------------------------------------

@Composable
private fun DetailScreen(item: H5Item, onBack: () -> Unit, onPlay: (String) -> Unit) {
    var detail by remember(item.subjectId) { mutableStateOf<H5Detail?>(null) }
    var loading by remember(item.subjectId) { mutableStateOf(true) }
    var playLoading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(item.subjectId) {
        runCatching { withContext(Dispatchers.IO) { H5Api.detail(item.subjectId, item.title) } }
            .onSuccess { detail = it; loading = false }
            .onFailure { error = it.message; loading = false }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Row(
            Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.Filled.ArrowBack, "Back", tint = Color.White)
            }
            Spacer(Modifier.width(8.dp))
            Text(item.title, fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Color.White)
        }
        Row(
            Modifier.padding(horizontal = 24.dp),
            horizontalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            Box(
                modifier = Modifier
                    .width(200.dp).aspectRatio(2f / 3f)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color(0xFF28292E)),
            ) {
                AsyncImage(item.coverUrl, item.title, Modifier.fillMaxSize())
            }
            Column(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.weight(1f)) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    item.year?.let { Text("$it", color = Color(0xFF9BA0A8)) }
                    item.rating?.let {
                        Text("★ %.1f".format(it), color = Color(0xFFF5C518), fontWeight = FontWeight.SemiBold)
                    }
                    val typeLabel = (detail?.takeIf { it.seasons.isNotEmpty() }?.isSeries ?: (item.type == 1))
                        .let { isSeries -> if (isSeries) "TV Series" else "Movie" }
                    Text(typeLabel, color = Color(0xFF9BA0A8))
                }
                when {
                    loading -> Text("Loading detail…", color = Color(0xFF9BA0A8))
                    error != null -> Text("Couldn't load detail: $error", color = Color(0xFFE5484D))
                    detail != null && detail!!.description.isNotBlank() -> {
                        Text("Summary", color = Color.White, fontWeight = FontWeight.Bold)
                        Text(detail!!.description, color = Color(0xFFEDF0F5), fontSize = 13.sp)
                    }
                }
                Button(
                    onClick = {
                        if (playLoading) return@Button
                        playLoading = true
                        // Resolve play URL on IO then jump to PlayerScreen.
                        // (Direct thread/coroutine since we're outside a
                        // suspend context here.)
                        Thread {
                            val isSeries = detail?.isSeries == true
                            val s1 = detail?.seasons?.firstOrNull()?.season ?: 1
                            val result = runCatching {
                                H5Api.play(
                                    subjectId = item.subjectId,
                                    season = if (isSeries) s1 else 0,
                                    episode = if (isSeries) 1 else 0,
                                )
                            }.getOrNull()
                            val url = result?.streams?.firstOrNull()?.url
                            // Compose state mutation needs to happen on the
                            // main thread; SwingUtilities.invokeLater works
                            // because Compose Desktop dispatches via Swing.
                            javax.swing.SwingUtilities.invokeLater {
                                playLoading = false
                                if (url != null) onPlay(url)
                                else error = "Couldn't resolve playback for this title."
                            }
                        }.start()
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = Color.Black,
                    ),
                    modifier = Modifier.height(48.dp),
                ) {
                    if (playLoading) {
                        CircularProgressIndicator(color = Color.Black, modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                        Text("Resolving…", fontWeight = FontWeight.Bold)
                    } else {
                        Icon(Icons.Filled.PlayArrow, null)
                        Spacer(Modifier.width(4.dp))
                        Text("Play", fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

// ---- PLAYER ------------------------------------------------------------

@Composable
private fun PlayerScreen(item: H5Item, mediaUrl: String, onBack: () -> Unit) {
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        VlcPlayer(mediaUrl = mediaUrl, modifier = Modifier.fillMaxSize())
        // Floating back button (top-left).
        IconButton(
            onClick = onBack,
            modifier = Modifier
                .padding(16.dp)
                .background(Color.Black.copy(alpha = 0.6f), CircleShape)
                .size(40.dp),
        ) {
            Icon(Icons.Filled.ArrowBack, "Back", tint = Color.White)
        }
        // Title pill (top-right, subtle).
        Text(
            item.title,
            color = Color.White.copy(alpha = 0.8f),
            fontSize = 12.sp,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(16.dp)
                .background(Color.Black.copy(alpha = 0.5f), RoundedCornerShape(8.dp))
                .padding(horizontal = 10.dp, vertical = 4.dp),
        )
    }
}

// ---- shared ------------------------------------------------------------

@Composable
private fun CenterStatus(message: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(12.dp))
            Text(message, color = Color.White)
        }
    }
}
