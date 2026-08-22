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
import androidx.compose.ui.unit.sp

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
    onPageSizeChange: (Int) -> Unit,
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
            Text(
                "Siblings drawn per parent",
                fontSize = TypeScale.small,
                fontWeight = FontWeight.SemiBold,
                color = colors.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            )
            pageSizeChoices.forEach { choice ->
                DropdownMenuItem(
                    text = {
                        Row(
                            modifier = Modifier.widthIn(min = 180.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            // The slot is reserved either way, so the numbers do not shift as the
                            // reader moves down the list.
                            Box(Modifier.size(16.dp)) {
                                if (choice == pageSize) {
                                    Icon(
                                        Icons.Default.Check,
                                        contentDescription = "current page size",
                                        tint = colors.primary,
                                        modifier = Modifier.size(16.dp),
                                    )
                                }
                            }
                            Text("$choice", fontSize = TypeScale.small)
                        }
                    },
                    onClick = {
                        menuOpen = false
                        onPageSizeChange(choice)
                    },
                )
            }
            HorizontalDivider()
            DropdownMenuItem(
                text = { Text("Collapse every group", fontSize = TypeScale.small) },
                enabled = hasExpandedGroups,
                onClick = {
                    menuOpen = false
                    onCollapseAllGroups()
                },
            )
        }
    }
}
