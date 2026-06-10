package com.moviebox.tv.ui

import android.annotation.SuppressLint
import android.view.ViewGroup
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.moviebox.tv.data.live.Channel
import com.moviebox.tv.ui.theme.Accent

/**
 * Fallback for live channels whose direct HLS stream URL returns a 403 (the
 * inner playlist URLs in the mkurugenzi_viewer catalog can't be reached
 * directly from a hardened TV — see LIVE_TV_BUG_REPORT.md §0). The
 * dlhd.pk iframe-style endpoints embed a self-contained JS player.
 *
 * We rotate through the catalog's [Channel.playerPaths] in order. Each path
 * maps to a different upstream backend; if the iframe doesn't fire its
 * `onPageFinished` within a deadline OR the inner page errors, we move to
 * the next path. Final failure calls [onAllPathsFailed].
 */
@Composable
fun LiveWebPlayer(
    channel: Channel,
    onAllPathsFailed: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val paths = remember(channel.id) {
        channel.playerPaths.ifEmpty { listOf("stream", "cast", "watch", "plus") }
    }
    var pathIndex by remember(channel.id) { mutableIntStateOf(0) }
    var loadingNow by remember { mutableStateOf(true) }
    val failedRef = rememberUpdatedState(onAllPathsFailed)

    val currentPath = paths.getOrNull(pathIndex)
    val url = currentPath?.let {
        "https://dlhd.pk/${it}/stream-${channel.id}.php"
    }

    Box(modifier.fillMaxSize().background(Color.Black)) {
        if (url != null) {
            WebViewBox(
                url = url,
                onProgressChanged = { p -> if (p >= 100) loadingNow = false },
                onError = {
                    if (pathIndex < paths.size - 1) {
                        pathIndex += 1; loadingNow = true
                    } else {
                        failedRef.value("All player backends failed for ${channel.displayName}.")
                    }
                },
                modifier = Modifier.fillMaxSize(),
            )
        }
        // Two timers per path:
        //  - 8s loading deadline: if the page never fires onPageFinished /
        //    onProgressChanged(100), assume the backend is unreachable.
        //  - 12s "max black screen" deadline: even after the page loads, if
        //    nothing visibly plays (Adscore + similar anti-bot pages stay
        //    black forever) we rotate to the next backend / give up.
        // The user can hit back at any point; the back navigation is owned
        // by the parent PlayerScreen, not this composable.
        LaunchedEffect(pathIndex) {
            loadingNow = true
            kotlinx.coroutines.delay(8000)
            if (loadingNow) {
                rotateOrFail(
                    pathIndex, paths.size,
                    failed = { msg -> failedRef.value(msg) },
                    advance = { pathIndex += 1 },
                    msgWhenOut =
                        "No backend responded for ${channel.displayName}.",
                )
            }
        }
        LaunchedEffect(pathIndex) {
            kotlinx.coroutines.delay(12_000)
            // Reached 12s on this backend. We have no reliable way to detect
            // "video is actually playing" from outside the WebView, so the
            // safe assumption when the user keeps seeing black is that this
            // backend won't deliver and we should move on.
            rotateOrFail(
                pathIndex, paths.size,
                failed = { msg -> failedRef.value(msg) },
                advance = { pathIndex += 1 },
                msgWhenOut =
                    "Could not start \"${channel.displayName}\" on any backend.",
            )
        }
        if (loadingNow) {
            Column(
                Modifier.align(Alignment.Center),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                CircularProgressIndicator(
                    color = Accent, strokeWidth = 3.dp,
                    modifier = Modifier.size(46.dp),
                )
                Text(
                    "Loading ${currentPath ?: "stream"} backend…",
                    color = Color.White.copy(alpha = .85f),
                    fontSize = 13.sp,
                )
                Text(
                    "Backend ${pathIndex + 1} of ${paths.size}",
                    color = Color.White.copy(alpha = .55f),
                    fontSize = 11.sp,
                )
            }
        }
    }
}

/** Either advance to the next path index, or report the final failure
 *  message. Shared by the two timeout effects in LiveWebPlayer. */
private fun rotateOrFail(
    currentIndex: Int,
    total: Int,
    failed: (String) -> Unit,
    advance: () -> Unit,
    msgWhenOut: String,
) {
    if (currentIndex < total - 1) advance() else failed(msgWhenOut)
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun WebViewBox(
    url: String,
    onProgressChanged: (Int) -> Unit,
    onError: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val onErrorState = rememberUpdatedState(onError)
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
                        .replace(Regex("wv\\b"), "")  // hide WebView marker
                        .replace(Regex("; Build/[^)]+"), "")
                }
                webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(
                        view: WebView?, request: WebResourceRequest?,
                    ): Boolean {
                        // Stay in-app — block popups + redirects to other domains.
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
                        // Only top-level errors matter — the iframe loads
                        // dozens of subresources, many of which fail benignly.
                        if (request?.isForMainFrame == true) onErrorState.value()
                    }
                }
                webChromeClient = object : WebChromeClient() {
                    override fun onProgressChanged(view: WebView?, newProgress: Int) {
                        onProgressChanged(newProgress)
                    }
                }
                loadUrl(url)
                webView = this
            }
        },
        update = { /* nothing — the LiveWebPlayer recreates by changing URL via factory key */ },
    )

    DisposableEffect(url) { onDispose { webView?.destroy() } }
}
