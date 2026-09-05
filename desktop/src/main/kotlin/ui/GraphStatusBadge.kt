package ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp

/**
 * What the canvas is drawing, and what it is not.
 *
 * The group cards already say it locally, but a reader who has scrolled away from every one of
 * them has no signal that the graph is partial — and a partial graph that does not say so is the
 * thing the whole aggregation design exists to avoid. So the figure lives here too, always
 * visible, on the surface it describes.
 *
 * The two ways a node can be missing are counted separately because they are undone separately:
 * aggregation is opened by expanding a group, the snapshot filter by changing the filter.
 */
@Composable
fun GraphStatusBadge(
    drawnNodeCount: Int,
    hiddenByAggregation: Int,
    groupCount: Int,
    hiddenByFilter: Int,
    pageSize: Int,
    pageSizeChoices: List<Int>,
    /** Whether anything is open to collapse. Not derivable from [groupCount]: expanding every
     *  page of a parent leaves no group behind and still needs a way back. */
    hasExpandedGroups: Boolean,
    /** Whether paging is currently switched off entirely — see `AppState.drawEverything`. */
    drawEverything: Boolean,
    onPageSizeChange: (Int) -> Unit,
    onDrawEverythingChange: (Boolean) -> Unit,
    onCollapseAllGroups: () -> Unit,
    /** What a typed page size is accepted between. The app's own bounds, by default. */
    pageSizeRange: IntRange = AppState.MIN_GRAPH_PAGE_SIZE..AppState.MAX_GRAPH_PAGE_SIZE,
) {
    val colors = MaterialTheme.colorScheme
    var menuOpen by remember { mutableStateOf(false) }
    var customOpen by remember { mutableStateOf(false) }
    val total = drawnNodeCount + hiddenByAggregation + hiddenByFilter
    val isPartial = hiddenByAggregation > 0 || hiddenByFilter > 0

    Box {
        Column(
            modifier = Modifier
                .background(colors.surface.copy(alpha = 0.9f), RoundedCornerShape(8.dp))
                .border(
                    1.dp,
                    if (isPartial) colors.primary.copy(alpha = 0.6f) else colors.outlineVariant,
                    RoundedCornerShape(8.dp),
                )
                .clickable { menuOpen = true }
                .padding(horizontal = 10.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    if (isPartial) {
                        "Drawing ${formatCount(drawnNodeCount)} of ${formatCount(total)} nodes"
                    } else {
                        "Drawing all ${formatCount(total)} nodes"
                    },
                    fontSize = TypeScale.small,
                    fontWeight = FontWeight.SemiBold,
                    color = colors.onSurface,
                )
                Icon(
                    Icons.Default.ArrowDropDown,
                    contentDescription = "Graph drawing options",
                    tint = colors.onSurfaceVariant,
                    modifier = Modifier.size(16.dp),
                )
            }
            if (hiddenByAggregation > 0) {
                Text(
                    "${formatCount(hiddenByAggregation)} inside " +
                        (if (groupCount == 1) "1 collapsed group" else "${formatCount(groupCount)} collapsed groups"),
                    fontSize = TypeScale.micro,
                    color = colors.onSurfaceVariant,
                )
            }
            if (hiddenByFilter > 0) {
                Text(
                    "${formatCount(hiddenByFilter)} removed by the snapshot filter",
                    fontSize = TypeScale.micro,
                    color = colors.onSurfaceVariant,
                )
            }
        }

        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
            GraphOptionsMenuItems(
                total = total,
                pageSize = pageSize,
                pageSizeChoices = pageSizeChoices,
                hiddenByAggregation = hiddenByAggregation,
                hasExpandedGroups = hasExpandedGroups,
                drawEverything = drawEverything,
                onPageSizeChange = { menuOpen = false; onPageSizeChange(it) },
                onCustomPageSize = { menuOpen = false; customOpen = true },
                onDrawEverythingChange = { menuOpen = false; onDrawEverythingChange(it) },
                onCollapseAllGroups = { menuOpen = false; onCollapseAllGroups() },
            )
        }
        if (customOpen) {
            PageSizeDialog(
                current = pageSize,
                range = pageSizeRange,
                onDismiss = { customOpen = false },
                onApply = { customOpen = false; onPageSizeChange(it) },
            )
        }
    }
}

/**
 * Everything the badge offers, as items rather than as a menu.
 *
 * They are a function of their own so a render can reach them. A `DropdownMenu` is a popup and its
 * Material entrance animates up from alpha 0, so an offscreen scene — whose clock does not advance
 * between frames — captures the badge and nothing else; opening the menu from a seeded state was
 * measured and drew the menu at one scene height and not at another, which is a capture worse than
 * none. The items are what this file decides — which page size carries the check, what the paging
 * item says, and which of the two are dead — and they draw the same way wherever they are put. The
 * popup's shape, shadow and padding around them are Material's.
 */
@Composable
fun GraphOptionsMenuItems(
    total: Int,
    pageSize: Int,
    pageSizeChoices: List<Int>,
    hiddenByAggregation: Int,
    hasExpandedGroups: Boolean,
    drawEverything: Boolean,
    onPageSizeChange: (Int) -> Unit,
    onCustomPageSize: () -> Unit,
    onDrawEverythingChange: (Boolean) -> Unit,
    onCollapseAllGroups: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    Text(
        "Siblings drawn per parent",
        fontSize = TypeScale.small,
        fontWeight = FontWeight.SemiBold,
        color = colors.onSurfaceVariant,
        // Indented past the check slot, so the heading starts where the choices under it start
        // rather than where their check marks do.
        modifier = Modifier.padding(
            start = MENU_ITEM_PADDING + MENU_CHECK_SIZE + MENU_CHECK_GAP,
            end = MENU_ITEM_PADDING,
            top = 6.dp,
            bottom = 6.dp,
        ),
    )
    pageSizeChoices.forEach { choice ->
        DropdownMenuItem(
            text = {
                MenuItemRow(checked = choice == pageSize, checkDescription = "current page size") {
                    Text("$choice", fontSize = TypeScale.small)
                }
            },
            onClick = { onPageSizeChange(choice) },
        )
    }
    // A size the list does not offer is still a size: `updateGraphPageSize` takes anything in
    // range, and a reader with a table that pages awkwardly at every listed number wants the one
    // that fits it. When the size in force is one of these, this row carries the check.
    val custom = pageSize !in pageSizeChoices
    DropdownMenuItem(
        text = {
            MenuItemRow(checked = custom, checkDescription = "current page size") {
                Text(if (custom) "Other (${formatCount(pageSize)})" else "Other…", fontSize = TypeScale.small)
            }
        },
        onClick = onCustomPageSize,
    )
    HorizontalDivider()
    // The count is in the label because it is the whole of what the reader is consenting to:
    // paging exists because a production table is tens of thousands of nodes, and an action that
    // switches it off must say how many before it is taken, not after.
    DropdownMenuItem(
        text = {
            MenuItemRow(checked = drawEverything, checkDescription = "paging is off") {
                Text(
                    if (drawEverything) {
                        "Paging off — drawing all ${formatCount(total)}"
                    } else {
                        "Draw all ${formatCount(total)} nodes"
                    },
                    fontSize = TypeScale.small,
                )
            }
        },
        // Off when there is nothing more to draw: the graph is already whole, and an action that
        // would change nothing is one the reader has to try to find out.
        enabled = drawEverything || hiddenByAggregation > 0,
        onClick = { onDrawEverythingChange(!drawEverything) },
    )
    DropdownMenuItem(
        text = {
            MenuItemRow(checked = false, checkDescription = null) {
                Text("Collapse every group", fontSize = TypeScale.small)
            }
        },
        enabled = hasExpandedGroups && !drawEverything,
        onClick = { onCollapseAllGroups() },
    )
}

/**
 * One menu row: a check slot, then the label.
 *
 * The slot is reserved whether or not it holds a mark, so the labels sit on one x and the numbers
 * do not shift as the reader moves down the list.
 */
@Composable
private fun MenuItemRow(
    checked: Boolean,
    checkDescription: String?,
    label: @Composable () -> Unit,
) {
    Row(
        modifier = Modifier.widthIn(min = 180.dp),
        horizontalArrangement = Arrangement.spacedBy(MENU_CHECK_GAP),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(MENU_CHECK_SIZE)) {
            if (checked) {
                Icon(
                    Icons.Default.Check,
                    contentDescription = checkDescription,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(MENU_CHECK_SIZE),
                )
            }
        }
        label()
    }
}

private val MENU_CHECK_SIZE = 16.dp
private val MENU_CHECK_GAP = 8.dp

/** Material's own horizontal padding inside a [DropdownMenuItem]. Named here because the menu's
 *  heading is laid out against it and has no other way to know it. */
private val MENU_ITEM_PADDING = 12.dp

/**
 * A page size typed rather than chosen.
 *
 * The field is [PageSizeField], a composable of its own for the same reason the menu's items
 * are: a dialog is a window and a capture cannot open one, so the part this file decides — the
 * field, its bounds line, its error state — is what gets rendered.
 */
@Composable
private fun PageSizeDialog(current: Int, range: IntRange, onDismiss: () -> Unit, onApply: (Int) -> Unit) {
    var text by remember { mutableStateOf(current.toString()) }
    val parsed = parsePageSize(text, range)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Siblings drawn per parent", fontSize = TypeScale.body) },
        text = { PageSizeField(text, { text = it }, range, onSubmit = { parsed?.let(onApply) }) },
        confirmButton = {
            TextButton(onClick = { parsed?.let(onApply) }, enabled = parsed != null) { Text("Apply") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** The typed page size, with the bounds it must fall within stated under it. */
@Composable
fun PageSizeField(
    text: String,
    onTextChange: (String) -> Unit,
    range: IntRange,
    onSubmit: () -> Unit = {},
) {
    val parsed = parsePageSize(text, range)
    OutlinedTextField(
        value = text,
        onValueChange = onTextChange,
        singleLine = true,
        isError = parsed == null,
        label = { Text("Page size") },
        // The bounds are always on screen, not only once the reader has strayed outside them:
        // the field is for a number the list did not offer, so what is on offer is the question.
        supportingText = {
            Text("A whole number from ${formatCount(range.first)} to ${formatCount(range.last)}")
        },
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { onSubmit() }),
        modifier = Modifier.width(PAGE_SIZE_FIELD_WIDTH),
    )
}

/**
 * What the reader typed, as a page size, or null when it is not one.
 *
 * Thousands separators are allowed because the bounds line prints one — a reader who copies
 * `2,000` back should not be told it is not a number.
 */
fun parsePageSize(text: String, range: IntRange): Int? =
    text.trim().replace(",", "").replace("_", "").replace(" ", "")
        .toIntOrNull()
        ?.takeIf { it in range }

private val PAGE_SIZE_FIELD_WIDTH = 240.dp
