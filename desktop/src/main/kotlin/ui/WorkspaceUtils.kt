package ui

import model.WorkspaceItem
import model.WorkspaceTableStatus
import org.slf4j.LoggerFactory
import service.TableFormat
import service.TableFormatDetector
import java.io.File

private val logger = LoggerFactory.getLogger("ui.WorkspaceUtils")

fun isTableDirectory(dir: File): Boolean = TableFormatDetector.detect(dir.toPath()) != TableFormat.UNKNOWN

/**
 * Recursively scans [warehouseDir] for table directories (Iceberg or Paimon).
 * Returns relative paths from the warehouse root, sorted alphabetically.
 * Stops recursing into a directory once it is detected as a table.
 * Skips hidden directories (starting with '.').
 */
fun scanForTables(warehouseDir: File, maxDepth: Int = 50): List<String> {
    if (!warehouseDir.isDirectory) return emptyList()
    val tables = mutableListOf<String>()
    val visited = mutableSetOf<String>()
    var directoriesScanned = 0
    val maxDirectories = 10_000

    var limitReached = false

    fun walk(dir: File, relativePath: String, depth: Int) {
        if (depth > maxDepth) return
        if (directoriesScanned >= maxDirectories) {
            if (!limitReached) {
                limitReached = true
                logger.warn("Directory scan limit ({}) reached in {}; some tables may not be discovered", maxDirectories, warehouseDir)
            }
            return
        }
        val canonical = runCatching { dir.canonicalPath }.getOrElse { dir.absolutePath }
        if (!visited.add(canonical)) return
        directoriesScanned++

        if (TableFormatDetector.detect(dir.toPath()) != TableFormat.UNKNOWN) {
            tables.add(relativePath)
            return // don't recurse into table directories
        }
        dir.listFiles()
            ?.filter { it.isDirectory && !it.name.startsWith(".") }
            ?.sortedBy { it.name }
            ?.forEach { child ->
                walk(child, "$relativePath/${child.name}", depth + 1)
            }
    }

    warehouseDir.listFiles()
        ?.filter { it.isDirectory && !it.name.startsWith(".") }
        ?.sortedBy { it.name }
        ?.forEach { child ->
            walk(child, child.name, 1)
        }
    return tables.sorted()
}

/**
 * Opens a native directory chooser dialog.
 * On macOS, uses the native Finder-style file dialog.
 * On other platforms, falls back to Swing JFileChooser.
 */
fun chooseDirectory(initialDir: File?): File? {
    val startDir = initialDir?.takeIf { it.exists() } ?: File(System.getProperty("user.home"))
    val os = System.getProperty("os.name", "").lowercase()
    if (os.contains("mac")) {
        val oldValue = System.getProperty("apple.awt.fileDialogForDirectories")
        System.setProperty("apple.awt.fileDialogForDirectories", "true")
        try {
            val dialog = java.awt.FileDialog(null as java.awt.Frame?, "Add Warehouse or Table")
            dialog.directory = startDir.absolutePath
            dialog.isVisible = true
            val dir = dialog.directory
            val file = dialog.file
            return if (dir != null && file != null) File(dir, file) else null
        } finally {
            if (oldValue != null) {
                System.setProperty("apple.awt.fileDialogForDirectories", oldValue)
            } else {
                System.clearProperty("apple.awt.fileDialogForDirectories")
            }
        }
    } else {
        val chooser = javax.swing.JFileChooser()
        chooser.fileSelectionMode = javax.swing.JFileChooser.DIRECTORIES_ONLY
        chooser.dialogTitle = "Add Warehouse or Table"
        chooser.currentDirectory = startDir
        return if (chooser.showOpenDialog(null) == javax.swing.JFileChooser.APPROVE_OPTION) {
            chooser.selectedFile
        } else null
    }
}

/** Returns a short badge label for the detected table format, or null if unknown. */
fun formatBadgeLabel(dir: File): String? = when (TableFormatDetector.detect(dir.toPath())) {
    TableFormat.ICEBERG -> "ICE"
    TableFormat.PAIMON -> "PMN"
    TableFormat.UNKNOWN -> null
}

fun canonicalWorkspacePath(path: String): String =
    runCatching { File(path).canonicalPath }.getOrElse { File(path).absolutePath }

fun deduplicateWorkspaceItems(items: List<WorkspaceItem>): List<WorkspaceItem> {
    val seen = mutableSetOf<String>()
    return items.filter { item ->
        val key = canonicalWorkspacePath(item.path)
        seen.add(key)
    }
}

fun initialWarehouseTableStatuses(items: List<WorkspaceItem>): Map<String, Map<String, WorkspaceTableStatus>> {
    return items.asSequence()
        .filterIsInstance<WorkspaceItem.Warehouse>()
        .associate { warehouse ->
            warehouse.path to warehouse.tables.associateWith { WorkspaceTableStatus.EXISTING }
        }
}

/**
 * What one sweep of the filesystem found about the workspace roots, and nothing else.
 *
 * It exists so the reading and the deciding can happen on different threads. The sweep is a
 * recursive directory walk per warehouse — measured at 90ms for a thousand tables and 226ms at the
 * 10,000-directory cap `scanForTables` stops at, on a warm cache and a local disk — and it runs on
 * a three-second timer, so doing it where the frames are drawn freezes the window for several of
 * them every three seconds forever. This carries the answer back to the main thread, which is the
 * only place [WorkspaceItem] state may be written.
 *
 * A path missing from either map is one the sweep did not cover, which is what a root added while
 * it was running looks like. The caller leaves such a root exactly as it is rather than guessing;
 * the next poll covers it.
 */
data class WorkspaceScan(
    /** Warehouse path → the table paths under it, relative to the warehouse and sorted. */
    val warehouseTables: Map<String, List<String>>,
    /** Single-table path → whether it is still a table directory on disk. */
    val singleTableExists: Map<String, Boolean>,
)

/**
 * Reads the filesystem for every root in [items]. Touches no state, so it is safe off the main
 * thread — and it takes the roots as a parameter rather than reading them, because reading Compose
 * state from a background dispatcher is the bug this is meant to avoid, not one to introduce.
 */
fun scanWorkspace(items: List<WorkspaceItem>): WorkspaceScan = WorkspaceScan(
    warehouseTables = items.filterIsInstance<WorkspaceItem.Warehouse>()
        .associate { it.path to scanForTables(File(it.path)) },
    singleTableExists = items.filterIsInstance<WorkspaceItem.SingleTable>()
        .associate { table ->
            val dir = File(table.path)
            table.path to (dir.exists() && dir.isDirectory && isTableDirectory(dir))
        },
)

/**
 * The status a single table carries after a sweep saw it — or did not.
 *
 * `null` means the sweep never looked at this root, so its status is whatever it already was.
 * A table that comes back after being marked gone is `NEW` rather than `EXISTING`, which is what
 * makes a table reappearing on disk visible in the list rather than silently ordinary.
 */
fun nextTableStatus(previous: WorkspaceTableStatus, existsNow: Boolean?): WorkspaceTableStatus = when {
    existsNow == null -> previous
    !existsNow -> WorkspaceTableStatus.DELETED
    previous == WorkspaceTableStatus.DELETED -> WorkspaceTableStatus.NEW
    else -> previous
}

/** The same rule over one warehouse's tables, keeping the ones that have gone so they can be shown. */
fun nextWarehouseStatuses(
    previous: Map<String, WorkspaceTableStatus>,
    scanned: List<String>,
): Map<String, WorkspaceTableStatus> {
    val present = scanned.toSet()
    return (previous.keys + scanned).toSortedSet().associateWith { table ->
        when {
            table !in present -> WorkspaceTableStatus.DELETED
            table !in previous -> WorkspaceTableStatus.NEW
            else -> nextTableStatus(previous.getValue(table), existsNow = true)
        }
    }
}
