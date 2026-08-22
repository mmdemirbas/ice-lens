package ui

import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.awt.Cursor

@Composable
fun DraggableVerticalDivider(onDrag: (Float) -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxHeight()
            .width(8.dp)
            .pointerHoverIcon(PointerIcon(Cursor(Cursor.E_RESIZE_CURSOR)))
            .pointerInput(Unit) {
                detectHorizontalDragGestures { change, dragAmount ->
                    change.consume()
                    onDrag(dragAmount)
                }
            }) {
        VerticalDivider(modifier = Modifier.align(Alignment.Center))
    }
}

@Composable
fun DraggableHorizontalDivider(onDrag: (Float) -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(8.dp)
            .pointerHoverIcon(PointerIcon(Cursor(Cursor.N_RESIZE_CURSOR)))
            .pointerInput(Unit) {
                detectVerticalDragGestures { change, dragAmount ->
                    change.consume()
                    onDrag(dragAmount)
                }
            }) {
        HorizontalDivider(
            modifier = Modifier.align(Alignment.Center),
            color = MaterialTheme.colorScheme.outlineVariant,
            thickness = 1.dp
        )
    }
}

@Composable
fun ToolbarGroup(content: @Composable RowScope.() -> Unit) {
    Surface(
        shape = RoundedCornerShape(4.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        color = MaterialTheme.colorScheme.surface
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.height(32.dp).padding(horizontal = 2.dp)
        ) {
            content()
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ToolbarIconButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    tooltip: String,
    onClick: () -> Unit,
    isSelected: Boolean = false,
    modifier: Modifier = Modifier
) {
    HoverTooltip(tooltip = tooltip) {
        IconButton(
            onClick = onClick,
            modifier = modifier,
            colors = if (isSelected) IconButtonDefaults.filledIconButtonColors(
                containerColor = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer
            ) else IconButtonDefaults.iconButtonColors()
        ) {
            Icon(icon, contentDescription = tooltip, modifier = Modifier.size(20.dp))
        }
    }
}

/** Wraps any content with a hover tooltip styled consistently across the app. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun HoverTooltip(
    tooltip: String,
    placement: TooltipPlacement = TooltipPlacement.CursorPoint(
        alignment = Alignment.BottomEnd,
        offset = DpOffset(0.dp, 16.dp)
    ),
    content: @Composable () -> Unit,
) {
    TooltipArea(
        tooltip = {
            Box(
                modifier = Modifier
                    // A tooltip that says what a control does is a sentence, not a name, and an
                    // unbounded one runs off the window rather than wrapping.
                    .widthIn(max = 280.dp)
                    .background(MaterialTheme.colorScheme.inverseSurface.copy(alpha = 0.92f), RoundedCornerShape(4.dp))
                    .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(4.dp))
                    .padding(8.dp)
            ) {
                Text(text = tooltip, color = MaterialTheme.colorScheme.inverseOnSurface, fontSize = TypeScale.small)
            }
        },
        delayMillis = TOOLTIP_DELAY_MS,
        tooltipPlacement = placement,
        content = content
    )
}

// ── Inspector building blocks ───────────────────────────────────────────────
// Shared by NodeDetails and by the scan-pruning section; they live here rather than beside one
// caller because more than one file draws a titled section and a table too wide for the panel.

@Composable
fun SectionTitle(title: String) {
    Text(title, fontWeight = FontWeight.Bold, fontSize = TypeScale.title)
    Spacer(Modifier.height(4.dp))
}

/**
 * A table wider than the panel it sits in, scrolled horizontally.
 *
 * [columnWidths] sizes columns individually; anything past its end falls back to [columnWidth].
 * One width for every column is the wrong default for this data — a field id needs four
 * characters and a decoded timestamp needs twenty, so a uniform width spends the panel on the
 * narrow columns and truncates the wide ones.
 *
 * Column order is load-bearing for the same reason. The reader sees the leftmost columns and
 * nothing else until they scroll, so the answer goes first and the identifiers follow it.
 */
@Composable
fun WideTable(
    headers: List<String>,
    rows: List<List<String>>,
    columnWidth: Dp = 180.dp,
    columnWidths: List<Dp> = emptyList(),
    /**
     * One colour per row for the leading cell, when that cell is a verdict rather than a label.
     * A column of prose verdicts reads at one weight and one colour, so the eye has to read every
     * row to find the interesting one — which defeats a table whose whole job is to be scanned.
     * The colour is never the only signal: the word stays, and it goes bold with it.
     */
    leadCellColors: List<Color?> = emptyList(),
) {
    val colors = MaterialTheme.colorScheme
    val horizontalState = rememberScrollState()
    val widths = List(headers.size) { index -> columnWidths.getOrNull(index) ?: columnWidth }
    val tableWidth = widths.fold(0.dp) { total, width -> total + width } +
        ((headers.size - 1).coerceAtLeast(0) * 9).dp + 16.dp
    Column(Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(horizontalState)
        ) {
            Column(
                Modifier
                    .width(tableWidth)
                    .clip(RoundedCornerShape(4.dp))
                    .border(1.dp, colors.outlineVariant, RoundedCornerShape(4.dp))
            ) {
                WideTableRow(headers, headers.size, widths, isHeader = true)
                rows.forEachIndexed { index, row ->
                    WideTableRow(row, headers.size, widths, isHeader = false, leadColor = leadCellColors.getOrNull(index))
                }
            }
        }
        // Without this the table simply appears to end at the panel edge: there is no cut, no
        // shadow and no scrollbar, so a reader has no way to know the columns past it exist.
        // The decoded value is one of those columns, which makes the missing affordance a
        // correctness problem rather than a cosmetic one.
        if (horizontalState.maxValue > 0) {
            HorizontalScrollbar(
                adapter = rememberScrollbarAdapter(horizontalState),
                modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
            )
        }
    }
}

@Composable
fun WideTableRow(
    cells: List<String>,
    columns: Int,
    widths: List<Dp>,
    isHeader: Boolean,
    leadColor: Color? = null,
) {
    val colors = MaterialTheme.colorScheme
    val bgColor = if (isHeader) colors.surfaceVariant else Color.Transparent
    val normalizedCells = if (cells.size < columns) {
        cells + List(columns - cells.size) { "" }
    } else {
        cells.take(columns)
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(bgColor)
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.Top
    ) {
        normalizedCells.forEachIndexed { index, cell ->
            if (index > 0) {
                Box(Modifier.width(1.dp).height(16.dp).background(colors.outlineVariant))
                Spacer(Modifier.width(8.dp))
            }
            val isVerdict = index == 0 && leadColor != null
            Text(
                text = cell,
                modifier = Modifier.width(widths[index]),
                fontSize = TypeScale.small,
                color = if (isVerdict) leadColor else Color.Unspecified,
                fontWeight = if (isHeader || isVerdict) FontWeight.Bold else FontWeight.Normal,
                fontFamily = if (isHeader) null else FontFamily.Monospace,
                // A row is as tall as its tallest cell, so a generous cap on one long cell costs
                // every row in the table. Four lines keeps a row scannable; the ellipsis says the
                // cell was cut, and the full value is on the node's own inspector — a partition
                // tuple in the Partition section, a bound in Column Statistics.
                maxLines = if (isHeader) 2 else 4,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
    HorizontalDivider(color = colors.outlineVariant, thickness = 0.5.dp)
}

/** A contribution's delta, signed, because a Paimon delta manifest can take files back out. */
