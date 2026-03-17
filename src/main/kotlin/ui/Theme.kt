package ui

import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val DarkSelectionAccent = Color(0xFF00E5FF)

const val TOOLTIP_DELAY_MS = 500

fun perceivedBrightness(color: Color): Float =
    0.2126f * color.red + 0.7152f * color.green + 0.0722f * color.blue

fun isDarkSurface(color: Color): Boolean =
    perceivedBrightness(color) < 0.5f

@Composable
fun selectionHighlightColor(): Color {
    val surface = MaterialTheme.colorScheme.surface
    return if (isDarkSurface(surface)) DarkSelectionAccent else MaterialTheme.colorScheme.primary
}

val IceLensLightColorScheme = lightColorScheme(
    primary = Color(0xFF1565C0),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFDDEBFF),
    onPrimaryContainer = Color(0xFF001C3B),
    secondary = Color(0xFF006B5F),
    onSecondary = Color.White,
    background = Color(0xFFF5F7FA),
    onBackground = Color(0xFF121417),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF121417),
    surfaceVariant = Color(0xFFE9EEF5),
    onSurfaceVariant = Color(0xFF475465),
    outline = Color(0xFF7A8798),
    outlineVariant = Color(0xFFB8C2D0),
    error = Color(0xFFBA1A1A),
    onError = Color.White
)

val IceLensDarkColorScheme = darkColorScheme(
    primary = Color(0xFFA9C7FF),
    onPrimary = Color(0xFF00315F),
    primaryContainer = Color(0xFF004A8A),
    onPrimaryContainer = Color(0xFFD9E7FF),
    secondary = Color(0xFF86D7CA),
    onSecondary = Color(0xFF003730),
    background = Color(0xFF101317),
    onBackground = Color(0xFFE2E6EC),
    surface = Color(0xFF161A20),
    onSurface = Color(0xFFE2E6EC),
    surfaceVariant = Color(0xFF2A3038),
    onSurfaceVariant = Color(0xFFC2CAD6),
    outline = Color(0xFF8C96A3),
    outlineVariant = Color(0xFF444C58),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005)
)
