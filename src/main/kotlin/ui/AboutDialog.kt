package ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.awt.Desktop
import java.net.URI

/**
 * About dialog with two tabs: "About" (version, diagnostics, links) and "Cheat Sheet" (keyboard shortcuts).
 *
 * @param onDismiss called when the dialog is closed
 * @param onError called with an error message if something fails (e.g., opening a URL)
 */
@Composable
fun AboutDialog(
    onDismiss: () -> Unit,
    onError: (String) -> Unit,
) {
    val githubUrl = "https://github.com/mmdemirbas/ice-lens"
    var aboutTab by remember { mutableIntStateOf(0) }
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Close")
            }
        },
        title = {
            Column {
                Text("Iceberg Lens")
                Spacer(Modifier.height(4.dp))
                Row {
                    TextButton(onClick = { aboutTab = 0 }) {
                        Text("About", fontWeight = if (aboutTab == 0) FontWeight.Bold else FontWeight.Normal)
                    }
                    TextButton(onClick = { aboutTab = 1 }) {
                        Text("Cheat Sheet", fontWeight = if (aboutTab == 1) FontWeight.Bold else FontWeight.Normal)
                    }
                }
            }
        },
        text = {
            val scrollState = rememberScrollState()
            Column(Modifier.verticalScroll(scrollState).widthIn(min = 400.dp)) {
                if (aboutTab == 0) {
                    val appVersion = remember {
                        runCatching {
                            val props = java.util.Properties()
                            props.load(Thread.currentThread().contextClassLoader.getResourceAsStream("version.properties"))
                            props.getProperty("version", "dev")
                        }.getOrDefault("dev")
                    }
                    Text("A visual inspector for Apache Iceberg tables. It visualizes metadata, snapshots, manifests, and row-level delete relationships.")
                    Spacer(Modifier.height(8.dp))
                    Text("Version: $appVersion", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(4.dp))
                    Text("Author: Muhammed Demirbaş")
                    Spacer(Modifier.height(8.dp))
                    TextButton(onClick = {
                        val info = buildString {
                            appendLine("Iceberg Lens $appVersion")
                            appendLine("OS: ${System.getProperty("os.name")} ${System.getProperty("os.version")} (${System.getProperty("os.arch")})")
                            appendLine("Java: ${System.getProperty("java.version")} (${System.getProperty("java.vendor")})")
                            appendLine("Runtime: ${System.getProperty("java.runtime.name")} ${System.getProperty("java.runtime.version")}")
                        }
                        val clipboard = java.awt.Toolkit.getDefaultToolkit().systemClipboard
                        clipboard.setContents(java.awt.datatransfer.StringSelection(info), null)
                    }) {
                        Text("Copy diagnostic info", fontSize = 12.sp)
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = githubUrl,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.clickable {
                            runCatching {
                                if (!Desktop.isDesktopSupported()) {
                                    error("Desktop browsing is not supported on this platform.")
                                }
                                Desktop.getDesktop().browse(URI(githubUrl))
                            }.onFailure { e ->
                                onError("Failed to open GitHub link: ${e.message}")
                            }
                        }
                    )
                } else {
                    @Composable fun shortcutRow(action: String, shortcut: String) {
                        Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                            Text(action, modifier = Modifier.weight(1f), fontSize = 13.sp)
                            Text(shortcut, fontSize = 13.sp, color = MaterialTheme.colorScheme.primary)
                        }
                    }
                    Text("Keyboard Shortcuts", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    Spacer(Modifier.height(4.dp))
                    shortcutRow("Undo node drag", "Ctrl/Cmd + Z")
                    shortcutRow("Zoom in", "Ctrl/Cmd + =")
                    shortcutRow("Zoom out", "Ctrl/Cmd + -")
                    shortcutRow("Reset zoom", "Ctrl/Cmd + 0")
                    shortcutRow("Fit graph to view", "Ctrl/Cmd + Shift + F")
                    shortcutRow("Re-apply layout", "Ctrl/Cmd + L")
                    HorizontalDivider(Modifier.padding(vertical = 2.dp))

                    Spacer(Modifier.height(12.dp))
                    Text("Graph Canvas", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    Spacer(Modifier.height(4.dp))
                    shortcutRow("Pan / scroll", "Scroll wheel or trackpad")
                    shortcutRow("Zoom", "Ctrl/Cmd + scroll")
                    shortcutRow("Select node", "Click node")
                    shortcutRow("Multi-select", "Ctrl/Cmd + click")
                    shortcutRow("Marquee select", "Drag on canvas (Select mode)")
                    shortcutRow("Toggle marquee (add/remove)", "Shift + drag")
                    shortcutRow("Drag node(s)", "Drag selected node")
                    shortcutRow("Deselect all", "Click empty area")
                    HorizontalDivider(Modifier.padding(vertical = 2.dp))

                    Spacer(Modifier.height(12.dp))
                    Text("Panels", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    Spacer(Modifier.height(4.dp))
                    shortcutRow("Toggle all panels", "Double-click empty area")
                    shortcutRow("Toggle inspector", "Double-click a node")
                    shortcutRow("Reposition panel", "Drag panel title bar")
                    shortcutRow("Hide panel", "Click \u2212 on panel title")
                    shortcutRow("Show panel", "Click icon in side bar")
                    HorizontalDivider(Modifier.padding(vertical = 2.dp))

                    Spacer(Modifier.height(12.dp))
                    Text("Workspace", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    Spacer(Modifier.height(4.dp))
                    shortcutRow("Add table/warehouse", "Click 'Add to Workspace'")
                    shortcutRow("Reorder items", "Drag workspace items")
                    shortcutRow("Remove item", "Click \u00D7 on item")
                }
            }
        }
    )
}
