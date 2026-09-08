package ui

import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val DarkSelectionAccent = Color(0xFF00E5FF)

const val TOOLTIP_DELAY_MS = 500
const val ERROR_AUTO_DISMISS_MS = 8000L
const val MAX_UNDO_DEPTH = 20
const val FILESYSTEM_POLL_INTERVAL_MS = 3000L

/**
 * How often a location in object storage is re-checked.
 *
 * Ten times the local interval, because the two cost nothing alike. A local check is a `stat`
 * against a warm page cache; a remote one is a LIST against a store that bills per request and
 * answers in tens to hundreds of milliseconds. On the three-second timer that is 1,200 requests an
 * hour per remote root for a table nobody is committing to — which is a bill, not a refresh.
 *
 * Thirty seconds is the compromise: a commit made elsewhere still appears without the reader doing
 * anything, and the standing cost of leaving the window open is small. An explicit reload does not
 * wait for it.
 */
const val REMOTE_POLL_INTERVAL_MS = 30_000L
const val MIN_ZOOM = 0.1f
const val MAX_ZOOM = 3f

fun perceivedBrightness(color: Color): Float =
    0.2126f * color.red + 0.7152f * color.green + 0.0722f * color.blue

fun isDarkSurface(color: Color): Boolean =
    perceivedBrightness(color) < 0.5f

/**
 * The scan-pruning verdicts as colours, for the leading cell of a verdict table.
 *
 * Skipped is the good news — a manifest a query never opens — so it takes the green. A term
 * nothing could be evaluated against takes the amber, because "would be read" for want of an
 * evaluator must not look like a manifest that was checked and kept. Read itself stays neutral:
 * it is the ordinary outcome and colouring it would spend attention on the majority of rows.
 *
 * There is no third function for the ordinary outcome. A verdict table passes `null` for those
 * rows, which leaves them at body colour and body weight — `WideTable` bolds a leading cell only
 * where one of these two was supplied, and a column bolded on every row has spent its emphasis
 * before the exception arrives.
 *
 * Both accents are lightened on a dark surface. #0A7048 has a perceived brightness of 0.30,
 * which is below the surface it would sit on.
 */
@Composable
fun verdictSkippedColor(): Color =
    if (isDarkSurface(MaterialTheme.colorScheme.surface)) Color(0xFF4CC38A) else Color(0xFF0A7048)

@Composable
fun verdictUnevaluatedColor(): Color =
    if (isDarkSurface(MaterialTheme.colorScheme.surface)) Color(0xFFE0A64A) else Color(0xFFA8600C)

/**
 * A dangling delete's row, in the same amber a verdict column's exception takes.
 *
 * Deliberately not a new colour: one vocabulary per window, and amber already means "this is the
 * row to look at" wherever a column of ordinary outcomes holds one that is not. It has its own
 * name because the *reason* differs — nothing failed to evaluate here, the evaluation succeeded
 * and the answer is that this delete file reaches nothing.
 */
@Composable
fun danglingDeleteColor(): Color = verdictUnevaluatedColor()

/**
 * The halo around a node the find bar matched.
 *
 * Amber rather than the accent, because the accent is already what selection means on this canvas
 * and the two are shown at once — the reader steps to a match and it becomes both. A match that
 * looked like a selection would make "which one am I on" unanswerable at a glance.
 */
val MatchHighlightLight = Color(0xFFB25E00)
val MatchHighlightDark = Color(0xFFE0A64A)

@Composable
fun matchHighlightColor(): Color =
    if (isDarkSurface(MaterialTheme.colorScheme.surface)) MatchHighlightDark else MatchHighlightLight

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
