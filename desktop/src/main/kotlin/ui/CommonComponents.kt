package ui

import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.foundation.text.selection.DisableSelection
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
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

/**
 * Which inspector sections the reader has folded away, keyed by [sectionKey].
 *
 * Held as a default plus the sections that differ from it, rather than as a set of collapsed
 * keys, because "collapse all" has to reach sections that are not on screen — a section only
 * exists while its node is selected, so a set built from what is drawn would forget every
 * section on every other node type. The default answers for all of them at once.
 */
@Stable
class SectionCollapseState {
    private var everything by mutableStateOf(false)
    private val differing = mutableStateMapOf<String, Boolean>()

    fun isCollapsed(key: String): Boolean = differing[key] ?: everything

    fun toggle(key: String) {
        differing[key] = !isCollapsed(key)
    }

    /** True only when nothing is left expanded, so the one button can say what it will do. */
    val allCollapsed: Boolean get() = everything && differing.none { !it.value }

    fun setAll(collapsed: Boolean) {
        everything = collapsed
        differing.clear()
    }
}

val LocalSectionCollapse = staticCompositionLocalOf { SectionCollapseState() }

/**
 * The stable half of a section title — what is left after the count and the verdict.
 *
 * A title carries live figures ("Manifest Entries (12)", "Scan Pruning — this manifest would be
 * skipped"), so the displayed string changes as the table does. Keying collapse on it would
 * silently re-expand a section whenever its count moved.
 */
/**
 * The ring that says where the keyboard is.
 *
 * A control that takes focus and draws nothing for it is reachable and unusable: `KeyboardReachTest`
 * proves Tab arrives at the tool-window bar and a pane's close button, and `focus-bar-1.png` and
 * `focus-pane-close-1.png` came back **byte-identical** before this existed, which is the cheapest
 * possible proof that focus was drawing nothing at all. Material's default indication is for press,
 * not for focus.
 *
 * One dp of `primary`, which is what the workspace list already draws around itself. That list
 * keeps its own copy rather than calling this: its focus state has two more consumers — the row
 * cursor is drawn only while the list holds the keyboard — so the state has to be hoisted there
 * anyway, and a modifier that hides it would need it hoisted back out.
 *
 * Goes **above** the `clickable` or `focusable` it belongs to, since `onFocusChanged` observes the
 * focus modifiers below it in the chain.
 */
@Composable
fun Modifier.focusRing(shape: Shape = RoundedCornerShape(4.dp)): Modifier {
    var focused by remember { mutableStateOf(false) }
    return this
        .onFocusChanged { focused = it.isFocused }
        .focusRing(focused, shape)
}

/**
 * The same ring, drawn where the caller says rather than where the chain sits.
 *
 * A Material `IconButton` expands its own layout to the 48dp minimum interaction size *after* the
 * modifier it was handed, so a ring in that chain is measured at 48dp and paints outside a 28dp
 * header — which is what the first `focus-pane-close-1.png` showed. The button keeps its full
 * click target and the ring is drawn on a box inside it, fed from the button's own
 * `interactionSource`.
 */
@Composable
fun Modifier.focusRing(focused: Boolean, shape: Shape = RoundedCornerShape(4.dp)): Modifier =
    border(FOCUS_RING_WIDTH, if (focused) MaterialTheme.colorScheme.primary else Color.Transparent, shape)

private val FOCUS_RING_WIDTH = 1.dp

fun sectionKey(title: String): String = title.substringBefore(" (").substringBefore(" —").trim()

/**
 * A titled inspector section, foldable.
 *
 * [content] is emitted straight into the caller's layout rather than into a `Column` of this
 * function's own: the panel is one long `Column` and a wrapper would change what `fillMaxWidth`
 * and `weight` inside a section resolve against. A lambda written at the call site keeps the
 * enclosing `ColumnScope` as its implicit receiver, so nothing at a call site has to change.
 *
 * The header is inside `DisableSelection` because the panel is a `SelectionContainer`: without
 * it, dragging across a title starts a text selection instead of the tap reaching the toggle,
 * and selecting a title is not something anyone copying a value out of a table wants anyway.
 */
@Composable
fun Section(title: String, content: @Composable () -> Unit) {
    val collapse = LocalSectionCollapse.current
    val key = sectionKey(title)
    val collapsed = collapse.isCollapsed(key)
    // The gap above a section belongs to the section, not to whatever preceded it. With the
    // spacing written at call sites, a folded panel kept an expanded panel's rhythm and read as
    // eight headings floating a screen apart instead of as a list of what the node holds.
    Spacer(Modifier.height(if (collapsed) 8.dp else 16.dp))
    // `clickable` makes the header a focus target, and on desktop it draws nothing when it holds
    // focus — so a keyboard user tabbing through the panel would have no idea which section Enter
    // is about to fold.
    var focused by remember { mutableStateOf(false) }
    DisableSelection {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .onFocusChanged { focused = it.isFocused }
                .clickable { collapse.toggle(key) }
                .border(
                    width = 1.dp,
                    color = if (focused) MaterialTheme.colorScheme.primary else Color.Transparent,
                    shape = RoundedCornerShape(4.dp),
                )
                .padding(vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = if (collapsed) Icons.Default.ChevronRight else Icons.Default.ExpandMore,
                contentDescription = if (collapsed) "Expand $title" else "Collapse $title",
                modifier = Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.width(4.dp))
            Text(title, fontWeight = FontWeight.Bold, fontSize = TypeScale.title)
        }
    }
    if (!collapsed) {
        Spacer(Modifier.height(4.dp))
        content()
    }
}

/**
 * What one [WideTable] laid out, reported to whoever asked.
 *
 * [availableWidth] is what the panel actually gave the table, measured rather than assumed: the
 * inspector's padding is decided in several places and a test that hard-codes a budget is asserting
 * against its own arithmetic instead of against the layout.
 */
data class WideTableMetrics(
    val headers: List<String>,
    val columnWidths: List<Dp>,
    val tableWidth: Dp,
    val availableWidth: Dp,
)

/**
 * Set by a test that wants to know what the tables in a panel came out as. Null in the application.
 *
 * The same shape as `LocalCardContentProbe`, and for the same reason: the interesting property is
 * one the composition decides, so it has to be read from inside a real composition rather than
 * recomputed beside it.
 */
val LocalWideTableProbe = staticCompositionLocalOf<((WideTableMetrics) -> Unit)?> { null }

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
    val probe = LocalWideTableProbe.current
    val density = LocalDensity.current
    Column(
        Modifier.fillMaxWidth().then(
            if (probe == null) Modifier else Modifier.onSizeChanged { size ->
                probe(WideTableMetrics(headers, widths, tableWidth, with(density) { size.width.toDp() }))
            }
        )
    ) {
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

/**
 * Text sized by its own font metrics rather than by Material3's 24sp body line height.
 *
 * See `CardColumn` in `NodeComponents.kt` — same defect, same fix, a third surface. Anything that
 * wraps needs it, because a 10sp caption otherwise occupies 24dp per line and a three-line
 * explanation asks for 72dp of a panel that is 250dp wide; a single-line label does not care.
 */
@Composable
fun CompactText(content: @Composable () -> Unit) {
    CompositionLocalProvider(
        LocalTextStyle provides LocalTextStyle.current.copy(lineHeight = TextUnit.Unspecified),
        content = content,
    )
}
