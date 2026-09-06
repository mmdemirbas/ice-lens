package ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.HelpOutline
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.AdsClick
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.PanTool
import androidx.compose.material.icons.filled.Schema
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.ZoomIn
import androidx.compose.material.icons.filled.ZoomOut
import androidx.compose.material.icons.filled.ZoomOutMap
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import model.snapshotFilterLabel
import java.awt.Desktop
import java.net.URI

/**
 * The row of controls above the canvas.
 *
 * Stateless on purpose: it reads values and reports intent, and every write to
 * `java.util.prefs` stays with the caller that owns the value. Extracted from `App.kt`, where it
 * was 266 lines inside a thousand-line composable and where "which of these `var`s does the
 * toolbar actually touch" could not be answered without reading all of it. The answer, once
 * asked, was eight things — which is what the signature below says.
 */
@Composable
fun Toolbar(
    state: AppState,
    isSelectMode: Boolean,
    onSelectModeChange: (Boolean) -> Unit,
    zoom: Float,
    onZoomChange: (Float) -> Unit,
    onFitGraph: () -> Unit,
    isDarkMode: Boolean,
    onDarkModeChange: (Boolean) -> Unit,
    onShowAbout: () -> Unit,
    snapshotFilterMenuExpanded: Boolean,
    onSnapshotFilterMenuChange: (Boolean) -> Unit,
    isSearchOpen: Boolean,
    onSearchOpenChange: (Boolean) -> Unit,
    onExport: (GraphExportFormat) -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .height(40.dp)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        ToolbarGroup {
            // Both tooltips say what dragging the empty canvas does, because that is the
            // only thing the two modes disagree about — a node is dragged and the wheel
            // pans in either one. "Pan Mode" and "Selection Mode" named the modes and
            // left the reader to try them.
            ToolbarIconButton(
                icon = Icons.Default.PanTool,
                tooltip = "Pan mode — drag the empty canvas to move the view",
                onClick = { onSelectModeChange(false) },
                isSelected = !isSelectMode,
                modifier = Modifier.size(32.dp)
            )
            Box(Modifier.width(1.dp).height(16.dp).background(MaterialTheme.colorScheme.outlineVariant))
            ToolbarIconButton(
                icon = Icons.Default.AdsClick,
                tooltip = "Select mode — drag the empty canvas to select what it covers " +
                    "(hold Shift to add or remove)",
                onClick = { onSelectModeChange(true) },
                isSelected = isSelectMode,
                modifier = Modifier.size(32.dp)
            )
        }

        Spacer(Modifier.width(16.dp))

        ToolbarGroup {
            ToolbarIconButton(
                icon = Icons.Default.ZoomOut,
                tooltip = "Zoom Out (Ctrl/Cmd + −)",
                onClick = { onZoomChange((zoom / 1.2f).coerceAtLeast(MIN_ZOOM)) },
                modifier = Modifier.size(32.dp)
            )
            Box(Modifier.width(1.dp).height(16.dp).background(MaterialTheme.colorScheme.outlineVariant))
            Text(
                "${(zoom * 100).toInt()}%",
                fontSize = TypeScale.small,
                modifier = Modifier.width(45.dp),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center
            )
            Box(Modifier.width(1.dp).height(16.dp).background(MaterialTheme.colorScheme.outlineVariant))
            ToolbarIconButton(
                icon = Icons.Default.ZoomIn,
                tooltip = "Zoom In (Ctrl/Cmd + =)",
                onClick = { onZoomChange((zoom * 1.2f).coerceAtMost(MAX_ZOOM)) },
                modifier = Modifier.size(32.dp)
            )
            Box(Modifier.width(1.dp).height(16.dp).background(MaterialTheme.colorScheme.outlineVariant))
            ToolbarIconButton(
                icon = Icons.Default.ZoomOutMap,
                tooltip = "Reset Zoom to 100% (Ctrl/Cmd + 0)",
                onClick = { onZoomChange(1f) },
                modifier = Modifier.size(32.dp)
            )
        }

        Spacer(Modifier.width(12.dp))

        ToolbarGroup {
            ToolbarIconButton(
                icon = Icons.Default.FullscreenExit,
                tooltip = "Fit Graph (Ctrl/Cmd + Shift + F)",
                onClick = {
                    if (state.visibleGraphModel != null) onFitGraph()
                },
                modifier = Modifier.size(32.dp)
            )
            Box(Modifier.width(1.dp).height(16.dp).background(MaterialTheme.colorScheme.outlineVariant))
            ToolbarIconButton(
                icon = Icons.Default.Schema,
                tooltip = "Re-apply Layout (Ctrl/Cmd + L)",
                onClick = { state.reapplyCurrentLayout() },
                modifier = Modifier.size(32.dp)
            )
            Box(Modifier.width(1.dp).height(16.dp).background(MaterialTheme.colorScheme.outlineVariant))
            // The bar itself is drawn on the canvas; this is its visible way in. A find bar that
            // only exists once you know the chord is a feature only its author has.
            ToolbarIconButton(
                icon = Icons.Default.Search,
                tooltip = "Find on the graph (Ctrl/Cmd + F)",
                onClick = { onSearchOpenChange(!isSearchOpen) },
                isSelected = isSearchOpen,
                modifier = Modifier.size(32.dp)
            )
            Box(Modifier.width(1.dp).height(16.dp).background(MaterialTheme.colorScheme.outlineVariant))
            ExportMenuButton(enabled = state.visibleGraphModel != null, onExport = onExport)
        }

        Spacer(Modifier.width(10.dp))

        SnapshotFilterGroup(
            state = state,
            expanded = snapshotFilterMenuExpanded,
            onExpandedChange = onSnapshotFilterMenuChange,
        )

        Spacer(Modifier.weight(1f))

        ToolbarGroup {
            ToolbarIconButton(
                icon = if (isDarkMode) Icons.Default.LightMode else Icons.Default.DarkMode,
                tooltip = if (isDarkMode) "Switch to light mode" else "Switch to dark mode",
                onClick = { onDarkModeChange(!isDarkMode) },
                modifier = Modifier.size(32.dp)
            )
            Box(Modifier.width(1.dp).height(16.dp).background(MaterialTheme.colorScheme.outlineVariant))
            fun openGithubLink() {
                runCatching {
                    if (!Desktop.isDesktopSupported()) {
                        error("Desktop browsing is not supported on this platform.")
                    }
                    Desktop.getDesktop().browse(URI("https://github.com/mmdemirbas/ice-lens"))
                }.onFailure { e ->
                    state.errorMsg = "Failed to open GitHub link: ${e.message}"
                }
            }
            ToolbarIconButton(
                icon = Icons.AutoMirrored.Filled.HelpOutline,
                tooltip = "About",
                onClick = onShowAbout,
                modifier = Modifier.size(32.dp)
            )
            Box(Modifier.width(1.dp).height(16.dp).background(MaterialTheme.colorScheme.outlineVariant))
            ToolbarIconButton(
                icon = Icons.AutoMirrored.Filled.OpenInNew,
                tooltip = "Open GitHub",
                onClick = { openGithubLink() },
                modifier = Modifier.size(32.dp)
            )
        }
    }
}

/**
 * The snapshot filter: a button carrying how much is filtered, and the menu that changes it.
 *
 * Its own composable because it is half the toolbar by line count and needs none of the rest of
 * it — only the table's snapshot options and whether the menu is open.
 */
@Composable
private fun SnapshotFilterGroup(
    state: AppState,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
) {
        ToolbarGroup {
            Box {
                ToolbarIconButton(
                    icon = Icons.Default.FilterList,
                    tooltip = "Filter by snapshots",
                    onClick = { onExpandedChange(!expanded) },
                    isSelected = state.selectedSnapshotFilterNodeIds.isNotEmpty(),
                    modifier = Modifier.size(32.dp)
                )
                DropdownMenu(
                    expanded = expanded,
                    onDismissRequest = { onExpandedChange(false) },
                    modifier = Modifier.widthIn(min = 320.dp, max = 520.dp)
                ) {
                    DropdownMenuItem(
                        text = {
                            Column {
                                Text(
                                    "Snapshot Filter",
                                    fontWeight = FontWeight.SemiBold
                                )
                                Text(
                                    "Show only nodes connected to selected snapshots",
                                    fontSize = TypeScale.small,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        },
                        onClick = {}
                    )
                    HorizontalDivider()
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        TextButton(
                            onClick = { state.updateSnapshotFilterSelection(state.allSnapshotFilterNodeIds) },
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                            modifier = Modifier.height(30.dp)
                        ) {
                            Icon(Icons.Default.DoneAll, contentDescription = "Select all", modifier = Modifier.size(14.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("All", fontSize = TypeScale.small)
                        }
                        TextButton(
                            onClick = { state.updateSnapshotFilterSelection(emptySet()) },
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                            modifier = Modifier.height(30.dp)
                        ) {
                            Icon(Icons.Default.Clear, contentDescription = "Clear selection", modifier = Modifier.size(14.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("None", fontSize = TypeScale.small)
                        }
                        TextButton(
                            onClick = {
                                state.updateSnapshotFilterSelection(state.allSnapshotFilterNodeIds - state.selectedSnapshotFilterNodeIds)
                            },
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                            modifier = Modifier.height(30.dp)
                        ) {
                            Icon(Icons.Default.SwapHoriz, contentDescription = "Invert selection", modifier = Modifier.size(14.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Invert", fontSize = TypeScale.small)
                        }
                    }
                    HorizontalDivider()
                    if (state.snapshotFilterOptions.isEmpty()) {
                        DropdownMenuItem(
                            text = { Text("No snapshots") },
                            onClick = {}
                        )
                    } else {
                        // H-10: cap height + scroll so long snapshot lists don't overflow the screen.
                        val filterScroll = rememberScrollState()
                        Column(
                            Modifier
                                .heightIn(max = 420.dp)
                                .verticalScroll(filterScroll)
                        ) {
                            state.snapshotFilterOptions.forEach { option ->
                                val isSelected = option.nodeId in state.selectedSnapshotFilterNodeIds
                                DropdownMenuItem(
                                    text = {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Checkbox(
                                                checked = isSelected,
                                                onCheckedChange = null
                                            )
                                            Spacer(Modifier.width(8.dp))
                                            Column {
                                                Text(snapshotFilterLabel(option), fontSize = TypeScale.small)
                                                Text(
                                                    formatAppTimestamp(option.timestampMs),
                                                    fontSize = TypeScale.micro,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                                )
                                            }
                                        }
                                    },
                                    onClick = {
                                        val updated = if (isSelected) {
                                            state.selectedSnapshotFilterNodeIds - option.nodeId
                                        } else {
                                            state.selectedSnapshotFilterNodeIds + option.nodeId
                                        }
                                        state.updateSnapshotFilterSelection(updated)
                                    }
                                )
                            }
                        }
                    }
                }
            }
            Box(Modifier.width(1.dp).height(16.dp).background(MaterialTheme.colorScheme.outlineVariant))
            Text(
                text = if (state.selectedSnapshotFilterNodeIds.isEmpty()) {
                    "All"
                } else {
                    "${state.selectedSnapshotFilterNodeIds.size}/${state.snapshotFilterOptions.size}"
                },
                fontSize = TypeScale.small,
                modifier = Modifier.padding(horizontal = 8.dp)
            )
        }
}

/**
 * The four ways out, behind one button.
 *
 * Four toolbar icons would spend a quarter of the bar on an action taken once a session, and the
 * formats are alternatives rather than parallel tools — a menu is the shape of "pick one". Each
 * item says what the format is *for* rather than repeating its own name, because "SVG" tells a
 * reader who already knows nothing new and a reader who does not know is the one reading the menu.
 */
@Composable
private fun ExportMenuButton(enabled: Boolean, onExport: (GraphExportFormat) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        ToolbarIconButton(
            icon = Icons.Default.FileDownload,
            tooltip = if (enabled) "Export the graph" else "Open a table to export it",
            onClick = { if (enabled) expanded = true },
            modifier = Modifier.size(32.dp),
        )
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            ExportMenuItems { format ->
                expanded = false
                onExport(format)
            }
        }
    }
}

/**
 * The items, separately from the menu around them, for the same reason `GraphOptionsMenuItems` is:
 * a `DropdownMenu` is a popup and a popup is not what an offscreen scene renders reliably, so this
 * is the part a capture can put in front of somebody.
 */
@Composable
fun ExportMenuItems(onPick: (GraphExportFormat) -> Unit) {
    GraphExportFormat.entries.forEach { format ->
        DropdownMenuItem(
            text = {
                Column {
                    Text(format.label, fontSize = TypeScale.small)
                    Text(
                        format.description,
                        fontSize = TypeScale.micro,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            onClick = { onPick(format) },
        )
    }
}
