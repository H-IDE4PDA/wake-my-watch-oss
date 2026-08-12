package com.h_ide4pda.wakemywatch.phone.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val WmwBackground = Color(0xFF030B18)
val WmwSurface = Color(0xFF081526)
val WmwCard = Color(0xFF0B1A2D)
val WmwBlue = Color(0xFF00C2FF)
val WmwPurple = Color(0xFF7B61F6)
val WmwGreen = Color(0xFF00E676)
val WmwText = Color(0xFFF6F8FF)
val WmwMuted = Color(0xFFA9B4C8)

private val Scheme = darkColorScheme(
    primary = WmwPurple,
    secondary = WmwBlue,
    tertiary = WmwGreen,
    background = WmwBackground,
    surface = WmwSurface,
    surfaceVariant = WmwCard,
    onPrimary = Color.White,
    onSecondary = Color.Black,
    onBackground = WmwText,
    onSurface = WmwText,
    onSurfaceVariant = WmwMuted,
)

@Composable
fun WakeMyWatchTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = Scheme, content = content)
}
