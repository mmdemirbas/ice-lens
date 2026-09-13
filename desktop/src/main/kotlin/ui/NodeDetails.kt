package ui

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.FirstPage
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.UnfoldLess
import androidx.compose.material.icons.filled.UnfoldMore
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import service.PositionalDeleteTally
import service.SampleRowReader
import model.DeleteCandidate
import model.UnreferencedFilesReport
import model.DeleteReachVerdict
import model.deleteCandidatesFor
import model.deleteKindOf
import model.DeleteFileKind
import model.ManifestContent
import model.ScanFilter
import model.isEmpty
import model.ComparableSnapshot
import service.GraphAggregation
import model.DiffSide
import model.snapshotDiff
import model.EntryFate
import model.LedgerEntry
import model.ManifestEntryView
import model.PaimonEntryEffect
import model.manifestLedger
import model.normalizeFilePath
import model.total
import model.DataFileContent
import model.DecodedPartition
import model.StatsDerivation
import model.DecodedValue
import model.GraphModel
import model.GraphNode
import model.KeyValuePairBytes
import model.SnapshotChange
import model.snapshotTotals
import model.FileChange
import model.manifestTallies
import model.partialRows
import model.partitionBreakdown
import model.planExpiry
import model.ExpiryOptions
import model.PaimonCompactionOptions
import model.RewriteOptions
import model.planRewrite
import model.PaimonExpiryInput
import model.paimonAppendVerdict
import model.planCompaction
import model.PaimonExpiryOptions
import model.TableMetadata
import model.stepComparableSnapshot
import model.UNDECODED_PARTITION
import model.LiveFile
import model.describe
import model.KeyValuePairLong
import model.MetadataLogEntry
import model.TableSchema
import model.metadataVersionFromFileName
import java.awt.Desktop
import java.io.File
import java.net.URI

/** One-line key per node type, used in the multi-select inspector. */
private fun multiSelectKey(node: GraphNode): String = when (node) {
    is GraphNode.TableNode -> node.summary.tablePath
    is GraphNode.MetadataNode -> node.fileName
    is GraphNode.SnapshotNode -> "${node.data.snapshotId ?: "?"} @ ${node.data.timestampMs?.let { formatTimestamp(it).substringBefore('\n') } ?: "?"}"
    is GraphNode.ManifestNode -> node.data.manifestPath?.substringAfterLast('/') ?: "?"
    is GraphNode.FileNode -> "${node.data.filePath?.substringAfterLast('/') ?: "?"} (${node.data.recordCount ?: "?"} rows)"
    is GraphNode.RowNode -> "row_idx=${node.data["row_idx"] ?: "?"} file_no=${node.data["file_no"] ?: "?"}"
    is GraphNode.ErrorNode -> node.message
    is GraphNode.PaimonSnapshotNode -> "${node.data.id ?: "?"} @ ${node.data.timeMillis?.let { formatTimestamp(it).substringBefore('\n') } ?: "?"}"
    is GraphNode.PaimonSchemaNode -> "schema-${node.data.id ?: "?"}"
    is GraphNode.PaimonManifestListNode -> node.kind
    is GraphNode.PaimonManifestNode -> node.data.fileName ?: "?"
    is GraphNode.PaimonDataFileNode -> "${node.entry.file?.fileName ?: "?"} (${node.entry.file?.rowCount ?: "?"} rows)"
    is GraphNode.GroupNode -> "${node.memberCount} ${node.kind.plural} not drawn"
}

private fun nodeTitle(node: GraphNode): String = when (node) {
    is GraphNode.TableNode -> "TABLE ${node.summary.tableName}"
    is GraphNode.MetadataNode -> "METADATA ${node.simpleId}"
    is GraphNode.SnapshotNode -> "SNAPSHOT ${node.simpleId}"
    is GraphNode.ManifestNode -> "MANIFEST ${node.simpleId}: ${if (node.data.content == 1) "DELETE" else "DATA"}"
    is GraphNode.FileNode -> "FILE ${node.simpleId}: ${fileContentLabel(node)}"
    is GraphNode.RowNode -> when (node.content) {
        1 -> "POS DELETE ROW"
        2 -> "EQ DELETE ROW"
        else -> "DATA ROW"
    }
    is GraphNode.ErrorNode -> "ERROR ${node.title}"
    is GraphNode.PaimonSnapshotNode -> "PAIMON SNAPSHOT ${node.simpleId}"
    is GraphNode.PaimonSchemaNode -> "PAIMON SCHEMA ${node.simpleId}"
    is GraphNode.PaimonManifestListNode -> "PAIMON ${node.kind.uppercase()} MANIFEST LIST"
    is GraphNode.PaimonManifestNode -> "PAIMON MANIFEST ${node.simpleId}"
    is GraphNode.PaimonDataFileNode -> "PAIMON FILE ${node.simpleId}"
    is GraphNode.GroupNode -> "NOT DRAWN: ${node.kind.plural.uppercase()}"
}

internal fun normalizeText(value: String?): String {
    if (value == null) return "N/A"
    return value
        .replace("\n", "\\n")
        .replace("\r", "\\r")
        .replace("\t", "\\t")
}

internal fun kvLongs(values: List<KeyValuePairLong>?): String =
    if (values.isNullOrEmpty()) "[]" else values.joinToString(", ") { "${it.key}:${it.value}" }

internal fun ByteArray.toHexShort(maxBytes: Int = 24): String {
    val head = take(maxBytes).joinToString("") { byte -> "%02x".format(byte) }
    return if (size > maxBytes) "$head..." else head
}

internal fun kvBytes(values: List<KeyValuePairBytes>?): String =
    if (values.isNullOrEmpty()) "[]" else values.joinToString(", ") { "${it.key}:${it.value.toHexShort()}" }

/**
 * A decoded bound for a table cell. A value that failed to decode shows its hex with the reason
 * appended rather than a bare "N/A" — the reader needs to know the difference between "no bound
 * was recorded" and "a bound was recorded and we could not read it".
 */
internal fun boundDisplay(bound: DecodedValue?): String = when {
    bound == null -> "N/A"
    bound.isError -> "${bound.display} — ${bound.error}"
    // The bytes are the narrower type's; the value is the same number, and the reader checking
    // it against the raw column needs to know which width to read the hex as.
    else -> bound.writtenAs?.let { "${bound.display} (written as ${it.typeName})" } ?: bound.display
}

/**
 * A file's partition tuple for a table cell, in the same `name=value` form Iceberg writes into the
 * file's own path. The three outcomes are kept distinct on purpose: a decoded tuple, a table that
 * genuinely has no partition fields, and a manifest whose spec could not be read.
 */
internal fun partitionCell(partition: DecodedPartition?): String = when {
    partition == null -> "N/A (no spec)"
    partition.isUnpartitioned -> "(unpartitioned)"
    else -> partition.path
}

internal fun longs(values: List<Long>?): String =
    if (values.isNullOrEmpty()) "[]" else values.joinToString(", ")

internal fun currentSnapshotLabel(currentSnapshotId: Long?): String = when (currentSnapshotId) {
    null -> "None"
    -1L -> "None (-1)"
    else -> currentSnapshotId.toString()
}

internal fun manifestContentRank(content: Int?): Int =
    service.IcebergGraphBuilder.manifestContentRank(content)

internal fun jsonToAnnotatedString(json: String, colors: androidx.compose.material3.ColorScheme): AnnotatedString {
    val stringStyle = SpanStyle(color = colors.secondary)
    val numberStyle = SpanStyle(color = colors.primary)
    val keywordStyle = SpanStyle(color = colors.tertiary, fontWeight = FontWeight.SemiBold)
    val punctStyle = SpanStyle(color = colors.onSurfaceVariant)
    val builder = AnnotatedString.Builder()

    var i = 0
    while (i < json.length) {
        val ch = json[i]
        when {
            ch == '"' -> {
                var j = i + 1
                var escaped = false
                while (j < json.length) {
                    val c = json[j]
                    if (c == '"' && !escaped) break
                    escaped = c == '\\' && !escaped
                    if (c != '\\') escaped = false
                    j++
                }
                val end = (j + 1).coerceAtMost(json.length)
                builder.pushStyle(stringStyle)
                builder.append(json.substring(i, end))
                builder.pop()
                i = end
            }
            ch == '-' || ch.isDigit() -> {
                var j = i + 1
                while (j < json.length && (json[j].isDigit() || json[j] in listOf('.', 'e', 'E', '+', '-'))) j++
                builder.pushStyle(numberStyle)
                builder.append(json.substring(i, j))
                builder.pop()
                i = j
            }
            json.startsWith("true", i) || json.startsWith("false", i) || json.startsWith("null", i) -> {
                val token = when {
                    json.startsWith("true", i) -> "true"
                    json.startsWith("false", i) -> "false"
                    else -> "null"
                }
                builder.pushStyle(keywordStyle)
                builder.append(token)
                builder.pop()
                i += token.length
            }
            ch in listOf('{', '}', '[', ']', ':', ',') -> {
                builder.pushStyle(punctStyle)
                builder.append(ch)
                builder.pop()
                i++
            }
            else -> {
                builder.append(ch)
                i++
            }
        }
    }
    return builder.toAnnotatedString()
}

private fun childNodes(node: GraphNode, graphModel: GraphModel): List<GraphNode> {
    val nodeById = graphModel.nodeById
    return graphModel.edges
        .asSequence()
        .filter { it.fromId == node.id }
        .mapNotNull { nodeById[it.toId] }
        .toList()
}

private data class DescendantRow(
    val rowNode: GraphNode.RowNode,
    val fileNode: GraphNode.FileNode?,
)

private fun collectDescendantRows(node: GraphNode, graphModel: GraphModel): List<DescendantRow> {
    val nodeById = graphModel.nodeById
    val childrenByParent = graphModel.edges
        .groupBy { it.fromId }
        .mapValues { (_, edges) -> edges.map { it.toId } }
    val seenRows = mutableSetOf<String>()
    val collected = mutableListOf<DescendantRow>()

    if (node is GraphNode.RowNode) {
        seenRows += node.id
        collected += DescendantRow(node, null)
    }

    fun visit(nodeId: String, currentFile: GraphNode.FileNode?) {
        val children = childrenByParent[nodeId].orEmpty()
            .mapNotNull { childId -> nodeById[childId] }
            .sortedWith(compareBy({ it.y }, { it.id }))
        children.forEach { child ->
            when (child) {
                is GraphNode.RowNode -> {
                    if (seenRows.add(child.id)) {
                        collected += DescendantRow(child, currentFile)
                    }
                }
                is GraphNode.FileNode -> visit(child.id, child)
                is GraphNode.PaimonDataFileNode -> visit(child.id, currentFile)
                else -> visit(child.id, currentFile)
            }
        }
    }

    visit(node.id, node as? GraphNode.FileNode)
    return collected.sortedWith(compareBy({ it.rowNode.y }, { it.rowNode.id }))
}

private fun identifierFieldNamesForNode(node: GraphNode, graphModel: GraphModel): List<String> {
    val nodeById = graphModel.nodeById
    val parentsByChild = graphModel.edges
        .groupBy { it.toId }
        .mapValues { (_, edges) -> edges.map { it.fromId } }

    fun identifiersFromMetadata(metadataNode: GraphNode.MetadataNode): List<String> {
        val metadata = metadataNode.data
        val schema = metadata.schemas
            .firstOrNull { it.schemaId == metadata.currentSchemaId }
            ?: metadata.schemas.firstOrNull()
            ?: return emptyList()
        val idSet = schema.identifierFieldIds.toSet()
        return schema.fields
            .filter { (it.id ?: -1) in idSet }
            .mapNotNull { it.name }
    }

    val metadataCandidates = mutableListOf<GraphNode.MetadataNode>()
    when (node) {
        is GraphNode.MetadataNode -> metadataCandidates += node
        is GraphNode.TableNode -> {
            val children = graphModel.edges
                .asSequence()
                .filter { it.fromId == node.id }
                .mapNotNull { edge -> nodeById[edge.toId] as? GraphNode.MetadataNode }
                .toList()
            metadataCandidates += children
        }
        else -> {
            val queue = ArrayDeque<String>()
            val visited = mutableSetOf<String>()
            queue.add(node.id)
            while (queue.isNotEmpty()) {
                val current = queue.removeFirst()
                if (!visited.add(current)) continue
                val parentIds = parentsByChild[current].orEmpty()
                parentIds.forEach { parentId ->
                    val parentNode = nodeById[parentId]
                    if (parentNode is GraphNode.MetadataNode) {
                        metadataCandidates += parentNode
                    }
                    queue.add(parentId)
                }
            }
        }
    }

    val chosen = metadataCandidates.maxByOrNull { it.simpleId } ?: return emptyList()
    return identifiersFromMetadata(chosen)
}

private fun formatEventLiteral(value: Any?): String = when (value) {
    is Number, is Boolean -> value.toString()
    else -> "'${value.toString().replace("'", "\\'")}'"
}

private fun buildChangelogEvent(
    row: GraphNode.RowNode,
    effectiveData: Map<String, Any>,
    orderedColumns: List<String>
): String {
    val op = if (row.content == 0) "+I" else "-D"
    val tupleValues = orderedColumns
        .mapNotNull { column -> effectiveData[column]?.let(::formatEventLiteral) }
    return "$op(${tupleValues.joinToString(",")})"
}

private fun asInt(value: Any?): Int? = when (value) {
    null -> null
    is Number -> value.toInt()
    else -> value.toString().toIntOrNull()
}

@Composable
internal fun RecursiveDataTableSection(
    node: GraphNode,
    graphModel: GraphModel
) {
    val rows = remember(node.id, graphModel.nodes, graphModel.edges) { collectDescendantRows(node, graphModel) }
    if (rows.isEmpty()) return

    val identifierFields = remember(node.id, graphModel.nodes, graphModel.edges) {
        identifierFieldNamesForNode(node, graphModel)
    }
    val dataRowIndexByFileAndPos = remember(graphModel.nodes) {
        graphModel.nodes
            .filterIsInstance<GraphNode.RowNode>()
            .filter { it.content == 0 }
            .mapNotNull { dataRow ->
                val fileNo = asInt(dataRow.data["file_no"]) ?: return@mapNotNull null
                val pos = asInt(dataRow.data["row_idx"]) ?: return@mapNotNull null
                (fileNo to pos) to dataRow
            }
            .toMap()
    }

    val effectiveDataByRowId = rows.associate { descendant ->
        val rowNode = descendant.rowNode
        val targetRow = if (rowNode.content == 1) {
            val targetFileNo = asInt(rowNode.data["target_file_no"])
            val targetPos = asInt(rowNode.data["pos"] ?: rowNode.data["position"])
            if (targetFileNo != null && targetPos != null) {
                dataRowIndexByFileAndPos[targetFileNo to targetPos]
            } else null
        } else {
            null
        }
        rowNode.id to (targetRow?.data ?: rowNode.data)
    }

    val metaColumns = setOf(
        "file_no",
        "row_idx",
        "target_file",
        "target_file_no",
        "local_file_path",
        "file_path",
        "pos",
        "position",
        GraphNode.RowNode.ROW_POSITION_KEY,
    )
    val allDataColumns = rows
        .flatMap { descendant -> effectiveDataByRowId[descendant.rowNode.id].orEmpty().keys }
        .filterNot { it in metaColumns }
        .distinct()
    val orderedDataColumns = identifierFields.filter { it in allDataColumns } +
        allDataColumns.filterNot { it in identifierFields }.sorted()

    Section("Changelog") {

        WideTable(
            headers = listOf("Idx", "Changelog", "Change") + orderedDataColumns,
            rows = rows.mapIndexed { index, descendant ->
                val rowNode = descendant.rowNode
                val effectiveData = effectiveDataByRowId[rowNode.id] ?: rowNode.data
                val change = when (rowNode.content) {
                    1 -> {
                        val targetFile = rowNode.data["target_file_no"] ?: "?"
                        val targetPos = rowNode.data["pos"] ?: rowNode.data["position"] ?: "?"
                        "del file=$targetFile pos=$targetPos"
                    }
                    2 -> {
                        val parts = identifierFields.mapNotNull { key ->
                            effectiveData[key]?.let { value -> "$key=$value" }
                        }
                        if (parts.isEmpty()) "del" else "del ${parts.joinToString(" ")}"
                    }
                    else -> "append"
                }
                val dataValues = orderedDataColumns.map { column ->
                    normalizeText(effectiveData[column]?.toString())
                }
                listOf(
                    "${index + 1}",
                    buildChangelogEvent(rowNode, effectiveData, orderedDataColumns),
                    change
                ) + dataValues
            }
        )
    }
}

/** Returns true when the path can be revealed via Desktop / Explorer (i.e. not a remote URI). */
private fun isLocalPath(path: String): Boolean {
    val trimmed = path.trim()
    if (trimmed.isEmpty()) return false
    // Cloud / remote schemes — Reveal silently fails on these.
    val remotePrefixes = listOf("s3://", "s3a://", "s3n://", "hdfs://", "gs://", "abfs://", "abfss://", "wasb://", "wasbs://", "oss://")
    if (remotePrefixes.any { trimmed.startsWith(it, ignoreCase = true) }) return false
    return true
}

private fun openContainingDirectory(path: String?) {
    val raw = path?.trim().orEmpty()
    if (raw.isEmpty()) return
    val file = if (raw.startsWith("file:")) {
        runCatching { File(URI(raw)) }.getOrNull()
    } else {
        File(raw)
    } ?: return
    val osName = System.getProperty("os.name")?.lowercase().orEmpty()
    runCatching {
        if (file.exists() && file.isFile) {
            when {
                osName.contains("mac") -> {
                    ProcessBuilder("open", "-R", file.absolutePath).start()
                    return
                }
                osName.contains("win") -> {
                    ProcessBuilder("explorer", "/select,${file.absolutePath}").start()
                    return
                }
            }
        }

        val target = if (file.isDirectory) file else file.parentFile ?: file
        if (Desktop.isDesktopSupported()) {
            Desktop.getDesktop().open(target)
        }
    }
}

private fun inspectorOpenPath(node: GraphNode): String? = when (node) {
    is GraphNode.TableNode -> node.summary.tablePath
    is GraphNode.MetadataNode -> node.localPath
    is GraphNode.SnapshotNode -> node.localPath
    is GraphNode.ManifestNode -> node.localPath
    is GraphNode.FileNode -> node.localPath
    is GraphNode.RowNode -> node.resolvedData["local_file_path"]?.toString()
    is GraphNode.ErrorNode -> node.path
    is GraphNode.PaimonSnapshotNode -> node.localPath
    is GraphNode.PaimonSchemaNode -> node.localPath
    is GraphNode.PaimonManifestListNode -> node.localPath
    is GraphNode.PaimonManifestNode -> node.localPath
    is GraphNode.PaimonDataFileNode -> node.localPath
    // A group stands for a set of files, not for one, so there is nothing to reveal in Finder.
    is GraphNode.GroupNode -> null
}

/**
 * How a file's path was arrived at. Only worth naming when it is not the recorded one, because
 * that is the case where a "file not found" is about a path the table never mentioned.
 */
internal fun pathResolutionLabel(resolution: model.PathResolution): String = when (resolution) {
    model.PathResolution.RECORDED -> "as recorded in the table"
    model.PathResolution.FORCED_RELATIVE ->
        "by file name, against the local metadata directory (the recorded path is not present here)"
    model.PathResolution.REBUILT_BESIDE_TABLE ->
        "by re-rooting the recorded path from the recorded warehouse to the local one (the recorded path is not present here)"
}

/**
 * The same two outcomes, reached a different way. A data file's fallback keeps the sub-path
 * below the table — `data/name=alpha/…` — and rebuilds it under the local table directory, so
 * saying "by file name" would describe the wrong operation.
 */
internal fun dataFileResolutionLabel(resolution: model.PathResolution): String = when (resolution) {
    model.PathResolution.RECORDED -> "as recorded in the table"
    model.PathResolution.FORCED_RELATIVE ->
        "by rebuilding the path under the local table directory (the recorded path is not present here)"
    // The third outcome is a data file's alone: a write.data.path file has no sub-path under the
    // table to rebuild, so it is re-rooted from the recorded warehouse to the local one.
    model.PathResolution.REBUILT_BESIDE_TABLE ->
        "by re-rooting the recorded path beside the table — it sits outside the table directory " +
            "(write.data.path), so it was placed under the local counterpart of the warehouse the table is in"
}

private fun deltaCell(value: Long): String = when {
    value > 0 -> "+${formatCount(value)}"
    else -> formatCount(value)
}

private fun deltaBytesCell(value: Long): String = when {
    value > 0 -> "+${formatBytes(value)}"
    value < 0 -> "-${formatBytes(-value)}"
    else -> formatBytes(0)
}

/**
 * The ledger a figure above was folded from: one row per manifest, in traversal order.
 *
 * This is the section that makes a count checkable rather than asserted. The figures in the
 * table above are the sum of this table's delta columns — not a number computed next to it — so
 * a reader who doubts "4 data files" can add the column up, and a reader who wonders why twelve
 * snapshots did not produce twelve times the files can read the rows that contributed nothing
 * and see which snapshot counted each manifest first.
 */
@Composable
internal fun DerivationSection(title: String, derivation: StatsDerivation) {
    if (derivation.contributions.isEmpty()) return
    val counted = derivation.contributions.count { !it.isRepeat }
    Section("$title — ${formatCount(counted)} counted, ${formatCount(derivation.repeats.size)} already counted") {
        Text(
            "One row per manifest as the traversal reached it. The figures above are this table's " +
                "delta columns summed; a manifest a later snapshot re-lists contributes nothing and " +
                "names where it was counted first.",
            fontSize = TypeScale.small,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 4.dp)
        )
        WideTable(
            // Counted sits second, before the numbers. A repeat contributes nothing, so its delta
            // cells are all dashes — and a row of dashes with no nearby word for why is the same
            // failure as a value column pushed off the panel edge. The snapshot that counted it
            // first is the detail behind the flag, so it goes last.
            headers = listOf(
                "Manifest", "Counted", "Data Files", "Records", "Bytes",
                "Entries", "Duplicate Entries", "First Counted In"
            ),
            columnWidths = listOf(200.dp, 70.dp, 90.dp, 90.dp, 110.dp, 80.dp, 130.dp, 240.dp),
            rows = derivation.contributions.map { contribution ->
                val delta = contribution.delta
                fun cell(value: () -> String) = if (contribution.isRepeat) "-" else value()
                listOf(
                    contribution.manifestPath.substringAfterLast('/'),
                    if (contribution.isRepeat) "repeat" else "yes",
                    cell { deltaCell(delta.dataFileCount.toLong()) },
                    cell { deltaCell(delta.recordCount) },
                    cell { deltaBytesCell(delta.totalSizeBytes) },
                    cell { formatCount(delta.manifestEntryCount) },
                    cell { formatCount(contribution.entriesSuppressedAsDuplicate) },
                    contribution.firstCountedIn ?: "-",
                )
            }
        )
    }
}

/**
 * How one manifest's counted figures come out of its entries, and which entries added nothing.
 *
 * The section above says whether the manifest list's recorded counts match what the entries add
 * up to. This says how that side of the comparison was reached, which is the half a reader
 * otherwise has to take on trust — and the same [manifestLedger] the table's own totals are
 * folded from, so there is no second implementation to disagree with the first.
 *
 * Only the entries that added nothing are listed. Every other entry is already in the entries
 * table below with its record count and its size; repeating all of them here would bury the
 * three rows that are the reason to look.
 */
@Composable
internal fun ManifestLedgerSection(entries: List<ManifestEntryView>) {
    if (entries.isEmpty()) return
    val colors = MaterialTheme.colorScheme
    // Scoped to this manifest: a fresh seen-set, so an entry naming a file some other manifest
    // counted first still counts here. The prose says so, because the table's totals do drop it.
    val ledger = remember(entries) {
        manifestLedger(
            entries = entries.map { view ->
                val path = view.entry.dataFile?.filePath?.takeIf { it.isNotBlank() }
                LedgerEntry(
                    fileKey = path?.let(::normalizeFilePath) ?: "path:${view.localPath}",
                    status = view.entry.status,
                    dataFile = view.entry.dataFile,
                )
            },
            liveEntriesOnly = true,
        )
    }
    val total = ledger.total()
    val dropped = ledger.filter { it.fate != EntryFate.COUNTED }

    Section("How the counted figures were reached") {
        Text(
            "Every entry takes a place in the entry count, because that figure measures what a scan " +
                "has to read. What it adds beyond that depends on two rules. Deduplication here is " +
                "scoped to this manifest — the table's own totals also drop a file some other " +
                "manifest counted first, which cannot be seen from inside one.",
            fontSize = TypeScale.small,
            color = colors.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 4.dp),
        )
        WideTable(
            headers = listOf("Figure", "Value", "From"),
            columnWidths = listOf(160.dp, 110.dp, 360.dp),
            rows = listOf(
                listOf("Entries", formatCount(total.manifestEntryCount), "every entry, whatever became of it"),
                listOf(
                    "counted",
                    formatCount(ledger.count { it.fate == EntryFate.COUNTED }),
                    "a live entry, first sighting of its data file",
                ),
                listOf(
                    "records a removal",
                    formatCount(ledger.count { it.fate == EntryFate.REMOVAL }),
                    "status = DELETED (2); the file it names is no longer in the table",
                ),
                listOf(
                    "already counted",
                    formatCount(ledger.count { it.fate == EntryFate.DUPLICATE }),
                    "another entry here names the same data-file path",
                ),
                listOf("Data files", formatCount(total.dataFileCount), "counted entries with content = 0"),
                listOf(
                    "Delete files",
                    formatCount(total.deleteFileCount),
                    "counted entries with content 1 or 2 — ${formatCount(total.posDeleteFileCount)} positional, " +
                        "${formatCount(total.eqDeleteFileCount)} equality",
                ),
                listOf("Records", formatCount(total.recordCount), "record_count summed over the data files"),
                listOf(
                    "Delete records",
                    formatCount(total.deleteRecordCount),
                    "record_count summed over the delete files — rows deleted, not table rows",
                ),
                listOf("Bytes", formatBytes(total.totalSizeBytes), "file_size_in_bytes summed over both"),
            ),
        )

        if (dropped.isEmpty()) {
            Text(
                "Every entry counted.",
                fontSize = TypeScale.small,
                color = colors.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
            return@Section
        }
    }
    Section("Entries that added nothing (${formatCount(dropped.size)})") {
        WideTable(
            headers = listOf("Why", "Entry", "Records it would have added"),
            columnWidths = listOf(150.dp, 310.dp, 170.dp),
            rows = dropped.map { entry ->
                listOf(
                    when (entry.fate) {
                        EntryFate.REMOVAL -> "records a removal"
                        EntryFate.DUPLICATE -> "already counted"
                        EntryFate.COUNTED -> "counted"
                    },
                    entry.filePath.substringAfterLast('/'),
                    formatCount(entry.recordCount),
                )
            },
            leadCellColors = dropped.map { verdictUnevaluatedColor() },
        )
    }
}

/**
 * What each of this manifest's entries did to the table's live set.
 *
 * This is Paimon's answer to the Iceberg ledger above, and it is deliberately a different shape.
 * `manifestLedger` gives a *verdict per entry* — counted, records a removal, already counted —
 * because an Iceberg entry can be decided on its own. A Paimon entry cannot: `_KIND=1` means
 * "remove what is there", so what it did depends on what the entries before it left behind. So each
 * row states the state the entry met and the effect the two produced together, which is the only
 * honest form the question takes here.
 *
 * The effects the reader is looking for are the ones that are not plain additions: a **replacement**
 * is a file rewritten in place, where the record delta is the difference rather than the new file's
 * whole count, and **removed nothing** is a delta carrying a removal for a file its base never
 * listed — a no-op that is invisible in every aggregate above.
 *
 * The trace is a `DeferredRead` because producing it means replaying the whole snapshot, not
 * opening a file. It is read here, once, for the manifest on screen.
 */
@Composable
internal fun PaimonReplayTraceSection(node: GraphNode.PaimonManifestNode) {
    val colors = MaterialTheme.colorScheme
    val trace = remember(node.id) { node.replayTrace.value }
    if (trace.isNullOrEmpty()) return

    val notPlain = trace.count { it.effect != PaimonEntryEffect.ADDED }

    CountedSection("How this manifest changed the live set", trace.size, "entries") {
        Text(
            "A Paimon manifest is replayed, not filtered: an entry's effect depends on what the " +
                "entries before it left in the table. The delta columns are what each entry moved " +
                "the running totals by — a rewrite moves them by the difference, and a removal for " +
                "a file that was not live moves nothing at all. " +
                if (notPlain == 0) "Every entry here was a plain addition."
                else "${formatCount(notPlain)} of these were not plain additions.",
            fontSize = TypeScale.small,
            color = colors.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 4.dp),
        )
        WideTable(
            // The effect first: it is the answer, and the panel is far narrower than the table.
            headers = listOf("Effect", "File", "Was live", "Records", "Bytes", "Files"),
            columnWidths = listOf(140.dp, 300.dp, 90.dp, 110.dp, 110.dp, 70.dp),
            rows = trace.map { row ->
                listOf(
                    row.effect.label,
                    (row.fileName ?: row.fileKey).substringAfterLast('/'),
                    if (row.wasLive) {
                        "${formatCount(row.previousRecordCount ?: 0L)} rows"
                    } else "no",
                    withSign(row.recordDelta),
                    withSign(row.byteDelta),
                    withSign(row.liveFileDelta.toLong()),
                )
            },
            // Colour marks the exception, not every row — the scan-pruning verdict column's rule.
            // Adding and removing are both ordinary here: a compaction commit removes files, and a
            // column emphasised on every row has spent its emphasis before the row worth finding
            // arrives. The two that are worth finding are a **rewrite in place**, where the record
            // delta is a difference and not a file's own count, and a removal that **found nothing
            // to remove**, which is a no-op invisible in every figure above.
            leadCellColors = trace.map { row ->
                when (row.effect) {
                    PaimonEntryEffect.ADDED, PaimonEntryEffect.REMOVED -> null
                    PaimonEntryEffect.REPLACED -> verdictUnevaluatedColor()
                    PaimonEntryEffect.REMOVED_ABSENT -> colors.error
                }
            },
        )
    }
}

/** A delta reads as a delta: the sign is always shown, including the plus. */
private fun withSign(value: Long): String =
    if (value > 0) "+${formatCount(value)}" else formatCount(value)

internal fun renderMetadataLogRows(items: List<MetadataLogEntry>): List<List<String>> =
    items.sortedBy { it.timestampMs ?: Long.MAX_VALUE }.map { entry ->
        listOf(
            formatTimestampShort(entry.timestampMs),
            normalizeText(entry.metadataFile)
        )
    }

@Composable
fun NodeDetailsContent(
    graphModel: GraphModel?,
    selectedNodeIds: Set<String>,
    /**
     * Opens a [GraphNode.GroupNode]. Defaulted so the render tests can draw the panel without a
     * running app; in the app it rebuilds the graph with that group expanded.
     */
    onExpandGroup: (String) -> Unit = {},
    /** Opens every remaining page of a group at once. Defaulted for the same reason. */
    onExpandGroupFully: (GraphNode.GroupNode) -> Unit = {},
    /** The scan filter, and the way to change it. Defaulted so the render tests need no state. */
    scanFilter: ScanFilter = ScanFilter.of(emptyList()),
    onScanFilterChange: (ScanFilter) -> Unit = {},
    /**
     * Which sections are folded. Remembered here rather than held per node, because a reader who
     * folds "Raw metadata.json" away means it for the table, not for the one metadata version
     * they happened to be looking at. A parameter so a render can capture the folded state, which
     * is otherwise unreachable without a click.
     */
    sectionCollapse: SectionCollapseState = remember { SectionCollapseState() },
    /**
     * Page ids the reader has opened, so the panel can offer the inverse.
     *
     * Passed rather than read off the graph because there is nothing on the graph to read: a
     * parent whose last page has been opened has no group node left standing for it, which is
     * exactly the state that needed a way back.
     */
    expandedGroupIds: Set<String> = emptySet(),
    onCollapseGroupsUnder: (String) -> Unit = {},
    /**
     * Replaces the selection. The comparison's step controls use it: stepping one snapshot is
     * selecting the pair with that side moved, so the panel needs no state of its own and the
     * canvas highlights the pair it is showing. Defaulted so a render needs no app.
     */
    onSelectNodes: (Set<String>) -> Unit = {},
) {
    val colors = MaterialTheme.colorScheme
    SelectionContainer {
        CompositionLocalProvider(
            LocalContentColor provides colors.onSurface,
            LocalSectionCollapse provides sectionCollapse,
        ) {
            if (selectedNodeIds.isEmpty()) {
                Text(
                    "Select a node to view details.",
                    fontSize = TypeScale.small,
                    color = colors.onSurfaceVariant,
                    modifier = Modifier.padding(8.dp)
                )
                return@CompositionLocalProvider
            }
            if (selectedNodeIds.size > 1) {
                val multiGraph = graphModel
                // Two snapshots selected is a question, not an accident: it is the only way to
                // ask what is different between two commits that are not parent and child. The
                // panel is scrollable because the answer is a file list, unlike the summary
                // table this branch otherwise shows.
                val pair = multiGraph?.let { comparableSnapshots(it, selectedNodeIds) }
                if (pair != null) {
                    Box(Modifier.fillMaxSize()) {
                        val scroll = rememberScrollState()
                        Column(Modifier.fillMaxSize().verticalScroll(scroll).padding(8.dp)) {
                            SnapshotComparison(pair.first, pair.second, multiGraph, onSelectNodes)
                        }
                        VerticalScrollbar(
                            adapter = rememberScrollbarAdapter(scroll),
                            modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight(),
                        )
                    }
                    return@CompositionLocalProvider
                }
                Column(Modifier.padding(8.dp)) {
                    Text("${selectedNodeIds.size} Nodes Selected", fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(8.dp))
                    if (multiGraph == null) {
                        Text("(graph not loaded)", fontSize = TypeScale.small, color = colors.onSurfaceVariant)
                    } else {
                        DetailTable {
                            DetailRow("Type / ID", "Key", isHeader = true)
                            selectedNodeIds.forEach { id ->
                                val n = multiGraph.nodeById[id] ?: return@forEach
                                DetailRow(nodeTitle(n), multiSelectKey(n))
                            }
                        }
                        if (multiGraph.nodeById.values.count { it.id in selectedNodeIds && it is ComparableSnapshot } > 2) {
                            Spacer(Modifier.height(8.dp))
                            Text(
                                "Select exactly two snapshots to compare what they hold.",
                                fontSize = TypeScale.small,
                                color = colors.onSurfaceVariant,
                            )
                        }
                    }
                }
                return@CompositionLocalProvider
            }

            val currentGraph = graphModel ?: return@CompositionLocalProvider
            val node = currentGraph.nodeById[selectedNodeIds.first()] ?: return@CompositionLocalProvider
            val children = remember(node.id, currentGraph.nodes, currentGraph.edges) { childNodes(node, currentGraph) }

            val scrollState = rememberScrollState()
            Box(Modifier.fillMaxSize()) {
                Column(
                    Modifier
                        .fillMaxSize()
                        .verticalScroll(scrollState)
                        .padding(8.dp)
                ) {
                    // The title takes its own line and the actions wrap under it.
                    //
                    // These shared one `Row` until a third action arrived, and a `Row` is the wrong
                    // container for it: it neither wraps nor clips, and it measures the unweighted
                    // children — the buttons — before the weighted title. The inspector pane opens at
                    // **300dp** and can be dragged to 200dp, so at the width this is actually read
                    // the buttons took the whole line and the title was laid out one character per
                    // line beneath them, with the last button painted past the edge where the reader
                    // could not reach it. The wide render looked correct throughout, which is why
                    // `collapse-pages-narrow-1.png` renders the panel at 300dp: a `Row` overflowing
                    // is invisible at any width where it happens to fit.
                    Text(
                        nodeTitle(node),
                        fontWeight = FontWeight.Bold,
                        fontSize = TypeScale.display,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        // One button, not two. It states what the click will do, which is
                        // unambiguous even when the reader has folded some sections by hand:
                        // "Collapse all" until nothing is left open, "Expand all" after.
                        val everythingFolded = sectionCollapse.allCollapsed
                        TextButton(
                            onClick = { sectionCollapse.setAll(!everythingFolded) },
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                            modifier = Modifier.height(28.dp)
                        ) {
                            Icon(
                                imageVector = if (everythingFolded) Icons.Default.UnfoldMore else Icons.Default.UnfoldLess,
                                contentDescription = null,
                                modifier = Modifier.size(14.dp)
                            )
                            Spacer(Modifier.width(4.dp))
                            Text(
                                if (everythingFolded) "Expand all" else "Collapse all",
                                fontSize = TypeScale.small,
                            )
                        }
                        val openPath = inspectorOpenPath(node)
                        if (!openPath.isNullOrBlank() && isLocalPath(openPath)) {
                            TextButton(
                                onClick = { openContainingDirectory(openPath) },
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                                modifier = Modifier.height(28.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.FolderOpen,
                                    contentDescription = "Open in file browser",
                                    modifier = Modifier.size(14.dp)
                                )
                                Spacer(Modifier.width(4.dp))
                                Text("Reveal", fontSize = TypeScale.small)
                            }
                        }
                        CollapseGroupsAction(node, expandedGroupIds, onCollapseGroupsUnder)
                    }
                    Text("Node ID: ${node.id}", fontSize = TypeScale.small, color = colors.onSurfaceVariant)
                    Spacer(Modifier.height(8.dp))

                    when (node) {
                    is GraphNode.TableNode -> TablePanel(node, currentGraph, children, scanFilter, onScanFilterChange)
                    is GraphNode.MetadataNode -> MetadataPanel(node, currentGraph)
                    is GraphNode.SnapshotNode -> SnapshotPanel(node, currentGraph, children)
                    is GraphNode.ManifestNode -> ManifestPanel(node, currentGraph, scanFilter)
                    is GraphNode.FileNode -> FilePanel(node, currentGraph)
                    is GraphNode.RowNode -> RowPanel(node, currentGraph)
                    is GraphNode.ErrorNode -> ErrorPanel(node)
                    is GraphNode.PaimonSnapshotNode -> PaimonSnapshotPanel(node, currentGraph)
                    is GraphNode.PaimonSchemaNode -> PaimonSchemaPanel(node)
                    is GraphNode.PaimonManifestListNode -> PaimonManifestListPanel(node, currentGraph)
                    is GraphNode.PaimonManifestNode -> PaimonManifestPanel(node, currentGraph)
                    is GraphNode.PaimonDataFileNode -> PaimonDataFilePanel(node, currentGraph)
                    is GraphNode.GroupNode -> GroupPanel(node, onExpandGroup, onExpandGroupFully)
                }
            }
            VerticalScrollbar(
                modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight(),
                adapter = rememberScrollbarAdapter(scrollState)
            )
        }
    }
    }
}

// --- Schema Evolution ---

private data class FieldInfo(
    val id: Int,
    val name: String,
    val required: Boolean,
    val type: String,
)

private fun extractFields(schema: TableSchema): Map<Int, FieldInfo> =
    schema.fields.mapNotNull { field ->
        val id = field.id ?: return@mapNotNull null
        id to FieldInfo(
            id = id,
            name = field.name ?: "field_$id",
            required = field.required ?: false,
            type = field.type?.toString()?.removeSurrounding("\"") ?: "unknown"
        )
    }.toMap()

private sealed class SchemaChange(val fieldId: Int, val fieldName: String) {
    class Added(id: Int, name: String, val type: String, val required: Boolean) : SchemaChange(id, name) {
        override fun toString() = "Added: $fieldName ($type${if (required) ", required" else ""})"
    }
    class Dropped(id: Int, name: String, val type: String) : SchemaChange(id, name) {
        override fun toString() = "Dropped: $fieldName ($type)"
    }
    class TypeChanged(id: Int, name: String, val oldType: String, val newType: String) : SchemaChange(id, name) {
        override fun toString() = "Type changed: $fieldName ($oldType \u2192 $newType)"
    }
    class Renamed(id: Int, val oldName: String, val newName: String) : SchemaChange(id, newName) {
        override fun toString() = "Renamed: $oldName \u2192 $newName"
    }
    class RequiredChanged(id: Int, name: String, val wasRequired: Boolean) : SchemaChange(id, name) {
        override fun toString() = if (wasRequired) "Made optional: $fieldName" else "Made required: $fieldName"
    }
}

private fun diffSchemas(oldSchema: TableSchema, newSchema: TableSchema): List<SchemaChange> {
    val oldFields = extractFields(oldSchema)
    val newFields = extractFields(newSchema)
    val changes = mutableListOf<SchemaChange>()

    // Added fields
    (newFields.keys - oldFields.keys).forEach { id ->
        val f = newFields[id]!!
        changes.add(SchemaChange.Added(id, f.name, f.type, f.required))
    }

    // Dropped fields
    (oldFields.keys - newFields.keys).forEach { id ->
        val f = oldFields[id]!!
        changes.add(SchemaChange.Dropped(id, f.name, f.type))
    }

    // Changed fields
    (oldFields.keys intersect newFields.keys).forEach { id ->
        val old = oldFields[id]!!
        val new = newFields[id]!!
        if (old.name != new.name) {
            changes.add(SchemaChange.Renamed(id, old.name, new.name))
        }
        if (old.type != new.type) {
            changes.add(SchemaChange.TypeChanged(id, new.name, old.type, new.type))
        }
        if (old.required != new.required) {
            changes.add(SchemaChange.RequiredChanged(id, new.name, old.required))
        }
    }

    return changes.sortedBy { it.fieldId }
}

/**
 * The rows a v3 deletion vector marks, decoded from the Puffin blob it lives in.
 *
 * This is the only thing a deletion vector is for, and it is the one thing the metadata does not
 * say: the manifest records where the blob is and how many rows it covers, and the positions
 * themselves are inside it.
 *
 * Two figures the writer recorded sit beside what was decoded, for the same reason
 * `manifestTallies` exists — nothing on a read path checks either, so the inspector does. A
 * cardinality that disagrees, or a checksum that fails, is shown rather than smoothed over: a
 * vector whose bytes do not match its own CRC is a corrupted table, and a reader looking at this
 * panel is the person who needs to know.
 */
/**
 * The inverse of expanding, on the parent it applies to.
 *
 * **This has to reach a parent that has no group node left**, which is the whole case it exists
 * for: opening every page of a node removes the last `GroupNode` beside it, and with it the only
 * thing on the canvas that knew those siblings were paged. So it hangs off the parent rather than
 * off a group, and it is one control for every kind of parent rather than thirteen.
 *
 * It sits in the header row because that row is what this panel does to the node — `Collapse all`
 * folds its sections, `Reveal` opens its directory — and because the alternative displaced the
 * identity table, which is what the reader selected the node to see. Its wording is the inverse of
 * the group card's `Show the next page`, deliberately not "collapse": the neighbouring
 * `Collapse all` is about sections, and two adjacent buttons reading "Collapse" would be about two
 * different things.
 *
 * Drawn only when this parent has pages open, so an ordinary panel pays one set lookup for it.
 */
@Composable
private fun CollapseGroupsAction(
    node: GraphNode,
    expandedGroupIds: Set<String>,
    onCollapseGroupsUnder: (String) -> Unit,
) {
    val mine = remember(node.id, expandedGroupIds) {
        GraphAggregation.expandedGroupIdsUnder(node.id, expandedGroupIds)
    }
    if (mine.isEmpty()) return

    TextButton(
        onClick = { onCollapseGroupsUnder(node.id) },
        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
        modifier = Modifier.height(28.dp),
    ) {
        Icon(
            imageVector = Icons.Default.FirstPage,
            contentDescription = "Draw only the first page of this node's children",
            modifier = Modifier.size(14.dp),
        )
        Spacer(Modifier.width(4.dp))
        Text("Back to one page", fontSize = TypeScale.small)
    }
}

/**
 * The two selected snapshots, oldest first, or null when the selection is not exactly two of them.
 *
 * Oldest first so that "added" and "removed" mean what a reader expects: the comparison runs
 * forward in time whichever order the two were clicked in. Sequence number decides it because it
 * is the commit order Iceberg itself assigns; the timestamp is a clock and two commits a
 * millisecond apart on a fast writer can carry the same one. Snapshot id breaks the remaining tie
 * so the panel is at least stable rather than flipping between recompositions.
 */
private fun comparableSnapshots(
    graph: GraphModel,
    selectedNodeIds: Set<String>,
): Pair<ComparableSnapshot, ComparableSnapshot>? {
    val selected = selectedNodeIds.mapNotNull { graph.nodeById[it] }
    if (selected.size != 2) return null
    // ComparableSnapshot, not SnapshotNode: a Paimon snapshot answers the same six questions by
    // its own rules, and the panel below never asks which format it is looking at. Two nodes of
    // *different* formats cannot appear in one graph, so no check for that is needed here.
    val snapshots = selected.filterIsInstance<ComparableSnapshot>()
    if (snapshots.size != 2 || snapshots.any { !it.canDiff }) return null

    val ordered = snapshots.sortedWith(
        compareBy(
            { it.commitOrder ?: Long.MAX_VALUE },
            { it.commitTimeMs ?: Long.MAX_VALUE },
            { it.commitId ?: Long.MAX_VALUE },
        ),
    )
    return ordered[0] to ordered[1]
}

/**
 * What is different between the contents of two snapshots.
 *
 * The panel's answer to a question `What this commit did` cannot reach. That section reads the
 * manifests one commit wrote and is only defined against that commit's parent; this is a set
 * difference between two complete live file sets, so the two need no relationship — a branch tip
 * against `main`, or a snapshot against one ten commits back.
 *
 * Computing it is in-memory work over a model that is already fully read, not I/O, so it happens
 * in a `remember` keyed on the two nodes rather than behind a button like the delete-file read.
 * What is deferred is the walk itself: `SnapshotNode.liveFiles` is a `DeferredRead`, so a table of
 * twenty commits never walks twenty closures to answer a question about two.
 */
/**
 * The two step buttons for one side of a comparison. A button is drawn disabled at the end of the
 * history rather than dropped, so the row keeps its shape and the reader sees there is no further
 * to go; the label names the commit the click would move to, since a step whose target is a
 * mystery is a step nobody takes on a table they do not know.
 */
@Composable
private fun ComparisonStepRow(
    label: String,
    stepped: ComparableSnapshot,
    pinned: ComparableSnapshot,
    graph: GraphModel,
    onSelectNodes: (Set<String>) -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val older = graph.stepComparableSnapshot(stepped.nodeId, -1)?.takeIf { it.nodeId != pinned.nodeId }
        ?: graph.stepComparableSnapshot(stepped.nodeId, -2)
    val newer = graph.stepComparableSnapshot(stepped.nodeId, 1)?.takeIf { it.nodeId != pinned.nodeId }
        ?: graph.stepComparableSnapshot(stepped.nodeId, 2)
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            "$label — snapshot ${stepped.displayNumber}:",
            fontSize = TypeScale.small,
            color = colors.onSurfaceVariant,
            modifier = Modifier.width(190.dp),
        )
        TextButton(
            onClick = { older?.let { onSelectNodes(setOf(pinned.nodeId, it.nodeId)) } },
            enabled = older != null,
            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
            modifier = Modifier.height(28.dp),
        ) {
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = null, modifier = Modifier.size(14.dp))
            Text(older?.let { "older (snapshot ${it.displayNumber})" } ?: "older (none)", fontSize = TypeScale.small)
        }
        TextButton(
            onClick = { newer?.let { onSelectNodes(setOf(pinned.nodeId, it.nodeId)) } },
            enabled = newer != null,
            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
            modifier = Modifier.height(28.dp),
        ) {
            Text(newer?.let { "newer (snapshot ${it.displayNumber})" } ?: "newer (none)", fontSize = TypeScale.small)
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, modifier = Modifier.size(14.dp))
        }
    }
}

@Composable
private fun SnapshotComparison(
    from: ComparableSnapshot,
    to: ComparableSnapshot,
    graph: GraphModel,
    onSelectNodes: (Set<String>) -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val diff = remember(from.nodeId, to.nodeId) {
        snapshotDiff(
            from.commitId, from.liveFiles.orEmpty(),
            to.commitId, to.liveFiles.orEmpty(),
        )
    }

    Text(
        "Comparing two snapshots",
        fontSize = TypeScale.title,
        fontWeight = FontWeight.Bold,
        color = colors.onSurface,
    )
    Spacer(Modifier.height(8.dp))
    // Pin one side and step the other: each side moves to its neighbour in commit order while the
    // other stays, which is how a branch is compared against successive points on main. Stepping
    // is selecting, so the canvas keeps showing the pair the panel is about.
    ComparisonStepRow("Older side", from, to, graph, onSelectNodes)
    ComparisonStepRow("Newer side", to, from, graph, onSelectNodes)
    Spacer(Modifier.height(8.dp))

    DetailTable {
        DetailRow("Property", "Value", isHeader = true)
        DetailRow("From (older)", "Snapshot ${from.displayNumber} — ${from.commitId ?: "N/A"}")
        DetailRow("To (newer)", "Snapshot ${to.displayNumber} — ${to.commitId ?: "N/A"}")
        DetailRow(
            "Relationship",
            when {
                to.parentCommitId == from.commitId && from.commitId != null -> "Parent and child"
                from.parentCommitId == to.commitId && to.commitId != null ->
                    "Parent and child, and the newer one is the parent — the commit order says so"
                // Null on both sides is not "unrelated", it is "this format records no parent" —
                // which Paimon does not. Saying they are not parent and child would be a claim
                // nothing here established.
                to.parentCommitId == null && from.parentCommitId == null ->
                    "This format records no parent on a snapshot, so nothing here says whether " +
                        "these two are adjacent. The comparison does not need to know."
                else -> "Not parent and child. This is a set difference, not a replay of the commits between them."
            },
        )
    }

    Section("What changed") {
        Text(
            buildString {
                if (diff.isEmpty) {
                    append("These two snapshots hold exactly the same files. ")
                    append("A commit that only rewrites metadata leaves the contents alone.")
                } else {
                    append("Reading forward, from the older snapshot to the newer. ")
                    append(
                        "Both sides are the live contents of each snapshot — every file its " +
                            "manifests still list — rather than the commits in between, which is " +
                            "what lets two snapshots on different branches be compared at all.",
                    )
                }
                if (diff.contradictory.isNotEmpty()) {
                    append(
                        " ${formatCount(diff.contradictory.size)} paths appear on both sides with " +
                            "different figures, which an immutable data file should make impossible.",
                    )
                }
            },
            fontSize = TypeScale.small,
            color = colors.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 4.dp),
        )
        WideTable(
            headers = listOf("Figure", "Added", "Removed", "Net"),
            columnWidths = listOf(180.dp, 120.dp, 120.dp, 120.dp),
            rows = listOf(
                listOf(
                    "Data files",
                    formatCount(diff.addedStats.dataFileCount),
                    formatCount(diff.removedStats.dataFileCount),
                    signed(diff.netDataFileCount.toLong()),
                ),
                listOf(
                    "Delete files",
                    formatCount(diff.addedStats.deleteFileCount),
                    formatCount(diff.removedStats.deleteFileCount),
                    signed((diff.addedStats.deleteFileCount - diff.removedStats.deleteFileCount).toLong()),
                ),
                listOf(
                    "Records",
                    formatCount(diff.addedStats.recordCount),
                    formatCount(diff.removedStats.recordCount),
                    signed(diff.netRecordCount),
                ),
                listOf(
                    "Bytes",
                    formatBytes(diff.addedStats.totalSizeBytes),
                    formatBytes(diff.removedStats.totalSizeBytes),
                    signedBytes(diff.netSizeBytes),
                ),
                listOf("Files on both sides", "—", "—", formatCount(diff.unchanged.size)),
            ),
        )
    }

    val moved = diff.removed + diff.added + diff.contradictory
    if (moved.isNotEmpty()) {
        Section("Files that differ (${formatCount(moved.size)})") {
            Text(
                if (diff.unchanged.isEmpty()) {
                    "Every file differs — no file is on both sides. Between these two snapshots " +
                        "the table was rewritten rather than added to."
                } else {
                    // Files on both sides are deliberately not listed: on a table of any size they
                    // are almost all of it, and a list where nearly every row reads the same hides
                    // the handful that do not. The figures above count them.
                    "Only the files that are on one side and not the other. " +
                        "${formatCount(diff.unchanged.size)} more are on both sides and are counted " +
                        "above rather than listed, because a list where almost every row reads the " +
                        "same hides the rows that do not."
                },
                fontSize = TypeScale.small,
                color = colors.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 4.dp),
            )
            val shown = moved.take(MAX_DIFF_ROWS)
            WideTable(
                // "Change", the same header the commit table uses, because it holds the same
                // three words and a reader who has seen one should not have to re-read the other.
                headers = listOf("Change", "File", "Kind", "Rows", "Size"),
                columnWidths = listOf(110.dp, 340.dp, 100.dp, 80.dp, 90.dp),
                rows = shown.map { entry ->
                    listOf(
                        when (entry.side) {
                            DiffSide.ONLY_IN_TO -> "added"
                            DiffSide.ONLY_IN_FROM -> "REMOVED"
                            DiffSide.CHANGED -> "CONTRADICTS"
                            DiffSide.IN_BOTH -> "both"
                        },
                        fileNameFromPath(entry.file.path),
                        when (entry.file.content) {
                            DataFileContent.POSITION_DELETES -> "pos delete"
                            DataFileContent.EQUALITY_DELETES -> "eq delete"
                            else -> "data"
                        },
                        formatCount(entry.file.recordCount),
                        formatBytes(entry.file.sizeBytes),
                    )
                },
                // A removal is the exception on an append-mostly table and is the row a reader
                // comparing two points in history is looking for; a contradiction is louder still.
                leadCellColors = shown.map { entry ->
                    when (entry.side) {
                        DiffSide.ONLY_IN_FROM -> colors.error
                        DiffSide.CHANGED -> colors.error
                        else -> null
                    }
                },
            )
            if (moved.size > MAX_DIFF_ROWS) {
                Spacer(Modifier.height(4.dp))
                Text(
                    "Showing the first ${formatCount(MAX_DIFF_ROWS)} of ${formatCount(moved.size)}. " +
                        "The figures above cover all of them.",
                    fontSize = TypeScale.small,
                    color = colors.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * The most file rows a comparison lists.
 *
 * Two snapshots either side of a large rewrite differ by every file in the table, and a `Column`
 * of a hundred thousand rows is a frozen window. The cap is stated on screen when it bites — the
 * same rule aggregation follows on the graph, for the same reason: a number silently truncated
 * reads as the whole answer.
 */
private const val MAX_DIFF_ROWS = 500

/** A net figure with its sign, because "0" and "-1,204" mean very different things in that column. */
private fun signed(value: Long): String = if (value > 0) "+${formatCount(value)}" else formatCount(value)

private fun signedBytes(value: Long): String =
    if (value >= 0) "+${formatBytes(value)}" else "-${formatBytes(-value)}"

/**
 * Which data files a v2 positional delete file removes rows from, and how many out of each.
 *
 * "3 delete files" is what the metadata can tell you; "and they remove 412 rows from these two
 * files" is inside the delete files themselves, one row per deleted position. Nothing on a read
 * path needs the breakdown — a scan applies the deletes rather than counting them — so nothing
 * produces it, which is why the panel has to.
 *
 * **It is behind a button, and that is the design rather than caution.** A graph is built for every
 * artifact the metadata names, so reading each delete file at build time would be a file open per
 * delete on a table where most are never looked at — the cost aggregation exists to avoid. The
 * deletion vector next door is lazy for the same reason and takes the same shape: a `FileNode`
 * carries the means to read, not the result of reading.
 *
 * The aggregation is DuckDB's. What crosses back is one row per targeted data file, whether the
 * file holds one position or four hundred thousand, which is what makes an unbounded read safe to
 * offer as a single click.
 *
 * The counted total is put beside the manifest's `record_count`, which is the same move as
 * `manifestTallies` and the vector's two figures: a scan plans against the recorded number and
 * never opens the file, so a disagreement has nowhere else to surface.
 *
 * [startRequested] is the same idea as `NodeDetailsContent`'s `sectionCollapse`: the interesting
 * state here is the one a click produces, and a render never reaches it unless it is passed in. It
 * is `internal` rather than private for that one caller, and there is no second code path — the
 * render exercises the same button, the same read and the same table the app draws.
 *
 * [onSettled] fires once the read has come back, either way. A capture needs it because the read
 * really is asynchronous: an `ImageComposeScene` only advances its own dispatcher when it is
 * rendered, so sixty frames in a tight loop finish long before a DuckDB query does and the PNG
 * shows the loading line. It is the same mechanism as `LocalCardContentProbe`, as a parameter
 * because there is exactly one call site rather than a card kind's worth.
 */
@Composable
internal fun PositionalDeleteTargets(
    node: GraphNode.FileNode,
    startRequested: Boolean = false,
    onSettled: () -> Unit = {},
) {
    val colors = MaterialTheme.colorScheme
    val path = node.localPath
    if (path.isNullOrBlank()) return

    // Keyed on the node so switching files puts the button back rather than showing the previous
    // file's answer under the new file's name.
    var requested by remember(node.id) { mutableStateOf(startRequested) }
    val outcome by produceState<Result<List<PositionalDeleteTally>>?>(null, node.id, requested) {
        value = null
        if (requested) {
            value = withContext(Dispatchers.IO) {
                runCatching { SampleRowReader.queryPositionalDeleteTargets(path) }
            }
            onSettled()
        }
    }

    Spacer(Modifier.height(8.dp))
    when {
        !requested -> {
            OutlinedButton(onClick = { requested = true }) {
                Text("Read the file — which data files, and how many rows each")
            }
        }
        outcome == null -> Text(
            "Reading ${fileNameFromPath(path)}…",
            fontSize = TypeScale.small,
            color = colors.onSurfaceVariant,
        )
        else -> outcome?.fold(
            onSuccess = { tallies ->
                val counted = tallies.sumOf { it.positions }
                val recorded = node.data.recordCount
                Text(
                    buildString {
                        append(
                            "${formatCount(counted)} deleted ${if (counted == 1L) "position" else "positions"}" +
                                " across ${formatCount(tallies.size)} data " +
                                if (tallies.size == 1) "file." else "files.",
                        )
                        when {
                            recorded == null -> append(" The manifest recorded no record_count to check it against.")
                            recorded == counted -> append(" The manifest records $recorded, which agrees.")
                            else -> append(
                                " The manifest records $recorded, which does not agree — a scan plans " +
                                    "against that figure without opening this file.",
                            )
                        }
                    },
                    fontSize = TypeScale.small,
                    color = if (recorded != null && recorded != counted) colors.error else colors.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 4.dp),
                )
                if (tallies.isNotEmpty()) {
                    // The bounds are one column, not two. They are read as a span — whether the
                    // deletes are a run or scattered over the file — rather than compared down
                    // the page, and two number columns spent 240dp of a panel that is narrower
                    // than this table to say what one says.
                    WideTable(
                        headers = listOf("Rows deleted", "Data file", "Positions"),
                        columnWidths = listOf(100.dp, 380.dp, 150.dp),
                        rows = tallies.map { tally ->
                            listOf(
                                formatCount(tally.positions),
                                fileNameFromPath(tally.dataFilePath),
                                if (tally.lowestPosition == tally.highestPosition) {
                                    formatCount(tally.lowestPosition)
                                } else {
                                    "${formatCount(tally.lowestPosition)}–${formatCount(tally.highestPosition)}"
                                },
                            )
                        },
                    )
                }
            },
            onFailure = { failure ->
                Text(
                    "Could not read ${fileNameFromPath(path)}: ${failure.message ?: failure::class.simpleName}",
                    fontSize = TypeScale.small,
                    color = colors.error,
                )
            },
        )
    }
}

@Composable
internal fun DeletionVectorSection(node: GraphNode.FileNode) {
    if (!node.isDeletionVector) return
    val colors = MaterialTheme.colorScheme
    val vector = node.deletionVector

    if (vector == null) {
        Section("Deleted Rows") {
            Text(
                "The Puffin blob could not be read, so the positions this vector marks are not " +
                    "known. The file may have moved since the manifest recorded it — the path it " +
                    "was looked for at is above.",
                fontSize = TypeScale.small,
                color = colors.onSurfaceVariant,
            )
        }
        return
    }

    Section("Deleted Rows (${formatCount(vector.cardinality)})") {
        DetailTable {
            DetailRow("Property", "Value", isHeader = true)
            DetailRow(
                "Positions Decoded",
                "${formatCount(vector.cardinality)} from the Roaring bitmap in the blob",
            )
            DetailRow(
                "Recorded Cardinality",
                when {
                    vector.recordedCardinality == null -> "not recorded by the writer"
                    vector.cardinalityAgrees -> "${formatCount(vector.recordedCardinality)} — agrees"
                    else -> "${formatCount(vector.recordedCardinality)} — DISAGREES with the " +
                        "${formatCount(vector.cardinality)} decoded"
                },
            )
            DetailRow(
                "Checksum",
                if (vector.checksumMatches) {
                    "the blob's CRC-32 agrees with its bytes"
                } else {
                    "FAILS — the blob's CRC-32 does not agree with its bytes"
                },
            )
        }
        // The caption belongs to the list below it, not to the table above it, so the gap above
        // is the larger of the two. Equal gaps put it between two things and attached to neither.
        Spacer(Modifier.height(12.dp))
        Text(
            if (vector.truncated) {
                "The first ${formatCount(vector.positions.size)} of " +
                    "${formatCount(vector.cardinality)} positions. Row positions are zero-based " +
                    "and count from the start of the referenced data file."
            } else {
                "Row positions, zero-based, counting from the start of the referenced data file."
            },
            fontSize = TypeScale.small,
            color = colors.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 4.dp),
        )
        // Run-folded rather than one per line: a compaction leaves vectors that delete a
        // contiguous block, and four hundred consecutive numbers down the panel say less than
        // "0-399" does while taking four hundred times the room. In a surface of its own for the
        // same reason every other value in this panel is: a bare `0` at the left margin reads as
        // a stray character rather than as the answer the section exists to give.
        DetailTable {
            Text(
                foldRuns(vector.positions),
                fontSize = TypeScale.small,
                fontFamily = FontFamily.Monospace,
                color = colors.onSurface,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
            )
        }
    }
}

/**
 * What one commit did, with the writer's own claim beside the count that checks it.
 *
 * A snapshot's `summary` map is the engine's account of its own commit, sitting in `metadata.json`
 * where nothing on the read path verifies it. The manifests that commit wrote are the account that
 * cannot be wrong without the table being wrong. Putting the two side by side is the same idea as
 * a manifest's Recorded Summary, one level up — and it is the only check either figure gets.
 *
 * Placed above the raw summary rather than below it: this is the answer to "what changed here",
 * and the summary is the data it was partly read from.
 */
/**
 * Which of this snapshot's delete files reach which of its data files, from the metadata alone.
 *
 * The pairing is what a scan actually does and what the tree cannot show: a delete file is drawn
 * under the manifest that lists it, beside data files it may have nothing to do with. The row that
 * matters is the one reaching **nothing** — a dangling delete, still planned against on every scan
 * until a rewrite drops it, and invisible in every count the panel above prints.
 *
 * Drawn even when there is nothing to say, the same rule as `Statistics (0)`: "this snapshot has no
 * delete files" is an answer a reader comes for, and a section that is simply absent cannot be told
 * from one this panel does not know how to render. What it will *not* do is walk for that answer —
 * the manifest list records each manifest's content, so whether there is a delete manifest at all
 * is known without opening anything, and the deferred walk is forced only when there is.
 */
/**
 * The delete files that reach this data file — [DeleteReachSection] asked from the other end.
 *
 * Standing on a data file is the position a reader is usually in when the question comes up ("why
 * does this file cost what it does"), and it is the direction the tree cannot answer at all: the
 * delete files that apply to it hang under other manifests entirely, and the ones drawn beside it
 * mostly do not apply.
 *
 * Answered over every delete file the **graph draws**, not over one snapshot's closure. Both
 * operands' sequence numbers and recorded targets are facts about the files themselves, so no walk
 * is needed — what that gives up is liveness, since a delete file a later commit removed is still
 * drawn. That is the scope `evaluateScan` already answers in, and it is said on screen rather than
 * left to be assumed.
 *
 * Absent entirely when the graph draws no delete file — unlike [DeleteReachSection], which is drawn
 * empty because "this commit lists no delete files" is a fact about the commit. Here the sentence
 * has no subject: there is nothing that could have reached this file.
 */
@Composable
internal fun DeletesReachingSection(node: GraphNode.FileNode, graph: GraphModel) {
    val colors = MaterialTheme.colorScheme
    if (deleteKindOf(node.data) != null) return

    val candidates = remember(node.id, graph.nodes) {
        deleteCandidatesFor(node, graph.nodes.filterIsInstance<GraphNode.FileNode>())
    }
    if (candidates.isEmpty()) return

    val reaching = candidates.filter { it.verdict != DeleteReachVerdict.RULED_OUT_BY_TARGET &&
        it.verdict != DeleteReachVerdict.RULED_OUT_BY_SEQUENCE }
    CountedSection("Deletes Reaching This File", reaching.size, "delete files") {
        Text(
            "Of the ${formatCount(candidates.size.toLong())} delete files drawn for this table, " +
                "these are the ones a scan could pair with this data file — by sequence number and " +
                "by the paths each records about itself, neither of which needs a file opened. The " +
                "ruled-out rows are kept so the reason is visible: a delete file drawn beside this " +
                "one usually applies to something else entirely. Every delete file the graph holds " +
                "is weighed, including any a later commit has since removed.",
            fontSize = TypeScale.small,
            color = colors.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 4.dp),
        )
        WideTable(
            // The reason comes before the file name, unlike the pruning table where the identifier
            // is `MANIFEST 1` and costs 130dp. A delete file's name is sixty characters, so putting
            // it second pushes the only cell that explains the verdict past the panel edge — and
            // this panel is 700dp at the width these captures are taken.
            headers = listOf("Reaches This", "Why", "Delete File", "Kind", "Seq"),
            columnWidths = listOf(120.dp, 330.dp, 260.dp, 130.dp, 60.dp),
            leadCellColors = candidates.map {
                when (it.verdict) {
                    DeleteReachVerdict.REACHES -> verdictSkippedColor()
                    DeleteReachVerdict.MAY_REACH -> verdictUnevaluatedColor()
                    else -> null
                }
            },
            rows = candidates.map { candidate ->
                listOf(
                    when (candidate.verdict) {
                        DeleteReachVerdict.REACHES -> "yes"
                        DeleteReachVerdict.MAY_REACH -> "maybe"
                        else -> "no"
                    },
                    when (candidate.verdict) {
                        DeleteReachVerdict.REACHES ->
                            "its recorded target is this file"
                        DeleteReachVerdict.RULED_OUT_BY_TARGET ->
                            "its recorded targets do not include this file"
                        // Kind-aware, because the rule differs and the sentence would otherwise
                        // be wrong for one of them: a positional delete fails only when it is
                        // strictly below, an equality delete when it is not strictly above.
                        DeleteReachVerdict.RULED_OUT_BY_SEQUENCE ->
                            if (candidate.kind == DeleteFileKind.EQUALITY) {
                                "sequence ${candidate.delete.sequenceNumber} is not above this " +
                                    "file's ${node.sequenceNumber}, and an equality delete must be"
                            } else {
                                "sequence ${candidate.delete.sequenceNumber} is below this file's " +
                                    "${node.sequenceNumber}, so this file did not exist yet"
                            }
                        DeleteReachVerdict.MAY_REACH -> when (candidate.kind) {
                            DeleteFileKind.EQUALITY ->
                                "an equality delete records no target, so only reading it settles this"
                            else -> "this file is inside its recorded range, which does not name it"
                        }
                    },
                    fileNameFromPath(candidate.delete.data.filePath.orEmpty()),
                    when (candidate.kind) {
                        DeleteFileKind.DELETION_VECTOR -> "deletion vector"
                        DeleteFileKind.POSITIONAL -> "positional"
                        DeleteFileKind.EQUALITY -> "equality"
                    },
                    "${candidate.delete.sequenceNumber}",
                )
            },
        )
        DeletedRowCount(node, candidates)
    }
}

/**
 * How many of this file's rows the reachable delete files actually remove, behind a click.
 *
 * The pairing above says *which* delete files matter; only their contents say how many rows they
 * take, because a positional delete records one row per deleted position and nothing summarises it.
 * That makes this the only way to a **live row count**: `record_count` counts rows before deletes,
 * and subtracting the delete files' own `record_count` is wrong the moment one of them is
 * dangling — on the merge-on-read fixture that subtraction gives 3 where the table holds 5.
 *
 * Behind an action for the same reason [PositionalDeleteTargets] is: reading delete files while a
 * graph is being built is a file open per delete on a table where most are never looked at. The
 * pairing is what makes the click cheap — the candidates are already narrowed to the files that
 * can reach this one.
 *
 * A deletion vector is not queried: it was decoded when its own panel was opened and its
 * cardinality is exact. The two are reported separately rather than added, because adding them
 * would double-count any position both mark — the v3 spec does not allow a data file to have both,
 * so the case should not arise, and a number that is wrong only in an impossible case is still a
 * number nobody could check.
 */
@Composable
internal fun DeletedRowCount(
    node: GraphNode.FileNode,
    candidates: List<DeleteCandidate>,
    startRequested: Boolean = false,
    onSettled: () -> Unit = {},
) {
    val colors = MaterialTheme.colorScheme
    val reachable = candidates.filter {
        it.verdict == DeleteReachVerdict.REACHES || it.verdict == DeleteReachVerdict.MAY_REACH
    }
    val positional = reachable.filter { it.kind == DeleteFileKind.POSITIONAL }
        .mapNotNull { it.delete.localPath }
    val vectors = reachable.filter { it.kind == DeleteFileKind.DELETION_VECTOR }.map { it.delete }
    val equalities = reachable.count { it.kind == DeleteFileKind.EQUALITY }
    if (positional.isEmpty() && vectors.isEmpty()) return

    var requested by remember(node.id) { mutableStateOf(startRequested) }
    val outcome by produceState<Result<Pair<Long, Long>>?>(null, node.id, requested) {
        value = null
        if (requested) {
            value = withContext(Dispatchers.IO) {
                runCatching {
                    val fromFiles = SampleRowReader.queryDeletedRowCount(
                        positional,
                        node.data.filePath.orEmpty(),
                    )
                    val fromVectors = vectors.sumOf { it.deletionVector?.positions?.size?.toLong() ?: 0L }
                    fromFiles to fromVectors
                }
            }
            onSettled()
        }
    }

    Spacer(Modifier.height(8.dp))
    when {
        !requested -> OutlinedButton(onClick = { requested = true }) {
            Text("Count the rows these delete", fontSize = TypeScale.small)
        }
        outcome == null -> Text(
            "Reading ${formatCount((positional.size + vectors.size).toLong())} delete files…",
            fontSize = TypeScale.small,
            color = colors.onSurfaceVariant,
        )
        else -> outcome?.fold(
            onSuccess = { (fromFiles, fromVectors) ->
                val rows = node.data.recordCount
                Text(
                    buildString {
                        if (fromFiles > 0 && fromVectors > 0) {
                            append(
                                "${formatCount(fromFiles)} from delete files and " +
                                    "${formatCount(fromVectors)} from deletion vectors. They are not " +
                                    "added: a position both mark would be counted twice, and the " +
                                    "format does not put both on one data file.",
                            )
                        } else {
                            val deleted = fromFiles + fromVectors
                            append("${formatCount(deleted)} of ")
                            append(rows?.let { formatCount(it) } ?: "an unrecorded number of")
                            append(" rows deleted")
                            if (rows != null) {
                                append(" — ${formatCount(rows - deleted)} live in this file.")
                            } else {
                                append(".")
                            }
                        }
                        if (equalities > 0) {
                            append(
                                " ${formatCount(equalities.toLong())} equality delete files also " +
                                    "reach this file and are not counted: they match rows by value, " +
                                    "which needs the data read rather than the delete.",
                            )
                        }
                    },
                    fontSize = TypeScale.small,
                    color = colors.onSurfaceVariant,
                )
            },
            onFailure = { failure ->
                Text(
                    "Could not read the delete files: ${failure.message ?: failure::class.simpleName}",
                    fontSize = TypeScale.small,
                    color = colors.error,
                )
            },
        )
    }
}

/**
 * The index files a Paimon snapshot's index manifest lists.
 *
 * `indexManifest` was a name in the identity table and nothing else — parsed into the model and
 * dropped — which is the same shape of gap Iceberg's `statistics` had, and invisible for the same
 * reason: nothing rendered it, so nothing noticed it was never read.
 *
 * Two kinds land here and they answer different questions. A `HASH` index is what a bucket looks a
 * primary key up in, so its size and row count are the cost of that lookup. A `DELETION_VECTORS`
 * index is Paimon's answer to the problem Iceberg solves with a Puffin vector, and it is the only
 * place the format records which data files have deleted rows — so its ranges get their own column
 * rather than being folded into a count.
 *
 * Drawn even when empty, the same rule as `Statistics (0)`: whether a snapshot names an index
 * manifest at all is an answer, and a section that is simply absent cannot be told from one this
 * panel does not know how to render.
 */
/**
 * Each bucket as the LSM tree its writer would restore at this snapshot, and what the next flush
 * would compact — `planCompaction`, the rules of `UniversalCompaction.pick()`, on the same
 * deferred replay the records and partitions above already ran. An append table has no tree, so
 * its side is the small-file count `sys.compact` would pack per partition.
 *
 * The verdict leads: "will the next write compact, and why" is the question, and a bucket that
 * would stall the writer is the one row that has to be findable without reading the others.
 */
@Composable
internal fun PaimonCompactionSection(node: GraphNode.PaimonSnapshotNode) {
    val colors = MaterialTheme.colorScheme
    if (!node.hasPrimaryKey) {
        AppendCompactionSection(node)
        return
    }
    val lsms = node.bucketLsms
    val options = PaimonCompactionOptions.from(node.tableOptions)
    val verdicts = lsms?.map { it.planCompaction(options) }.orEmpty()
    val compacting = verdicts.count { it.compacts }
    val title = "Compaction" + when {
        verdicts.any { it.stalls } -> " — a writer would wait"
        compacting > 0 -> " — $compacting due"
        else -> ""
    }
    CountedSection(title, verdicts.size, "buckets") {
        Text(
            "Each bucket is an LSM tree: every level-0 file is a sorted run of its own, each higher " +
                "level is one run, and the writer asks UniversalCompaction.pick() on every flush. " +
                "Under num-sorted-run.compaction-trigger (${options.trigger}) runs it picks nothing; " +
                "at it, by size — the runs newer than the oldest against " +
                "${options.maxSizeAmplificationPercent}% of the oldest, else the newest runs within " +
                "${options.sizeRatioPercent}% of each other; above it, regardless. Past " +
                "num-sorted-run.stop-trigger (${options.stopTrigger}) the writer waits for the " +
                "compaction." +
                (if (options.forceUpLevel0) " This table forces level 0 up on every flush (lookup, deletion vectors or first-row)." else "") +
                (if (options.writeOnly) " This table is write-only: nothing compacts." else ""),
            fontSize = TypeScale.small,
            color = colors.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 4.dp),
        )
        if (lsms == null) {
            Text("Not readable here — this snapshot's manifests are not retained.", fontSize = TypeScale.small, color = colors.onSurfaceVariant)
        } else if (verdicts.isNotEmpty()) {
            WideTable(
                headers = listOf("Next Flush", "Partition", "Bucket", "Sorted Runs", "Levels", "Files", "Bytes"),
                columnWidths = listOf(190.dp, 190.dp, 70.dp, 100.dp, 190.dp, 70.dp, 110.dp),
                rows = verdicts.map { v ->
                    listOf(
                        v.describe(),
                        v.lsm.partition.ifEmpty { "(unpartitioned)" },
                        "${v.lsm.bucket}",
                        "${v.lsm.sortedRunCount} of ${options.trigger}",
                        v.lsm.describeLevels(),
                        "${v.lsm.fileCount}",
                        formatBytes(v.lsm.runs.sumOf { it.sizeBytes }),
                    )
                },
                leadCellColors = verdicts.map { v ->
                    when {
                        v.stalls -> colors.error
                        v.compacts -> verdictSkippedColor()
                        else -> null
                    }
                },
            )
        }
    }
}

/** The append-table side of [PaimonCompactionSection]: what `sys.compact` would pack, per partition. */
@Composable
private fun AppendCompactionSection(node: GraphNode.PaimonSnapshotNode) {
    val colors = MaterialTheme.colorScheme
    val entries = node.bucketLsms
    val verdicts = entries?.let { lsms ->
        // The append rule is per partition and size-only; the trees carry the same files.
        lsms.groupBy { it.partition }.entries.sortedBy { it.key }.map { (partition, trees) ->
            val files = trees.flatMap { t -> t.runs.flatMap { it.files } }
            paimonAppendVerdict(partition, files, node.tableOptions)
        }
    }.orEmpty()
    val packing = verdicts.count { it.wouldPack }
    CountedSection("Compaction" + (if (packing > 0) " — sys.compact would run on $packing" else ""), verdicts.size, "partitions") {
        Text(
            "An append table has no levels and compacts only when sys.compact or a compaction job " +
                "runs. It packs the files under 7/10 of target-file-size per partition, and a pack is " +
                "a task once it holds compaction.min.file-num files or twice the target in bytes.",
            fontSize = TypeScale.small,
            color = colors.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 4.dp),
        )
        if (entries == null) {
            Text("Not readable here — this snapshot's manifests are not retained.", fontSize = TypeScale.small, color = colors.onSurfaceVariant)
        } else if (verdicts.isNotEmpty()) {
            WideTable(
                headers = listOf("sys.compact", "Partition", "Small Files", "Files", "Small Bytes", "Threshold"),
                columnWidths = listOf(190.dp, 190.dp, 90.dp, 70.dp, 110.dp, 110.dp),
                rows = verdicts.map { v ->
                    listOf(
                        v.describe(),
                        v.partition.ifEmpty { "(unpartitioned)" },
                        "${v.smallFileCount}",
                        "${v.fileCount}",
                        formatBytes(v.smallFileBytes),
                        formatBytes(v.compactionFileSizeBytes),
                    )
                },
                leadCellColors = verdicts.map { if (it.wouldPack) verdictSkippedColor() else null },
            )
        }
    }
}

/**
 * The snapshot file's three record counts against the manifests it names — `paimonRecordTallies`.
 * The total is checked against the replay's live rows, which is the same walk the comparison
 * runs; the delta the way the writer sums it, by entry; the changelog by its entries' rows. Read
 * off the node's deferred tallies, so opening the panel twice replays once.
 */
@Composable
internal fun PaimonRecordsSection(node: GraphNode.PaimonSnapshotNode) {
    val colors = MaterialTheme.colorScheme
    val tallies = node.recordTallies.orEmpty()
    val disagreeing = tallies.count { it.agrees == false }
    val title = "Recorded Records" + if (disagreeing > 0) " — $disagreeing DISAGREE" else ""
    Section(title) {
        // Data evolution: a patch file's rows are rows the table already had, and Paimon's total
        // sums file rows, so the figure a scan returns is stated before the table that agrees
        // with the writer — the same shape as the vector note in the index section.
        val live = node.liveFiles.orEmpty()
        val partialRows = live.partialRows()
        if (partialRows > 0) {
            val partialFiles = live.count { it.partial }
            Text(
                "${formatCount(partialRows)} rows in ${formatCount(partialFiles.toLong())} partial-column " +
                    "${if (partialFiles == 1) "file" else "files"} are columns of rows other files hold" +
                    (node.data.totalRecordCount?.let { " — the snapshot's ${formatCount(it)} rows read as ${formatCount(it - partialRows)}" } ?: "") +
                    ".",
                fontSize = TypeScale.small,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(bottom = 4.dp),
            )
        }
        Text(
            "The three record counts the snapshot file carries, beside the same figures read from the " +
                "manifests it names: the total against the rows the replay ends holding, the delta as " +
                "the writer sums it — rows added by the delta list's entries minus rows removed — and " +
                "the changelog by its entries. A total that disagrees went wrong at an earlier commit " +
                "and was carried forward; nothing on a read path checks it.",
            fontSize = TypeScale.small,
            color = colors.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 4.dp),
        )
        WideTable(
            headers = listOf("Agrees", "Figure", "In the manifests", "The snapshot said"),
            columnWidths = listOf(110.dp, 170.dp, 140.dp, 150.dp),
            rows = tallies.map { tally ->
                listOf(
                    when (tally.agrees) {
                        true -> "yes"
                        false -> "NO"
                        null -> "not recorded"
                    },
                    tally.label,
                    formatCount(tally.counted),
                    tally.recorded?.let { formatCount(it) } ?: "not recorded",
                )
            },
            leadCellColors = tallies.map { tally ->
                when (tally.agrees) {
                    true -> null
                    false -> colors.error
                    null -> verdictUnevaluatedColor()
                }
            },
        )
    }
}

@Composable
internal fun PaimonIndexFilesSection(node: GraphNode.PaimonSnapshotNode) {
    val colors = MaterialTheme.colorScheme
    val files = node.indexFiles
    CountedSection("Index Files", files.size, "index files") {
        if (files.isEmpty()) {
            Text(
                if (node.data.indexManifest.isNullOrBlank()) {
                    "This snapshot names no index manifest. A table with no primary key and no " +
                        "deletion vectors writes none."
                } else {
                    "This snapshot names an index manifest, and it lists nothing."
                },
                fontSize = TypeScale.small,
                color = colors.onSurfaceVariant,
            )
            return@CountedSection
        }
        Text(
            "One index file per bucket. A HASH index is what a bucket looks a primary key up in; a " +
                "DELETION_VECTORS index is where Paimon records the rows deleted from a data file " +
                "without rewriting it, and is the only place that link exists.",
            fontSize = TypeScale.small,
            color = colors.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 4.dp),
        )
        // The one figure a reader wants before the table: how many rows the snapshot's own
        // totalRecordCount still counts that a scan will never return. Paimon's total is a sum of
        // file row counts, and a vector marks rows without changing any of them.
        val ranges = files.flatMap { it.deletionVectorRanges?.filterNotNull().orEmpty() }
        if (ranges.isNotEmpty()) {
            val marked = ranges.sumOf { it.cardinality ?: 0L }
            val total = node.data.totalRecordCount
            Text(
                "${formatCount(marked)} rows across ${formatCount(ranges.size.toLong())} data files " +
                    "are marked deleted by vectors" +
                    (total?.let { " — the snapshot's ${formatCount(it)} rows are ${formatCount(it - marked)} live" } ?: "") +
                    ".",
                fontSize = TypeScale.small,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(bottom = 4.dp),
            )
        }
        WideTable(
            // Deleted rows before the file name: on a table with deletion vectors that column is
            // the answer, and a file name is the identifier the table decides — the one thing
            // `WideTable` is willing to put behind a scroll.
            headers = listOf("Type", "Bucket", "Rows", "Size", "Deleted rows", "File"),
            columnWidths = listOf(160.dp, 70.dp, 90.dp, 90.dp, 260.dp, 300.dp),
            rows = files.map { file ->
                val ranges = file.deletionVectorRanges?.filterNotNull().orEmpty()
                listOf(
                    file.indexType ?: "N/A",
                    "${file.bucket ?: "N/A"}",
                    formatCount(file.rowCount ?: 0L),
                    formatBytes(file.fileSize ?: 0L),
                    if (ranges.isEmpty()) {
                        "—"
                    } else {
                        ranges.joinToString("; ") { range ->
                            "${fileNameFromPath(range.dataFileName.orEmpty())}: " +
                                "${range.cardinality?.let { formatCount(it) } ?: "?"}"
                        }
                    },
                    file.fileName ?: "N/A",
                )
            },
        )
    }
}

/** Rows the unreferenced-file table lists before it says how many more there are — same cap as the diff. */
private const val MAX_UNREFERENCED_ROWS = 500

/**
 * What is under the table root that no metadata names — [findUnreferencedFiles], behind a click.
 *
 * Behind a click because it is a walk of the whole table directory, which on a remote table is a
 * subtree listing and on a large local one is the one thing here that scales with the data rather
 * than the metadata. [startRequested] and [onSettled] follow `PositionalDeleteTargets`: the walk
 * runs on `Dispatchers.IO` and a capture has to wait for it. The result is read through the node's
 * `DeferredRead`, so a second look at the panel does not walk again.
 */
@Composable
internal fun UnreferencedFilesSection(
    node: GraphNode.TableNode,
    startRequested: Boolean = false,
    onSettled: () -> Unit = {},
) {
    val colors = MaterialTheme.colorScheme
    if (!node.unreferencedFiles.isPresent) return

    var requested by remember(node.id) { mutableStateOf(startRequested) }
    val outcome by produceState<Result<UnreferencedFilesReport>?>(null, node.id, requested) {
        value = null
        if (requested) {
            value = withContext(Dispatchers.IO) {
                runCatching { requireNotNull(node.unreferencedFiles.value) { "no report" } }
            }
            onSettled()
        }
    }
    val report = outcome?.getOrNull()
    val title = if (report != null) "Unreferenced Files (${formatCount(report.unreferenced.size)})" else "Unreferenced Files"

    Section(title) {
        // The caveat about what "referenced" means goes under the answer, not above it: a reader
        // who has not clicked needs one sentence, and one who has needs the number first.
        val caveat = "Referenced means named by any metadata version on disk, so Iceberg's " +
            "remove_orphan_files, which reaches from the current one only, can delete more than is " +
            "listed here. Paimon tags, branches and consumers are followed."
        when {
            !requested -> {
                Text(
                    "Files under the table root that no metadata names — a write that failed after its " +
                        "files landed, or a file the format wrote and did not commit. A walk of the whole " +
                        "table directory.",
                    fontSize = TypeScale.small,
                    color = colors.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
                OutlinedButton(onClick = { requested = true }) {
                    Text("Walk the table directory")
                }
            }
            outcome == null -> Text("Walking the table directory…", fontSize = TypeScale.small, color = colors.onSurfaceVariant)
            report == null -> Text(
                "Could not walk the table directory: ${outcome?.exceptionOrNull()?.message ?: "unknown error"}",
                fontSize = TypeScale.small,
                color = colors.error,
            )
            else -> {
                Text(
                    if (report.unreferenced.isEmpty()) {
                        "Every one of the ${formatCounted(report.filesOnDisk, "file")} on disk is named by the metadata."
                    } else {
                        "${formatCounted(report.unreferenced.size, "file")} (${formatBytes(report.unreferencedBytes)}) " +
                            "that no metadata names, of ${formatCounted(report.filesOnDisk, "file")} on disk."
                    },
                    fontSize = TypeScale.small,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(bottom = 4.dp),
                )
                report.problems.forEach { problem ->
                    Text("Could not read: $problem", fontSize = TypeScale.small, color = colors.error)
                }
                if (report.unreferenced.isNotEmpty()) {
                    WideTable(
                        headers = listOf("Size", "File"),
                        columnWidths = listOf(90.dp, 700.dp),
                        rows = report.unreferenced.take(MAX_UNREFERENCED_ROWS).map { file ->
                            listOf(formatBytes(file.sizeBytes), report.relativePathOf(file))
                        },
                    )
                    if (report.unreferenced.size > MAX_UNREFERENCED_ROWS) {
                        Text(
                            "…and ${formatCount(report.unreferenced.size - MAX_UNREFERENCED_ROWS)} more.",
                            fontSize = TypeScale.small,
                            color = colors.onSurfaceVariant,
                        )
                    }
                }
                Text(caveat, fontSize = TypeScale.small, color = colors.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
            }
        }
    }
}

/**
 * What an `ANALYZE TABLE` commit wrote, on the snapshot that names it.
 *
 * The merged record count leads because it is the one figure the format records nowhere else: a
 * snapshot's `totalRecordCount` sums file rows, so an updated key counts once per version it still
 * has and a `-D` row counts as a row, while this is the count after the merge engine — what a scan
 * returns. Drawn on every snapshot, most of which name nothing, for the same reason the index
 * section is: only an `ANALYZE` commit writes one, and a section that is absent cannot be told from
 * one this panel does not know how to render.
 */
@Composable
internal fun PaimonStatisticsSection(node: GraphNode.PaimonSnapshotNode) {
    val colors = MaterialTheme.colorScheme
    val stats = node.statistics
    val columns = stats?.colStats.orEmpty()
    CountedSection("Statistics", columns.size, "column statistics") {
        if (stats == null) {
            Text(
                if (node.data.statistics.isNullOrBlank()) {
                    "This snapshot names no statistics file. Only an ANALYZE TABLE commit writes one, " +
                        "and it names the snapshot it measured."
                } else {
                    "This snapshot names a statistics file that could not be read — see the read errors."
                },
                fontSize = TypeScale.small,
                color = colors.onSurfaceVariant,
            )
            return@CountedSection
        }
        val merged = stats.mergedRecordCount
        val total = node.data.totalRecordCount
        Text(
            buildString {
                append(merged?.let { "${formatCount(it)} rows after the merge engine" } ?: "No merged row count")
                stats.mergedRecordSize?.let { append(" (${formatBytes(it)})") }
                stats.snapshotId?.let { append(", measured at snapshot $it") }
                if (merged != null && total != null) {
                    append(
                        if (merged == total) " — the same as the snapshot's file-row total"
                        else " — the snapshot's file-row total is ${formatCount(total)}",
                    )
                }
                append(".")
            },
            fontSize = TypeScale.small,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(bottom = 4.dp),
        )
        if (columns.isEmpty()) {
            Text(
                "No column statistics: the statement did not say FOR ALL COLUMNS or name any.",
                fontSize = TypeScale.small,
                color = colors.onSurfaceVariant,
            )
            return@CountedSection
        }
        WideTable(
            headers = listOf("Column", "Distinct Values", "Nulls", "Min", "Max", "Avg Length", "Max Length"),
            columnWidths = listOf(140.dp, 120.dp, 80.dp, 160.dp, 160.dp, 90.dp, 90.dp),
            rows = columns.entries.sortedBy { it.value.colId ?: Int.MAX_VALUE }.map { (name, col) ->
                listOf(
                    name,
                    col.distinctCount?.let { formatCount(it) } ?: "N/A",
                    col.nullCount?.let { formatCount(it) } ?: "N/A",
                    // Absent for a string column, which is how Paimon writes it rather than a gap.
                    col.min ?: "—",
                    col.max ?: "—",
                    col.avgLen?.let { formatCount(it) } ?: "N/A",
                    col.maxLen?.let { formatCount(it) } ?: "N/A",
                )
            },
        )
    }
}

@Composable
internal fun DeleteReachSection(node: GraphNode.SnapshotNode, children: List<GraphNode>) {
    val colors = MaterialTheme.colorScheme
    val hasDeleteManifest = children.filterIsInstance<GraphNode.ManifestNode>()
        .any { it.data.content == ManifestContent.DELETES }
    val reach = if (hasDeleteManifest) node.deleteReach.orEmpty() else emptyList()

    CountedSection("Delete Reach", reach.size, "delete files") {
        if (reach.isEmpty()) {
            Text(
                if (hasDeleteManifest) {
                    "This snapshot lists a delete manifest, but no live delete file inside it."
                } else {
                    "No delete files here: every manifest this snapshot lists holds data entries only."
                },
                fontSize = TypeScale.small,
                color = colors.onSurfaceVariant,
            )
            return@CountedSection
        }
        Text(
            "A scan pairs a delete file with a data file by two rules, both answerable without " +
                "opening either: the delete file's sequence number must be at or above the data " +
                "file's — strictly above, for an equality delete — and its recorded targets must " +
                "not rule the path out. \"Reaches nothing\" is a proof, and it means the file is " +
                "dangling: still read during planning, deleting rows that are no longer here.",
            fontSize = TypeScale.small,
            color = colors.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 4.dp),
        )
        WideTable(
            headers = listOf("Reaches", "Delete File", "Kind", "Seq", "Records", "Recorded Targets"),
            columnWidths = listOf(150.dp, 260.dp, 130.dp, 60.dp, 90.dp, 300.dp),
            leadCellColors = reach.map { if (it.isDangling) danglingDeleteColor() else null },
            rows = reach.map { file ->
                listOf(
                    when {
                        file.isDangling -> "nothing"
                        file.reaches.isEmpty() -> "${formatCount(file.mayReach.size.toLong())} unsettled"
                        file.mayReach.isEmpty() ->
                            "${formatCount(file.reaches.size.toLong())} data " +
                                if (file.reaches.size == 1) "file" else "files"
                        else -> "${formatCount(file.reaches.size.toLong())} + " +
                            "${formatCount(file.mayReach.size.toLong())} unsettled"
                    },
                    fileNameFromPath(file.deletePath),
                    when (file.kind) {
                        DeleteFileKind.DELETION_VECTOR -> "deletion vector"
                        DeleteFileKind.POSITIONAL -> "positional"
                        DeleteFileKind.EQUALITY -> "equality"
                    },
                    "${file.sequenceNumber}",
                    formatCount(file.recordCount ?: 0L),
                    file.targets.onlyPath?.let { "names ${fileNameFromPath(it)}" }
                        ?: file.targets.low?.let { low ->
                            "${fileNameFromPath(low)} … ${fileNameFromPath(file.targets.high.orEmpty())}"
                        }
                        ?: "none recorded — applies by value",
                )
            },
        )
        // The files each one reaches, listed once rather than per row: on any real table the same
        // data file is named by several delete files, and a column of them would be the same paths
        // repeated down the table.
        reach.filter { it.reaches.isNotEmpty() }.forEach { file ->
            Text(
                "${fileNameFromPath(file.deletePath)} → " +
                    file.reaches.joinToString(", ") { fileNameFromPath(it) },
                fontSize = TypeScale.micro,
                color = colors.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
    }
}

/**
 * The six `total-*` figures the summary records about the table at this commit, against the live
 * set folded from the closure the commit names — `snapshotTotals`. The other half of the summary
 * from what `CommitSection` checks: that one reads the manifests the commit wrote, this one the
 * whole closure, which is the walk the two-snapshot comparison runs and is read off the node's
 * deferred live set so a panel opened twice walks it once.
 */
/**
 * The clock an expiry plan is measured from. A composition local so a render can pin it: an age
 * is "now minus a timestamp", and a capture taken against the wall clock draws a different panel
 * every day it is run.
 */
val LocalExpiryClock = androidx.compose.runtime.compositionLocalOf<() -> Long> { { System.currentTimeMillis() } }

@Composable
internal fun expiryClock(): Long = LocalExpiryClock.current()

/**
 * What `expire_snapshots` would remove from this metadata, decided by [planExpiry] — the rules of
 * Iceberg's `RemoveSnapshots`, ref by ref — under two cutoffs side by side: the table's defaults,
 * and `older_than = now`, which is the most a procedure call can ask by age. Two columns rather
 * than a form, because the question a reader arrives with is "what is protecting this snapshot",
 * and the answer is the same ref either way; only the age rule moves between the columns.
 */
@Composable
internal fun ExpirySection(metadata: TableMetadata, nowMs: Long) {
    val colors = MaterialTheme.colorScheme
    val byDefaults = metadata.planExpiry(ExpiryOptions(nowMs = nowMs))
    val byAge = metadata.planExpiry(ExpiryOptions(nowMs = nowMs, olderThanMs = nowMs))
    val title = "Expiry" + if (byAge.removed.isNotEmpty()) " — ${byAge.removed.size} would go" else ""
    Section(title) {
        Text(
            "What expire_snapshots would keep, and why, the way RemoveSnapshots decides it: a ref " +
                "keeps its snapshot, a branch keeps its ancestors while they are within its " +
                "min-snapshots-to-keep or newer than its cutoff, and anything on no ref goes once " +
                "it is older than the cutoff. A branch's own max-snapshot-age-ms replaces older_than " +
                "for everything the branch reaches. The first column is the table's defaults " +
                "(${formatRetentionMs(nowMs - byDefaults.defaultCutoffMs).substringBefore(" (")} cutoff, " +
                "keep ${byDefaults.defaultMinSnapshotsToKeep}); the second is older_than = now.",
            fontSize = TypeScale.small,
            color = colors.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 4.dp),
        )
        val expiringRefs = byDefaults.refs.filter { !it.retained }
        if (expiringRefs.isNotEmpty()) {
            Text(
                "Refs past their max-ref-age: " + expiringRefs.joinToString(", ") { "${it.name} (${it.reason})" } + ".",
                fontSize = TypeScale.small,
                fontWeight = FontWeight.Bold,
                color = colors.error,
                modifier = Modifier.padding(bottom = 4.dp),
            )
        }
        val byAgeById = byAge.snapshots.associateBy { it.snapshotId }
        val ordered = metadata.snapshots.sortedBy { it.sequenceNumber ?: Long.MAX_VALUE }.mapNotNull { it.snapshotId }
        WideTable(
            headers = listOf("Under the defaults", "Snapshot ID", "With older_than = now"),
            // The verdict leads and wraps; the panel opens at 300dp, so the leading column must
            // fit there or the reader scrolls before reading anything.
            columnWidths = listOf(190.dp, 190.dp, 300.dp),
            rows = ordered.map { id ->
                val defaults = byDefaults.snapshots.first { it.snapshotId == id }
                val age = byAgeById.getValue(id)
                listOf(
                    if (defaults.retained) "kept — " + defaults.describeKeptBy() else "REMOVED",
                    id.toString(),
                    if (age.retained) "kept — " + age.describeKeptBy() else "REMOVED",
                )
            },
            leadCellColors = ordered.map { id ->
                if (byDefaults.snapshots.first { it.snapshotId == id }.retained) null else colors.error
            },
        )
    }
}

/**
 * What `expire_snapshots` would remove from this Paimon table, decided by
 * [PaimonExpiryInput.planExpiry] — the rules of `ExpireSnapshotsImpl.expire()` — under two calls
 * side by side: a bare call, which runs under the table's own `snapshot.*` options, and
 * `retain_min = 1, older_than = now`, which is as far as a call can go without `retain_max` — so
 * what the second column still keeps is what no call can remove: a consumer's bookmark, or the
 * run's limit. The same two-column shape as the Iceberg section, because the reader's question
 * is the same — "what is protecting this snapshot".
 */
@Composable
internal fun PaimonExpirySection(input: PaimonExpiryInput, nowMs: Long) {
    val colors = MaterialTheme.colorScheme
    val plans = runCatching {
        input.planExpiry(PaimonExpiryOptions(nowMs = nowMs)) to
            input.planExpiry(PaimonExpiryOptions(nowMs = nowMs, retainMin = 1, olderThanMs = nowMs))
    }
    val byAge = plans.getOrNull()?.second
    val title = "Expiry" + if (byAge != null && byAge.removed.isNotEmpty()) " — ${byAge.removed.size} would go" else ""
    Section(title) {
        val (byDefaults, byAgePlan) = plans.getOrElse { failure ->
            // Paimon rejects the same options before running: the panel says so rather than guessing.
            Text(
                "expire_snapshots would refuse this table's options: ${failure.message}",
                fontSize = TypeScale.small,
                fontWeight = FontWeight.Bold,
                color = colors.error,
            )
            return@Section
        }
        val retainMaxLabel = if (byDefaults.retainMax == Int.MAX_VALUE) "unbounded" else "${byDefaults.retainMax}"
        Text(
            "What expire_snapshots would keep, and why, the way ExpireSnapshotsImpl decides it: the " +
                "newest snapshot.num-retained.min stay (${byDefaults.retainMin} here), anything beyond " +
                "snapshot.num-retained.max goes whatever its age ($retainMaxLabel here), a consumer's " +
                "next snapshot and everything after it stay, at most snapshot.expire.limit " +
                "(${byDefaults.maxDeletes}) go in one run, and between those bounds the run stops at " +
                "the first snapshot younger than the cutoff. The first column is a bare call, under the " +
                "table's snapshot.time-retained " +
                "(${formatRetentionMs(nowMs - byDefaults.cutoffMs).substringBefore(" (")}); the second " +
                "is retain_min = 1 with older_than = now, as far as a call goes without retain_max. " +
                "A removed snapshot a tag names lives on as the tag.",
            fontSize = TypeScale.small,
            color = colors.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 4.dp),
        )
        val byAgeById = byAgePlan.snapshots.associateBy { it.snapshotId }
        fun verdict(v: model.PaimonSnapshotExpiryVerdict): String = when {
            v.retained -> "kept — " + v.describeKeptBy()
            v.tags.isNotEmpty() -> "REMOVED — lives on as tag " + v.tags.joinToString(", ")
            else -> "REMOVED"
        }
        WideTable(
            headers = listOf("Under the table's options", "Snapshot ID", "With retain_min = 1, older_than = now"),
            columnWidths = listOf(190.dp, 120.dp, 300.dp),
            rows = byDefaults.snapshots.map { v ->
                listOf(verdict(v), v.snapshotId.toString(), verdict(byAgeById.getValue(v.snapshotId)))
            },
            leadCellColors = byDefaults.snapshots.map { if (it.retained) null else colors.error },
        )
    }
}

/**
 * What `rewrite_data_files` would rewrite at this snapshot under a bare call — [planRewrite], the
 * rules of Iceberg's `SizeBasedDataRewriter`, over the same live set and delete pairing the
 * sections above already read. The options come from the table: `write.target-file-size-bytes`
 * off the latest metadata, and the current spec, since a file under an older spec is planned as
 * unpartitioned.
 *
 * One column, unlike the expiry sections: the only option a reader reaches for is
 * `min-input-files`, and the row already says how many files the group has against it.
 */
@Composable
internal fun RewriteSection(node: GraphNode.SnapshotNode, graph: GraphModel) {
    val colors = MaterialTheme.colorScheme
    val latest = graph.nodes.filterIsInstance<GraphNode.MetadataNode>()
        .maxByOrNull { metadataVersionFromFileName(it.fileName) ?: -1 }?.data
    val options = RewriteOptions.forTable(latest?.properties.orEmpty(), latest?.defaultSpecId)
    val live = node.liveFiles
    val plan = live?.let { planRewrite(it, node.deleteReach.orEmpty(), options) }
    val rewritten = plan?.rewrittenGroups.orEmpty()
    val title = "Rewrite" + if (rewritten.isNotEmpty()) " — ${rewritten.sumOf { it.files.size }} files would go" else ""
    CountedSection(title, plan?.groups?.size ?: 0, "groups") {
        Text(
            "What rewrite_data_files would rewrite, the way SizeBasedDataRewriter plans it: a file is " +
                "a candidate when it is outside ${formatBytes(options.minFileSizeBytes)}–" +
                "${formatBytes(options.maxFileSizeBytes)} (75% and 180% of write.target-file-size-bytes, " +
                "${formatBytes(options.targetFileSizeBytes)}) or when file-scoped deletes mark " +
                "${(options.deleteRatioThreshold * 100).toInt()}% of its rows; candidates are packed per " +
                "partition, and a group is rewritten with at least ${options.minInputFiles} files " +
                "(min-input-files), more than the target in bytes, or a file past the delete ratio. " +
                "Every small file is a candidate; it takes ${options.minInputFiles} of them.",
            fontSize = TypeScale.small,
            color = colors.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 4.dp),
        )
        when {
            plan == null -> Text("Not readable here — this snapshot's manifests are not retained.", fontSize = TypeScale.small, color = colors.onSurfaceVariant)
            plan.groups.isEmpty() -> Text("No candidates: every live data file is within the size range and under the delete ratio.", fontSize = TypeScale.small, color = colors.onSurfaceVariant)
            else -> WideTable(
                headers = listOf("Verdict", "Partition", "Files", "Bytes", "Output Files", "Highest Delete Ratio"),
                columnWidths = listOf(190.dp, 190.dp, 70.dp, 110.dp, 100.dp, 150.dp),
                rows = plan.groups.map { g ->
                    listOf(
                        if (g.rewritten) "REWRITTEN — " + g.reasons.joinToString("; ") { it.label }
                        else "left alone — ${g.files.size} of ${options.minInputFiles} files",
                        g.partition.ifEmpty { "(unpartitioned)" },
                        "${g.files.size}",
                        formatBytes(g.inputBytes),
                        if (g.rewritten) "${g.outputFiles}" else "—",
                        g.files.maxOfOrNull { it.deleteRatio }?.let { "${(it * 100).toInt()}%" } ?: "0%",
                    )
                },
                leadCellColors = plan.groups.map { if (it.rewritten) verdictSkippedColor() else null },
            )
        }
    }
}

/**
 * The snapshot's live files by partition, largest first — what Iceberg's `.partitions` metadata
 * table answers, folded from the same [LiveFile] set the totals above and the comparison use.
 * Format-agnostic through [ComparableSnapshot], because "how is this table skewed" is the same
 * question of either. The walk is the one [TotalsSection] already ran, so this costs no read.
 */
@Composable
internal fun PartitionsSection(snapshot: ComparableSnapshot) {
    val colors = MaterialTheme.colorScheme
    val live = snapshot.liveFiles
    val shares = live?.partitionBreakdown().orEmpty()
    val unpartitioned = shares.size == 1 && shares.single().partition.isEmpty()
    CountedSection("Partitions", shares.size, "partitions") {
        when {
            live == null -> Text(
                "Not readable here — this snapshot's manifests are not retained.",
                fontSize = TypeScale.small,
                color = colors.onSurfaceVariant,
            )
            shares.isEmpty() -> Text("No live files.", fontSize = TypeScale.small, color = colors.onSurfaceVariant)
            unpartitioned -> {
                val only = shares.single()
                Text(
                    "Unpartitioned: ${formatCount(only.dataFileCount.toLong())} data files, " +
                        "${formatCount(only.dataRecordCount)} records, ${formatBytes(only.dataSizeBytes)}" +
                        (if (only.deleteFileCount > 0) ", ${formatCount(only.deleteFileCount.toLong())} delete files" else "") + ".",
                    fontSize = TypeScale.small,
                    color = colors.onSurfaceVariant,
                )
            }
            else -> {
                val largest = shares.first()
                val totalBytes = shares.sumOf { it.dataSizeBytes }
                Text(
                    "Largest: ${largest.partition} — ${formatBytes(largest.dataSizeBytes)} of ${formatBytes(totalBytes)}" +
                        (if (totalBytes > 0) " (${largest.dataSizeBytes * 100 / totalBytes}%)" else "") +
                        " across ${formatCount(shares.size.toLong())} partitions.",
                    fontSize = TypeScale.small,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(bottom = 4.dp),
                )
                WideTable(
                    headers = listOf("Partition", "Data Files", "Records", "Bytes", "Delete Files", "Delete Records"),
                    columnWidths = listOf(190.dp, 90.dp, 110.dp, 110.dp, 100.dp, 120.dp),
                    rows = shares.map { share ->
                        listOf(
                            share.partition,
                            formatCount(share.dataFileCount.toLong()),
                            formatCount(share.dataRecordCount),
                            formatBytes(share.dataSizeBytes),
                            formatCount(share.deleteFileCount.toLong()),
                            formatCount(share.deleteRecordCount),
                        )
                    },
                    leadCellColors = shares.map { if (it.partition == UNDECODED_PARTITION) colors.error else null },
                )
            }
        }
    }
}

@Composable
internal fun TotalsSection(node: GraphNode.SnapshotNode) {
    val colors = MaterialTheme.colorScheme
    val live = node.liveFiles
    val tallies = live?.let { snapshotTotals(node.data.summary, it) }
    val disagreeing = tallies?.count { it.agrees == false } ?: 0
    val title = "What this commit left" + if (disagreeing > 0) " — $disagreeing DISAGREE" else ""
    Section(title) {
        Text(
            "The running totals the summary carries — kept by the writer as the previous total " +
                "plus what this commit added minus what it removed — beside the same figures folded " +
                "from every manifest this snapshot lists. A total that disagrees has been wrong since " +
                "some earlier commit and carried forward since; nothing on a read path checks it.",
            fontSize = TypeScale.small,
            color = colors.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 4.dp),
        )
        if (tallies == null) {
            Text(
                "This snapshot's manifest list is gone with its expiry, so there is no closure to count " +
                    "and the totals it recorded stand unchecked.",
                fontSize = TypeScale.small,
                color = verdictUnevaluatedColor(),
            )
        } else {
            WideTable(
                headers = listOf("Agrees", "Figure", "In the closure", "The commit said"),
                columnWidths = listOf(110.dp, 170.dp, 140.dp, 140.dp),
                rows = tallies.map { tally ->
                    listOf(
                        when (tally.agrees) {
                            true -> "yes"
                            false -> "NO"
                            null -> "not recorded"
                        },
                        tally.label,
                        formatCount(tally.counted),
                        tally.recorded?.let { formatCount(it) } ?: "not recorded",
                    )
                },
                leadCellColors = tallies.map { tally ->
                    when (tally.agrees) {
                        true -> null
                        false -> colors.error
                        null -> verdictUnevaluatedColor()
                    }
                },
            )
        }
    }
}

@Composable
internal fun CommitSection(change: SnapshotChange) {
    val colors = MaterialTheme.colorScheme
    // Only the figures one side or the other actually speaks about. A plain append states three
    // of the eight, and the other five would each draw a row reading "0 / not recorded / nothing
    // to check" — six rows of the unevaluated colour, wrapped to two lines, above the two rows
    // carrying the answer. A verdict column spends its emphasis on whatever it marks most often.
    // The manifest split is in the sentence above for every commit, so its two rows appear only
    // where the summary recorded the figures — a rewrite_manifests — rather than as two amber
    // "nothing to check" rows on every panel.
    val tallies = change.fileTallies.filter { it.recorded != null || it.counted != 0L } +
        change.manifestTallies.filter { it.recorded != null }
    val disagreeing = change.disagreements.size

    val title = "What this commit did" + if (disagreeing > 0) " — $disagreeing DISAGREE" else ""
    Section(title) {
        Text(
            buildString {
                append(change.operation?.let { "An Iceberg \"$it\"." } ?: "This commit records no operation.")
                append(
                    if (change.isRoot) {
                        " It is the first commit on this table, so everything here is an addition."
                    } else {
                        " Read from the manifests this commit wrote, which is what makes it this " +
                            "commit's work rather than its ancestors'."
                    },
                )
                // The split the counts below rest on, stated for every commit: a rewrite_manifests
                // changes no file and this line is the whole of what it did.
                append(
                    " It wrote ${formatCount(change.manifestsWritten)} of the " +
                        "${formatCount(change.manifests.size)} manifests it lists and carried " +
                        "${formatCount(change.manifestsCarried)} forward unchanged.",
                )
                if (change.unattributedManifests > 0) {
                    append(
                        " ${formatCount(change.unattributedManifests)} of the manifests it lists " +
                            "record no adding snapshot, so nothing can say which commit wrote them " +
                            "and they are left out of the counts below.",
                    )
                }
            },
            fontSize = TypeScale.small,
            color = colors.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 4.dp),
        )
        Text(
            "Figures neither the commit nor its manifests say anything about are left out — a " +
                "plain append states three of the fourteen.",
            fontSize = TypeScale.small,
            color = colors.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 4.dp),
        )
        WideTable(
            // Same column order as the manifest tally table, for the same reason: the reader is
            // here to find out whether anything disagrees, not to compare eight pairs by eye.
            headers = listOf("Agrees", "Figure", "In the manifests", "The commit said"),
            columnWidths = listOf(110.dp, 170.dp, 140.dp, 140.dp),
            rows = tallies.map { tally ->
                listOf(
                    when (tally.agrees) {
                        true -> "yes"
                        false -> "NO"
                        null -> "nothing to check"
                    },
                    tally.label,
                    formatCount(tally.counted),
                    tally.recorded?.let { formatCount(it) } ?: "not recorded",
                )
            },
            leadCellColors = tallies.map { tally ->
                when (tally.agrees) {
                    true -> null
                    false -> colors.error
                    null -> verdictUnevaluatedColor()
                }
            },
        )

        if (change.files.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            Text(
                "The files themselves, one row each. A removal is an entry this commit wrote with " +
                    "status DELETED — the file is out of the table from here on, though an older " +
                    "snapshot still reads it.",
                fontSize = TypeScale.small,
                color = colors.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 4.dp),
            )
            WideTable(
                headers = listOf("Change", "File", "Kind", "Rows", "Size"),
                columnWidths = listOf(90.dp, 360.dp, 100.dp, 80.dp, 90.dp),
                rows = change.files.map { file ->
                    listOf(
                        if (file.change == FileChange.ADDED) "added" else "REMOVED",
                        fileNameFromPath(file.path),
                        when (file.content) {
                            DataFileContent.POSITION_DELETES -> "pos delete"
                            DataFileContent.EQUALITY_DELETES -> "eq delete"
                            else -> "data"
                        },
                        formatCount(file.recordCount),
                        formatBytes(file.sizeBytes),
                    )
                },
                // Removal is the exception on an append-mostly table, and it is the row a reader
                // scanning a commit history is looking for.
                leadCellColors = change.files.map { file ->
                    if (file.change == FileChange.ADDED) null else colors.error
                },
            )
        }
    }
}

/** `[0, 1, 2, 5, 9, 10]` becomes `"0-2, 5, 9-10"`. A run of one stays a bare number. */
internal fun foldRuns(positions: List<Long>): String {
    if (positions.isEmpty()) return "none"
    val parts = mutableListOf<String>()
    var start = positions.first()
    var previous = start
    fun close() = parts.add(if (start == previous) "$start" else "$start-$previous")
    positions.drop(1).forEach { position ->
        if (position == previous + 1) {
            previous = position
        } else {
            close()
            start = position
            previous = position
        }
    }
    close()
    return parts.joinToString(", ")
}

/**
 * A section that states how many things it holds, and draws when it holds none.
 *
 * The counts used to live in a second table above the sections — nine rows saying `Total Schemas`,
 * `Total Refs`, `Snapshot Log Entries`, each restating a size the section right below it never
 * printed. A count belongs to the thing it counts; two places holding one number is two places to
 * read and one of them is redundant.
 *
 * Drawing an empty section rather than skipping it is the other half. A metadata file's panel is
 * a list of what the format defines, and "no statistics files" is an answer a reader comes for —
 * a section that is simply absent cannot be told from one this panel does not know how to draw.
 */
@Composable
internal fun CountedSection(title: String, count: Int, nothing: String, content: @Composable () -> Unit) {
    Section("$title (${formatCount(count)})") {
        if (count == 0) {
            Text(
                "No $nothing.",
                fontSize = TypeScale.small,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            content()
        }
    }
}

@Composable
internal fun SchemaEvolutionSection(metadataChildren: List<GraphNode.MetadataNode>) {
    // Collect all unique schemas across metadata versions
    val allSchemas = metadataChildren.flatMap { meta ->
        meta.data.schemas.map { schema -> meta to schema }
    }
    val uniqueSchemas = allSchemas
        .distinctBy { it.second.schemaId }
        .sortedBy { it.second.schemaId ?: Int.MAX_VALUE }

    if (uniqueSchemas.size < 2) return // No evolution to show

    Section("Schema Evolution") {

        val changes = mutableListOf<Triple<Int, Int, List<SchemaChange>>>() // fromSchemaId, toSchemaId, changes
        for (i in 0 until uniqueSchemas.size - 1) {
            val oldSchema = uniqueSchemas[i].second
            val newSchema = uniqueSchemas[i + 1].second
            val diff = diffSchemas(oldSchema, newSchema)
            if (diff.isNotEmpty()) {
                changes.add(Triple(oldSchema.schemaId ?: i, newSchema.schemaId ?: (i + 1), diff))
            }
        }

        if (changes.isEmpty()) {
            DetailTable {
                DetailRow("Status", "No field changes detected between schema versions")
            }
            return@Section
        }

        val colors = MaterialTheme.colorScheme

        changes.forEach { (fromId, toId, diffs) ->
            Text(
                "Schema $fromId \u2192 $toId",
                fontWeight = FontWeight.SemiBold,
                fontSize = TypeScale.small,
                modifier = Modifier.padding(top = 8.dp, bottom = 4.dp)
            )
            DetailTable {
                diffs.forEach { change ->
                    val changeColor = when (change) {
                        is SchemaChange.Added -> colors.secondary
                        is SchemaChange.Dropped -> colors.error
                        else -> colors.onSurface
                    }
                    DetailRow(
                        key = when (change) {
                            is SchemaChange.Added -> "+ Added"
                            is SchemaChange.Dropped -> "- Dropped"
                            is SchemaChange.TypeChanged -> "\u0394 Type"
                            is SchemaChange.Renamed -> "\u0394 Rename"
                            is SchemaChange.RequiredChanged -> "\u0394 Required"
                        },
                        value = change.toString().substringAfter(": ")
                    )
                }
            }
        }

        // Show current schema fields
        val latestSchema = uniqueSchemas.last().second
        Spacer(Modifier.height(8.dp))
        Text(
            "Current Schema (ID ${latestSchema.schemaId ?: "?"}): ${latestSchema.fields.size} fields",
            fontWeight = FontWeight.Medium,
            fontSize = TypeScale.small,
            modifier = Modifier.padding(bottom = 4.dp)
        )
        val identifierIds = latestSchema.identifierFieldIds.toSet()
        DetailTable {
            DetailRow("ID", "Name / Type / Required", isHeader = true)
            latestSchema.fields.forEach { field ->
                val isIdentifier = (field.id ?: -1) in identifierIds
                val typeStr = field.type?.toString()?.removeSurrounding("\"") ?: "unknown"
                val suffix = buildString {
                    if (field.required == true) append(", required")
                    if (isIdentifier) append(", identifier")
                }
                DetailRow(
                    "${field.id ?: "?"}",
                    "${field.name ?: "?"} ($typeStr$suffix)"
                )
            }
        }
    }
}

// --- Properties Evolution ---

@Composable
internal fun PropertiesEvolutionSection(metadataChildren: List<GraphNode.MetadataNode>) {
    if (metadataChildren.isEmpty()) return

    // Collect properties from each metadata version
    data class VersionProps(val version: String, val props: Map<String, String>)
    val versions = metadataChildren.map { meta ->
        VersionProps(
            version = metadataVersionFromFileName(meta.fileName)?.let { "v$it" } ?: meta.fileName,
            props = meta.data.properties
        )
    }

    // Merge all property keys
    val allKeys = versions.flatMap { it.props.keys }.toSortedSet()
    if (allKeys.isEmpty()) return

    Section("Table Properties") {

        val colors = MaterialTheme.colorScheme

        // If only one metadata version, show a simple table
        if (versions.size == 1) {
            DetailTable {
                DetailRow("Key", "Value", isHeader = true)
                versions[0].props.toSortedMap().forEach { (k, v) ->
                    DetailRow(k, v)
                }
            }
            return@Section
        }

        // Multiple versions: show evolution
        // First show the current values
        val latestProps = versions.last().props

        // Detect changes across versions
        data class PropChange(
            val key: String,
            val fromVersion: String,
            val toVersion: String,
            val oldValue: String?,
            val newValue: String?,
        )

        val changes = mutableListOf<PropChange>()
        for (i in 0 until versions.size - 1) {
            val oldV = versions[i]
            val newV = versions[i + 1]
            // Use a set to avoid double-reporting keys present in both versions.
            val bothKeys = oldV.props.keys union newV.props.keys
            bothKeys.forEach { key ->
                val oldVal = oldV.props[key]
                val newVal = newV.props[key]
                if (oldVal != newVal) {
                    changes.add(PropChange(key, oldV.version, newV.version, oldVal, newVal))
                }
            }
        }

        // Show current properties with change indicators
        DetailTable {
            DetailRow("Key", "Value", isHeader = true)
            allKeys.forEach { key ->
                val currentVal = latestProps[key]
                val hasChanges = changes.any { it.key == key }
                if (currentVal != null) {
                    DetailRow(
                        key = if (hasChanges) "\u0394 $key" else key,
                        value = currentVal
                    )
                } else {
                    DetailRow(key = "- $key", value = "(removed)")
                }
            }
        }

        // Show change log if there are any
        if (changes.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            Text(
                "Property Changes",
                fontWeight = FontWeight.SemiBold,
                fontSize = TypeScale.small,
                modifier = Modifier.padding(bottom = 4.dp)
            )

            val changeHeaders = listOf("Property", "From", "To", "Old Value", "New Value")
            val changeRows = changes.map { change ->
                listOf(
                    change.key,
                    change.fromVersion,
                    change.toVersion,
                    change.oldValue ?: "(not set)",
                    change.newValue ?: "(removed)"
                )
            }
            WideTable(
                headers = changeHeaders,
                rows = changeRows,
                columnWidth = 160.dp
            )
        }
    }
}
