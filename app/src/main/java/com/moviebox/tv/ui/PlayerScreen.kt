package com.moviebox.tv.ui

import android.app.Activity
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.net.Uri
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.height
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
import androidx.compose.foundation.focusable
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
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
    // Mirror overlay visibility to the singleton MainActivity's
    // dispatchKeyEvent reads. Without this gate, DPAD_LEFT/RIGHT seeks
    // 10s even while the user is trying to navigate the overlay's own
    // buttons — the "remote becomes useless when watching" complaint.
    LaunchedEffect(controlsVisible) {
        com.moviebox.tv.remote.RemoteController.playerOverlayVisible = controlsVisible
    }
    // When the WebView fallback (LiveWebPlayer) is active, ExoPlayer's
    // periodic updatePlayback never fires, so RemoteController.title and
    // friends sit at whatever the last ExoPlayer call left them — the
    // phone SPA / Now Playing card show "" instead of the channel name.
    // Mirror the channel directly so the user can see what's on TV from
    // any remote pane while the WebView is doing the playing.
    LaunchedEffect(
        state.useLiveWebPlayer,
        state.currentLiveChannel?.id,
        state.play?.title,
    ) {
        if (state.useLiveWebPlayer && state.currentLiveChannel != null) {
            com.moviebox.tv.remote.RemoteController.updatePlayback(
                title = state.currentLiveChannel.displayName,
                pos = 0L,
                dur = 0L,
                playing = true,
            )
        }
    }
    // Mirror current episode coordinates into RemoteController so the
    // phone SPA's /api/state can show or hide the "Next / Prev episode"
    // controls based on whether we're on a series or a movie.
    LaunchedEffect(state.currentSe, state.currentEp) {
        com.moviebox.tv.remote.RemoteController.updateEpisode(
            state.currentSe, state.currentEp,
        )
    }
    DisposableEffect(Unit) {
        onDispose {
            // PlayerScreen left composition entirely — Home / list screens
            // shouldn't be considered "overlay hidden" mode by the seek
            // gate. Default back to "visible" so DPAD always passes
            // through outside of playback.
            com.moviebox.tv.remote.RemoteController.playerOverlayVisible = true
        }
    }
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
    // Live stream health — VideoPlayer reports stall recovery actions so
    // PlayerScreen can surface a small "Stabilising…" indicator instead of
    // staying silent while the buffer rebuilds.
    var stabilising by remember { mutableStateOf<String?>(null) }

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

    // Physical-remote input. Because we run with PlayerView.useController=false
    // and own all chrome in Compose, there is NO other listener for hardware
    // remote presses — without this block the TV remote does nothing on the
    // player screen (which is why Netflix/Crackle work and we don't). We grab
    // focus on mount and intercept the standard set of D-pad + media keys.
    val playerFocus = remember { FocusRequester() }
    LaunchedEffect(play?.mediaUrl) {
        if (play?.mediaUrl?.isNotBlank() == true) {
            runCatching { playerFocus.requestFocus() }
        }
    }
    val seekStep = 10_000L  // mirrors the SPA's ±10s buttons for muscle-memory consistency

    Box(
        Modifier.fillMaxSize().background(Color.Black)
            .focusRequester(playerFocus)
            .focusable()
            .onKeyEvent { evt ->
                // Compose fires both Down and Up; only act on Down so a single
                // press doesn't double-fire.
                if (evt.type != KeyEventType.KeyDown) return@onKeyEvent false
                val exo = exoRef ?: return@onKeyEvent false
                // Surface any key press by showing the controls, so the user
                // sees the affordances they're navigating with.
                controlsVisible = true
                when (evt.key) {
                    Key.DirectionLeft, Key.MediaRewind, Key.MediaSkipBackward -> {
                        // Seek 10s back; clamp at 0 so we don't trigger
                        // re-prepare on a -ve position.
                        exo.seekTo((exo.currentPosition - seekStep).coerceAtLeast(0))
                        true
                    }
                    Key.DirectionRight, Key.MediaFastForward, Key.MediaSkipForward -> {
                        exo.seekTo(exo.currentPosition + seekStep)
                        true
                    }
                    Key.MediaPlayPause -> {
                        exo.playWhenReady = !exo.isPlaying
                        true
                    }
                    Key.MediaPlay -> { exo.playWhenReady = true; true }
                    Key.MediaPause -> { exo.playWhenReady = false; true }
                    Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> {
                        // OK button: toggle play/pause if controls are
                        // already up; otherwise just bring them up. Matches
                        // Netflix's behaviour.
                        if (controlsVisible) exo.playWhenReady = !exo.isPlaying
                        true
                    }
                    Key.DirectionUp, Key.DirectionDown -> {
                        // Just show the overlay — when the custom Compose
                        // controls grow nav-target focusables, those will
                        // own up/down for picker focus.
                        true
                    }
                    else -> false
                }
            }
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
                    // Both layers of the cascade are now exhausted —
                    // record a bounce so subsequent taps short-circuit
                    // straight to the WebView path and the grid card
                    // can flag this channel as unstable.
                    vm.recordChannelBounce(state.currentLiveChannel.id)
                    vm.surfaceError(msg)
                    vm.back()
                },
                onBack = { vm.back() },
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
                onStabilising = { stabilising = it },
                tryDowngrade = vm::downgradeQuality,
                resumeMs = if (play.isLive) 0L else state.resumeMs,
                onProgress = { pos, dur ->
                    if (!play.isLive) vm.saveProgress(pos, dur)
                },
                defaultSubtitleLang =
                    LocalConfiguration.current.locales[0].language,
                resizeMode = resizeMode,
                isLive = play.isLive,
                onFatalLiveError = { vm.forceFallbackToWebPlayer() },
                onLiveError = { msg ->
                    // Long-haul resilience cascade. Returns true if the
                    // caller should also call exo.prepare() to attempt
                    // in-place recovery. Returns false if we've routed to
                    // a different recovery (WebView fallback) or are
                    // bailing entirely, in which case prepare() would
                    // just thrash a doomed ExoPlayer instance.
                    when {
                        state.currentLiveChannel == null || state.useLiveWebPlayer -> {
                            vm.surfaceError(msg)
                            vm.back()
                            false
                        }
                        vm.shouldFallbackToWebPlayer() -> {
                            // Stop battering the same channel with the
                            // same recovery cycle — the loop-detection
                            // window has elapsed, switch transports.
                            vm.fallbackToWebPlayer()
                            false
                        }
                        else -> {
                            // Most common case: token expired OR a
                            // recoverable upstream blip. refreshLiveStream
                            // invalidates the proxy cache + bumps the
                            // failure counter; PlayerScreen's caller then
                            // re-prepares ExoPlayer on the fresh state.
                            vm.refreshLiveStream()
                            true
                        }
                    }
                },
                preferSoftwareDecoder = state.currentLiveChannel?.id?.let {
                    vm.preferSoftwareDecoderFor(it)
                } ?: false,
                onCodecFlapping = {
                    state.currentLiveChannel?.id?.let {
                        vm.markChannelAsCodecFlapping(it)
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

        // Stabilising pill — only on live streams when the resilience tracker
        // takes an action (drops bitrate / re-prepares the manifest). Sits
        // slightly above the transient status pill so they don't collide.
        AnimatedVisibility(
            visible = stabilising != null,
            modifier = Modifier.align(Alignment.TopCenter)
                .statusBarsPadding().padding(top = 18.dp),
        ) {
            Box(
                Modifier.clip(RoundedCornerShape(20.dp))
                    .background(Color(0xCCE8B341))  // warm amber so it reads
                    .padding(horizontal = 14.dp, vertical = 8.dp),
            ) {
                Text(
                    "↻  ${stabilising ?: ""}",
                    color = Color.Black, fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
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
            visible = controlsVisible && play != null && !state.useLiveWebPlayer,
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
            visible = controlsVisible && play != null && !state.useLiveWebPlayer,
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
            visible = controlsVisible && play != null && play.isLive != true && !state.useLiveWebPlayer,
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

        // ---- Up Next overlay (VOD series, last 30s of an episode) ----
        // Netflix-style: appears bottom-right ~30s before the end with a
        // countdown + next episode info + Play Now / Cancel buttons. Auto-
        // advances when the timer hits 0 so the user doesn't have to do
        // anything. The user complaint was "the app fails to proceed
        // automatically when one episode ends" — ExoPlayer's STATE_ENDED
        // doesn't always fire cleanly on the heavier aoneroom resources,
        // and even when it does the seasons array may not be populated
        // yet. This overlay short-circuits both paths.
        // Detection trigger: appear once we're inside the "credits window"
        // (last 2 min by default). dlhd / aoneroom don't expose a real
        // "credits start" timestamp, so we approximate — most episodes
        // run end credits over the final ~90-120 s. Triggering this early
        // means the user sees the Up Next card during credits and can
        // either tap Play Now or wait for the 10-s auto-advance counter.
        val upNext = nextEpisodeFor(state)
        val nearEnd = play != null && !play.isLive && durationMs > 0 &&
            positionMs >= (durationMs - UP_NEXT_WINDOW_MS)
        val showUpNext = nearEnd && state.currentSe != null && upNext != null
        AnimatedVisibility(
            visible = showUpNext,
            modifier = Modifier.align(Alignment.BottomEnd)
                .padding(end = 22.dp, bottom = 82.dp),
            enter = androidx.compose.animation.fadeIn() +
                androidx.compose.animation.slideInVertically { it / 4 },
            exit = androidx.compose.animation.fadeOut(),
        ) {
            if (upNext != null) {
                // Independent 10-second auto-advance timer. Starts the moment
                // the overlay first appears, NOT tied to wall-clock remaining
                // duration — earlier versions kept the counter equal to "ms
                // until duration end" which meant a 2-min credits window
                // showed the counter at "120s, 119s, …" and only auto-fired
                // at the very end of credits. The user's "10-second next
                // button" complaint maps to a fixed 10-s window from when
                // detection fires.
                var counter by remember(upNext) { mutableStateOf(AUTO_ADVANCE_SECONDS) }
                LaunchedEffect(upNext, state.autoplayNext) {
                    counter = AUTO_ADVANCE_SECONDS
                    while (counter > 0 && state.autoplayNext) {
                        kotlinx.coroutines.delay(1_000)
                        counter -= 1
                    }
                    if (state.autoplayNext) {
                        vm.playEpisode(upNext.first, upNext.second)
                    }
                }
                UpNextCard(
                    nextSeason = upNext.first,
                    nextEpisode = upNext.second,
                    secondsRemaining = counter,
                    onPlayNow = { vm.playEpisode(upNext.first, upNext.second) },
                    onDismiss = { vm.setAutoplay(false) },
                )
            }
        }
        // End-of-content fallback when there's no next episode (movie OR
        // last episode of the series). Drop the user back to the details
        // page where "More like this" already lives — better than leaving
        // them on a static last frame for the rest of the credits.
        val endOfContent = nearEnd && state.currentSe == null && play?.isLive == false
        val endOfSeries = nearEnd && state.currentSe != null && upNext == null
        if (endOfContent || endOfSeries) {
            LaunchedEffect(state.currentSe, play?.mediaUrl) {
                // Wait for the actual end before bouncing — don't yank the
                // user out mid-credits if they're enjoying them.
                while (durationMs > 0 && positionMs < durationMs - 1_500) {
                    kotlinx.coroutines.delay(500)
                }
                if (state.autoplayNext) vm.back()
            }
        }
    }
}

/** How early (before content end) the Up Next overlay starts showing.
 *  Sized for the typical end-credit length so the user sees the card during
 *  credits instead of catching it in the last 30 seconds. */
private const val UP_NEXT_WINDOW_MS: Long = 120_000

/** Independent countdown that fires the next episode. Replaces the old
 *  "remaining wall-clock until duration end" timer, which left users with
 *  a 2-minute counter ticking through credits instead of giving them a
 *  predictable 10-second window before auto-advance. */
private const val AUTO_ADVANCE_SECONDS: Int = 10

/** A live channel that fires this many ERROR_CODE_BEHIND_LIVE_WINDOW
 *  events inside [BEHIND_LIVE_WINDOW_MS] gets force-fallback'd to the
 *  WebView player. Even with snap-to-live recovery on every error, some
 *  CDN siblings serve a manifest window smaller than the player's
 *  configured offsets allow — we drift back every ~30s and the user
 *  sees "plays, stops, plays, stops" forever. BBC One UK on zalis was
 *  the repro. WebView's playback stack doesn't share the same windowing
 *  constraints so it usually rescues these. */
private const val BEHIND_THRESHOLD: Int = 4
private const val BEHIND_LIVE_WINDOW_MS: Long = 3 * 60 * 1000L

/** Compute the next (season, episode) tuple based on current UiState, or
 *  null if there is no next episode (last episode of last season, or
 *  seasons data hasn't loaded yet). */
private fun nextEpisodeFor(state: UiState): Pair<Int, Int>? {
    val seasons = state.details?.seasons ?: return null
    val se = state.currentSe ?: return null
    val ep = state.currentEp ?: return null
    val cur = seasons.firstOrNull { it.season == se } ?: return null
    return if (ep < cur.episodes) se to (ep + 1)
    else seasons.getOrNull(seasons.indexOfFirst { it.season == se } + 1)
        ?.let { it.season to 1 }
}

@Composable
private fun UpNextCard(
    nextSeason: Int,
    nextEpisode: Int,
    secondsRemaining: Int,
    onPlayNow: () -> Unit,
    onDismiss: () -> Unit,
) {
    Box(
        Modifier.clip(RoundedCornerShape(14.dp))
            .background(Color(0xEE0A0C12))
            .border(1.dp, Color(0x55FFFFFF), RoundedCornerShape(14.dp))
            .padding(14.dp),
    ) {
        Column(horizontalAlignment = Alignment.Start) {
            Text("Up next in ${secondsRemaining}s",
                color = Color(0xFFE8B341),
                fontWeight = FontWeight.Bold, fontSize = 12.sp)
            Spacer(Modifier.height(4.dp))
            Text("S${nextSeason}·E${nextEpisode}",
                color = Color.White,
                fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(
                    Modifier.clip(RoundedCornerShape(18.dp))
                        .background(Color.White)
                        .clickable(onClick = onPlayNow)
                        .padding(horizontal = 14.dp, vertical = 7.dp),
                ) {
                    Text("Play now",
                        color = Color.Black,
                        fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
                }
                Box(
                    Modifier.clip(RoundedCornerShape(18.dp))
                        .background(Color(0x33FFFFFF))
                        .clickable(onClick = onDismiss)
                        .padding(horizontal = 14.dp, vertical = 7.dp),
                ) {
                    Text("Cancel",
                        color = Color.White,
                        fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
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
    /** Live-stream resilience — emit a short message (or null to clear) when
     *  the tracker forces a bitrate drop or re-prepares the source. */
    onStabilising: (String?) -> Unit = {},
    /** If a decoder/render error fires, ask the VM to drop one quality notch. */
    tryDowngrade: () -> Boolean = { false },
    resumeMs: Long,
    onProgress: (Long, Long) -> Unit,
    defaultSubtitleLang: String,
    resizeMode: Int,
    isLive: Boolean,
    /** Called when a live HLS stream errors fatally (token expiry, upstream
     *  offline, etc). Distinct from VOD, where we try downgrading quality. */
    /** Live-only callback fired when ExoPlayer cannot make progress
     *  (Source error, silent freeze, etc.). The outer layer decides
     *  whether we re-prepare in place (return true) or have already
     *  initiated a different recovery — typically the WebView fallback
     *  after the in-place retry has failed too many times — in which
     *  case the player should NOT call prepare() (return false). */
    onLiveError: (String) -> Boolean = { true },
    /** Permanent error that the native HLS path can never recover from
     *  for this specific stream (e.g. AUDIO_TRACK_INIT_FAILED on a
     *  channel whose audio sample-rate the device's AudioTrack rejects).
     *  Caller is expected to switch transports immediately (force-fall
     *  to the WebView player) rather than burn the resilience cascade
     *  on errors that will recur every re-prepare. */
    onFatalLiveError: () -> Unit = {},
    /** When true, ExoPlayer is built with the FFmpeg-preferred renderer
     *  factory — software decode — to sidestep a hardware codec that
     *  flaps on the current channel's content. See [LiveCodecFlapDetector]. */
    preferSoftwareDecoder: Boolean = false,
    /** Fired when [LiveCodecFlapDetector] sees the hardware decoder
     *  being torn down + rebuilt too often on a live stream. */
    onCodecFlapping: () -> Unit = {},
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
    val fatalLiveState = rememberUpdatedState(onFatalLiveError)
    val stabilisingState = rememberUpdatedState(onStabilising)
    // Track BehindLiveWindow recovery timestamps per-channel-session.
    // remember(mediaUrl) means this resets when the user switches
    // channels, so a previous channel's rotting manifest doesn't
    // count against a freshly-played one.
    val behindLiveWindow = remember(mediaUrl) { mutableListOf<Long>() }
    var lastKey by remember { mutableStateOf<String?>(null) }
    val liveState = rememberUpdatedState(isLive)

    // Live-stream resilience tracker. Kept outside ExoPlayer.Listener so the
    // listener block stays clean; the listener just feeds it events.
    val resilience = remember(isLive) { LiveResilience() }
    // Catches the "silent freeze" case where ExoPlayer stays in READY or
    // BUFFERING with playWhenReady=true but currentPosition stops
    // advancing — no Source error, no STATE_ENDED, so none of the other
    // recovery paths trigger. Ticks every 2 s while live playback is
    // active; fires recovery after 20 s of no position progress.
    val freezeWatchdog = remember(isLive) { LiveFreezeWatchdog() }
    // Hardware-decoder flap detector. Counts decoder-init events; when
    // they cluster (≥8 in 12 s), we know the Realtek/AML SoC is reading
    // every segment boundary as a format change and we should switch
    // to the software (FFmpeg) renderer for this channel.
    val flapDetector = remember(isLive) { LiveCodecFlapDetector() }
    // VOD slow-motion detector. ExoPlayer reports dropped video frames via
    // AnalyticsListener; if sustained drops exceed ~12% over an 8s window
    // the hardware decoder can't keep up with the chosen variant — fire
    // tryDowngrade() and announce it. Reset per-content via the
    // LaunchedEffect(contentKey) further down.
    val vodDrops = remember(isLive) { VodFrameDropTracker() }
    // Track selector shared between the player and the resilience logic —
    // resilience uses it to cap max bitrate after repeated stalls.
    val trackSelector = remember(isLive) {
        androidx.media3.exoplayer.trackselection.DefaultTrackSelector(context).apply {
            // Tunneled video — only for live. SurfaceFlinger on this TCL
            // panel was composing the BLAST surface at ~31 fps even though
            // the source is 59.94 fps; result was the codec dropping ~32
            // frames/sec at render and the user seeing 1 fps choppy video
            // they perceived as "the stream is reconnecting". Tunneling
            // pipes codec output past SurfaceFlinger straight to the
            // display, which is the path TCL/Realtek actually optimize
            // for live TV. VOD doesn't show the symptom (24/30 fps source
            // composites cleanly under SurfaceFlinger), so leave it off
            // there — tunneling makes some Compose UI overlays harder to
            // mix and we don't want to regress the movies path.
            if (isLive) {
                parameters = buildUponParameters()
                    .setTunnelingEnabled(true)
                    .build()
            }
        }
    }

    val exo = remember(isLive, preferSoftwareDecoder) {
        // Live streams (public CDN, CORS open, no auth) want a CLEAN factory —
        // injecting the MovieBox Referer would either be ignored or rejected.
        // VOD reuses the header-injecting factory the rest of the app relies on.
        // Live HLS: OkHttpDataSource with HTTP/1.1 forced. We previously tried
        // OkHttp with its default protocol list [H2, H1] — froze on the TCL
        // TV's TLS stack during HTTP/2 ALPN negotiation. Forcing HTTP/1.1
        // sidesteps that path entirely while still getting OkHttp's
        // connection pool + keep-alive (the actual win vs DefaultHttpDataSource,
        // which goes through Android's java.net.HttpURLConnection and pays a
        // fresh handshake more often than the browser does).
        val httpFactory = if (isLive) {
            val ok = okhttp3.OkHttpClient.Builder()
                .protocols(listOf(okhttp3.Protocol.HTTP_1_1))
                .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
                .readTimeout(20, java.util.concurrent.TimeUnit.SECONDS)
                .retryOnConnectionFailure(true)
                .build()
            androidx.media3.datasource.okhttp.OkHttpDataSource.Factory(ok)
                .setUserAgent(
                    "Mozilla/5.0 (Linux; Android 12) AppleWebKit/537.36 " +
                    "(KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"
                )
        } else {
            DefaultHttpDataSource.Factory()
                .setAllowCrossProtocolRedirects(true)
                .setConnectTimeoutMs(15_000)
                .setReadTimeoutMs(20_000)
                .setUserAgent(
                    "Mozilla/5.0 (Linux; Android 12) AppleWebKit/537.36 " +
                    "(KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"
                )
                .setDefaultRequestProperties(Constants.mediaHeaders)
        }
        // Live tuning: keep the latency tight to ~12s but allow a real cushion
        // so a momentary network dip doesn't pause playback. Numbers in ms:
        //   minBuffer  / maxBuffer  / bufferForPlayback / bufferForPlaybackAfterRebuffer
        val loadControl = if (isLive) {
            // Live: a deep buffer is our primary defense against the
            // HTTP/1.1 head-of-line blocking that ExoPlayer's DefaultHttp
            // DataSource is stuck with. minBuffer 45s gives us nearly a
            // minute of pre-loaded chunks; maxBuffer 90s lets ExoPlayer
            // get further ahead when the network IS cooperating. 10s
            // bufferForPlayback means we don't START until we have a
            // real cushion (vs 5s where a hiccup in the first 5s puts
            // us right back to buffering).
            // bufferForPlaybackAfterRebuffer was 15 s. That made every
            // recovery user-visible: even when the codec decoded its first
            // new frame in <3 s, ExoPlayer would sit on it dragging the
            // buffer up to 15 s of content before unpausing — and live
            // segments come down at roughly real-time, so that's 15 s of
            // wallclock with "Reconnecting…" on screen. 3 s is enough to
            // ride out a single segment hiccup without flapping; the deep
            // 45 s minBuffer still protects steady-state playback.
            DefaultLoadControl.Builder()
                .setBufferDurationsMs(45_000, 90_000, 10_000, 3_000)
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
            // Custom LoadErrorHandlingPolicy: Media3's default gives up after
            // 3 retries on a chunk failure, which over real Wi-Fi means a
            // single dropped segment cascades into a Source error and the
            // user sees "Reconnecting…" → bounce. 6 retries with a longer
            // backoff floor lets the LiveStreamProxy's own 3-retry budget
            // (200/400/800 ms) compose nicely — combined we tolerate ~5 s of
            // intermittent network trouble before the source-error path is
            // reached.
            HlsMediaSource.Factory(dataSourceFactory)
                .setAllowChunklessPreparation(true)
                .setLoadErrorHandlingPolicy(
                    object : androidx.media3.exoplayer.upstream
                        .DefaultLoadErrorHandlingPolicy() {
                        override fun getMinimumLoadableRetryCount(dataType: Int): Int = 6
                        override fun getRetryDelayMsFor(
                            loadErrorInfo: androidx.media3.exoplayer.upstream
                                .LoadErrorHandlingPolicy.LoadErrorInfo,
                        ): Long {
                            val attempts = loadErrorInfo.errorCount
                            // 1s, 2s, 4s, 8s, 8s, 8s — caps at 8s so we
                            // don't sit on a single dead chunk forever.
                            return (1000L shl (attempts - 1).coerceAtMost(3))
                                .coerceAtMost(8_000L)
                        }
                    }
                )
        } else {
            DefaultMediaSourceFactory(dataSourceFactory)
        }
        // Renderer factory: when [preferSoftwareDecoder] is set (the SoC
        // flapped on this channel earlier in the session), filter the
        // MediaCodec selector down to software-only decoders. Every
        // Android device ships built-in C2 software decoders
        // (c2.android.avc.decoder for H.264, c2.android.hevc.decoder for
        // HEVC, etc.) — no extra .so files needed. They're slower than
        // hardware but tolerate segment-boundary format hints without
        // tearing the codec down, which was the Realtek failure mode.
        // Use NextRenderersFactory — drop-in replacement for the Media3
        // DefaultRenderersFactory that ships an ffmpeg AAC audio decoder.
        // The platform AAC decoder on this TCL refuses to open AudioTrack
        // for 24 kHz stereo (FOX USA / FOXNY USA's audio); ffmpeg decodes
        // the same stream and outputs 48 kHz, which the AudioTrack
        // accepts. EXTENSION_RENDERER_MODE_PREFER means ffmpeg gets
        // chosen ahead of the platform decoder when both can handle the
        // mime type — costs ~5 MB APK but lets every channel play
        // natively without the WebView fallback.
        val renderersFactory = if (isLive) {
            io.github.anilbeesetti.nextlib.media3ext.ffdecoder
                .NextRenderersFactory(context)
                .setExtensionRendererMode(
                    androidx.media3.exoplayer.DefaultRenderersFactory
                        .EXTENSION_RENDERER_MODE_PREFER,
                )
        } else {
            androidx.media3.exoplayer.DefaultRenderersFactory(context)
        }
        // Audio path tweaks (both safe defaults regardless of channel):
        //  - Float output uses 32-bit float PCM, which lets the resampler
        //    handle odd source sample rates (FOXNY USA's 24 kHz audio
        //    failed AudioTrack init with the int16 default — the user's
        //    "FOXNY shows reconnecting" was actually an audio-init crash
        //    cascading through onPlayerError).
        //  - Capabilities-receiver lets ExoPlayer detect the device's
        //    supported HDMI passthrough formats and avoid asking
        //    AudioTrack for configs the TV can't accept.
        if (isLive) {
            runCatching {
                @Suppress("UnstableApiUsage")
                renderersFactory.setEnableAudioFloatOutput(true)
            }
        }
        if (preferSoftwareDecoder) {
            renderersFactory.setMediaCodecSelector { mimeType, requiresSecure, requiresTunneling ->
                androidx.media3.exoplayer.mediacodec.MediaCodecUtil
                    .getDecoderInfos(mimeType, requiresSecure, requiresTunneling)
                    .filter { !it.hardwareAccelerated }
            }
        } else if (isLive) {
            // Hardware-first selector. Default Media3 selector returned
            // c2.android.avc.decoder (Google's software AVC) for some live
            // channels (e.g. FOXNY USA 720p60) when tunneling was on, even
            // though the TCL Realtek hardware AVC decoder is present and
            // capable. The fallback happens because the hardware decoder
            // refused tunneling for that specific stream's CSD; the
            // selector then picked the next "tunneling-supporting" entry,
            // which on Android is always the software decoder. The result
            // was 720p60 software decode → MediaCodec::flush every 2 s and
            // ~600 dropped frames per minute (the user's "reconnecting"
            // complaint). Sort hardware-accelerated decoders first;
            // tunneling still applies if the chosen HW one supports it,
            // otherwise we get a non-tunneled HW decoder which is still
            // dramatically better than tunneled software.
            renderersFactory.setMediaCodecSelector { mimeType, requiresSecure, requiresTunneling ->
                androidx.media3.exoplayer.mediacodec.MediaCodecUtil
                    .getDecoderInfos(mimeType, requiresSecure, requiresTunneling)
                    .sortedByDescending { it.hardwareAccelerated }
            }
        }
        ExoPlayer.Builder(context, renderersFactory)
            .setMediaSourceFactory(sourceFactory)
            .setLoadControl(loadControl)
            .setTrackSelector(trackSelector)
            .setAudioAttributes(AudioAttributes.DEFAULT, true)
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(androidx.media3.common.C.WAKE_MODE_NETWORK)
            .build()
            .apply { playWhenReady = true }
        // WAKE_MODE_NETWORK = partial wake lock + Wi-Fi lock held while
        // the player is playing. Released when the player is paused,
        // stopped, or destroyed. The wake lock counts as ongoing user
        // activity to Android's PowerManagerService, which prevents the
        // TV's DreamX bedtime / DayDream screensaver from triggering
        // mid-stream. FLAG_KEEP_SCREEN_ON (set in the outer PlayerScreen
        // DisposableEffect) handles the panel; the wake lock handles the
        // "should we start a Dream" check that fires independently.
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
                    // Target offset trimmed 22s → 10s and max 45s → 24s.
                    // dlhd publishes a typical 6-segment × 4 s = ~24 s
                    // manifest window; sitting 22 s back left only ~2 s
                    // of buffer to the back edge before BehindLiveWindow
                    // fired, and a maxOffset of 45 s allowed drift WELL
                    // past the manifest window length. Nick / kolis with
                    // ~1 s playlist fetches looped on this every 30-60 s
                    // — error, snap to default position (still old slow
                    // offset), drift again. New numbers keep us closer to
                    // the live edge so a slow CDN can't push us off the
                    // back. Also bump maxPlaybackSpeed to 1.10 so the
                    // player can speed up to catch up to live edge after
                    // a hiccup before falling out of the window.
                    // v0.1.43: tightened again. The 10/4/24 numbers still
                    // landed us against the back edge on slow CDNs (vomos
                    // for FOX USA, zalis for BBC One UK) — manifest
                    // window is ~24 s, maxOffset of 24 s left ZERO
                    // safety margin. Pull everything closer to the live
                    // edge and bump maxPlaybackSpeed to 1.15 so the
                    // player aggressively catches up before drifting
                    // off the back. 8 s safety margin (24 – 16) instead
                    // of 0.
                    MediaItem.LiveConfiguration.Builder()
                        .setTargetOffsetMs(6_000)
                        .setMinOffsetMs(2_000)
                        .setMaxOffsetMs(16_000)
                        .setMinPlaybackSpeed(0.95f)
                        .setMaxPlaybackSpeed(1.15f)
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
            // Feed the Debug pane's realtime metrics.
            com.moviebox.tv.debug.Telemetry.updateRealtime(
                bufferMs = exo.totalBufferedDuration,
                bitrateBps = exo.videoFormat?.bitrate?.toLong()?.takeIf { it > 0 } ?: 0L,
                resolution = exo.videoFormat?.let { f ->
                    if (f.width > 0 && f.height > 0) "${f.width}x${f.height}" else ""
                } ?: "",
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
                val isBuffering = playbackState == Player.STATE_BUFFERING
                bufferingState.value(isBuffering)
                if (playbackState == Player.STATE_ENDED) {
                    if (liveState.value) {
                        // Live streams should never genuinely "end" while the
                        // user is watching. ExoPlayer transitions to ENDED
                        // when the upstream HLS playlist either returned an
                        // `#EXT-X-ENDLIST` tag (upstream signed off) or
                        // simply stopped advancing past the live edge
                        // (network blip, origin hiccup). Either way the
                        // recovery is the same: seek to live edge and
                        // re-prepare. Was previously a no-op via endedState,
                        // which left the player silently paused — that's
                        // exactly the "channel pausing by itself" behaviour
                        // the user reported.
                        stabilisingState.value("Reconnecting…")
                        com.moviebox.tv.debug.Telemetry.note(
                            com.moviebox.tv.debug.Telemetry.Severity.WARN,
                            "Live STATE_ENDED — seeking to live edge",
                        )
                        runCatching {
                            exo.seekTo(androidx.media3.common.C.TIME_UNSET)
                            exo.prepare()
                        }
                    } else {
                        endedState.value()
                    }
                }
                if (isBuffering && playbackState != Player.STATE_BUFFERING) {
                    // No-op; left for parallel structure with the telemetry
                    // call below — we only want to count "transition INTO
                    // buffering" not "still buffering".
                }
                if (isBuffering) {
                    com.moviebox.tv.debug.Telemetry.onBufferStart()
                }
                // Live resilience: feed state transitions to the tracker.
                if (liveState.value) {
                    resilience.onBufferingChanged(
                        buffering = isBuffering,
                        player = exo,
                        trackSelector = trackSelector,
                        announce = { msg ->
                            stabilisingState.value(msg)
                            if (msg != null) com.moviebox.tv.debug.Telemetry.note(
                                com.moviebox.tv.debug.Telemetry.Severity.WARN, msg,
                            )
                        },
                    )
                }
            }
            override fun onIsPlayingChanged(isPlayingNow: Boolean) {
                playingState.value(isPlayingNow)
            }
            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                com.moviebox.tv.debug.Telemetry.onPlayFailed(
                    error.cause?.message ?: error.message ?: "playback error",
                )
                val httpCode = (error.cause as? androidx.media3.datasource
                    .HttpDataSource.InvalidResponseCodeException)?.responseCode
                android.util.Log.w(
                    "LiveDiag",
                    "PLAYER onPlayerError live=${liveState.value} " +
                        "code=$httpCode errName=${error.errorCodeName} " +
                        "cause=${error.cause?.javaClass?.simpleName} " +
                        "msg=${error.cause?.message ?: error.message}",
                )
                if (liveState.value) {
                    val cause = error.cause
                    // BEHIND_LIVE_WINDOW: the player drifted further behind
                    // the live edge than the manifest covers (typical on
                    // slow CDN siblings — kolis often does ~1 s playlist
                    // fetches that drain the 22 s buffer faster than it
                    // fills). The right recovery is just to jump to the
                    // current live edge — NOT to refresh the proxy cache,
                    // which doesn't help and counts against the WebView
                    // fallback budget. Without this specific handler the
                    // player loops every 30 s: error → refreshLiveStream
                    // → re-prepare → buffers ~30 s → falls behind again
                    // → error. User reported as "Nick plays for 30 s then
                    // shows black screen / trying to recover" forever.
                    if (error.errorCode == androidx.media3.common.PlaybackException
                            .ERROR_CODE_BEHIND_LIVE_WINDOW
                    ) {
                        // Count BehindLiveWindow events per channel. After
                        // BEHIND_THRESHOLD in a rolling window, the snap-
                        // to-live recovery isn't actually keeping up — the
                        // CDN's manifest window is shorter than our offsets
                        // tolerate, so we drift back every 30 s no matter
                        // what we do. Surface this as a permanent native-
                        // path failure and let the WebView fallback take
                        // over (it's a different playback stack that
                        // doesn't have the same windowing constraints).
                        val now = SystemClock.elapsedRealtime()
                        val recentBehind = behindLiveWindow
                            .filter { now - it < BEHIND_LIVE_WINDOW_MS }
                            .toMutableList()
                            .apply { add(now) }
                        behindLiveWindow.clear(); behindLiveWindow.addAll(recentBehind)
                        if (recentBehind.size >= BEHIND_THRESHOLD) {
                            android.util.Log.w(
                                "LiveDiag",
                                "PLAYER BEHIND_LIVE_WINDOW × ${recentBehind.size}" +
                                    " in ${BEHIND_LIVE_WINDOW_MS / 1000}s — manifest" +
                                    " window too short for native player; force" +
                                    " WebView fallback",
                            )
                            behindLiveWindow.clear()
                            fatalLiveState.value()
                            return
                        }
                        android.util.Log.w(
                            "LiveDiag",
                            "PLAYER BEHIND_LIVE_WINDOW (${recentBehind.size}" +
                                "/$BEHIND_THRESHOLD) — snap closer to live edge",
                        )
                        runCatching {
                            // v0.1.43: seekToDefaultPosition lands at
                            // TARGET_OFFSET (6 s back) — same place we
                            // just drifted off from. Instead, jump to
                            // the live window's tail so we get max
                            // margin before the next drift can hit the
                            // back edge. duration() on a live source is
                            // the manifest's current length; window
                            // = [0..duration()].
                            val win = exo.duration
                            if (win > 0) exo.seekTo(maxOf(0L, win - 2_000))
                            else exo.seekToDefaultPosition()
                            exo.prepare()
                        }
                        return
                    }
                    // Audio-init failure used to fast-path to WebView for
                    // streams with sample rates the platform AudioTrack
                    // refuses (FOX USA / FOXNY USA at 24 kHz). With the
                    // ffmpeg audio renderer (v0.1.42) the ffmpeg AAC
                    // decoder outputs 48 kHz regardless of source rate,
                    // so this error code shouldn't appear for normal
                    // streams. Keep the WebView fast-path as a safety
                    // net for any case ffmpeg also fails — but it now
                    // means a real codec problem, not "TCL just doesn't
                    // like this sample rate."
                    if (error.errorCode == androidx.media3.common.PlaybackException
                            .ERROR_CODE_AUDIO_TRACK_INIT_FAILED
                    ) {
                        android.util.Log.w(
                            "LiveDiag",
                            "PLAYER audio-init failure — fast-pathing " +
                                "to WebView fallback for this channel",
                        )
                        // Tell the resilience cascade to skip its retry
                        // budget and switch transports immediately.
                        fatalLiveState.value()
                        return
                    }
                    val msg = when {
                        cause is androidx.media3.datasource.HttpDataSource
                            .InvalidResponseCodeException &&
                            cause.responseCode in listOf(401, 403, 410) ->
                            "This channel is offline right now."
                        else -> "Couldn't connect — try another channel."
                    }
                    // Recover the player IN PLACE. Previously we only surfaced
                    // a friendly message and let the outer onLiveError chain
                    // call vm.refreshLiveStream() — which only bumped a counter
                    // without ever telling ExoPlayer to leave ERROR state. The
                    // channel would stay paused with the last decoded frame on
                    // screen until the user manually navigated away. This is
                    // the "channel pauses itself" symptom the user reported
                    // for the case where the upstream cut wins the race
                    // against the LiveStreamProxy stall detector (15 s
                    // threshold) — e.g. a single segment 4xx that ExoPlayer
                    // sees direct from the CDN, never via our proxy.
                    //
                    // Order matters here. The outer onLiveError callback
                    // runs synchronously when we invoke liveErrorState.value,
                    // and for the in-place-retry branch it calls
                    // vm.refreshLiveStream() → liveProxy.invalidate(channelId).
                    // We need that cache invalidation to complete BEFORE
                    // exo.prepare() fires its first /master/+/inner/ fetches,
                    // otherwise ExoPlayer races the invalidate and lands on
                    // the still-stale cache entry.
                    //
                    // Sequence: surface message → outer invalidates proxy
                    // → exo.seekTo+prepare picks up clean state.
                    stabilisingState.value("Reconnecting…")
                    com.moviebox.tv.debug.Telemetry.note(
                        com.moviebox.tv.debug.Telemetry.Severity.WARN,
                        "Live source error — re-preparing",
                    )
                    val shouldPrepare = liveErrorState.value(msg)
                    if (shouldPrepare) {
                        runCatching {
                            exo.seekTo(androidx.media3.common.C.TIME_UNSET)
                            exo.prepare()
                        }
                    }
                    // If shouldPrepare == false the outer handler already
                    // routed to a different recovery (WebView fallback or
                    // bounce back to channel grid). Re-preparing would just
                    // start a new BUFFERING cycle on a player that's about
                    // to be disposed.
                    return
                }
                // VOD: try one notch lower (e.g. 1080P HEVC → 720P / 480P H.264).
                // If no lower quality is available, the player is left paused
                // and the user can hit back.
                downgradeState.value()
            }
        }
        exo.addListener(listener)

        // VOD slow-motion detector. Only attached for VOD streams; live HLS
        // uses LiveResilience instead.
        val analytics = object :
            androidx.media3.exoplayer.analytics.AnalyticsListener {
            override fun onDroppedVideoFrames(
                eventTime: androidx.media3.exoplayer.analytics.AnalyticsListener.EventTime,
                droppedFrames: Int,
                elapsedMs: Long,
            ) {
                com.moviebox.tv.debug.Telemetry.onDroppedFrames(droppedFrames, elapsedMs)
                if (liveState.value) return
                // Initial 5s settle — the first few frames are nearly always
                // dropped while ExoPlayer warms the decoder.
                if (exo.currentPosition < 5_000) return
                if (vodDrops.observe(droppedFrames, elapsedMs)) {
                    stabilisingState.value(
                        "Reducing quality for smoother playback…",
                    )
                    val didDowngrade = downgradeState.value()
                    // If no lower variant exists, clear the message — there's
                    // nothing we can do silently. The pill still flashed
                    // briefly so the user knows we noticed.
                    if (!didDowngrade) {
                        stabilisingState.value(null)
                    }
                }
            }
            // Live-only: hardware-decoder flap detection. Each codec
            // init event is fed into [flapDetector]; when it crosses
            // its threshold, [onCodecFlapping] tells the VM to remember
            // this channel needs software decode, which bumps a state
            // counter that recomposes us with `preferSoftwareDecoder =
            // true`, rebuilding `exo` with the FFmpeg renderer.
            override fun onVideoDecoderInitialized(
                eventTime: androidx.media3.exoplayer.analytics.AnalyticsListener.EventTime,
                decoderName: String,
                initializedTimestampMs: Long,
                initializationDurationMs: Long,
            ) {
                if (!liveState.value || preferSoftwareDecoder) return
                flapDetector.onDecoderInitialized()
                if (flapDetector.isFlapping()) {
                    com.moviebox.tv.debug.Telemetry.note(
                        com.moviebox.tv.debug.Telemetry.Severity.WARN,
                        "Codec flapping on $decoderName — falling back to software",
                    )
                    stabilisingState.value("Switching decoder…")
                    onCodecFlapping()
                    flapDetector.reset()
                }
            }
        }
        exo.addAnalyticsListener(analytics)

        // Silent-freeze watchdog. Only meaningful for live: VOD plays a
        // finite media item; a "freeze" there is just an end-of-stream
        // condition handled elsewhere.
        //
        // CAUTION — tunneled video disables this watchdog. With tunneling,
        // codec frames go straight to the display and Media3's
        // `currentPosition` stops ticking back through the normal
        // reporting path. The watchdog reads currentPosition to decide
        // "frozen"; under tunneling it sees a stationary position even on
        // perfectly healthy playback and fires every 30 s (cooldown).
        // Each fire = seekTo + prepare = a visible "stop and play" the
        // user perceives as reconnecting. 10 false-positive fires in 5 min
        // exhaust [MAX_RESOLVE_FAILURES_BEFORE_WEBVIEW] and dump the user
        // into the Adscore-blocked WebView path. Diag log from one such
        // session:
        //
        //   10:37:41 FREEZE_WATCHDOG firing → count=6
        //   10:38:11 FREEZE_WATCHDOG firing → count=7
        //   ...
        //   10:40:11 FALLBACK_WEB after=10 failures
        //
        // while every PROXY ch=303 inner OK fetch in between reported
        // mediaSeq advancing normally — the stream WAS playing fine.
        //
        // The original failure mode this watchdog was added for (codec
        // QIB dropping to zero with no error fired) cannot happen under
        // tunneling: the render path is entirely different and the codec
        // can't get stuck composing into a SurfaceFlinger layer that the
        // OS has throttled. So we lose nothing by skipping the watchdog
        // here, and lose a major source of user-visible flapping.
        val tunnelingOn = trackSelector.parameters.tunnelingEnabled
        if (liveState.value && !tunnelingOn) {
            freezeWatchdog.start(exo) {
                android.util.Log.w(
                    "LiveDiag",
                    "PLAYER FREEZE_WATCHDOG firing — re-preparing",
                )
                // Same shape as onPlayerError's recovery path.
                stabilisingState.value("Reconnecting…")
                com.moviebox.tv.debug.Telemetry.note(
                    com.moviebox.tv.debug.Telemetry.Severity.WARN,
                    "Live silent freeze — re-preparing",
                )
                // Trigger the outer error handler so it invalidates the
                // proxy cache (and may also decide to fall back to
                // WebView once the in-place retry budget is exhausted).
                // Mirrors the onPlayerError order above.
                val shouldPrepare = liveErrorState.value("Reconnecting…")
                if (shouldPrepare) {
                    runCatching {
                        exo.seekTo(androidx.media3.common.C.TIME_UNSET)
                        exo.prepare()
                    }
                }
            }
        }

        onDispose {
            if (exo.duration > 0) {
                progressState.value(exo.currentPosition, exo.duration)
            }
            RemoteController.clearPlayback()
            freezeWatchdog.stop()
            exo.removeListener(listener)
            exo.removeAnalyticsListener(analytics)
            exo.release()
        }
    }

    // Reset the drop tracker every time the content (or quality variant)
    // changes, so a fresh attempt after a manual quality switch isn't
    // poisoned by old counters.
    LaunchedEffect(contentKey, mediaUrl) {
        vodDrops.reset()
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
        // SURFACE LEAK FIX: PlayerView holds a SurfaceView whose GPU
        // render-target only gets freed if we explicitly detach the player
        // before the view leaves composition. On TCL Android TVs the GPU
        // has 8 RTS IDs total; after 8 reconnect cycles without this
        // cleanup the GPU panics with "HWPerfSetSurfaceInfo: Max RTS IDs
        // (8) reached" and subsequent ExoPlayer surfaces refuse to render
        // — visible to the user as "reconnecting" with a black screen.
        onRelease = { playerView ->
            runCatching { playerView.player = null }
        },
    )
}
