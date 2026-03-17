package ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val DarkSelectionAccent = Color(0xFF00E5FF)

fun perceivedBrightness(color: Color): Float =
    0.2126f * color.red + 0.7152f * color.green + 0.0722f * color.blue

fun isDarkSurface(color: Color): Boolean =
    perceivedBrightness(color) < 0.5f

@Composable
fun selectionHighlightColor(): Color {
    val surface = MaterialTheme.colorScheme.surface
    return if (isDarkSurface(surface)) DarkSelectionAccent else MaterialTheme.colorScheme.primary
}
