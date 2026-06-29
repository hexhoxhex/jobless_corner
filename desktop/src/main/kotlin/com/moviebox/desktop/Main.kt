package com.moviebox.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.CircularProgressIndicator
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Vijana BaruBaru — Desktop edition. First runnable milestone:
 *  - Compose Multiplatform Desktop window
 *  - MovieWay-style dark palette (matches Android v0.1.86+)
 *  - Loads the H5 home rows from aoneroom (real catalog, not mock data)
 *  - Renders title, poster placeholder, year, rating per item
 *
 * What's still ahead before this is shippable:
 *  - Real poster images (Coil 3 multiplatform or AsyncImage equivalent)
 *  - Detail page + episode picker
 *  - VLCJ playback for movies (Phase 1)
 *  - Live TV grid + LiveStreamProxy (Phase 2)
 *  - Mac .dmg + Windows .msi installer packaging (compose.desktop has it built-in)
 */
fun main() = application {
    val state = rememberWindowState(width = 1280.dp, height = 800.dp)
    Window(
        onCloseRequest = ::exitApplication,
        title = "Vijana BaruBaru",
        state = state,
    ) {
        MaterialTheme(colorScheme = movieWayDark()) {
            HomeScreen()
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

@Composable
private fun HomeScreen() {
    var rows by remember { mutableStateOf<List<H5Row>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        runCatching {
            withContext(Dispatchers.IO) { H5Api.home() }
        }.onSuccess { result ->
            rows = result
            loading = false
        }.onFailure { e ->
            error = e.message ?: "Failed to load home"
            loading = false
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        when {
            loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.height(12.dp))
                    Text("Loading catalog…", color = Color.White)
                }
            }
            error != null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("Couldn't load: $error", color = Color.White)
            }
            rows.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("Empty catalog (server responded but no rows).", color = Color.White)
            }
            else -> Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState())
            ) {
                Text(
                    "Vijana BaruBaru",
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                    modifier = Modifier.padding(start = 24.dp, top = 16.dp, bottom = 8.dp),
                )
                rows.forEach { row ->
                    RowSection(row)
                }
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}

@Composable
private fun RowSection(row: H5Row) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(vertical = 12.dp)) {
        Text(
            row.title,
            fontSize = 16.sp,
            fontWeight = FontWeight.SemiBold,
            color = Color.White,
            modifier = Modifier.padding(horizontal = 24.dp),
        )
        LazyRow(
            contentPadding = PaddingValues(horizontal = 24.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(row.items) { item -> PosterCard(item) }
        }
    }
}

@Composable
private fun PosterCard(item: H5Item) {
    Column(
        modifier = Modifier.width(140.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        // Poster placeholder (real images via Coil multiplatform later).
        Box(
            modifier = Modifier
                .width(140.dp)
                .height(210.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(Color(0xFF28292E)),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                item.title.take(2).uppercase(),
                color = Color(0xFF767B85),
                fontSize = 28.sp,
                fontWeight = FontWeight.Bold,
            )
        }
        Text(
            item.title,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            color = Color.White,
            maxLines = 1,
        )
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            item.year?.let {
                Text("$it", fontSize = 11.sp, color = Color(0xFF9BA0A8))
            }
            item.rating?.let {
                Text("★ %.1f".format(it), fontSize = 11.sp, color = Color(0xFFF5C518), fontWeight = FontWeight.SemiBold)
            }
        }
    }
}
