package com.moviebox.tv.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Indication
import androidx.compose.foundation.IndicationNodeFactory
import androidx.compose.foundation.LocalIndication
import androidx.compose.runtime.Composable
import androidx.compose.foundation.interaction.FocusInteraction
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.node.DelegatableNode
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.invalidateDraw
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/*
 * "The remote pointer sometimes is not visible on some buttons."
 *
 * Every Material button, switch and menu item, and every plain `.clickable`
 * row or chip, showed focus only through Material's ripple overlay: white at
 * about 10% alpha. On a phone that is a hover hint; across a room on a dark
 * TV screen it is nothing — the Play / Download / Trailer buttons on a title,
 * the schedule headings, the channel chips under an event, Settings, the
 * Favourites and Downloads rows all looked unfocused while focused, so the
 * user lost track of where they were. Only the cards (tvFocusable), the hero,
 * the rail and the player controls drew their own focus.
 *
 * Two layers, both TV-only:
 *  - [tvFocusRing]: an explicit white ring just outside the control plus the
 *    official 1.1x focus scale, for named controls (buttons, switches,
 *    menu items) where we know the shape.
 *  - [TvFocusIndication]: the app-wide default indication on TV, so any
 *    `.clickable` that does not draw its own focus still gets a visible one
 *    (light fill + inner ring). Controls that draw their own pass
 *    `indication = null` and are unaffected.
 *
 * Both animate in the draw phase only (see android-tv-compose skill, Focus).
 */

/**
 * Visible D-pad focus for a control that has none of its own: a white ring
 * [gap] outside its [shape] and a small scale-up. Put it on the control's
 * `modifier`; it reacts when the control or anything inside it has focus.
 * No-op on phones.
 *
 * [inside] draws the ring within the bounds instead, for rows inside a popup
 * (dropdown items) where anything outside would be clipped.
 */
fun Modifier.tvFocusRing(
    shape: Shape = RoundedCornerShape(percent = 50),
    scaleOnFocus: Float = 1.1f,
    color: Color = Color.White,
    width: Dp = 3.dp,
    gap: Dp = 3.dp,
    inside: Boolean = false,
): Modifier = composed {
    if (!LocalIsTv.current) return@composed this
    val focused = remember { mutableStateOf(false) }
    val level = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        snapshotFlow { focused.value }.collectLatest { on ->
            level.animateTo(
                if (on) 1f else 0f,
                spring(dampingRatio = 0.9f, stiffness = 500f),
            )
        }
    }
    this
        .graphicsLayer {
            val s = 1f + (scaleOnFocus - 1f) * level.value
            scaleX = s
            scaleY = s
        }
        .drawWithContent {
            drawContent()
            val a = level.value
            if (a <= 0.01f) return@drawWithContent
            val w = width.toPx()
            if (inside) {
                drawRect(color.copy(alpha = 0.12f * a))
            }
            // Grow (outside) or shrink (inside) the outline so the stroke
            // sits fully outside / fully inside the control.
            val grow = if (inside) -w / 2f else gap.toPx() + w / 2f
            translate(-grow, -grow) {
                val sz = Size(size.width + 2 * grow, size.height + 2 * grow)
                drawOutline(
                    shape.createOutline(sz, layoutDirection, this),
                    color = color.copy(alpha = color.alpha * a),
                    style = Stroke(width = w),
                )
            }
        }
        .onFocusChanged { focused.value = it.hasFocus }
}

/**
 * Gives a TV screen a visible starting point.
 *
 * A screen opened from the rail or a button replaces the control that had
 * focus, so it opened with NOTHING focused: no highlight anywhere, and on
 * Settings the D-pad did not even move until something was clicked. Asks
 * [target] for focus, retrying briefly while it composes, and stops for good
 * as soon as [screenHasFocus] reports focus inside the screen — so it never
 * pulls focus away from where the user has already moved. No-op on phones.
 */
@Composable
fun TvInitialFocus(
    target: androidx.compose.ui.focus.FocusRequester,
    key: Any? = Unit,
    screenHasFocus: () -> Boolean,
) {
    if (!LocalIsTv.current) return
    LaunchedEffect(key) {
        repeat(20) {
            if (screenHasFocus()) return@LaunchedEffect
            runCatching { target.requestFocus() }
            kotlinx.coroutines.delay(150)
        }
    }
}

/**
 * Indication for a control that already draws its own focus (player
 * buttons, tvFocusRing users): none on TV, so the [TvFocusIndication]
 * fallback doesn't stack a second, square ring inside a round button; the
 * normal touch ripple on phones.
 */
@Composable
fun ownFocusIndication(): Indication? =
    if (LocalIsTv.current) null else LocalIndication.current

/**
 * Default indication on TV (provided in AppRoot): whatever `.clickable`
 * does not draw its own focus gets a light fill and a white inner ring
 * while focused. Drawn inside the clickable's bounds, so a `clip(shape)`
 * before it shapes the highlight too.
 */
object TvFocusIndication : IndicationNodeFactory {
    override fun create(interactionSource: InteractionSource): DelegatableNode =
        TvFocusIndicationNode(interactionSource)

    override fun equals(other: Any?): Boolean = other === this
    override fun hashCode(): Int = 0x7f0c05
}

private class TvFocusIndicationNode(
    private val source: InteractionSource,
) : Modifier.Node(), DrawModifierNode {

    private val level = Animatable(0f)

    override fun onAttach() {
        coroutineScope.launch {
            val focuses = mutableListOf<FocusInteraction.Focus>()
            source.interactions.collect { i ->
                when (i) {
                    is FocusInteraction.Focus -> focuses.add(i)
                    is FocusInteraction.Unfocus -> focuses.remove(i.focus)
                    else -> return@collect
                }
                val target = if (focuses.isEmpty()) 0f else 1f
                launch {
                    level.animateTo(target, tween(durationMillis = 120)) {
                        invalidateDraw()
                    }
                }
            }
        }
    }

    override fun ContentDrawScope.draw() {
        drawContent()
        val a = level.value
        if (a <= 0.01f) return
        drawRect(Color.White.copy(alpha = 0.14f * a))
        val w = 3.dp.toPx()
        drawRoundRect(
            color = Color.White.copy(alpha = a),
            topLeft = Offset(w / 2f, w / 2f),
            size = Size(size.width - w, size.height - w),
            cornerRadius = CornerRadius(10.dp.toPx()),
            style = Stroke(width = w),
        )
    }
}

/**
 * Compose's `focusRestorer()` minus its pin. Use this one for every lazy row.
 *
 * Coming back into a row lands on the card you left, as before. What is
 * gone: on leaving a row, Compose 1.7's FocusRestorerNode PINS the card you
 * left so it stays composed, and releases that pin on re-entry or detach.
 * When the row's contents change meanwhile, the lazy list disposes the
 * pinned card and releases its pins itself — and the restorer's later
 * release throws "Release should only be called once", killing the app.
 * Reproduced on the title page every time: focus an episode, UP to the
 * season tabs, move to another season (the episode row is replaced), BACK.
 * The same holds for any row whose items change under it (Live now ticks
 * every minute, home rows refresh).
 *
 * The pin only kept a card that had scrolled out of composition
 * restorable; a row does not scroll while focus is elsewhere, so the card
 * is still there. If it is not, entry falls back to the default.
 */
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
fun Modifier.tvFocusRestorer(): Modifier = composed {
    val requester = remember { androidx.compose.ui.focus.FocusRequester() }
    this
        .then(Modifier.focusRequester(requester))
        .focusProperties {
            exit = {
                requester.saveFocusedChild()
                androidx.compose.ui.focus.FocusRequester.Default
            }
            enter = {
                if (requester.restoreFocusedChild()) androidx.compose.ui.focus.FocusRequester.Cancel
                else androidx.compose.ui.focus.FocusRequester.Default
            }
        }
}
