package ui

import model.WorkspaceItem
import model.WorkspaceTableStatus
import java.io.File

fun isIcebergTable(dir: File): Boolean {
    val metaDir = File(dir, "metadata")
    return metaDir.exists() && metaDir.isDirectory && metaDir.listFiles { f ->
        f.name.endsWith(".metadata.json")
    }?.isNotEmpty() == true
}

fun scanForTables(warehouseDir: File): List<String> {
    return warehouseDir.listFiles { file ->
        file.isDirectory && isIcebergTable(file)
    }?.map { it.name }?.sorted() ?: emptyList()
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
