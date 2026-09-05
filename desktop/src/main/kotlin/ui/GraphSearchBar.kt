package ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import model.GraphSearchResult

/** Wide enough for a data file's name to be typed without the field scrolling under the cursor. */
private val SEARCH_FIELD_WIDTH = 260.dp

/**
 * Finding a node on the drawing, drawn on the drawing.
 *
 * It sits on the canvas rather than in the toolbar for two reasons. The toolbar is a fixed-height
 * `Row` of icon groups and a `Row` neither wraps nor clips — a 260dp field added to it would push
 * the rightmost group off the edge at a narrow window, unreachable, with nothing looking wrong.
 * And a question about what this drawing contains belongs on the drawing, which is the same
 * argument [GraphStatusBadge] is placed by.
 *
 * The counter is always drawn, including as `0 / 0`, because a find bar that shows nothing when
 * there is nothing cannot be told from one that has not run. When aggregation has folded nodes
 * away, the second line says how many were not searched — "no matches" is otherwise a claim about
 * the whole table that is only true of the part of it drawn.
 */
@Composable
fun GraphSearchBar(
    query: String,
    result: GraphSearchResult,
    currentNodeId: String?,
    onQueryChange: (String) -> Unit,
    onStep: (forward: Boolean) -> Unit,
    onClose: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val focusRequester = remember { FocusRequester() }

    // Opening the bar and having to click it would make the shortcut worth nothing.
    LaunchedEffect(Unit) { runCatching { focusRequester.requestFocus() } }

    Column(
        modifier = Modifier
            .background(colors.surface, RoundedCornerShape(6.dp))
            .border(1.dp, colors.outlineVariant, RoundedCornerShape(6.dp))
            .padding(horizontal = 6.dp, vertical = 4.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = query,
                onValueChange = onQueryChange,
                singleLine = true,
                placeholder = { Text("path, format, partition, operation…", fontSize = TypeScale.small) },
                textStyle = MaterialTheme.typography.bodySmall,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { onStep(true) }),
                modifier = Modifier
                    .width(SEARCH_FIELD_WIDTH)
                    .focusRequester(focusRequester)
                    // Enter steps and Shift+Enter steps back, the find-bar contract everywhere.
                    // Escape closes from inside the field, where the reader's hands already are.
                    .onPreviewKeyEvent { event ->
                        if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                        when (event.key) {
                            Key.Enter, Key.NumPadEnter -> { onStep(!event.isShiftPressed); true }
                            Key.Escape -> { onClose(); true }
                            else -> false
                        }
                    },
            )

            Spacer(Modifier.width(8.dp))
            Text(
                text = "${positionOf(result, currentNodeId)} / ${result.size}",
                fontSize = TypeScale.small,
                fontFamily = FontFamily.Monospace,
                color = if (result.isEmpty && query.isNotBlank()) colors.error else colors.onSurfaceVariant,
            )

            StepButton(Icons.Default.KeyboardArrowUp, "Previous match (Shift+Enter)") { onStep(false) }
            StepButton(Icons.Default.KeyboardArrowDown, "Next match (Enter)") { onStep(true) }
            StepButton(Icons.Default.Close, "Close (Esc)", onClick = onClose)
        }

        if (result.notDrawn > 0) {
            Text(
                "${formatCount(result.notDrawn)} nodes are not drawn and were not searched — " +
                    "raise the page size or expand a group to include them",
                fontSize = TypeScale.micro,
                color = colors.onSurfaceVariant,
                modifier = Modifier.padding(start = 4.dp, top = 2.dp, bottom = 2.dp),
            )
        }
    }
}

/** Which match the reader is standing on, 1-based, or 0 when the selection is not one of them. */
private fun positionOf(result: GraphSearchResult, currentNodeId: String?): Int =
    result.matches.indexOf(currentNodeId) + 1

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun StepButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    tooltip: String,
    onClick: () -> Unit,
) {
    HoverTooltip(tooltip = tooltip) {
        IconButton(onClick = onClick, modifier = Modifier.size(28.dp)) {
            Icon(icon, contentDescription = tooltip, modifier = Modifier.size(16.dp))
        }
    }
}
