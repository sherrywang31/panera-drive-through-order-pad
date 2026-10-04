package com.driveorderpad.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val LightColors = lightColorScheme(
    primary = Color(0xFF355B38), onPrimary = Color.White,
    primaryContainer = Color(0xFFD7E9CE), onPrimaryContainer = Color(0xFF143517),
    secondary = Color(0xFF52634D),
    surface = Color(0xFFF7F8F3), onSurface = Color(0xFF1B211A),
    background = Color(0xFFF7F8F3), onBackground = Color(0xFF1B211A),
    surfaceContainer = Color(0xFFEEF1E8), surfaceContainerLowest = Color.White,
    outline = Color(0xFF72796C), outlineVariant = Color(0xFFC5CCBC),
    error = Color(0xFFB3261E), errorContainer = Color(0xFFF9DEDC), onErrorContainer = Color(0xFF410E0B),
)
private val DarkColors = darkColorScheme(
    primary = Color(0xFFACD49F), onPrimary = Color(0xFF193819),
    primaryContainer = Color(0xFF31512C), onPrimaryContainer = Color(0xFFCCE8BE),
    secondary = Color(0xFFBBCBB2),
    surface = Color(0xFF151C15), onSurface = Color(0xFFE0E5D8),
    background = Color(0xFF151C15), onBackground = Color(0xFFE0E5D8),
    surfaceContainer = Color(0xFF222A20), surfaceContainerLowest = Color(0xFF10170F),
    outline = Color(0xFF8D9684), outlineVariant = Color(0xFF434D3B),
    error = Color(0xFFFFB4AB), errorContainer = Color(0xFF6C211B), onErrorContainer = Color(0xFFFFDAD6),
)

@Composable
fun OrderPadTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors, content = content)
}
