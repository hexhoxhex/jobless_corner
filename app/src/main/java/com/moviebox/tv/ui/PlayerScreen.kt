package com.moviebox.tv.ui

import android.app.Activity
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.net.Uri
import android.view.View
import android.view.ViewGroup
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AspectRatio
import androidx.compose.material.icons.filled.Forward10
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.moviebox.tv.data.CaptionTrack
import com.moviebox.tv.data.PlayInfo
import com.moviebox.tv.net.Constants
import com.moviebox.tv.remote.RemoteController
import com.moviebox.tv.ui.theme.Accent
import com.moviebox.tv.ui.theme.SurfaceElevated
import com.moviebox.tv.ui.theme.TextMuted

@Composable
fun PlayerScreen(state: UiState, vm: MainViewModel) {
    val play = state.play
    val context = LocalContext.current
    val view = LocalView.current
    val activity = context as? Activity
    val isLandscape =
        LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    var controlsVisible by remember { mutableStateOf(true) }
    var buffering by remember { mutableStateOf(false) }
    var playing by remember { mutableStateOf(true) }
    var statusPill by remember { mutableStateOf<String?>(null) }
    var resizeMode by remember {
        mutableStateOf(AspectRatioFrameLayout.RESIZE_MODE_FIT)
    }
    // Live scrubber state: position read from the player every 500ms, plus a
    // user-drag override that suppresses the read while the thumb is being
    // moved so the bar doesn't fight the user.
    var positionMs by remember { mutableLongStateOf(0L) }
    var durationMs by remember { mutableLongStateOf(0L) }
    var scrubbing by remember { mutableStateOf(false) }
    var scrubTo by remember { mutableLongStateOf(0L) }
    var exoRef by remember { mutableStateOf<androidx.media3.common.Player?>(null) }

    // Auto-hide controls after 3.5s idle. Any tap resets the timer.
    LaunchedEffect(controlsVisible, playing) {
        if (controlsVisible && playing && !buffering) {
            kotlinx.coroutines.delay(3500)
            controlsVisible = false
        }
    }
    // Tick the scrubber while controls are visible. No work otherwise — we
    // don't want a 500ms timer firing forever when the user can't see it.
    LaunchedEffect(controlsVisible, play) {
        if (!controlsVisible) return@LaunchedEffect
        while (true) {
            exoRef?.let {
                if (!scrubbing) positionMs = it.currentPosition
                durationMs = it.duration.coerceAtLeast(0)
            }
            kotlinx.coroutines.delay(500)
        }
    }

    // Auto-dismiss the status pill ~1.5s after it appears.
    LaunchedEffect(statusPill) {
        if (statusPill != null) {
            kotlinx.coroutines.delay(1500)
            statusPill = null
        }
    }
    // Show pill briefly whenever play/buffer state changes, so a tap feels
    // confirmed even when the user didn't toggle anything.
    LaunchedEffect(playing, buffering) {
        statusPill = when {
            buffering -> "Loading"
            playing -> "Playing"
            else -> "Paused"
        }
    }

    // Surface the current quality + dub options to the mobile remote so the
    // SPA's Now Playing pane can show pickers.
    LaunchedEffect(play) {
        if (play != null) {
            RemoteController.updatePlayTracks(
                selectedQ = play.selected,
                qs = play.qualities.map { it.label },
                selectedD = play.selectedDub,
                ds = play.dubs.map { it.name },
            )
        }
    }

    // Immersive: hide the system bars while the player is on screen, and
    // restore orientation + bars when leaving. Also keep the TV/phone screen
    // awake — Android TVs sleep on inactivity timers and ignore Media3
    // playback by itself.
    DisposableEffect(Unit) {
        val window = activity?.window
        val controller = window?.let { WindowCompat.getInsetsController(it, view) }
        controller?.hide(WindowInsetsCompat.Type.systemBars())
        controller?.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        window?.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose {
            controller?.show(WindowInsetsCompat.Type.systemBars())
            window?.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            activity?.requestedOrientation =
                ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }

    Box(
        Modifier.fillMaxSize().background(Color.Black)
            // Tap anywhere on the player surface toggles the controls. When
            // controls are hidden we treat the next tap as "show", not as
            // "interact" — feels much more like Netflix / Apple TV.
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = { controlsVisible = !controlsVisible },
            ),
    ) {
        // ---- WebView fallback for live streams whose direct HLS 403's ----
        if (play != null && play.isLive && state.useLiveWebPlayer &&
            state.currentLiveChannel != null
        ) {
            LiveWebPlayer(
                channel = state.currentLiveChannel,
                onAllPathsFailed = { msg ->
                    vm.surfaceError(msg)
                    vm.back()
                },
                modifier = Modifier.fillMaxSize(),
            )
        } else if (play != null && play.mediaUrl.isNotBlank()) {
            VideoPlayer(
                mediaUrl = play.mediaUrl,
                captions = play.captions,
                contentKey = vm.contentKey,
                onEnded = {
                    // Live streams don't "end" in the normal sense, and there's
                    // no next episode for them; only autoplay VOD series.
                    if (!play.isLive && state.autoplayNext && state.currentSe != null)
                        vm.nextEpisode()
                },
                onControlsVisible = { /* native controller is off; we own visibility */ },
                onBuffering = { buffering = it },
                onPlayingChanged = { playing = it },
                onExoReady = { exoRef = it },
                tryDowngrade = vm::downgradeQuality,
                resumeMs = if (play.isLive) 0L else state.resumeMs,
                onProgress = { pos, dur ->
                    if (!play.isLive) vm.saveProgress(pos, dur)
                },
                defaultSubtitleLang =
                    LocalConfiguration.current.locales[0].language,
                resizeMode = resizeMode,
                isLive = play.isLive,
                onLiveError = { msg ->
                    // First failure → try the WebView fallback for the same
                    // channel. If that also fails, LiveWebPlayer fires
                    // onAllPathsFailed → vm.back() with the message.
                    if (state.currentLiveChannel != null && !state.useLiveWebPlayer) {
                        vm.fallbackToWebPlayer()
                    } else {
                        vm.surfaceError(msg)
                        vm.back()
                    }
                },
                title = play.let {
                    if (it.isLive) it.title
                    else if (state.currentSe != null)
                        "S${it.season}E${it.episode} · ${it.episodeTitle.ifBlank { it.title }}"
                    else it.title
                },
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Box(Modifier.fillMaxSize(), Alignment.Center) {
                if (state.error != null) Text(state.error, color = TextMuted)
                else LoadingPulse()
            }
        }

        // Starting / re-buffering spinner over the video.
        // The new BigPlayPauseBtn already shows a spinner for buffering inside
        // the play-button circle. Show the full-screen pulse only for the
        // initial "we haven't started yet" state where the play button isn't
        // mounted yet because controls are hidden.
        if (play != null && play.mediaUrl.isNotBlank() &&
            state.playLoading && !controlsVisible
        ) {
            Box(Modifier.fillMaxSize(), Alignment.Center) { LoadingPulse() }
        }

        // Transient status pill: confirms what's happening when the user taps.
        AnimatedVisibility(
            visible = statusPill != null,
            modifier = Modifier.align(Alignment.TopCenter)
                .statusBarsPadding().padding(top = 64.dp),
        ) {
            val label = statusPill ?: ""
            Box(
                Modifier.clip(RoundedCornerShape(20.dp))
                    .background(Color(0xCC000000))
                    .padding(horizontal = 14.dp, vertical = 8.dp),
            ) {
                Text(
                    when (label) {
                        "Playing" -> "▶  Playing"
                        "Paused"  -> "⏸  Paused"
                        else      -> "•  Loading"
                    },
                    color = Color.White, fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }

        // Top overlay: back + title + audio/quality + fullscreen. Fades with
        // the native controller so the video can use the whole screen.
        AnimatedVisibility(
            visible = controlsVisible && play != null,
            modifier = Modifier.align(Alignment.TopCenter),
        ) {
            Box(
                Modifier.fillMaxWidth().background(
                    Brush.verticalGradient(
                        0f to Color(0xCC000000), 1f to Color.Transparent,
                    )
                ),
            ) {
                Row(
                    Modifier.fillMaxWidth().statusBarsPadding().padding(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    CircleBtn(Icons.AutoMirrored.Filled.ArrowBack) { vm.back() }
                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (play?.isLive == true) {
                                Box(
                                    Modifier.padding(end = 8.dp)
                                        .clip(RoundedCornerShape(4.dp))
                                        .background(Color(0xCCE5484D))
                                        .padding(horizontal = 6.dp, vertical = 2.dp),
                                ) {
                                    Text("LIVE", color = Color.White,
                                        fontSize = 10.sp, fontWeight = FontWeight.Bold)
                                }
                            }
                            Text(
                                play?.let {
                                    if (it.isLive) it.title
                                    else if (state.currentSe != null)
                                        "S${it.season}E${it.episode} · ${it.episodeTitle.ifBlank { it.title }}"
                                    else it.title
                                } ?: "",
                                color = Color.White, fontWeight = FontWeight.SemiBold,
                                fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            )
                        }
                        if (play?.isLive == true && play.subtitle.isNotBlank()) {
                            Text(play.subtitle, color = TextMuted, fontSize = 11.sp,
                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                    if (play != null && !play.isLive) {
                        if (play.dubs.size > 1) {
                            Dropdown("Audio: ${play.selectedDub}",
                                play.dubs.map { d ->
                                    (d.name + if (d.original) " (Original)" else "") to d.name
                                }) { vm.changeDub(it) }
                        }
                        Dropdown(play.selected,
                            play.qualities.map { it.label to it.label }) {
                            vm.changeQuality(it)
                        }
                    }
                    CircleBtn(Icons.Filled.AspectRatio) {
                        resizeMode = when (resizeMode) {
                            AspectRatioFrameLayout.RESIZE_MODE_FIT ->
                                AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                            AspectRatioFrameLayout.RESIZE_MODE_ZOOM ->
                                AspectRatioFrameLayout.RESIZE_MODE_FILL
                            else -> AspectRatioFrameLayout.RESIZE_MODE_FIT
                        }
                    }
                    CircleBtn(
                        if (isLandscape) Icons.Filled.FullscreenExit
                        else Icons.Filled.Fullscreen,
                    ) {
                        activity?.requestedOrientation =
                            if (isLandscape) ActivityInfo.SCREEN_ORIENTATION_USER
                            else ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                    }
                }
            }
        }

        // ---- Center cluster: skip back · play/pause · skip forward ----
        AnimatedVisibility(
            visible = controlsVisible && play != null,
            modifier = Modifier.align(Alignment.Center),
            enter = androidx.compose.animation.fadeIn(),
            exit = androidx.compose.animation.fadeOut(),
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(32.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // Live streams don't seek; show only the play/pause toggle.
                if (play?.isLive != true) {
                    SkipBtn(Icons.Filled.Replay10) {
                        exoRef?.seekTo((positionMs - 10_000).coerceAtLeast(0))
                    }
                }
                BigPlayPauseBtn(
                    playing = playing,
                    buffering = buffering,
                    onClick = {
                        exoRef?.let { it.playWhenReady = !it.isPlaying }
                    },
                )
                if (play?.isLive != true) {
                    SkipBtn(Icons.Filled.Forward10) {
                        exoRef?.seekTo(
                            (positionMs + 10_000)
                                .coerceAtMost(durationMs.coerceAtLeast(0))
                        )
                    }
                }
            }
        }

        // ---- Bottom strip: scrubber + time labels (VOD only) ----
        AnimatedVisibility(
            visible = controlsVisible && play != null && play.isLive != true,
            modifier = Modifier.align(Alignment.BottomCenter),
            enter = androidx.compose.animation.fadeIn(),
            exit = androidx.compose.animation.fadeOut(),
        ) {
            Box(
                Modifier.fillMaxWidth().background(
                    Brush.verticalGradient(
                        0f to Color.Transparent, 1f to Color(0xCC000000),
                    )
                ),
            ) {
                Column(
                    Modifier.fillMaxWidth().padding(
                        start = 18.dp, end = 18.dp, top = 12.dp, bottom = 18.dp,
                    ),
                ) {
                    val dur = durationMs.coerceAtLeast(1)
                    val cur = (if (scrubbing) scrubTo else positionMs)
                        .coerceIn(0, dur)
                    androidx.compose.material3.Slider(
                        value = cur.toFloat(),
                        valueRange = 0f..dur.toFloat(),
                        onValueChange = {
                            scrubbing = true
                            scrubTo = it.toLong()
                        },
                        onValueChangeFinished = {
                            exoRef?.seekTo(scrubTo)
                            positionMs = scrubTo
                            scrubbing = false
                        },
                        colors = androidx.compose.material3.SliderDefaults.colors(
                            thumbColor = Accent,
                            activeTrackColor = Accent,
                            inactiveTrackColor = Color(0x55FFFFFF),
                        ),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            formatTime(cur), color = Color.White,
                            fontSize = 12.sp, fontWeight = FontWeight.Medium,
                        )
                        Text(
                            formatTime(dur), color = Color.White.copy(alpha = .8f),
                            fontSize = 12.sp, fontWeight = FontWeight.Medium,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun BigPlayPauseBtn(
    playing: Boolean,
    buffering: Boolean,
    onClick: () -> Unit,
) {
    Box(
        Modifier.size(76.dp).clip(CircleShape)
            .background(Color(0xCCFFFFFF))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        if (buffering) {
            CircularProgressIndicator(
                color = Accent, strokeWidth = 3.dp,
                modifier = Modifier.size(36.dp),
            )
        } else {
            Icon(
                if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                null,
                tint = Color.Black,
                modifier = Modifier.size(40.dp),
            )
        }
    }
}

@Composable
private fun SkipBtn(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onClick: () -> Unit,
) {
    Box(
        Modifier.size(56.dp).clip(CircleShape).background(Color(0x66000000))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, null, tint = Color.White, modifier = Modifier.size(30.dp))
    }
}

private fun formatTime(ms: Long): String {
    val totalSec = (ms / 1000).coerceAtLeast(0)
    val s = totalSec % 60
    val m = (totalSec / 60) % 60
    val h = totalSec / 3600
    return if (h > 0) "%d:%02d:%02d".format(h, m, s)
    else "%d:%02d".format(m, s)
}

@Composable
private fun LoadingPulse() {
    val transition = rememberInfiniteTransition(label = "pulse")
    val alpha by transition.animateFloat(
        initialValue = 0.35f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(750), repeatMode = RepeatMode.Reverse,
        ),
        label = "alpha",
    )
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        CircularProgressIndicator(
            color = Accent, strokeWidth = 3.dp, modifier = Modifier.size(46.dp),
        )
        Text(
            "Starting…",
            color = Color.White.copy(alpha = alpha),
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
        )
    }
}

@Composable
private fun Dropdown(
    label: String,
    options: List<Pair<String, String>>,
    onPick: (String) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Box {
        Box(
            Modifier.clip(RoundedCornerShape(16.dp)).background(Color(0x55000000))
                .clickable { open = true }.padding(horizontal = 12.dp, vertical = 6.dp),
        ) {
            Text(label, color = Color.White, fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            options.forEach { (text, value) ->
                DropdownMenuItem(text = { Text(text) }, onClick = {
                    onPick(value); open = false
                })
            }
        }
    }
}

@Composable
private fun CircleBtn(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onClick: () -> Unit,
) {
    Box(
        Modifier.size(40.dp).clip(CircleShape).background(Color(0x55000000))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, null, tint = Color.White, modifier = Modifier.size(22.dp)) }
}

@Composable
private fun VideoPlayer(
    mediaUrl: String,
    captions: List<CaptionTrack>,
    contentKey: String,
    onEnded: () -> Unit,
    onControlsVisible: (Boolean) -> Unit,
    onBuffering: (Boolean) -> Unit,
    onPlayingChanged: (Boolean) -> Unit,
    /** Hand the underlying ExoPlayer back to PlayerScreen so the custom
     *  controls overlay can read currentPosition for the scrubber and seek. */
    onExoReady: (androidx.media3.common.Player) -> Unit = {},
    /** If a decoder/render error fires, ask the VM to drop one quality notch. */
    tryDowngrade: () -> Boolean = { false },
    resumeMs: Long,
    onProgress: (Long, Long) -> Unit,
    defaultSubtitleLang: String,
    resizeMode: Int,
    isLive: Boolean,
    /** Called when a live HLS stream errors fatally (token expiry, upstream
     *  offline, etc). Distinct from VOD, where we try downgrading quality. */
    onLiveError: (String) -> Unit = {},
    title: String,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val endedState = rememberUpdatedState(onEnded)
    val visibilityState = rememberUpdatedState(onControlsVisible)
    val bufferingState = rememberUpdatedState(onBuffering)
    val playingState = rememberUpdatedState(onPlayingChanged)
    val downgradeState = rememberUpdatedState(tryDowngrade)
    val progressState = rememberUpdatedState(onProgress)
    val liveErrorState = rememberUpdatedState(onLiveError)
    var lastKey by remember { mutableStateOf<String?>(null) }
    val liveState = rememberUpdatedState(isLive)

    val exo = remember(isLive) {
        // Live streams (public CDN, CORS open, no auth) want a CLEAN factory —
        // injecting the MovieBox Referer would either be ignored or rejected.
        // VOD reuses the header-injecting factory the rest of the app relies on.
        val httpFactory = DefaultHttpDataSource.Factory()
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(15_000)
            .setReadTimeoutMs(20_000)
            .setUserAgent(
                "Mozilla/5.0 (Linux; Android 12) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"
            )
            .apply {
                if (!isLive) setDefaultRequestProperties(Constants.mediaHeaders)
            }
        // Live tuning: keep the latency tight to ~12s but allow a real cushion
        // so a momentary network dip doesn't pause playback. Numbers in ms:
        //   minBuffer  / maxBuffer  / bufferForPlayback / bufferForPlaybackAfterRebuffer
        val loadControl = if (isLive) {
            DefaultLoadControl.Builder()
                .setBufferDurationsMs(15_000, 40_000, 3_000, 6_000)
                .build()
        } else {
            DefaultLoadControl.Builder()
                .setBufferDurationsMs(30_000, 120_000, 2_500, 5_000)
                .build()
        }
        val dataSourceFactory = DefaultDataSource.Factory(context, httpFactory)
        val sourceFactory = if (isLive) {
            // Explicit HLS factory: dlhd.pk streams are .m3u8 but Media3's
            // type sniffer is occasionally wrong on signed-token URLs.
            HlsMediaSource.Factory(dataSourceFactory).setAllowChunklessPreparation(true)
        } else {
            DefaultMediaSourceFactory(dataSourceFactory)
        }
        ExoPlayer.Builder(context)
            .setMediaSourceFactory(sourceFactory)
            .setLoadControl(loadControl)
            .setAudioAttributes(AudioAttributes.DEFAULT, true)
            .setHandleAudioBecomingNoisy(true)
            .build()
            .apply { playWhenReady = true }
    }

    LaunchedEffect(mediaUrl, contentKey) {
        if (mediaUrl.isBlank()) return@LaunchedEffect
        val item = if (isLive) {
            // 12s target offset = our "head space" buffer behind the live edge.
            // ExoPlayer will resync toward this target after rebuffer events.
            MediaItem.Builder()
                .setUri(mediaUrl)
                .setMimeType(MimeTypes.APPLICATION_M3U8)
                .setLiveConfiguration(
                    MediaItem.LiveConfiguration.Builder()
                        .setTargetOffsetMs(12_000)
                        .setMinOffsetMs(6_000)
                        .setMaxOffsetMs(30_000)
                        .setMinPlaybackSpeed(0.97f)
                        .setMaxPlaybackSpeed(1.03f)
                        .build()
                )
                .build()
        } else {
            // Prefer the device language; fall back to English if unavailable.
            val codes = captions.map { it.code }
            val defaultCode = if (defaultSubtitleLang in codes) defaultSubtitleLang
            else "en"
            val subs = captions.map { c ->
                val mime = if (c.url.endsWith(".vtt", true)) MimeTypes.TEXT_VTT
                else MimeTypes.APPLICATION_SUBRIP
                val b = MediaItem.SubtitleConfiguration.Builder(Uri.parse(c.url))
                    .setMimeType(mime).setLanguage(c.code).setLabel(c.name)
                if (c.code == defaultCode) b.setSelectionFlags(C.SELECTION_FLAG_DEFAULT)
                b.build()
            }
            MediaItem.Builder().setUri(mediaUrl)
                .setSubtitleConfigurations(subs).build()
        }
        // VOD: resume from saved position; quality/dub swap on same content keeps
        // the current position. Live: always go to (or near) the live edge.
        val seekTo = when {
            isLive -> C.TIME_UNSET
            contentKey == lastKey -> exo.currentPosition
            else -> resumeMs
        }
        if (seekTo == C.TIME_UNSET) exo.setMediaItem(item)
        else exo.setMediaItem(item, seekTo)
        exo.prepare()
        lastKey = contentKey
    }

    // Push state to the mobile remote every 1s; persist resume pos every ~5s.
    LaunchedEffect(Unit) {
        var tick = 0
        while (true) {
            kotlinx.coroutines.delay(1_000)
            RemoteController.updatePlayback(
                title, exo.currentPosition, exo.duration.coerceAtLeast(0),
                exo.isPlaying,
            )
            if (++tick % 5 == 0 && exo.isPlaying && exo.duration > 0) {
                progressState.value(exo.currentPosition, exo.duration)
            }
        }
    }

    DisposableEffect(Unit) {
        RemoteController.player = exo
        onExoReady(exo)
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                bufferingState.value(playbackState == Player.STATE_BUFFERING)
                if (playbackState == Player.STATE_ENDED) endedState.value()
            }
            override fun onIsPlayingChanged(isPlayingNow: Boolean) {
                playingState.value(isPlayingNow)
            }
            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                if (liveState.value) {
                    // Live HLS doesn't have a quality ladder to drop into, and
                    // a token-expiry or upstream-offline failure leaves the
                    // player stuck at 00:00 with no feedback. Surface a
                    // friendly error and pop back to the channel grid.
                    val cause = error.cause
                    val msg = when {
                        cause is androidx.media3.datasource.HttpDataSource
                            .InvalidResponseCodeException &&
                            cause.responseCode in listOf(401, 403, 410) ->
                            "This channel is offline right now."
                        else -> "Couldn't connect — try another channel."
                    }
                    liveErrorState.value(msg)
                    return
                }
                // VOD: try one notch lower (e.g. 1080P HEVC → 720P / 480P H.264).
                // If no lower quality is available, the player is left paused
                // and the user can hit back.
                downgradeState.value()
            }
        }
        exo.addListener(listener)
        onDispose {
            if (exo.duration > 0) {
                progressState.value(exo.currentPosition, exo.duration)
            }
            RemoteController.clearPlayback()
            exo.removeListener(listener)
            exo.release()
        }
    }

    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            PlayerView(ctx).apply {
                player = exo
                useController = false   // we own the chrome — see PlayerScreen
                setShowSubtitleButton(false)
                setShowBuffering(PlayerView.SHOW_BUFFERING_NEVER)
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                )
            }
        },
        update = { it.resizeMode = resizeMode },
    )
}
