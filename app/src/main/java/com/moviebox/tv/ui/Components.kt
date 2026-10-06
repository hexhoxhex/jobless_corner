package com.moviebox.tv.ui

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.composed
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.SolidColor
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import androidx.compose.animation.core.Animatable
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.inset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.SubcomposeAsyncImage
import com.moviebox.tv.data.Item
import com.moviebox.tv.ui.theme.Accent
import com.moviebox.tv.ui.theme.Gold
import com.moviebox.tv.ui.theme.SurfaceElevated
import com.moviebox.tv.ui.theme.TextMuted

/**
 * D-pad-friendly focus indication for TV.
 *
 * Replaces the default click-only interaction with: scale up on focus,
 * bright accent border, soft shadow, and (critically) brings the focused
 * element into view of its parent scrollable so the user can keep
 * pressing right/down without the focus marker disappearing off-screen.
 *
 * On phones (`LocalIsTv == false`) this collapses to a plain ripple-less
 * clickable, since touch users don't navigate by focus.
 *
 * Apply as the SAME modifier you'd use for clickable() — replaces it.
 * Pass [shape] matching the visible card shape so the border / shadow
 * track it.
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
fun Modifier.tvFocusable(
    shape: androidx.compose.ui.graphics.Shape = RoundedCornerShape(10.dp),
    scaleOnFocus: Float = 1.06f,
    borderWidth: Dp = 3.dp,
    borderColor: Color = Accent,
    onClick: () -> Unit,
    /** Optional long-press handler. On phones triggers via touch hold;
     *  on Android TV remotes the system surfaces a DPAD_CENTER long-press
     *  as a long click here. Used by the LIVE tab's ChannelCard to let
     *  the user star/unstar a channel without leaving the grid. */
    onLongClick: (() -> Unit)? = null,
): Modifier = composed {
    val isTv = LocalIsTv.current
    if (!isTv) {
        return@composed this.combinedClickable(
            interactionSource = remember { MutableInteractionSource() },
            indication = null,
            onClick = onClick,
            onLongClick = onLongClick,
        )
    }
    // Nothing here READS focus during composition — only the layer and draw
    // lambdas below do. That is the whole point.
    //
    // This used to animate with `animate*AsState` and apply the values via
    // `.scale(scale)` / `.border(width = border)`, which read them in
    // composition: every frame of the ~200 ms focus animation recomposed the
    // card that gained focus AND the one that lost it, and recomposing a card
    // re-runs its poster image. Measured on the TV while browsing the home
    // rows: 48-54% janky frames, median 36-42 ms, 99th percentile 250-300 ms,
    // ~28 frames per run blamed on the UI thread. Read inside graphicsLayer /
    // drawWithContent, a focus change only redraws — the card is not rebuilt.
    // Same look: same scale, spring, shadow, ring and clip as before.
    val focused = remember { mutableStateOf(false) }
    val bringIntoView = remember { BringIntoViewRequester() }
    val scale = remember { Animatable(1f) }
    val ring = remember { Animatable(0f) }   // 0..1 of borderWidth
    LaunchedEffect(Unit) {
        snapshotFlow { focused.value }.collectLatest { isFocused ->
            coroutineScope {
                if (isFocused) launch { runCatching { bringIntoView.bringIntoView() } }
                launch {
                    scale.animateTo(
                        if (isFocused) scaleOnFocus else 1f,
                        spring(dampingRatio = 0.8f, stiffness = 320f),
                    )
                }
                ring.animateTo(
                    if (isFocused) 1f else 0f,
                    spring(dampingRatio = 0.9f, stiffness = 400f),
                )
            }
        }
    }
    this
        .bringIntoViewRequester(bringIntoView)
        .graphicsLayer {
            val s = scale.value
            scaleX = s
            scaleY = s
            val on = focused.value
            shadowElevation = if (on) 12.dp.toPx() else 0f
            this.shape = shape
            clip = on                         // .shadow() clipped when elevated
            ambientShadowColor = borderColor
            spotShadowColor = borderColor
        }
        .drawWithContent {
            drawContent()
            val w = ring.value * borderWidth.toPx()
            if (w > 0.5f) {
                // .border() draws inside the bounds; inset by half the stroke
                // so this one does too.
                inset(w / 2f) {
                    drawOutline(
                        shape.createOutline(size, layoutDirection, this),
                        color = borderColor,
                        style = Stroke(width = w),
                    )
                }
            }
        }
        .onFocusChanged { focused.value = it.isFocused }
        .focusable()
        .combinedClickable(
            interactionSource = remember { MutableInteractionSource() },
            indication = null,
            onClick = onClick,
            onLongClick = onLongClick,
        )
}

@Composable
fun SectionHeader(
    title: String,
    onSeeAll: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val isTv = LocalIsTv.current
    val padH = if (isTv) 32.dp else 16.dp
    val fontSize = if (isTv) 22.sp else 18.sp
    Row(
        modifier = modifier.fillMaxWidth().padding(horizontal = padH),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, fontSize = fontSize, fontWeight = FontWeight.Bold)
        if (onSeeAll != null) {
            Text(
                "See all",
                fontSize = if (isTv) 14.sp else 12.sp,
                fontWeight = FontWeight.SemiBold,
                color = TextMuted,
                modifier = Modifier.clickable(onClick = onSeeAll),
            )
        }
    }
}

@Composable
fun RatingPill(rating: Double?, modifier: Modifier = Modifier) {
    if (rating == null || rating <= 0) return
    val isTv = LocalIsTv.current
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .background(Color(0xCC000000))
            .padding(horizontal = 6.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Filled.Star, null, tint = Gold,
            modifier = Modifier.size(if (isTv) 14.sp.value.dp else 12.dp),
        )
        Text(
            " %.1f".format(rating),
            fontSize = if (isTv) 13.sp else 11.sp,
            fontWeight = FontWeight.SemiBold,
            color = Color.White,
        )
    }
}

/**
 * Poster image that gracefully degrades when the cover URL is missing or
 * fails to load. We render a deterministic, title-coloured gradient with the
 * title centred — way better than a blank surface.
 */
@Composable
fun PosterImage(
    url: String?,
    title: String = "",
    modifier: Modifier = Modifier,
) {
    Box(modifier.background(SurfaceElevated)) {
        if (url.isNullOrBlank()) {
            PosterFallback(title, Modifier.fillMaxSize())
        } else {
            // AsyncImage, not SubcomposeAsyncImage. The subcompose variant
            // runs a separate composition for its loading/error slots and
            // re-runs it on every state change — Coil's own docs say not to
            // use it in lists, and this is every card of every row. Same look
            // here: the dimmed fallback sits underneath while the poster
            // loads (it crossfades in over it), and goes un-dimmed if the
            // poster fails — the only recomposition left, once per image.
            // AsyncImage also sizes the decode to the card's own bounds
            // rather than the poster's full resolution.
            var failed by remember(url) { mutableStateOf(false) }
            PosterFallback(title, Modifier.fillMaxSize(), dim = !failed)
            if (!failed) {
                coil.compose.AsyncImage(
                    model = url,
                    contentDescription = title.ifBlank { null },
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                    onError = { failed = true },
                )
            }
        }
    }
}

@Composable
private fun PosterFallback(
    title: String,
    modifier: Modifier = Modifier,
    dim: Boolean = false,
) {
    val (c1, c2) = titleColors(title)
    Box(
        modifier.background(
            Brush.linearGradient(
                colors = if (dim)
                    listOf(SurfaceElevated, SurfaceElevated)
                else listOf(c1, c2),
            )
        ),
        contentAlignment = Alignment.Center,
    ) {
        if (!dim) {
            Text(
                title.take(40),
                color = Color.White.copy(alpha = 0.92f),
                fontSize = 14.sp, fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center, maxLines = 3,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(10.dp),
            )
        }
    }
}

/** Deterministic gradient pair from the title so identical titles look stable. */
private fun titleColors(title: String): Pair<Color, Color> {
    val seed = title.hashCode().toLong() and 0xFFFFFFFFL
    val hueA = (seed % 360).toInt()
    val hueB = ((seed / 360 + 60) % 360).toInt()
    return Color.hsv(hueA.toFloat(), 0.55f, 0.55f) to
        Color.hsv(hueB.toFloat(), 0.65f, 0.30f)
}

/** A poster tile used in rows and grids. TV-aware sizing + D-pad focus state. */
@Composable
fun PosterCard(
    item: Item,
    width: Dp? = null,
    /** When non-null, renders a big numbered badge at the top-left corner of
     *  the poster (MovieWay's "Series Rankings" pattern — 1, 2, 3 with the
     *  brand-green pill behind). Used for ranked rows like Trending. */
    rank: Int? = null,
    onClick: () -> Unit,
) {
    val isTv = LocalIsTv.current
    val actualWidth = width ?: if (isTv) 180.dp else 120.dp
    Column(
        Modifier
            .width(actualWidth)
            .tvFocusable(
                shape = RoundedCornerShape(10.dp),
                onClick = onClick,
            ),
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(2f / 3f)
                .clip(RoundedCornerShape(10.dp)),
        ) {
            PosterImage(item.coverUrl, item.title, Modifier.fillMaxSize())
            if (rank != null) {
                // Ranked badge: solid brand-green pill with big number in the
                // top-left. Sits over the rating pill if both are present —
                // ranked rows get the rank, others get the rating.
                Box(
                    Modifier
                        .align(Alignment.TopStart)
                        .padding(start = 6.dp, top = 6.dp)
                        .background(
                            com.moviebox.tv.ui.theme.Accent,
                            shape = RoundedCornerShape(
                                topStart = 6.dp,
                                bottomEnd = 8.dp,
                                topEnd = 0.dp,
                                bottomStart = 0.dp,
                            ),
                        )
                        .padding(horizontal = 8.dp, vertical = 2.dp),
                ) {
                    Text(
                        "$rank",
                        color = androidx.compose.ui.graphics.Color.Black,
                        fontWeight = FontWeight.Black,
                        fontSize = if (isTv) 18.sp else 16.sp,
                    )
                }
            } else {
                RatingPill(
                    item.rating,
                    Modifier.align(Alignment.TopStart).padding(6.dp),
                )
            }
            // Provider badge: mark 4KHDHub items so cross-provider search
            // results are unambiguous (aoneroom vs the gap-filler source).
            if (item.subjectId.startsWith(com.moviebox.tv.net.FourKHdHub.PREFIX)) {
                Box(
                    Modifier
                        .align(Alignment.TopEnd)
                        .padding(6.dp)
                        .background(
                            com.moviebox.tv.ui.theme.Accent,
                            RoundedCornerShape(4.dp),
                        )
                        .padding(horizontal = 5.dp, vertical = 1.dp),
                ) {
                    Text(
                        "4K",
                        color = androidx.compose.ui.graphics.Color.Black,
                        fontWeight = FontWeight.Black,
                        fontSize = if (isTv) 11.sp else 9.sp,
                    )
                }
            }
        }
        Text(
            item.title,
            fontSize = if (isTv) 14.sp else 12.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 6.dp),
        )
        Text(
            item.year?.toString()
                ?: item.type.name.lowercase().replaceFirstChar { it.uppercase() },
            fontSize = if (isTv) 12.sp else 11.sp,
            color = TextMuted,
        )
    }
}

@Composable
fun ScrimBox(brush: Brush, modifier: Modifier = Modifier) {
    Box(modifier.background(brush))
}
