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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
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
) {
    val colors = MaterialTheme.colorScheme
    var menuOpen by remember { mutableStateOf(false) }
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
                onDrawEverythingChange = { menuOpen = false; onDrawEverythingChange(it) },
                onCollapseAllGroups = { menuOpen = false; onCollapseAllGroups() },
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
