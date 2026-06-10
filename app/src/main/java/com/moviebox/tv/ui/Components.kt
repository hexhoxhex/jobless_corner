package com.moviebox.tv.ui

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
): Modifier = composed {
    val isTv = LocalIsTv.current
    if (!isTv) {
        return@composed this.clickable(
            interactionSource = remember { MutableInteractionSource() },
            indication = null,
            onClick = onClick,
        )
    }
    var focused by remember { mutableStateOf(false) }
    val bringIntoView = remember { BringIntoViewRequester() }
    val scale by animateFloatAsState(
        if (focused) scaleOnFocus else 1f,
        animationSpec = spring(dampingRatio = 0.8f, stiffness = 320f),
        label = "tv-focus-scale",
    )
    val border by animateDpAsState(
        if (focused) borderWidth else 0.dp,
        animationSpec = spring(dampingRatio = 0.9f, stiffness = 400f),
        label = "tv-focus-border",
    )
    LaunchedEffect(Unit) {
        snapshotFlow { focused }.collectLatest { isFocused ->
            if (isFocused) {
                runCatching { bringIntoView.bringIntoView() }
            }
        }
    }
    this
        .bringIntoViewRequester(bringIntoView)
        .scale(scale)
        .shadow(
            elevation = if (focused) 12.dp else 0.dp,
            shape = shape,
            ambientColor = borderColor,
            spotColor = borderColor,
        )
        .border(width = border, brush = SolidColor(borderColor), shape = shape)
        .onFocusChanged { focused = it.isFocused }
        .focusable()
        .clickable(
            interactionSource = remember { MutableInteractionSource() },
            indication = null,
            onClick = onClick,
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
                "SEE ALL",
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
            SubcomposeAsyncImage(
                model = url,
                contentDescription = title.ifBlank { null },
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
                loading = { PosterFallback(title, Modifier.fillMaxSize(), dim = true) },
                error   = { PosterFallback(title, Modifier.fillMaxSize()) },
            )
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
            RatingPill(
                item.rating,
                Modifier.align(Alignment.TopStart).padding(6.dp),
            )
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
