package ui

import model.WorkspaceItem
import model.WorkspaceTableStatus
import org.slf4j.LoggerFactory
import service.ObjectStorage
import service.StorageLocation
import service.TableFormat
import service.TableFormatDetector
import java.io.File

private val logger = LoggerFactory.getLogger("ui.WorkspaceUtils")

fun isTableDirectory(dir: File): Boolean = TableFormatDetector.detect(dir.toPath()) != TableFormat.UNKNOWN

/**
 * The same question for a location that may not be on this machine.
 *
 * Everything about a workspace root is a string until something opens it, and this is where a
 * remote one stops being treated as a local path. `StorageLocation.pathOf` gives back a `Path` on
 * whichever filesystem the scheme names, so the detector runs unchanged either way.
 */
fun isTableLocation(path: String): Boolean =
    runCatching { TableFormatDetector.detect(StorageLocation.pathOf(path)) != TableFormat.UNKNOWN }
        .getOrDefault(false)

/**
 * Every table under a warehouse, wherever the warehouse is.
 *
 * A local warehouse is walked; a remote one is **globbed**, because a walk over object storage
 * costs a request per level per table and the two markers this app detects a format by are path
 * shapes a glob can express directly. The result is relative to [warehouse] either way, which is
 * what the rest of the workspace expects.
 */
fun scanForTablesAt(warehouse: String): List<String> =
    if (!StorageLocation.isRemote(warehouse)) scanForTables(File(warehouse))
    else remoteTablesAt(warehouse).keys.toList()

/**
 * The same listing, keeping the format each table's own path shape already revealed.
 *
 * `globTables` matched either a metadata JSON under a table's `metadata` directory or a numbered
 * file under its `snapshot` one, and which of the two it was is the whole of what detection asks —
 * so the format arrives with the listing and costs nothing. It is
 * worth carrying rather than re-deriving: `TableFormatDetector` on a remote path is two network
 * round trips, and the badge is drawn during composition, so asking there would put one of those
 * per table on the main thread for as long as the warehouse is expanded.
 */
internal fun remoteTablesAt(warehouse: String): Map<String, TableFormat> {
    val root = warehouse.trimEnd('/')
    return ObjectStorage.globTables(root)
        .mapNotNull { (path, format) ->
            path.removePrefix("$root/").takeIf { it != path }?.let { it to format }
        }
        .sortedBy { it.first }
        .toMap()
}

/**
 * The format of a remote root, or UNKNOWN once it is no longer a table — letting a refusal
 * through as an exception.
 *
 * [isTableLocation] cannot answer this on its own, and the reason is worth stating because it is
 * the same trap twice: `TableFormatDetector` asks `Files.isDirectory`, which is *specified* to
 * answer `false` rather than throw, so through any filesystem it reports a refused key and a
 * dropped table identically. The glob is only there to fail — what it returns is not consulted,
 * because object storage has no directory entries and a table's own prefix usually holds no files
 * directly. Deciding stays with the detector.
 */
private fun remoteTableFormat(path: String): TableFormat {
    ObjectStorage.glob("${path.trimEnd('/')}/*")
    return runCatching { TableFormatDetector.detect(StorageLocation.pathOf(path)) }
        .getOrDefault(TableFormat.UNKNOWN)
}

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
fun formatBadgeLabel(dir: File): String? = formatBadge(TableFormatDetector.detect(dir.toPath()))

/**
 * The badge for a format that is already known — the one place the two short labels are written.
 *
 * There is deliberately no path-taking form any more. One existed, it read well, and nothing ever
 * called it: every call site had a local directory, so the remote branch it was written for was
 * never exercised, and remote tables simply drew no badge. The answer for a remote table comes
 * from the sweep now, which learned it while listing.
 */
fun formatBadge(format: TableFormat): String? = when (format) {
    TableFormat.ICEBERG -> "ICE"
    TableFormat.PAIMON -> "PMN"
    TableFormat.UNKNOWN -> null
}

/**
 * The form of a path two workspace entries are compared by.
 *
 * A remote URL must not go through `File.canonicalPath`: that resolves it against the working
 * directory and turns `s3://warehouse/db` into `<cwd>/s3:/b/db` — a path that is not the location,
 * would be stored instead of it, and would never open anything.
 */
fun canonicalWorkspacePath(path: String): String =
    if (StorageLocation.isRemote(path)) path.trim().trimEnd('/')
    else runCatching { File(path).canonicalPath }.getOrElse { File(path).absolutePath }

/**
 * The status a workspace *root* row draws, or null when nothing is being claimed about it.
 *
 * A warehouse used to be called deleted whenever `File(path).exists()` answered false, which is
 * true of **every** location in object storage: `File("s3://warehouse/db")` is a relative path that
 * is not there, so a bucket the reader had just added drew in error red with "(deleted)" beside it
 * for as long as it stayed in the workspace. Whether a remote warehouse is still there is a
 * question about the store, which only a sweep can ask — so until one has, this claims nothing
 * rather than claiming the wrong thing.
 */
fun workspaceRootStatus(
    item: WorkspaceItem,
    singleTableStatuses: Map<String, WorkspaceTableStatus>,
): WorkspaceTableStatus? = when (item) {
    is WorkspaceItem.SingleTable -> singleTableStatuses[item.path] ?: WorkspaceTableStatus.EXISTING
    is WorkspaceItem.Warehouse ->
        if (StorageLocation.isRemote(item.path)) null
        else if (!File(item.path).exists()) WorkspaceTableStatus.DELETED
        else null
}

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
 *
 * **That "not covered" rule is what lets a remote root be swept on a slower cadence.** A local
 * warehouse is a directory walk against a warm page cache; a remote one is two recursive globs
 * against object storage, and on the three-second timer that is a request every three seconds for
 * as long as the window is open — against a store that bills per request. `includeRemote = false`
 * omits those roots from both maps, and the existing rule then leaves them exactly as they were.
 */
data class WorkspaceScan(
    /** Warehouse path → the table paths under it, relative to the warehouse and sorted. */
    val warehouseTables: Map<String, List<String>>,
    /** Single-table path → whether it is still a table directory on disk. */
    val singleTableExists: Map<String, Boolean>,
    /**
     * Root path → why the store could not be asked, in the words the store's own error produced.
     *
     * A root here is absent from the other two maps, so the "not covered" rule leaves whatever it
     * already showed alone. That is the point: a refused key must not blank a warehouse full of
     * tables, and it must not mark a single table deleted, because neither is what happened.
     */
    val unreachable: Map<String, String> = emptyMap(),
    /**
     * Table path → its format badge, for the tables whose format the sweep learned while listing.
     *
     * Remote tables only, and that asymmetry is the point rather than an omission: a local badge
     * is a `stat` the row can do itself during composition, while a remote one is two network
     * round trips. This carries the answer the listing already produced so the row never asks.
     */
    val tableFormats: Map<String, String> = emptyMap(),
)

/**
 * Reads the filesystem for every root in [items]. Touches no state, so it is safe off the main
 * thread — and it takes the roots as a parameter rather than reading them, because reading Compose
 * state from a background dispatcher is the bug this is meant to avoid, not one to introduce.
 */
fun scanWorkspace(items: List<WorkspaceItem>, includeRemote: Boolean = true): WorkspaceScan {
    val warehouseTables = mutableMapOf<String, List<String>>()
    val singleTableExists = mutableMapOf<String, Boolean>()
    val unreachable = mutableMapOf<String, String>()
    val tableFormats = mutableMapOf<String, String>()

    items.filter { includeRemote || !StorageLocation.isRemote(it.path) }.forEach { item ->
        val root = item.path.trimEnd('/')
        // The catch is here rather than inside the helpers because this is where the decision is:
        // a sweep can carry "I could not ask" back, while a helper can only invent an answer.
        runCatching {
            when (item) {
                is WorkspaceItem.Warehouse -> if (StorageLocation.isRemote(item.path)) {
                    val found = remoteTablesAt(item.path)
                    warehouseTables[item.path] = found.keys.toList()
                    found.forEach { (relative, format) ->
                        formatBadge(format)?.let { tableFormats["$root/$relative"] = it }
                    }
                } else {
                    warehouseTables[item.path] = scanForTables(File(item.path))
                }
                is WorkspaceItem.SingleTable -> if (StorageLocation.isRemote(item.path)) {
                    val format = remoteTableFormat(item.path)
                    singleTableExists[item.path] = format != TableFormat.UNKNOWN
                    formatBadge(format)?.let { tableFormats[item.path] = it }
                } else {
                    singleTableExists[item.path] =
                        File(item.path).let { dir -> dir.exists() && dir.isDirectory && isTableDirectory(dir) }
                }
            }
        }.onFailure { failure ->
            logger.warn("Could not read the workspace root {}: {}", item.path, failure.message)
            unreachable[item.path] = failure.message?.takeIf { it.isNotBlank() }
                ?: "${failure::class.simpleName} while reading ${item.path}"
        }
    }
    return WorkspaceScan(warehouseTables, singleTableExists, unreachable, tableFormats)
}

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
