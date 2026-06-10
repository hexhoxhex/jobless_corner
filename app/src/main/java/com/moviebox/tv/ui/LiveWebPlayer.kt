package com.moviebox.tv.ui

import android.annotation.SuppressLint
import android.view.ViewGroup
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.moviebox.tv.data.live.Channel
import com.moviebox.tv.ui.theme.Accent

/**
 * Fallback for live channels whose direct HLS stream URL returns a 403 (see
 * `LIVE_TV_BUG_REPORT.md` §0 — the catalog's inner playlist URLs are
 * universally rejected by nginx). Loads `dlhd.pk/{path}/stream-{id}.php` in
 * a WebView. Many such pages are protected by Adscore-style anti-bot
 * fingerprinting and stay black forever, so we time-bound each attempt.
 *
 * What this composable owns:
 *  - A small top bar (back + channel name) so the user is never trapped on
 *    a black screen.
 *  - Backend rotation: at most 3 backends, 7s per attempt. Total worst-case
 *    is ~21s before we bail out to the channel grid.
 *  - A single user-visible message at the end.
 */
@Composable
fun LiveWebPlayer(
    channel: Channel,
    onAllPathsFailed: (String) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Iframe pages tend to behave the same regardless of path — first 3 are
    // enough. The rest just keep the user staring at black.
    val paths = remember(channel.id) {
        channel.playerPaths.ifEmpty {
            listOf("stream", "cast", "watch")
        }.take(3)
    }
    var pathIndex by remember(channel.id) { mutableIntStateOf(0) }
    val failedRef = rememberUpdatedState(onAllPathsFailed)
    val backRef = rememberUpdatedState(onBack)

    val currentPath = paths.getOrNull(pathIndex)
    val url = currentPath?.let {
        "https://dlhd.pk/${it}/stream-${channel.id}.php"
    }

    Box(modifier.fillMaxSize().background(Color.Black)) {
        if (url != null) {
            WebViewBox(
                url = url,
                onMainFrameError = {
                    if (pathIndex < paths.size - 1) pathIndex += 1
                    else failedRef.value(
                        "Couldn't reach \"${channel.displayName}\".",
                    )
                },
                modifier = Modifier.fillMaxSize(),
            )
        }

        // Single hard 7s deadline per backend. We have no reliable way to
        // know whether the inner video player has actually started
        // (Adscore-protected pages load fine and stay black), so the safest
        // signal is: if you've been staring at this backend for 7s, move on.
        LaunchedEffect(pathIndex) {
            kotlinx.coroutines.delay(7_000)
            if (pathIndex < paths.size - 1) pathIndex += 1
            else failedRef.value(
                "\"${channel.displayName}\" isn't streaming right now.",
            )
        }

        // Always-visible top bar: back button + channel name. Stops the
        // "trapped on a black screen" complaint.
        Box(
            Modifier.fillMaxWidth().align(Alignment.TopCenter).background(
                Brush.verticalGradient(
                    0f to Color(0xCC000000), 1f to Color.Transparent,
                )
            )
        ) {
            Row(
                Modifier.fillMaxWidth().statusBarsPadding().padding(10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Box(
                    Modifier.size(40.dp).clip(CircleShape)
                        .background(Color(0x66000000))
                        .clickable { backRef.value() },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowBack, null,
                        tint = Color.White, modifier = Modifier.size(22.dp),
                    )
                }
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            Modifier.padding(end = 8.dp)
                                .clip(RoundedCornerShape(4.dp))
                                .background(Color(0xCCE5484D))
                                .padding(horizontal = 6.dp, vertical = 2.dp),
                        ) {
                            Text("LIVE",
                                color = Color.White, fontSize = 10.sp,
                                fontWeight = FontWeight.Bold)
                        }
                        Text(
                            channel.displayName,
                            color = Color.White,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 15.sp,
                        )
                    }
                    if (!channel.group.isNullOrBlank()) {
                        Text(
                            "${channel.group} · backend ${pathIndex + 1}/${paths.size}",
                            color = Color.White.copy(alpha = 0.55f),
                            fontSize = 11.sp,
                        )
                    }
                }
            }
        }

        // Small bottom-centred loading hint. Stays unobtrusive — if the
        // video starts the user won't really see it; if it doesn't they
        // know we're not just frozen.
        Column(
            Modifier.align(Alignment.BottomCenter).padding(bottom = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            CircularProgressIndicator(
                color = Accent, strokeWidth = 2.dp,
                modifier = Modifier.size(22.dp),
            )
            Spacer(Modifier.height(6.dp))
            Text(
                "Trying ${currentPath ?: "—"} backend",
                color = Color.White.copy(alpha = 0.7f),
                fontSize = 11.sp, textAlign = TextAlign.Center,
            )
        }
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun WebViewBox(
    url: String,
    onMainFrameError: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val onMainFrameErrorState = rememberUpdatedState(onMainFrameError)
    var webView by remember { mutableStateOf<WebView?>(null) }

    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            WebView(ctx).apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                )
                setBackgroundColor(android.graphics.Color.BLACK)
                settings.apply {
                    javaScriptEnabled = true
                    domStorageEnabled = true
                    mediaPlaybackRequiresUserGesture = false
                    loadWithOverviewMode = true
                    useWideViewPort = true
                    userAgentString = userAgentString
                        .replace(Regex("wv\\b"), "")
                        .replace(Regex("; Build/[^)]+"), "")
                }
                webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(
                        view: WebView?, request: WebResourceRequest?,
                    ): Boolean {
                        // Block navigation away from the embed host.
                        val host = request?.url?.host ?: return false
                        return host !in setOf(
                            "dlhd.pk", "donis.jimpenopisonline.online",
                            "out-1.welovetocare.shop",
                            "junkieembeds.pages.dev",
                            "wikisport.info", "tv-bu1.blogspot.com",
                        )
                    }
                    override fun onReceivedError(
                        view: WebView?,
                        request: WebResourceRequest?,
                        error: android.webkit.WebResourceError?,
                    ) {
                        if (request?.isForMainFrame == true) {
                            onMainFrameErrorState.value()
                        }
                    }
                }
                webChromeClient = WebChromeClient()
                loadUrl(url)
                webView = this
            }
        },
    )

    DisposableEffect(url) { onDispose { webView?.destroy() } }
}
