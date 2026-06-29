package com.moviebox.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.SwingPanel
import androidx.compose.ui.graphics.Color
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import uk.co.caprica.vlcj.factory.discovery.NativeDiscovery
import uk.co.caprica.vlcj.player.component.EmbeddedMediaPlayerComponent

/**
 * VLCJ-backed video player. libvlc discovery happens once globally
 * (calling NativeDiscovery().discover() repeatedly is fine but wasteful).
 * The actual video render area is a Swing component bridged into Compose
 * via SwingPanel.
 *
 * libvlc lookup order on each OS:
 *  - Mac: /Applications/VLC.app/Contents/MacOS/lib (user must have VLC installed)
 *  - Windows: registry → C:\Program Files\VideoLAN\VLC
 *  - Linux: /usr/lib/x86_64-linux-gnu and friends
 *
 * If discovery fails, the player shows a clear "VLC not found — install it
 * from videolan.org" message instead of silently crashing.
 *
 * Later: bundle libvlc with the packaged installer so the user doesn't
 * need a separate VLC install. compose.desktop's nativeDistributions block
 * can include native libraries.
 */
object VlcDiscovery {
    @Volatile private var ran = false
    @Volatile var ok = false
        private set
    fun ensure() {
        if (ran) return
        synchronized(this) {
            if (ran) return
            ok = runCatching { NativeDiscovery().discover() }.getOrDefault(false)
            ran = true
        }
    }
}

@Composable
fun VlcPlayer(
    mediaUrl: String,
    modifier: Modifier = Modifier,
) {
    LaunchedEffect(Unit) { VlcDiscovery.ensure() }
    if (!VlcDiscovery.ok) {
        Box(
            modifier = modifier.background(Color.Black),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                "VLC is required for playback.\nInstall from videolan.org and re-launch.",
                color = Color.White,
            )
        }
        return
    }

    var component by remember { mutableStateOf<EmbeddedMediaPlayerComponent?>(null) }
    var playing by remember { mutableStateOf(false) }

    Box(modifier.background(Color.Black)) {
        SwingPanel(
            background = Color.Black,
            modifier = Modifier.fillMaxSize(),
            factory = {
                val c = EmbeddedMediaPlayerComponent()
                component = c
                c
            },
        )
        if (!playing) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
            }
        }
    }

    LaunchedEffect(mediaUrl, component) {
        val c = component ?: return@LaunchedEffect
        if (mediaUrl.isBlank()) return@LaunchedEffect
        runCatching {
            c.mediaPlayer().media().play(mediaUrl)
            playing = true
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            runCatching {
                component?.mediaPlayer()?.controls()?.stop()
                component?.release()
            }
        }
    }
}
