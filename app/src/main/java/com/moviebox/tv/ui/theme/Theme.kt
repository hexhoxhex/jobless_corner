package com.moviebox.tv.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// Palette refreshed from MovieWay (com.community.oneroom), decompiled
// 2026-06-27. Same data architecture as us now (v0.1.85); modelling the
// look completes the convergence.
//
// 5-step background scale (blue-tinted, not pure black):
val Bg = Color(0xFF101114)           // deepest base
val Surface = Color(0xFF191F2B)      // primary surface — the distinctive choice
val SurfaceElevated = Color(0xFF28292E)  // cards / elevated
val SurfaceHigh = Color(0xFF383A40)   // hover / highest elevation
val Outline = Color(0xFF61656D)       // borders / dividers

// Brand: vibrant green CTA + bright highlight (replaces our old purple).
val Accent = Color(0xFF07B84E)        // primary CTAs (Download, Play, Search)
val AccentSoft = Color(0xFF2FF58B)    // bright highlight / active state

// 4-step text scale (warm-gray):
val TextPrimary = Color(0xFFFFFFFF)
val TextSecondary = Color(0xFFEDF0F5)
val TextMuted = Color(0xFF9BA0A8)
val TextHint = Color(0xFF767B85)

val Gold = Color(0xFFF5C518)          // ratings star (unchanged)
val Danger = Color(0xFFE5484D)        // errors / "stop" actions (warmer red)

private val colors = darkColorScheme(
    primary = Accent,
    onPrimary = Color.White,
    secondary = AccentSoft,
    background = Bg,
    onBackground = TextPrimary,
    surface = Surface,
    onSurface = TextPrimary,
    surfaceVariant = SurfaceElevated,
    onSurfaceVariant = TextMuted,
    error = Danger,
)

@Composable
fun MovieBoxTheme(
    @Suppress("UNUSED_PARAMETER") dark: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(colorScheme = colors, content = content)
}
