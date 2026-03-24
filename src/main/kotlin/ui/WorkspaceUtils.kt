package ui

import model.WorkspaceItem
import model.WorkspaceTableStatus
import service.TableFormat
import service.TableFormatDetector
import java.io.File

fun isTableDirectory(dir: File): Boolean = TableFormatDetector.detect(dir) != TableFormat.UNKNOWN

fun isIcebergTable(dir: File): Boolean = TableFormatDetector.isIcebergTable(dir)

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

    fun walk(dir: File, relativePath: String, depth: Int) {
        if (depth > maxDepth || directoriesScanned >= maxDirectories) return
        val canonical = runCatching { dir.canonicalPath }.getOrElse { dir.absolutePath }
        if (!visited.add(canonical)) return
        directoriesScanned++

        if (TableFormatDetector.detect(dir) != TableFormat.UNKNOWN) {
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
