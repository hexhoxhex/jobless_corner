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
        // The iframe pages don't always reliably fire a "video started" event,
        // so we just give them ~8s to take over the screen. If they have, the
        // spinner is harmless underneath; if they haven't, we rotate paths.
        LaunchedEffect(pathIndex) {
            loadingNow = true
            kotlinx.coroutines.delay(8000)
            // If still loading after 8s, treat it as a path failure and rotate.
            if (loadingNow) {
                if (pathIndex < paths.size - 1) {
                    pathIndex += 1
                } else {
                    failedRef.value(
                        "No backend responded for ${channel.displayName}.",
                    )
                }
            }
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
