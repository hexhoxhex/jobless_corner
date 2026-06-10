package com.moviebox.tv.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val Bg = Color(0xFF0B0D12)
val Surface = Color(0xFF151821)
val SurfaceElevated = Color(0xFF1E222D)
val Accent = Color(0xFF7C5CFC)
val AccentSoft = Color(0xFF9B86FF)
val TextPrimary = Color(0xFFF4F5F7)
val TextMuted = Color(0xFF9AA3B2)
val Gold = Color(0xFFF5C518)
val Danger = Color(0xFFFF5C7A)

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
