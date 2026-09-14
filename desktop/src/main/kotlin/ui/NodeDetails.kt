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
import service.EqualityDeleteTargets
import service.PositionalDeleteTally
import service.SampleRowReader
import model.DeletionVector
import model.IcebergExportCheck
import model.SchemaFieldRow
import model.DeleteCandidate
import model.OrphanFate
import model.UnreferencedFilesReport
import model.planOrphanRemoval
import model.DeleteReachVerdict
import model.deleteCandidatesFor
import model.deleteKindOf
import model.ledgerKey
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
import model.MetadataTally
import model.MissingFilesReport
import model.METRICS_MAX_INFERRED_DEFAULT
import model.PartitionFieldCheck
import model.PartitionFieldVerdict
import model.partialRows
import model.partitionBreakdown
import model.stepComparableSnapshot
import model.UNDECODED_PARTITION
import model.LiveFile
import model.describe
import model.KeyValuePairLong
import model.MetadataLogEntry
import model.MetadataVersionInfo
import model.SchemaChangeKind
import model.SchemaStep
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

/**
 * A schema as one row per field, nested fields included — [SchemaFieldRow], the same table on
 * the Iceberg metadata panel and the Paimon schema panel. The field column holds the dotted
 * path, which is the name a filter and Iceberg's metadata tables use, and a container's row
 * says only what it is; its fields are the rows under it. [identifierIds] draws the
 * identifier column (Iceberg only); the default columns are drawn only where a field records
 * one, since on most tables they would be a column of `none`.
 */
@Composable
internal fun SchemaFieldsTable(rows: List<SchemaFieldRow>, identifierIds: Set<Int>? = null) {
    val hasInitial = rows.any { it.initialDefault != null }
    val hasWrite = rows.any { it.writeDefault != null }
    val headers = listOf("Field ID", "Field", "Required", "Type") +
        (if (identifierIds != null) listOf("Is Identifier Field") else emptyList()) +
        (if (hasInitial) listOf("Initial Default") else emptyList()) +
        (if (hasWrite) listOf(if (hasInitial) "Write Default" else "Default") else emptyList())
    val widths = listOf(90.dp, 220.dp, 80.dp, 180.dp) +
        (if (identifierIds != null) listOf(120.dp) else emptyList()) +
        (if (hasInitial) listOf(120.dp) else emptyList()) +
        (if (hasWrite) listOf(120.dp) else emptyList())
    WideTable(
        headers = headers,
        columnWidths = widths,
        rows = rows.map { row ->
            listOf(row.id.toString(), row.path, row.required.toString(), row.type) +
                (if (identifierIds != null) listOf(if (row.id in identifierIds) "Yes" else "No") else emptyList()) +
                (if (hasInitial) listOf(row.initialDefault ?: "none") else emptyList()) +
                (if (hasWrite) listOf(row.writeDefault ?: "none") else emptyList())
        },
    )
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
                    is GraphNode.SnapshotNode -> SnapshotPanel(node, currentGraph, children, scanFilter)
                    is GraphNode.ManifestNode -> ManifestPanel(node, currentGraph, scanFilter)
                    is GraphNode.FileNode -> FilePanel(node, currentGraph)
                    is GraphNode.RowNode -> RowPanel(node, currentGraph)
                    is GraphNode.ErrorNode -> ErrorPanel(node)
                    is GraphNode.PaimonSnapshotNode -> PaimonSnapshotPanel(node, currentGraph, scanFilter)
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

/**
 * The newest drawn, unexpired snapshot listing a manifest that holds [node] — the scope a delete
 * file's targets are counted in, since which data files a delete reaches is a question about
 * one commit. The graph's structural edges are walked rather than the model, so the answer is
 * about a snapshot the reader can see; a manifest carried forward is listed by every later
 * snapshot, and the newest is where the delete is still live, if it is anywhere.
 */
internal fun newestSnapshotListing(node: GraphNode.FileNode, graph: GraphModel): GraphNode.SnapshotNode? {
    val nodeById = graph.nodeById
    val manifestIds = graph.edges.asSequence().filter { it.toId == node.id }.map { it.fromId }
        .filter { nodeById[it] is GraphNode.ManifestNode }.toSet()
    if (manifestIds.isEmpty()) return null
    return graph.edges.asSequence().filter { it.toId in manifestIds }
        .mapNotNull { nodeById[it.fromId] as? GraphNode.SnapshotNode }
        .filter { !it.expired && it.readInput.isPresent }
        .maxWithOrNull(compareBy({ it.data.sequenceNumber ?: -1L }, { it.data.timestampMs ?: 0L }, { it.data.snapshotId ?: 0L }))
}

/**
 * [PositionalDeleteTargets]' twin for an equality delete, which names no target at all: the
 * file is read against every data file the snapshot's pairing leaves for it and the matching rows
 * counted per file ([EqualityDeleteTargets]). Same shape — a button, the read on IO, [startRequested]
 * and [onSettled] for the capture — and the answer leads with whether the delete still removes
 * anything, since a delete every candidate answers zero for is the equality kind's dangling.
 */
@Composable
internal fun EqualityDeleteTargetsSection(
    node: GraphNode.FileNode,
    graph: GraphModel,
    startRequested: Boolean = false,
    onSettled: () -> Unit = {},
) {
    val colors = MaterialTheme.colorScheme
    val deleteKey = node.data.ledgerKey() ?: return
    val snapshot = remember(node.id, graph.nodes) { newestSnapshotListing(node, graph) }
    var requested by remember(node.id) { mutableStateOf(startRequested) }
    val outcome by produceState<Result<EqualityDeleteTargets.Result?>?>(null, node.id, requested) {
        value = null
        if (requested) {
            value = withContext(Dispatchers.IO) {
                runCatching {
                    val input = snapshot?.readInput?.value ?: throw IllegalStateException("the snapshot's files could not be read")
                    EqualityDeleteTargets.count(input, deleteKey)
                }
            }
            onSettled()
        }
    }

    Spacer(Modifier.height(8.dp))
    if (snapshot == null) {
        Text(
            "No drawn snapshot lists this file's manifest, so there is no scope to count its targets in — " +
                "expand the snapshots above, or draw the whole table.",
            fontSize = TypeScale.small,
            color = colors.onSurfaceVariant,
        )
        return
    }
    val scope = "snapshot ${snapshot.data.snapshotId}, the newest drawn listing this file's manifest"
    when {
        !requested -> {
            OutlinedButton(onClick = { requested = true }) {
                Text("Read the file — which data files hold rows it matches, and how many each")
            }
            Text(
                "Counted at $scope: the delete against every data file the pairing leaves for it there.",
                fontSize = TypeScale.small,
                color = colors.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
        outcome == null -> Text(
            "Reading ${fileNameFromPath(node.localPath.orEmpty())} against its candidates at $scope…",
            fontSize = TypeScale.small,
            color = colors.onSurfaceVariant,
        )
        else -> outcome?.fold(
            onSuccess = { result ->
                if (result == null) {
                    Text(
                        "Not live at $scope: that commit's pairing lists no such delete file, so it applies to nothing there.",
                        fontSize = TypeScale.small,
                        color = colors.onSurfaceVariant,
                    )
                    return@fold
                }
                val candidates = result.files.size
                val headline = when {
                    candidates == 0 -> "The pairing leaves no data file for it at $scope — dangling by the metadata alone " +
                        "(sequence, partition or bounds ruled every live file out), nothing read."
                    result.removesNothing == true -> "Removes nothing at $scope: ${formatCounted(candidates, "candidate data file")} " +
                        "read, none holds a row this delete matches — dangling, which only the rows could say."
                    else -> "${formatCount(result.matched)} ${if (result.matched == 1L) "row" else "rows"} across " +
                        "${formatCount(result.filesWithMatches.toLong())} of ${formatCounted(candidates, "candidate data file")} " +
                        "match this delete at $scope" +
                        (if (result.failed > 0) "; ${formatCounted(result.failed, "file")} could not be read" else "") +
                        (if (result.filesLeft > 0) "; ${formatCounted(result.filesLeft, "candidate")} left unopened by the cap of ${EqualityDeleteTargets.MAX_FILES}" else "") +
                        "."
                }
                Text(
                    headline,
                    fontSize = TypeScale.small,
                    color = if (result.removesNothing == true || candidates == 0) colors.error else colors.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 4.dp),
                )
                if (result.files.isNotEmpty()) {
                    WideTable(
                        headers = listOf("Rows matched", "Data file", "Records", "Note"),
                        columnWidths = listOf(110.dp, 380.dp, 90.dp, 300.dp),
                        rows = result.files.map { match ->
                            listOf(
                                match.matched?.let { formatCount(it) } ?: "not read",
                                fileNameFromPath(match.file.recordedPath),
                                match.file.recordCount?.let { formatCount(it) } ?: "N/A",
                                match.error ?: if ((match.matched ?: 0L) == 0L) "no row matches: the metadata could not rule it out, the rows do" else "",
                            )
                        },
                        leadCellColors = result.files.map { if (it.error != null) colors.error else null },
                    )
                }
            },
            onFailure = { failure ->
                Text(
                    "Could not count: ${failure.message ?: failure::class.simpleName}",
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
        DeletionVectorBody(vector, "the referenced data file")
    }
}

/**
 * The Paimon twin: the vector the latest index manifest naming this file records for it, decoded
 * from the index file on first use. The same figures and the same list as the Iceberg section,
 * because it is the same answer — the rows a scan drops from this file without the file having
 * changed — reached through a different container.
 */
@Composable
internal fun PaimonDeletionVectorSection(node: GraphNode.PaimonDataFileNode) {
    val range = node.vectorRange ?: return
    val colors = MaterialTheme.colorScheme
    val vector = node.deletionVector.value

    if (vector == null) {
        Section("Deleted Rows") {
            Text(
                "The index manifest records a vector for this file in ${range.indexFileName} " +
                    "(${formatCount(range.cardinality ?: 0L)} rows), and it could not be read — " +
                    "the index file may have moved since the manifest recorded it.",
                fontSize = TypeScale.small,
                color = colors.onSurfaceVariant,
            )
        }
        return
    }

    Section("Deleted Rows (${formatCount(vector.cardinality)})") {
        DeletionVectorBody(
            vector, "this file",
            leadingRows = listOf(
                "Index File" to "${range.indexFileName}, at offset ${range.offset} for ${formatBytesExact(range.length)}",
            ),
        )
    }
}

/**
 * The decoded vector against the two things recorded about it, then its positions run-folded.
 * [target] is what the positions count from, as the caption names it.
 */
@Composable
private fun DeletionVectorBody(vector: DeletionVector, target: String, leadingRows: List<Pair<String, String>> = emptyList()) {
    val colors = MaterialTheme.colorScheme
    DetailTable {
        DetailRow("Property", "Value", isHeader = true)
        leadingRows.forEach { (key, value) -> DetailRow(key, value) }
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
                "and count from the start of $target."
        } else {
            "Row positions, zero-based, counting from the start of $target."
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

    val reaching = candidates.filter { it.verdict == DeleteReachVerdict.REACHES || it.verdict == DeleteReachVerdict.MAY_REACH }
    CountedSection("Deletes Reaching This File", reaching.size, "delete files") {
        Text(
            "Of the ${formatCount(candidates.size.toLong())} delete files drawn for this table, " +
                "these are the ones a scan could pair with this data file — by sequence number, by " +
                "spec and partition, by the paths each records about itself and, for an equality " +
                "delete, by the bounds on its columns, none of which needs a file opened. The " +
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
                        // A delete is keyed by the spec and partition it was written under; only an
                        // equality delete under an unpartitioned spec is weighed against every file.
                        DeleteReachVerdict.RULED_OUT_BY_PARTITION ->
                            "it is keyed to ${describeScope(candidate.delete)}; this file is in ${describeScope(node)}"
                        DeleteReachVerdict.RULED_OUT_BY_BOUNDS ->
                            "its bounds on the equality columns do not overlap this file's"
                        DeleteReachVerdict.MAY_REACH -> when (candidate.kind) {
                            DeleteFileKind.EQUALITY ->
                                "it records no target and its bounds overlap this file's; only reading it settles this"
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

/** `p=y under spec 1`, or `the unpartitioned spec 0` — the key a scan files a delete under. */
private fun describeScope(node: GraphNode.FileNode): String {
    val partition = node.partition?.path
    return if (partition.isNullOrEmpty()) "the unpartitioned spec ${node.specId}" else "$partition under spec ${node.specId}"
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
/** The append-table side of [PaimonCompactionSection]: what `sys.compact` would pack, per partition. */
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
            columnWidths = listOf(160.dp, 70.dp, 90.dp, 300.dp, 260.dp, 300.dp),
            rows = files.map { file ->
                val ranges = file.deletionVectorRanges?.filterNotNull().orEmpty()
                listOf(
                    file.indexType ?: "N/A",
                    "${file.bucket ?: "N/A"}",
                    formatCount(file.rowCount ?: 0L),
                    // `_FILE_SIZE` against the file — the `manifest_length` rule; the file is what the expiry plan charges.
                    recordedAgainstFile(file.fileSize, file.fileName?.let { node.sizesOnDisk.indexFiles[it] }),
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
 * The Iceberg metadata a Paimon table writes beside its own, against the table — see
 * [IcebergExportCheck]. Behind a click for the same reason the orphan walk is: it is a second
 * table's read. The headline says whether an Iceberg reader is looking at the latest commit,
 * and how many of the live files it sees; on a primary-key table the level rule is stated
 * rather than left as a count that disagrees, since between compactions it leaves most files
 * out by design.
 */
@Composable
internal fun IcebergExportSection(
    node: GraphNode.TableNode,
    startRequested: Boolean = false,
    onSettled: () -> Unit = {},
) {
    val colors = MaterialTheme.colorScheme
    if (!node.icebergExport.isPresent) return

    var requested by remember(node.id) { mutableStateOf(startRequested) }
    val outcome by produceState<Result<IcebergExportCheck>?>(null, node.id, requested) {
        value = null
        if (requested) {
            value = withContext(Dispatchers.IO) {
                runCatching { requireNotNull(node.icebergExport.value) { "no Iceberg metadata under metadata/" } }
            }
            onSettled()
        }
    }
    val check = outcome?.getOrNull()
    val agrees = check?.agrees == true
    val title = "Iceberg Metadata" + when {
        check == null -> ""
        agrees -> " — current"
        else -> " — disagrees"
    }

    Section(title) {
        // The number leads once it exists and the explanation follows it, the
        // `UnreferencedFilesSection` rule: a reader who has not clicked needs the sentence, and one
        // who has needs the answer first.
        val intro = "metadata.iceberg.storage = table-location: every commit also writes Iceberg metadata " +
            "under metadata/, so an Iceberg reader opens this table's data files as an Iceberg table. What " +
            "that reader sees is what the export says, checked here against the table itself."
        when {
            !requested -> {
                Text(intro, fontSize = TypeScale.small, color = colors.onSurfaceVariant, modifier = Modifier.padding(bottom = 8.dp))
                OutlinedButton(onClick = { requested = true }) { Text("Read the Iceberg metadata") }
            }
            outcome == null -> Text("Reading the Iceberg metadata…", fontSize = TypeScale.small, color = colors.onSurfaceVariant)
            check == null -> Text(
                "Could not read: ${outcome?.exceptionOrNull()?.message ?: "unknown error"}",
                fontSize = TypeScale.small,
                color = colors.error,
            )
            else -> {
                Text(
                    check.describe.replaceFirstChar { it.uppercase() } + ".",
                    fontSize = TypeScale.small,
                    fontWeight = FontWeight.Bold,
                    color = if (agrees) colors.onSurface else colors.error,
                    modifier = Modifier.padding(bottom = 4.dp),
                )
                if (check.belowExportedLevel.isNotEmpty()) {
                    Text(
                        "${formatCounted(check.belowExportedLevel.size, "live file")} ${if (check.belowExportedLevel.size == 1) "is" else "are"} not exported by rule: a primary-key table's export lists " +
                            (if (check.aboveLevelZero) "files above level 0 only, with the deletion vectors as Iceberg's" else "files at the highest level (${check.exportedLevel}) only") +
                            ", since an Iceberg reader merges nothing — a full compaction moves them there.",
                        fontSize = TypeScale.small,
                        color = verdictUnevaluatedColor(),
                        modifier = Modifier.padding(bottom = 4.dp),
                    )
                }
                if (check.exportedBelowLevel.isNotEmpty()) {
                    Text(
                        "${formatCounted(check.exportedBelowLevel.size, "live file")} below that level " +
                            "${if (check.exportedBelowLevel.size == 1) "is" else "are"} in the export all the same: a commit " +
                            "that finds no metadata for the snapshot before it rebuilds the export from the snapshot, " +
                            "listing every file a read returns without merging.",
                        fontSize = TypeScale.small,
                        color = verdictUnevaluatedColor(),
                        modifier = Modifier.padding(bottom = 4.dp),
                    )
                }
                if (check.paimonVectors.isNotEmpty()) {
                    Text(
                        if (check.vectorsExported) {
                            "Each exported vector is a puffin delete file whose container is the table's own index file, at the " +
                                "range the index manifest records and with its cardinality — a 64-bit vector's blob is Iceberg's own layout, " +
                                "so an Iceberg reader opens it as written."
                        } else {
                            "The table's ${formatCounted(check.paimonVectors.size, "deletion vector")} ${if (check.paimonVectors.size == 1) "is" else "are"} not " +
                                "exported: Paimon writes them as Iceberg's only under deletion-vectors.bitmap64 with " +
                                "metadata.iceberg.format-version = 3, so an Iceberg reader of this export sees the rows they mark as live."
                        },
                        fontSize = TypeScale.small,
                        color = if (check.vectorsExported) colors.onSurfaceVariant else verdictUnevaluatedColor(),
                        modifier = Modifier.padding(bottom = 4.dp),
                    )
                }
                check.readErrors.forEach { Text("Could not read ${fileNameFromPath(it.path)}: ${it.message}", fontSize = TypeScale.small, color = colors.error) }
                check.missingFromIceberg.take(MAX_EXPORT_ROWS).forEach { Text("Live here, not in the export: $it", fontSize = TypeScale.small, color = colors.error) }
                check.extraInIceberg.take(MAX_EXPORT_ROWS).forEach { Text("In the export, not live here: $it", fontSize = TypeScale.small, color = colors.error) }
                check.vectorsMissingFromIceberg.take(MAX_EXPORT_ROWS).forEach { Text("Vector here, not in the export: $it", fontSize = TypeScale.small, color = colors.error) }
                check.vectorsExtraInIceberg.take(MAX_EXPORT_ROWS).forEach { Text("Vector in the export, none here: $it", fontSize = TypeScale.small, color = colors.error) }
                check.vectorsDisagreeing.take(MAX_EXPORT_ROWS).forEach {
                    Text("Vector recorded differently: $it — the export says ${check.icebergVectors[it]}, the index manifest ${check.paimonVectors[it]}", fontSize = TypeScale.small, color = colors.error)
                }
                val more = listOf(check.missingFromIceberg, check.extraInIceberg, check.vectorsMissingFromIceberg, check.vectorsExtraInIceberg, check.vectorsDisagreeing)
                    .sumOf { (it.size - MAX_EXPORT_ROWS).coerceAtLeast(0) }
                if (more > 0) Text("…and ${formatCount(more)} more.", fontSize = TypeScale.small, color = colors.onSurfaceVariant)
                Text(intro, fontSize = TypeScale.small, color = colors.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
            }
        }
    }
}

/** Disagreeing files named before the rest are counted. */
private const val MAX_EXPORT_ROWS = 20

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
    val nowMs = expiryClock()

    Section(title) {
        // The caveat about what "referenced" means goes under the answer, not above it: a reader
        // who has not clicked needs one sentence, and one who has needs the number first.
        val caveat = "Referenced means named by any metadata version on disk. Iceberg's " +
            "remove_orphan_files reaches from the current one only and can delete more than is " +
            "listed here — such files are planned below as named by an older version. Paimon tags, " +
            "branches and consumers are followed."
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
                val plan = planOrphanRemoval(report, nowMs)
                if (plan.rows.isNotEmpty()) {
                    // The procedure's own verdict leads: a reader who ran remove_orphan_files and
                    // saw it delete nothing needs "younger than the cutoff" before the file name.
                    val heldBack = listOfNotNull(
                        plan.tooYoung.takeIf { it > 0 }?.let { "${formatCounted(it, "file")} younger than the cutoff" },
                        plan.unlisted.takeIf { it > 0 }?.let { "${formatCounted(it, "file")} in a place it never lists" },
                    )
                    Text(
                        "A bare remove_orphan_files now (older_than = ${plan.defaultIntervalText} ago, the default) would " +
                            "delete ${formatCounted(plan.removed.size, "file")} (${formatBytes(plan.removedBytes)})" +
                            (if (heldBack.isEmpty()) "." else "; held back: ${heldBack.joinToString(", ")}.") +
                            (if (report.unreachedFromCurrent.isNotEmpty()) {
                                " Among them are ${formatCounted(report.unreachedFromCurrent.size, "file")} the metadata names: " +
                                    "only an older metadata version, or a DELETED entry, names them, and the procedure reads neither."
                            } else {
                                ""
                            }),
                        fontSize = TypeScale.small,
                        modifier = Modifier.padding(top = 4.dp, bottom = 4.dp),
                    )
                    WideTable(
                        headers = listOf("Verdict", "File", "Size", "Modified", "Why"),
                        columnWidths = listOf(110.dp, 520.dp, 90.dp, 170.dp, 560.dp),
                        rows = plan.rows.take(MAX_UNREFERENCED_ROWS).map { row ->
                            listOf(
                                when (row.fate) {
                                    OrphanFate.REMOVED -> "REMOVED"
                                    OrphanFate.TOO_YOUNG -> "too young"
                                    OrphanFate.UNLISTED -> "never listed"
                                },
                                row.relativePath,
                                formatBytes(row.file.sizeBytes),
                                formatAppTimestamp(row.file.modifiedMs),
                                row.reason,
                            )
                        },
                        leadCellColors = plan.rows.take(MAX_UNREFERENCED_ROWS).map { row ->
                            when (row.fate) {
                                OrphanFate.REMOVED -> colors.error
                                OrphanFate.TOO_YOUNG -> null
                                OrphanFate.UNLISTED -> verdictUnevaluatedColor()
                            }
                        },
                    )
                    if (plan.rows.size > MAX_UNREFERENCED_ROWS) {
                        Text(
                            "…and ${formatCount(plan.rows.size - MAX_UNREFERENCED_ROWS)} more.",
                            fontSize = TypeScale.small,
                            color = colors.onSurfaceVariant,
                        )
                    }
                    Text(
                        if (report.format == service.TableFormat.ICEBERG) {
                            "The procedure refuses an older_than inside the last 24 hours, so the youngest files " +
                                "a call can remove are a day old; it lists the whole table location and skips names " +
                                "starting with _ or ."
                        } else {
                            "The procedure refuses an older_than in the future; it lists manifest/, index/, " +
                                "statistics/, the bucket directories and the snapshot/ and changelog/ directories, " +
                                "and nothing else."
                        },
                        fontSize = TypeScale.small,
                        color = colors.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp),
                    )
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

/**
 * What changed from each schema to the next, by field id, with the first snapshot written
 * under it — see [model.schemaEvolution]. Format-agnostic: the steps ride `TableNode.schemaEvolution`,
 * filled by both builders from the model, never from the drawn metadata nodes, which aggregation
 * folds past the page size. One table for every step, because the reader's question is "when
 * was this column renamed", which a table per step answers only by scrolling; the first schema
 * is a line, since its "changes" are the columns it started with. The change column marks the
 * two kinds that can lose a reader data — a drop, and a type change that is not a promotion the
 * format allows — and leaves the rest at body weight.
 */
@Composable
internal fun SchemaEvolutionSection(steps: List<SchemaStep>) {
    if (steps.isEmpty()) return
    val colors = MaterialTheme.colorScheme
    val first = steps.first()
    val later = steps.drop(1)
    val changes = later.sumOf { it.changes.size }
    Section("Schema Evolution (${formatCounted(steps.size, "schema")})") {
        Text(
            "Schema ${first.toId} started with ${formatCounted(first.changes.size, "column")}" +
                (if (later.isEmpty()) " and is the only schema." else "; ${formatCounted(changes, "change")} across ${formatCounted(later.size, "later schema")}, each by field id — a rename keeps the id, a drop and an add do not.") +
                (first.firstSnapshotId?.let { " First written by snapshot $it." } ?: ""),
            fontSize = TypeScale.small,
            color = colors.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 8.dp),
        )
        if (later.isNotEmpty()) {
            val rows = later.flatMap { step -> step.changes.map { step to it } }
            WideTable(
                headers = listOf("Change", "Column", "Detail", "Schema", "First Written By"),
                columnWidths = listOf(150.dp, 200.dp, 320.dp, 130.dp, 420.dp),
                rows = rows.map { (step, change) ->
                    listOf(
                        change.kind.label,
                        change.column.ifEmpty { "—" },
                        change.detail,
                        step.label,
                        step.firstSnapshotId?.let { id ->
                            "snapshot $id" + (step.firstSnapshotOperation?.let { " ($it" } ?: "") +
                                (step.firstSnapshotTimestampMs?.let { ", ${formatAppTimestamp(it)}" } ?: "") + (if (step.firstSnapshotOperation != null) ")" else "")
                        } ?: "no snapshot written under it",
                    )
                },
                leadCellColors = rows.map { (_, change) ->
                    when (change.kind) {
                        SchemaChangeKind.DROPPED -> colors.error
                        SchemaChangeKind.TYPE_CHANGED -> if (isPromotion(change.detail)) null else colors.error
                        else -> null
                    }
                },
            )
        }
    }
}

/**
 * One step on its own schema's panel — what this schema changed from the one before it, and the
 * first snapshot written under it — the same rows the table's section lists for every step.
 */
@Composable
internal fun SchemaStepSection(step: SchemaStep) {
    val colors = MaterialTheme.colorScheme
    val written = step.firstSnapshotId?.let { id -> "First written by snapshot $id" + (step.firstSnapshotOperation?.let { " ($it)" } ?: "") + "." } ?: "No snapshot written under it."
    if (step.fromId == null) {
        Section("Changes") {
            Text("The first schema: ${formatCounted(step.changes.size, "column")}. $written", fontSize = TypeScale.small, color = colors.onSurfaceVariant)
        }
        return
    }
    Section("Changes from schema ${step.fromId} (${formatCount(step.changes.size)})") {
        Text(written, fontSize = TypeScale.small, color = colors.onSurfaceVariant, modifier = Modifier.padding(bottom = 8.dp))
        WideTable(
            headers = listOf("Change", "Column", "Detail"),
            columnWidths = listOf(150.dp, 200.dp, 420.dp),
            rows = step.changes.map { listOf(it.kind.label, it.column.ifEmpty { "—" }, it.detail) },
            leadCellColors = step.changes.map { if (it.kind == SchemaChangeKind.DROPPED) colors.error else null },
        )
    }
}

/** The type changes Iceberg's spec allows on a column: a widening, and a decimal's precision growing. */
private fun isPromotion(detail: String): Boolean {
    val (from, to) = detail.split(" → ").takeIf { it.size == 2 } ?: return false
    return (from == "int" && to == "long") || (from == "float" && to == "double") ||
        (from.startsWith("decimal(") && to.startsWith("decimal(") && from.substringAfter(", ") == to.substringAfter(", "))
}

// --- Properties Evolution ---

/**
 * The table's properties as the newest version has them, and every change between two
 * versions — from the model's versions ([MetadataVersionInfo.properties]), never the drawn
 * metadata nodes, which aggregation folds past the page size and which would then lose the
 * early changes. A key set, changed or removed is one row of the changes table, naming the two
 * versions it happened between.
 */
@Composable
internal fun PropertiesEvolutionSection(versions: List<MetadataVersionInfo>) {
    if (versions.isEmpty()) return
    val labelOf = { v: MetadataVersionInfo -> v.version?.let { "v$it" } ?: v.fileName }
    val allKeys = versions.flatMap { it.properties.keys }.toSortedSet()
    if (allKeys.isEmpty()) return
    val colors = MaterialTheme.colorScheme
    val latest = versions.last().properties
    data class PropChange(val key: String, val from: String, val to: String, val oldValue: String?, val newValue: String?)
    val changes = versions.zipWithNext().flatMap { (older, newer) ->
        (older.properties.keys union newer.properties.keys).sorted().mapNotNull { key ->
            val old = older.properties[key]
            val new = newer.properties[key]
            if (old == new) null else PropChange(key, labelOf(older), labelOf(newer), old, new)
        }
    }
    // Counted by every key any version set, so a table whose properties were all removed still lists them as removed.
    CountedSection("Table Properties", allKeys.size, "properties") {
        if (versions.size > 1) {
            Text(
                "As ${labelOf(versions.last())} has them" +
                    (if (changes.isEmpty()) ", unchanged across ${formatCounted(versions.size, "metadata version")}." else "; ${formatCounted(changes.size, "change")} across ${formatCounted(versions.size, "metadata version")} below."),
                fontSize = TypeScale.small,
                color = colors.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 8.dp),
            )
        }
        // A wide table rather than a detail table: a key is the table's own vocabulary and wrapped at the detail table's label width.
        WideTable(
            headers = listOf("Key", "Value"),
            columnWidths = listOf(260.dp, 480.dp),
            rows = allKeys.map { key ->
                val current = latest[key]
                listOf(key, when {
                    current == null -> "(removed)"
                    changes.any { it.key == key } -> "$current (changed)"
                    else -> current
                })
            },
        )
        if (changes.isNotEmpty()) {
            Text(
                "Property Changes",
                fontWeight = FontWeight.SemiBold,
                fontSize = TypeScale.small,
                modifier = Modifier.padding(top = 8.dp, bottom = 4.dp),
            )
            WideTable(
                headers = listOf("Property", "From", "To", "Old Value", "New Value"),
                columnWidths = listOf(240.dp, 80.dp, 80.dp, 260.dp, 260.dp),
                rows = changes.map { listOf(it.key, it.from, it.to, it.oldValue ?: "(not set)", it.newValue ?: "(removed)") },
            )
        }
    }
}

/**
 * A file's partition tuple against its own column bounds — the one pair of recorded figures
 * about a file that no read path compares, and the one that decides which files a scan
 * opens. Metadata only, drawn at once under the partition on both file panels; a
 * disagreement is the file a partition filter skips for the value its rows hold.
 */
@Composable
internal fun MetricsModesSection(node: GraphNode.FileNode) {
    val colors = MaterialTheme.colorScheme
    val checks = node.metricsModes
    val at = node.metricsConfig ?: return
    if (checks.isEmpty()) return
    val differing = checks.count { it.agrees == false }
    Section("Metrics Modes" + if (differing > 0) " — $differing differ" else "") {
        Text(
            "Which statistics the writer records for a column is `write.metadata.metrics.*`, read " +
                "here as of ${at.source}: the default is truncate(16) on every column, a table past " +
                "${METRICS_MAX_INFERRED_DEFAULT} columns records nothing for the rest unless told to, " +
                "and a column with no bounds is one no filter can skip this file on. Each column's " +
                "mode, what set it, and whether the file records that shape.",
            fontSize = TypeScale.small,
            color = colors.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 4.dp),
        )
        WideTable(
            headers = listOf("Agrees", "Column", "Mode", "Recorded", "Set By", "Why"),
            columnWidths = listOf(100.dp, 170.dp, 110.dp, 150.dp, 420.dp, 420.dp),
            leadCellColors = checks.map {
                when (it.agrees) {
                    false -> colors.error
                    null -> verdictUnevaluatedColor()
                    true -> null
                }
            },
            rows = checks.map { c ->
                listOf(
                    when (c.agrees) {
                        true -> "yes"
                        false -> "NO"
                        null -> "not judged"
                    },
                    c.column,
                    c.configured.mode.spelled,
                    c.recorded,
                    c.configured.setBy,
                    c.reason,
                )
            },
        )
    }
}

@Composable
internal fun PaimonStatsModesSection(node: GraphNode.PaimonDataFileNode) {
    val colors = MaterialTheme.colorScheme
    val checks = node.statsModes
    if (checks.isEmpty()) return
    val differing = checks.count { it.agrees == false }
    val level = node.entry.file?.let { model.paimonStatsWriteLevel(it) }
    Section("Stats Modes" + if (differing > 0) " — $differing differ" else "") {
        Text(
            "Which statistics the writer records for a column is `metadata.stats-mode` and its " +
                "siblings, read here from the schema this file names (schema ${node.entry.file?.schemaId ?: "?"}" +
                (level?.let { ", the mode for level $it, the level the file was written to" } ?: "") + "): " +
                "the default is truncate(16) on every value column, a system column is truncate(128) " +
                "whatever the table says, and a column with no bounds is one no filter can skip this " +
                "file on. Each column's mode, what set it, and whether the file records that shape.",
            fontSize = TypeScale.small,
            color = colors.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 4.dp),
        )
        WideTable(
            headers = listOf("Agrees", "Column", "Mode", "Recorded", "Set By", "Why"),
            columnWidths = listOf(100.dp, 170.dp, 110.dp, 150.dp, 420.dp, 420.dp),
            leadCellColors = checks.map {
                when (it.agrees) {
                    false -> colors.error
                    null -> verdictUnevaluatedColor()
                    true -> null
                }
            },
            rows = checks.map { c ->
                listOf(
                    when (c.agrees) {
                        true -> "yes"
                        false -> "NO"
                        null -> "not judged"
                    },
                    c.column,
                    c.configured.mode.spelled,
                    c.recorded,
                    c.configured.setBy,
                    c.reason,
                )
            },
        )
    }
}

@Composable
internal fun PartitionBoundsSection(checks: List<PartitionFieldCheck>) {
    val colors = MaterialTheme.colorScheme
    val disagreeing = checks.count { it.verdict == PartitionFieldVerdict.DISAGREES }
    Section("Partition Against Bounds" + if (disagreeing > 0) " — $disagreeing disagree" else "") {
        Text(
            "A scan prunes on the partition before it looks at a bound, so a file registered under " +
                "the wrong partition is skipped for the value its rows hold, with nothing failing. " +
                "Every row's source value transforms to the partition value, so both bounds must — " +
                "exactly for a number or a date; a string bound is truncated, so the value is held " +
                "to lie within it; a bucket only where the bounds are one value.",
            fontSize = TypeScale.small,
            color = colors.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 4.dp),
        )
        WideTable(
            headers = listOf("Verdict", "Field", "Recorded", "From Bounds", "Transform", "Source", "Why"),
            columnWidths = listOf(100.dp, 130.dp, 150.dp, 190.dp, 110.dp, 120.dp, 420.dp),
            leadCellColors = checks.map {
                when (it.verdict) {
                    PartitionFieldVerdict.DISAGREES -> colors.error
                    PartitionFieldVerdict.NOT_CHECKED -> verdictUnevaluatedColor()
                    PartitionFieldVerdict.AGREES -> null
                }
            },
            rows = checks.map { c ->
                listOf(
                    when (c.verdict) {
                        PartitionFieldVerdict.AGREES -> "agrees"
                        PartitionFieldVerdict.DISAGREES -> "DISAGREES"
                        PartitionFieldVerdict.NOT_CHECKED -> "not checked"
                    },
                    c.field,
                    c.recorded,
                    c.fromBounds ?: "N/A",
                    c.transform.ifEmpty { "N/A" },
                    c.source,
                    c.reason,
                )
            },
        )
    }
}

/**
 * A metadata file's own figures against the same figures folded from its contents — on the
 * Iceberg metadata panel, the Paimon schema panel and the Paimon snapshot panel. The rule
 * column says what each figure decides, because a disagreement here is not a wrong panel: it
 * is a table the engine refuses to open, or one whose next DDL hands an id out twice.
 */
@Composable
internal fun MetadataTalliesSection(tallies: List<MetadataTally>) {
    val colors = MaterialTheme.colorScheme
    val disagreeing = tallies.count { it.agrees == false }
    Section("Recorded Figures" + if (disagreeing > 0) " — $disagreeing disagree" else "") {
        Text(
            "Each figure the file records about itself, beside the same figure folded from its " +
                "contents. Some a reader refuses the table on; the ids the next DDL allocates from " +
                "are checked by nothing, and a short one gives a new column or partition field an id " +
                "already in use.",
            fontSize = TypeScale.small,
            color = colors.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 4.dp),
        )
        WideTable(
            headers = listOf("Agrees", "Figure", "Recorded", "Folded", "What it decides"),
            columnWidths = listOf(110.dp, 170.dp, 170.dp, 190.dp, 620.dp),
            leadCellColors = tallies.map {
                when (it.agrees) {
                    true -> null
                    false -> colors.error
                    null -> verdictUnevaluatedColor()
                }
            },
            rows = tallies.map { t ->
                listOf(
                    when (t.agrees) {
                        true -> "yes"
                        false -> "NO"
                        null -> "nothing to check"
                    },
                    t.label,
                    t.recorded,
                    t.counted,
                    t.consequence,
                )
            },
        )
    }
}

/**
 * What the retained snapshots need that is not there — [findMissingFiles], behind a click,
 * beside the walk it is the converse of. A missing data file draws no rows and fails no read
 * until a query opens it, so the table panel is where it has to be findable; each one is
 * listed with the snapshots that read it, which is what says whether an expiry would have
 * freed it or a query will hit it.
 */
@Composable
internal fun MissingFilesSection(
    node: GraphNode.TableNode,
    startRequested: Boolean = false,
    onSettled: () -> Unit = {},
) {
    val colors = MaterialTheme.colorScheme
    if (!node.missingFiles.isPresent) return

    var requested by remember(node.id) { mutableStateOf(startRequested) }
    val outcome by produceState<Result<MissingFilesReport>?>(null, node.id, requested) {
        value = null
        if (requested) {
            value = withContext(Dispatchers.IO) { runCatching { requireNotNull(node.missingFiles.value) { "no report" } } }
            onSettled()
        }
    }
    val report = outcome?.getOrNull()
    val title = if (report != null) "Missing Files (${formatCount(report.missing.size)})" else "Missing Files"

    Section(title) {
        when {
            !requested -> {
                Text(
                    "Files the retained snapshots name that are not there — a data file an orphan " +
                        "cleanup or a hand deletion took, a manifest list an expiry lost. A missing data " +
                        "file draws no rows and fails nothing until a query opens it. One stat per file " +
                        "the retained snapshots need.",
                    fontSize = TypeScale.small,
                    color = colors.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
                OutlinedButton(onClick = { requested = true }) { Text("Check that every needed file is there") }
            }
            outcome == null -> Text("Checking the files the retained snapshots need…", fontSize = TypeScale.small, color = colors.onSurfaceVariant)
            report == null -> Text(
                "Could not check: ${outcome?.exceptionOrNull()?.message ?: "unknown error"}",
                fontSize = TypeScale.small,
                color = colors.error,
            )
            else -> {
                val needed = "${formatCounted(report.needed, "file")} the ${formatCounted(report.snapshotsChecked, "retained snapshot")} need"
                Text(
                    if (report.missing.isEmpty()) "Every one of the $needed is there." else "${formatCounted(report.missing.size, "file")} of the $needed ${if (report.missing.size == 1) "is" else "are"} not there.",
                    fontSize = TypeScale.small,
                    fontWeight = FontWeight.Bold,
                    color = if (report.missing.isEmpty()) colors.onSurface else colors.error,
                    modifier = Modifier.padding(bottom = 4.dp),
                )
                if (report.missing.isNotEmpty()) {
                    WideTable(
                        headers = listOf("Kind", "File", "Read By"),
                        columnWidths = listOf(110.dp, 520.dp, 400.dp),
                        leadCellColors = report.missing.map { colors.error },
                        rows = report.missing.take(MAX_UNREFERENCED_ROWS).map { file ->
                            listOf(file.kind.label, report.relativePathOf(file), file.neededBy.joinToString(", "))
                        },
                    )
                    if (report.missing.size > MAX_UNREFERENCED_ROWS) {
                        Text("…and ${formatCount(report.missing.size - MAX_UNREFERENCED_ROWS)} more.", fontSize = TypeScale.small, color = colors.onSurfaceVariant)
                    }
                }
                Text(
                    "Needed means live in a snapshot the newest metadata retains, or under Paimon's snapshot/, a tag or a branch — " +
                        "the files only an expired snapshot listed are gone by design and not counted, nor are older metadata versions.",
                    fontSize = TypeScale.small,
                    color = colors.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        }
    }
}
