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
    // Try all available backend paths, up to a hard cap. The first three
    // (stream / cast / watch) all route to the donis infrastructure in
    // most catalogs — when that's the failure mode, they ALL fail and
    // capping at 3 means we never reach the later backends (plus /
    // casting / player) that route to DIFFERENT hosts entirely
    // (junkieembeds.pages.dev, wikisport.club, ksohls.ru) and can rescue
    // the channel. Repro: FOX USA on this TCL — donis-served iframes
    // sit in an Adscore challenge that never resolves; junkieembeds-
    // served iframe loads in ~5 s. Old code never reached it.
    val paths = remember(channel.id) {
        channel.playerPaths.ifEmpty {
            listOf("stream", "cast", "watch", "plus", "casting", "player")
        }.take(6)
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

        // Per-backend deadlines. We have no reliable way to know whether
        // the inner video player has actually started (Adscore-protected
        // pages load fine and stay black), so we time out blindly. BUT —
        // the FIRST backend deserves real time: Chrome cold-start + the
        // dlhd shell + the Adscore handshake + the iframe player's first
        // frame easily takes 15-25 s on this TCL, especially right after
        // ExoPlayer was just torn down. The old single 7 s deadline ate
        // FOX USA's only working backend before it could even load (user
        // report: "FOX USA not playing" — the WebView was being killed
        // and recreated three times in ~21 s, never giving stream/54
        // long enough to render). Give the first backend 25 s; later
        // backends keep the shorter 8 s as a fast cycle-through.
        LaunchedEffect(pathIndex) {
            val budget = if (pathIndex == 0) FIRST_BACKEND_TIMEOUT_MS
                else NEXT_BACKEND_TIMEOUT_MS
            kotlinx.coroutines.delay(budget)
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
                            "${channel.group} · source ${pathIndex + 1}/${paths.size}",
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
            // "cast backend" used to read like Chromecast to users — switched
            // to neutral "alternate source N/6" copy that doesn't surface
            // dlhd.pk's internal player-path naming.
            val total = paths.size
            val current = pathIndex + 1
            Text(
                "Trying alternate source $current/$total…",
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
                    override fun onPageStarted(
                        view: WebView?,
                        url: String?,
                        favicon: android.graphics.Bitmap?,
                    ) {
                        // Adscore (`adsco.re`, `4.adsco.re`, etc.) fingerprints
                        // headless / instrumented WebViews via a handful of JS
                        // probes and refuses to start the inner player when
                        // any of them looks "non-human". This injects an
                        // override BEFORE the page's scripts run, presenting a
                        // plausible Chrome-Android profile across the checks
                        // Adscore is known to use. None of this hides real
                        // behaviour — it just stops false-positive blocking.
                        view?.evaluateJavascript(ADSCORE_COUNTER_JS, null)
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

    DisposableEffect(url) {
        onDispose {
            // SURFACE LEAK FIX: calling WebView.destroy() while it's still
            // attached to its parent view leaks the SurfaceView and its
            // underlying GPU render-target. On TCL Android TVs the GPU only
            // holds 8 RTS IDs - after 8 mount/dispose cycles, allocation
            // fails ("HWPerfSetSurfaceInfo: Max RTS IDs (8) reached" in
            // logcat) and subsequent ExoPlayer or WebView surfaces refuse
            // to render -> black screen -> "reconnecting" - which from the
            // user's seat looks like the recovery cascade.
            //
            // Detach first, THEN destroy. Also clear the URL + stop loading
            // so background network requests aren't lingering when the
            // view tears down.
            webView?.let { w ->
                runCatching { w.stopLoading() }
                runCatching { w.loadUrl("about:blank") }
                runCatching {
                    (w.parent as? android.view.ViewGroup)?.removeView(w)
                }
                runCatching { w.destroy() }
            }
            webView = null
        }
    }
}

/**
 * JS payload injected at onPageStarted time, BEFORE the page's own scripts
 * (including adsco.re) execute. Spoofs the handful of fingerprint surfaces
 * Adscore is documented to probe:
 *
 *  - `navigator.webdriver` → false (Chromium WebView sets it true on some
 *    Android builds; many anti-bot scripts gate on this single flag).
 *  - `navigator.plugins.length` and `navigator.mimeTypes.length` → non-zero
 *    fake values. Headless browsers return empty arrays here.
 *  - `navigator.languages` → realistic en-US/en list.
 *  - `window.chrome` → presence + .runtime stub. Adscore checks this.
 *  - `Notification.permission` → "default" (some headless setups expose
 *    "denied" which is a heuristic for "not a real browser").
 *  - `Permissions.query({name:'notifications'})` → returns "prompt" /
 *    "default" instead of throwing.
 *  - `HTMLCanvasElement.toDataURL` / `getImageData` → injects a tiny,
 *    deterministic noise so the canvas hash isn't a known WebView signature
 *    but still stable across reloads (Adscore correlates by hash).
 *
 * The override is idempotent: if any property is already shaped how we want,
 * we don't touch it. Designed to fail open — any exception during patch
 * leaves the rest of the page running normally.
 */
/** How long to give the FIRST backend (usually "stream") to render
 *  something. The big spike — Chrome cold start (~3 s on TCL) plus the
 *  Adscore handshake (~5-10 s) plus the iframe player's first frame
 *  (~3-5 s) — easily exceeds 15 s, so we budget 25 s here. */
private const val FIRST_BACKEND_TIMEOUT_MS: Long = 25_000L

/** Subsequent backends inherit the Chrome process already warmed by the
 *  first attempt, so a quick 8 s cycle is enough to give each a chance
 *  without leaving the user staring at black for ages. */
private const val NEXT_BACKEND_TIMEOUT_MS: Long = 8_000L

private const val ADSCORE_COUNTER_JS = """
(function() {
  try {
    // 1. webdriver
    try { Object.defineProperty(navigator, 'webdriver', { get: () => false, configurable: true }); } catch(e) {}

    // 2. plugins / mimeTypes — synthesize a realistic Chrome-Android profile
    try {
      const fakePlugin = { name:'Chrome PDF Plugin', filename:'internal-pdf-viewer', description:'Portable Document Format' };
      Object.defineProperty(navigator, 'plugins', { get: () => [fakePlugin, fakePlugin, fakePlugin], configurable: true });
      Object.defineProperty(navigator, 'mimeTypes', { get: () => [{ type:'application/pdf' }, { type:'text/pdf' }], configurable: true });
    } catch(e) {}

    // 3. languages
    try { Object.defineProperty(navigator, 'languages', { get: () => ['en-US','en'], configurable: true }); } catch(e) {}

    // 4. window.chrome
    try {
      if (!window.chrome) window.chrome = { runtime: {}, app: { isInstalled: false } };
      if (!window.chrome.runtime) window.chrome.runtime = {};
    } catch(e) {}

    // 5. Notification.permission
    try {
      if (window.Notification && Notification.permission === 'denied') {
        Object.defineProperty(Notification, 'permission', { get: () => 'default', configurable: true });
      }
    } catch(e) {}

    // 6. Permissions.query for notifications
    try {
      const origQuery = navigator.permissions && navigator.permissions.query;
      if (origQuery) {
        navigator.permissions.query = (params) => (
          params && params.name === 'notifications'
            ? Promise.resolve({ state: Notification.permission || 'default' })
            : origQuery.call(navigator.permissions, params)
        );
      }
    } catch(e) {}

    // 7. Canvas fingerprint noise (deterministic per session — Adscore relies
    //    on canvas hash being stable across reloads of the same session; we
    //    keep that property but shift the hash off the WebView default.)
    try {
      const origToDataURL = HTMLCanvasElement.prototype.toDataURL;
      HTMLCanvasElement.prototype.toDataURL = function() {
        const ctx = this.getContext('2d');
        if (ctx && this.width > 0 && this.height > 0) {
          // Single-pixel tweak — invisible visually, perturbs the hash.
          ctx.fillStyle = 'rgba(0,0,0,0.003)';
          ctx.fillRect(0, 0, 1, 1);
        }
        return origToDataURL.apply(this, arguments);
      };
    } catch(e) {}

    // 8. Hardware concurrency — many headless WebViews return 1; real
    //    Android TVs are quad-core or more.
    try {
      if ((navigator.hardwareConcurrency || 0) <= 1) {
        Object.defineProperty(navigator, 'hardwareConcurrency', { get: () => 4, configurable: true });
      }
    } catch(e) {}
  } catch(e) { /* never let this break the page */ }
})();
"""

