package ui

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FirstPage
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.UnfoldLess
import androidx.compose.material.icons.filled.UnfoldMore
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import service.PositionalDeleteTally
import service.SampleRowReader
import model.DeleteCandidate
import model.UnreferencedFilesReport
import model.DeleteReachVerdict
import model.deleteCandidatesFor
import model.deleteKindOf
import model.deleteTargetsOf
import model.DeleteFileKind
import model.ManifestContent
import model.effectiveMinSequenceNumber
import model.effectiveSequenceNumber
import model.DataFile
import model.statisticsRows
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
import model.ManifestEntryStatus
import model.ScanPredicate
import model.TermEffect
import model.evaluatePruning
import model.SnapshotChange
import model.snapshotTotals
import model.FileChange
import model.manifestTallies
import model.MAIN_BRANCH
import model.PaimonFileSource
import model.paimonManifestTallies
import model.KeyValuePairLong
import model.MetadataLogEntry
import model.SnapshotLogEntry
import model.TableSchema
import model.TableSchemaField
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

private fun normalizeText(value: String?): String {
    if (value == null) return "N/A"
    return value
        .replace("\n", "\\n")
        .replace("\r", "\\r")
        .replace("\t", "\\t")
}

private fun kvLongs(values: List<KeyValuePairLong>?): String =
    if (values.isNullOrEmpty()) "[]" else values.joinToString(", ") { "${it.key}:${it.value}" }

private fun ByteArray.toHexShort(maxBytes: Int = 24): String {
    val head = take(maxBytes).joinToString("") { byte -> "%02x".format(byte) }
    return if (size > maxBytes) "$head..." else head
}

private fun kvBytes(values: List<KeyValuePairBytes>?): String =
    if (values.isNullOrEmpty()) "[]" else values.joinToString(", ") { "${it.key}:${it.value.toHexShort()}" }

/**
 * A decoded bound for a table cell. A value that failed to decode shows its hex with the reason
 * appended rather than a bare "N/A" — the reader needs to know the difference between "no bound
 * was recorded" and "a bound was recorded and we could not read it".
 */
private fun boundDisplay(bound: DecodedValue?): String = when {
    bound == null -> "N/A"
    bound.isError -> "${bound.display} — ${bound.error}"
    else -> bound.display
}

/**
 * A file's partition tuple for a table cell, in the same `name=value` form Iceberg writes into the
 * file's own path. The three outcomes are kept distinct on purpose: a decoded tuple, a table that
 * genuinely has no partition fields, and a manifest whose spec could not be read.
 */
private fun partitionCell(partition: DecodedPartition?): String = when {
    partition == null -> "N/A (no spec)"
    partition.isUnpartitioned -> "(unpartitioned)"
    else -> partition.path
}

private fun longs(values: List<Long>?): String =
    if (values.isNullOrEmpty()) "[]" else values.joinToString(", ")

private fun currentSnapshotLabel(currentSnapshotId: Long?): String = when (currentSnapshotId) {
    null -> "None"
    -1L -> "None (-1)"
    else -> currentSnapshotId.toString()
}

private fun manifestContentRank(content: Int?): Int =
    service.IcebergGraphBuilder.manifestContentRank(content)

private fun jsonToAnnotatedString(json: String, colors: androidx.compose.material3.ColorScheme): AnnotatedString {
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
private fun RecursiveDataTableSection(
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
private fun pathResolutionLabel(resolution: model.PathResolution): String = when (resolution) {
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
private fun dataFileResolutionLabel(resolution: model.PathResolution): String = when (resolution) {
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
private fun DerivationSection(title: String, derivation: StatsDerivation) {
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
private fun ManifestLedgerSection(entries: List<ManifestEntryView>) {
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
private fun PaimonReplayTraceSection(node: GraphNode.PaimonManifestNode) {
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

private fun renderSnapshotLogRows(items: List<SnapshotLogEntry>): List<List<String>> =
    items.sortedBy { it.timestampMs ?: Long.MAX_VALUE }.map { entry ->
        listOf(
            formatTimestampShort(entry.timestampMs),
            "${entry.snapshotId ?: "N/A"}"
        )
    }

private fun renderMetadataLogRows(items: List<MetadataLogEntry>): List<List<String>> =
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
                            SnapshotComparison(pair.first, pair.second)
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
                    is GraphNode.TableNode -> {
                        val summary = node.summary
                        val metadataChildren = children
                            .filterIsInstance<GraphNode.MetadataNode>()
                            .sortedBy { it.simpleId }
                        val metadataNodeByFileName = metadataChildren.associateBy { it.fileName }
                        val versionFileNames = summary.metadataVersions.map { it.fileName }.toSet()
                        val mergedMetadataRows = buildList {
                            summary.metadataVersions.forEachIndexed { index, version ->
                                val metadataNode = metadataNodeByFileName[version.fileName]
                                add(
                                    listOf(
                                        "${index + 1}",
                                        metadataNode?.id ?: "N/A",
                                        version.fileName,
                                        "${version.version ?: "N/A"}",
                                        formatTimestampShort(version.fileLastModifiedMs),
                                        formatTimestampShort(version.metadataLastUpdatedMs),
                                        "${version.snapshotCount}",
                                        currentSnapshotLabel(version.currentSnapshotId)
                                    )
                                )
                            }
                            metadataChildren
                                .filter { it.fileName !in versionFileNames }
                                .forEachIndexed { index, metadataNode ->
                                    add(
                                        listOf(
                                            "${summary.metadataVersions.size + index + 1}",
                                            metadataNode.id,
                                            metadataNode.fileName,
                                            "N/A",
                                            "N/A",
                                            formatTimestampShort(metadataNode.data.lastUpdatedMs),
                                            "${metadataNode.data.snapshots.size}",
                                            currentSnapshotLabel(metadataNode.data.currentSnapshotId)
                                        )
                                    )
                                }
                        }

                        DetailTable {
                            DetailRow("Property", "Value", isHeader = true)
                            DetailRow("Name", summary.tableName)
                            DetailRow("Table Path", summary.tablePath, copyable = true)
                            DetailRow("Location", summary.location ?: "N/A", copyable = true)
                            DetailRow("Table UUID", summary.tableUuid ?: "N/A", copyable = true)
                            DetailRow("Format Version", "${summary.formatVersion ?: "N/A"}")
                            DetailRow("Current Snapshot ID", currentSnapshotLabel(summary.currentSnapshotId))
                            DetailRow("Current Metadata Version", "${summary.currentMetadataVersion ?: "N/A"}")
                            DetailRow(
                                "version-hint.text",
                                summary.versionHintText?.takeIf { it.isNotBlank() }
                                    ?: "Not present — normal unless the table is HadoopCatalog-managed"
                            )
                        }

                        // Only where the format keeps branches under the table: a Paimon branch is
                        // a directory of its own snapshots over the table's data, and an Iceberg
                        // branch is a ref on the metadata file, listed on that node. Null is the
                        // second case; an empty list is a Paimon table with no branch/, which is
                        // an answer.
                        summary.branches?.let { branches ->
                            CountedSection("Branches", branches.size, "branches — the table's own snapshot/ is $MAIN_BRANCH") {
                                WideTable(
                                    headers = listOf("Branch", "Snapshots", "Latest", "Schemas", "Tags", "Read Errors", "Path"),
                                    rows = branches.map { branch ->
                                        listOf(
                                            branch.name,
                                            "${branch.snapshotCount}",
                                            branch.latestSnapshotId?.toString() ?: "none — created empty",
                                            "${branch.schemaCount}",
                                            "${branch.tagCount}",
                                            "${branch.readErrorCount}",
                                            branch.path,
                                        )
                                    },
                                    columnWidths = listOf(110.dp, 80.dp, 200.dp, 70.dp, 60.dp, 90.dp, 220.dp),
                                )
                            }
                        }

                        // A consumer is a streaming reader's bookmark, and the reason an expiry
                        // stops short: expire_snapshots keeps every snapshot from the reader's
                        // next one on. Null on Iceberg, which has no such thing.
                        summary.consumers?.let { consumers ->
                            CountedSection("Consumers", consumers.size, "consumers — no streaming reader has left a bookmark under consumer/") {
                                Text(
                                    "Each is a streaming reader's bookmark: the snapshot it will consume next, " +
                                        "which expire_snapshots will not expire, nor anything after it. A reader " +
                                        "standing on an old snapshot is why a table keeps more history than its " +
                                        "retention says.",
                                    fontSize = TypeScale.small,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(bottom = 4.dp),
                                )
                                // The exception leads: a bookmark whose snapshot is gone is a
                                // reader that will fail on its next read, and it is the one row
                                // that has to be findable without reading the others.
                                WideTable(
                                    headers = listOf("Next in snapshot/", "Consumer", "Next Snapshot", "Path"),
                                    rows = consumers.map { consumer ->
                                        listOf(
                                            if (consumer.nextSnapshotPresent) "yes" else "NO — expired from under the reader",
                                            consumer.name,
                                            consumer.nextSnapshot?.toString() ?: "not recorded",
                                            consumer.path,
                                        )
                                    },
                                    columnWidths = listOf(240.dp, 120.dp, 120.dp, 220.dp),
                                    leadCellColors = consumers.map { if (it.nextSnapshotPresent) null else MaterialTheme.colorScheme.error },
                                )
                            }
                        }

                        RecursiveDataTableSection(node = node, graphModel = currentGraph)

                        // Directly under the table's identity, because it is the only control on
                        // this panel and everything below it is a readout. It was first placed
                        // after the history figures, which reads well in a listing of section
                        // names and put it 6,000dp down the rendered panel — past the metadata
                        // file times, the manifest list times, both stat blocks and both
                        // derivation ledgers. A control nobody scrolls to is a control nobody
                        // has. It costs about eighty dp here while no filter is entered.
                        currentGraph?.let { graph ->
                            ScanPruningSection(graph, scanFilter, onScanFilterChange)
                        }
                        // The panel's other control, kept beside the first for the same reason.
                        UnreferencedFilesSection(node)

                        // Folded, and out of the identity table above, because none of the three
                        // is identity and together they were the largest thing on the panel: each
                        // renders local, UTC and epoch, so three rows are nine lines and about
                        // 600dp — roughly half of the ~1,300dp that stood between "Collapse all"
                        // and the list of section names it produces. The same check on the other
                        // node kinds says this is not a general rule about timestamps: a
                        // snapshot's `Timestamp` and a Paimon data file's `Creation Time` are
                        // recorded, singular, and part of what identifies the artifact, so they
                        // stay where they are.
                        Section("Table Times") {
                            Text(
                                "None of these is recorded by Iceberg as a table creation or " +
                                    "update time — there is no such field. The first two are the " +
                                    "ends of the retained metadata timeline, so expiring old " +
                                    "metadata moves \"created\" forward and the table can be far " +
                                    "older than it says.",
                                fontSize = TypeScale.small,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(bottom = 4.dp),
                            )
                            DetailTable {
                                DetailRow("Property", "Value", isHeader = true)
                                DetailRow(
                                    "Oldest retained metadata",
                                    formatTimestamp(summary.tableCreationMs),
                                )
                                DetailRow(
                                    "Newest retained metadata",
                                    formatTimestamp(summary.tableLastUpdateMs),
                                )
                                DetailRow(
                                    "Current metadata last-updated-ms",
                                    formatTimestamp(summary.lastUpdatedMs),
                                )
                            }
                        }

                        Section("Metadata Files") {
                            DetailTable {
                                DetailRow("Metric", "Value", isHeader = true)
                                DetailRow("File Count", "${summary.metadataFileCount}")
                                DetailRow("Known", "${summary.metadataFileTimes.knownCount}")
                                DetailRow("Missing", "${summary.metadataFileTimes.missingCount}")
                                DetailRow("Oldest", formatTimestamp(summary.metadataFileTimes.oldestMs))
                                DetailRow("Latest", formatTimestamp(summary.metadataFileTimes.newestMs))
                            }
                        }

                        Section("Snapshot Manifest Lists") {
                            DetailTable {
                                DetailRow("Metric", "Value", isHeader = true)
                                DetailRow("Unique Snapshots", "${summary.snapshotCount}")
                                DetailRow("File Count", "${summary.snapshotManifestListFileCount}")
                                DetailRow("Known", "${summary.snapshotManifestListFileTimes.knownCount}")
                                DetailRow("Missing", "${summary.snapshotManifestListFileTimes.missingCount}")
                                DetailRow("Oldest", formatTimestamp(summary.snapshotManifestListFileTimes.oldestMs))
                                DetailRow("Latest", formatTimestamp(summary.snapshotManifestListFileTimes.newestMs))
                            }
                        }

                        Section("Current Snapshot") {
                            val current = summary.current
                            if (summary.currentSnapshotId == null) {
                                DetailTable {
                                    DetailRow("Metric", "Value", isHeader = true)
                                    DetailRow("State", "No current snapshot — the table has no committed data")
                                }
                            } else {
                                DetailTable {
                                    DetailRow("Metric", "Value", isHeader = true)
                                    DetailRow("Snapshot ID", currentSnapshotLabel(summary.currentSnapshotId))
                                    DetailRow("Records", formatCount(current.recordCount))
                                    DetailRow("Data Files", "${formatCount(current.dataFileCount)}  (${formatBytes(current.dataSizeBytes)})")
                                    DetailRow(
                                        "Delete Files",
                                        "${formatCount(current.deleteFileCount)}  (${formatBytes(current.deleteSizeBytes)})" +
                                            " — ${formatCount(current.posDeleteFileCount)} pos / ${formatCount(current.eqDeleteFileCount)} eq"
                                    )
                                    DetailRow("Delete Records", formatCount(current.deleteRecordCount))
                                    DetailRow("Total Size", formatBytes(current.totalSizeBytes))
                                    DetailRow("Manifests", "${formatCount(current.manifestCount)}  (${formatCount(current.dataManifestCount)} data / ${formatCount(current.deleteManifestCount)} delete)")
                                    DetailRow("Manifest Entries", "${formatCount(current.manifestEntryCount)}  (${formatCount(current.deletedEntryCount)} recording a removal)")
                                }
                                DerivationSection("How the current figures were derived", summary.currentDerivation)
                            }
                        }

                        Section("All Retained History") {
                            val history = summary.history
                            DetailTable {
                                DetailRow("Metric", "Value", isHeader = true)
                                DetailRow("Snapshots", formatCount(summary.snapshotCount))
                                DetailRow("Metadata Versions", formatCount(summary.metadataFileCount))
                                DetailRow(
                                    "Manifests",
                                    "${formatCount(history.manifestCount)}  (${formatCount(history.dataManifestCount)} data / ${formatCount(history.deleteManifestCount)} delete)"
                                )
                                DetailRow("Manifest Entries", "${formatCount(history.manifestEntryCount)}  (${formatCount(history.deletedEntryCount)} recording a removal)")
                                DetailRow("Distinct Data Files", "${formatCount(history.dataFileCount)}  (${formatBytes(history.dataSizeBytes)})")
                                DetailRow(
                                    "Distinct Delete Files",
                                    "${formatCount(history.deleteFileCount)}  (${formatBytes(history.deleteSizeBytes)})"
                                )
                                DetailRow("Referenced Bytes", formatBytes(history.totalSizeBytes))
                                DetailRow("Manifest Files Known / Missing", "${summary.manifestFileTimes.knownCount} / ${summary.manifestFileTimes.missingCount}")
                                // One row per timestamp. formatTimestamp returns three lines (local,
                                // UTC, epoch), so joining two of them into one cell runs the second
                                // block's first line onto the first block's last one and pushes the
                                // rest past maxLines — the arrow ends up mid-paragraph and the second
                                // epoch is simply not drawn. The Metadata Files section above already
                                // uses separate rows; this follows it.
                                DetailRow("Manifest Files Oldest", formatTimestamp(summary.manifestFileTimes.oldestMs))
                                DetailRow("Manifest Files Latest", formatTimestamp(summary.manifestFileTimes.newestMs))
                                DetailRow("Data Files Known / Missing", "${summary.dataFileTimes.knownCount} / ${summary.dataFileTimes.missingCount}")
                                DetailRow("Data Files Oldest", formatTimestamp(summary.dataFileTimes.oldestMs))
                                DetailRow("Data Files Latest", formatTimestamp(summary.dataFileTimes.newestMs))
                            }
                            DerivationSection("How the history figures were derived", summary.historyDerivation)
                        }

                        if (mergedMetadataRows.isNotEmpty()) {
                            Section("Metadata Nodes & Timeline") {
                                WideTable(
                                    headers = listOf(
                                        "Order",
                                        "Node",
                                        "File",
                                        "Version",
                                        "File Updated",
                                        "Metadata Last Updated",
                                        "Snapshots",
                                        "Current Snapshot ID"
                                    ),
                                    rows = mergedMetadataRows
                                )
                            }
                        }

                        SchemaEvolutionSection(metadataChildren)
                        PropertiesEvolutionSection(metadataChildren)
                    }

                    is GraphNode.MetadataNode -> {
                        DetailTable {
                            DetailRow("Property", "Value", isHeader = true)
                            DetailRow("File Name", node.fileName)
                            DetailRow("Format Version", "${node.data.formatVersion}")
                            DetailRow("Table UUID", "${node.data.tableUuid ?: "N/A"}", copyable = true)
                            DetailRow("Location", "${node.data.location ?: "N/A"}", copyable = true)
                            DetailRow("Last Seq. Num.", "${node.data.lastSequenceNumber ?: "N/A"}")
                            DetailRow("Last Updated", formatTimestamp(node.data.lastUpdatedMs))
                            DetailRow("Last Column ID", "${node.data.lastColumnId ?: "N/A"}")
                            DetailRow("Current Schema ID", "${node.data.currentSchemaId ?: "N/A"}")
                            DetailRow("Default Spec ID", "${node.data.defaultSpecId ?: "N/A"}")
                            DetailRow("Last Partition ID", "${node.data.lastPartitionId ?: "N/A"}")
                            DetailRow("Default Sort Order ID", "${node.data.defaultSortOrderId ?: "N/A"}")
                            DetailRow("Current Snapshot ID", currentSnapshotLabel(node.data.currentSnapshotId))
                        }

                        RecursiveDataTableSection(node = node, graphModel = currentGraph)

                        CountedSection("Properties", node.data.properties.size, "properties") {
                            DetailTable {
                                DetailRow("Key", "Value", isHeader = true)
                                node.data.properties.toSortedMap().forEach { (k, v) ->
                                    DetailRow(k, v)
                                }
                            }
                        }

                        CountedSection("Schemas", node.data.schemas.size, "schemas") {
                            node.data.schemas
                                .sortedBy { it.schemaId ?: Int.MAX_VALUE }
                                .forEach { schema ->
                                    Text(
                                        "Schema ${schema.schemaId ?: "Unknown"}",
                                        fontWeight = FontWeight.SemiBold,
                                        fontSize = TypeScale.body
                                    )
                                    Spacer(Modifier.height(4.dp))
                                    WideTable(
                                        headers = listOf(
                                            "Field ID",
                                            "Field Name",
                                            "Required",
                                            "Type",
                                            "Is Identifier Field"
                                        ),
                                        rows = schema.fields
                                            .sortedBy { it.id ?: Int.MAX_VALUE }
                                            .map { field ->
                                                val isIdentifier = field.id != null && field.id in schema.identifierFieldIds
                                                listOf(
                                                    "${field.id ?: "N/A"}",
                                                    field.name ?: "N/A",
                                                    "${field.required ?: false}",
                                                    normalizeText(field.type?.toString()?.trim('"')),
                                                    if (isIdentifier) "Yes" else "No"
                                                )
                                            }
                                    )
                                    Spacer(Modifier.height(12.dp))
                                }
                        }

                        CountedSection("Partition Specs", node.data.partitionSpecs.size, "partition specs") {
                            node.data.partitionSpecs
                                .sortedBy { it.specId ?: Int.MAX_VALUE }
                                .forEach { spec ->
                                    Text(
                                        "Spec ${spec.specId ?: "Unknown"}",
                                        fontWeight = FontWeight.SemiBold,
                                        fontSize = TypeScale.body
                                    )
                                    Spacer(Modifier.height(4.dp))
                                    WideTable(
                                        headers = listOf("Source ID", "Field ID", "Name", "Transform"),
                                        rows = if (spec.fields.isEmpty()) listOf(listOf("N/A", "N/A", "N/A", "N/A")) else spec.fields.map { field ->
                                            listOf(
                                                "${field.sourceId ?: "N/A"}",
                                                "${field.fieldId ?: "N/A"}",
                                                field.name ?: "N/A",
                                                normalizeText(field.transform?.toString())
                                            )
                                        }
                                    )
                                    Spacer(Modifier.height(12.dp))
                                }
                        }

                        CountedSection("Sort Orders", node.data.sortOrders.size, "sort orders") {
                            node.data.sortOrders
                                .sortedBy { it.orderId ?: Int.MAX_VALUE }
                                .forEach { order ->
                                    Text(
                                        "Order ${order.orderId ?: "Unknown"}",
                                        fontWeight = FontWeight.SemiBold,
                                        fontSize = TypeScale.body
                                    )
                                    Spacer(Modifier.height(4.dp))
                                    WideTable(
                                        headers = listOf("Source ID", "Transform", "Direction", "Null Order"),
                                        rows = if (order.fields.isEmpty()) listOf(listOf("N/A", "N/A", "N/A", "N/A")) else order.fields.map { field ->
                                            listOf(
                                                "${field.sourceId ?: "N/A"}",
                                                normalizeText(field.transform?.toString()),
                                                field.direction ?: "N/A",
                                                field.nullOrder ?: "N/A"
                                            )
                                        }
                                    )
                                    Spacer(Modifier.height(12.dp))
                                }
                        }

                        CountedSection("Refs", node.data.refs.size, "refs") {
                            WideTable(
                                headers = listOf("Name", "Type", "Snapshot ID", "Max Ref Age MS", "Max Snapshot Age MS", "Min Snapshots To Keep"),
                                rows = node.data.refs.toSortedMap().map { (name, ref) ->
                                    listOf(
                                        name,
                                        ref.type ?: "N/A",
                                        "${ref.snapshotId ?: "N/A"}",
                                        "${ref.maxRefAgeMs ?: "N/A"}",
                                        "${ref.maxSnapshotAgeMs ?: "N/A"}",
                                        "${ref.minSnapshotsToKeep ?: "N/A"}"
                                    )
                                }
                            )
                        }

                        CountedSection("Snapshots", node.data.snapshots.size, "snapshots") {
                            val snapshots = node.data.snapshots.sortedBy { it.timestampMs ?: Long.MAX_VALUE }
                            WideTable(
                                headers = listOf(
                                    "Snapshot ID",
                                    "Parent Snapshot ID",
                                    "Sequence Number",
                                    "Schema ID",
                                    "Timestamp",
                                    "Manifest List",
                                    "Operation",
                                    "Summary"
                                ),
                                rows = snapshots.map { snapshot ->
                                    listOf(
                                        "${snapshot.snapshotId ?: "N/A"}",
                                        "${snapshot.parentSnapshotId ?: "None"}",
                                        snapshot.sequenceNumber?.toString() ?: "0 (v1)",
                                        "${snapshot.schemaId ?: "N/A"}",
                                        formatTimestampShort(snapshot.timestampMs),
                                        normalizeText(snapshot.manifestList),
                                        snapshot.summary["operation"] ?: "N/A",
                                        if (snapshot.summary.isEmpty()) "N/A"
                                        else snapshot.summary.toSortedMap().entries.joinToString(", ") { "${it.key}=${it.value}" }
                                    )
                                }
                            )
                        }

                        CountedSection("Snapshot Log", node.data.snapshotLog.size, "snapshot log entries") {
                            DetailTable {
                                DetailRow("Timestamp", "Snapshot ID", isHeader = true)
                                renderSnapshotLogRows(node.data.snapshotLog).forEach { row ->
                                    DetailRow(row.getOrElse(0) { "N/A" }, row.getOrElse(1) { "N/A" })
                                }
                            }
                        }

                        CountedSection("Metadata Log", node.data.metadataLog.size, "metadata log entries") {
                            DetailTable {
                                DetailRow("Timestamp", "Metadata File", isHeader = true)
                                renderMetadataLogRows(node.data.metadataLog).forEach { row ->
                                    DetailRow(row.getOrElse(0) { "N/A" }, row.getOrElse(1) { "N/A" })
                                }
                            }
                        }

                        // One row per *blob*, not per file. The list in metadata.json is a list of
                        // files each holding a list of blobs, and neither level is the question a
                        // reader has: that is "how many distinct values does this column have",
                        // which is one row per blob with its field ids resolved to a name. The
                        // column goes first because it is the answer, then the figure — the
                        // WideTable ordering rule, since the panel is narrower than the table.
                        // The Puffin footers are a DeferredRead on the node: opened on the first
                        // render of this panel and never again, rather than at graph-build time
                        // where it would be a file open per metadata version of the table.
                        val footers = node.statisticsFooters.value.orEmpty()
                        val statsRows = remember(node.data, footers) {
                            statisticsRows(node.data) { file -> footers[file.statisticsPath]?.footer }
                        }
                        CountedSection("Statistics", node.data.statistics.size, "statistics files") {
                            node.data.statistics.forEach { file ->
                                val read = footers[file.statisticsPath]
                                DetailTable {
                                    DetailRow("File", fileNameFromPath(file.statisticsPath.orEmpty()))
                                    DetailRow("Snapshot", file.snapshotId?.toString() ?: "N/A")
                                    DetailRow("Size", file.fileSizeInBytes?.let { formatBytes(it) } ?: "N/A")
                                    DetailRow(
                                        "Footer Size",
                                        file.fileFooterSizeInBytes?.let { formatBytes(it) } ?: "N/A",
                                    )
                                    // Recorded beside read, the same idea as a manifest's tallies:
                                    // metadata.json holds a copy of the blob list so a planner
                                    // never opens the file, and that copy is what can go stale.
                                    DetailRow(
                                        "Blobs",
                                        when {
                                            read?.footer != null ->
                                                "${file.blobMetadata.size} recorded, " +
                                                    "${read.footer!!.blobs.size} in the file"
                                            else -> "${file.blobMetadata.size} recorded, file not read"
                                        },
                                    )
                                    read?.footer?.createdBy?.let { DetailRow("Written By", it) }
                                    read?.problem?.let {
                                        // The path is named because the recorded one is usually
                                        // some other machine's, and a reader told a file is
                                        // missing needs to know which one was looked at.
                                        DetailRow("Could Not Open", "${read.localPath}: $it")
                                    }
                                }
                            }
                            WideTable(
                                headers = listOf(
                                    "Column", "Distinct Values", "In File", "Type",
                                    "Size", "Codec", "Snapshot", "Sequence",
                                ),
                                rows = statsRows.map { row ->
                                    listOf(
                                        row.column,
                                        row.ndv?.let { formatCount(it) } ?: "N/A",
                                        // Three states, not two: a file never opened and a file
                                        // opened without this blob would otherwise read the same,
                                        // and the second means the table is pointing a planner at
                                        // statistics that are not there.
                                        when {
                                            !row.fileRead -> "not read"
                                            row.fileNdv == null -> "missing"
                                            else -> formatCount(row.fileNdv!!)
                                        },
                                        row.type,
                                        row.compressedLength?.let { formatBytes(it) } ?: "N/A",
                                        row.codec ?: "N/A",
                                        row.snapshotId?.toString() ?: "N/A",
                                        row.sequenceNumber?.toString() ?: "N/A",
                                    )
                                },
                                // Sized to the longest value each column can hold rather than to
                                // this fixture: the sketch type is a fixed vocabulary string and a
                                // snapshot id is 19 digits, and at the first two widths tried both
                                // wrapped on every row, which doubled the height of the whole table
                                // to say nothing.
                                columnWidths = listOf(
                                    120.dp, 110.dp, 90.dp, 250.dp, 80.dp, 70.dp, 180.dp, 80.dp,
                                ),
                                // Only a row whose file disagreed is coloured. A column bolded on
                                // every row has spent its emphasis before the exception arrives.
                                leadCellColors = statsRows.map { row ->
                                    if (row.agrees == false) colors.error else null
                                },
                            )
                        }

                        CountedSection(
                            "Partition Statistics",
                            node.data.partitionStatistics.size,
                            "partition statistics files",
                        ) {
                            WideTable(
                                headers = listOf("File", "Snapshot", "Size"),
                                rows = node.data.partitionStatistics.map { file ->
                                    listOf(
                                        fileNameFromPath(file.statisticsPath.orEmpty()),
                                        file.snapshotId?.toString() ?: "N/A",
                                        file.fileSizeInBytes?.let { formatBytes(it) } ?: "N/A",
                                    )
                                },
                                columnWidths = listOf(260.dp, 150.dp, 80.dp),
                            )
                        }

                        Section("Raw metadata.json") {
                            val rawJson = node.rawJson
                            if (!rawJson.isNullOrBlank()) {
                                val rawJsonScrollX = rememberScrollState()
                                val rawJsonScrollY = rememberScrollState()
                                // H-4: highlight once per (rawJson, theme), not on every recomposition.
                                val highlightedJson = remember(rawJson, colors) { jsonToAnnotatedString(rawJson, colors) }
                                Box(
                                    Modifier
                                        .fillMaxWidth()
                                        .heightIn(min = 220.dp, max = 420.dp)
                                        .clip(androidx.compose.foundation.shape.RoundedCornerShape(4.dp))
                                        .border(1.dp, colors.outlineVariant, androidx.compose.foundation.shape.RoundedCornerShape(4.dp))
                                        .background(colors.surface)
                                        .horizontalScroll(rawJsonScrollX)
                                        .verticalScroll(rawJsonScrollY)
                                        .padding(8.dp)
                                ) {
                                    Text(
                                        text = highlightedJson,
                                        fontFamily = FontFamily.Monospace,
                                        fontSize = TypeScale.small
                                    )
                                }
                            } else {
                                DetailTable {
                                    DetailRow("Raw JSON", "N/A", isHeader = true)
                                }
                            }
                        }
                    }

                    is GraphNode.SnapshotNode -> {
                        DetailTable {
                            DetailRow("Property", "Value", isHeader = true)
                            DetailRow("Snapshot ID", "${node.data.snapshotId}")
                            if (node.expired) {
                                DetailRow(
                                    "Expired",
                                    "yes — its manifest list is gone and the current metadata no longer " +
                                        "lists it; this older metadata version still does. The summary " +
                                        "below is what the writer recorded; nothing under it can be read.",
                                )
                            }
                            // "None" is the root commit; a parent id with no snapshot behind it
                            // means the parent has been expired away, and the two are different
                            // facts about the table's history.
                            val parentId = node.data.parentSnapshotId
                            DetailRow(
                                "Parent ID",
                                when {
                                    parentId == null -> "None — this is the table's first commit"
                                    currentGraph?.nodeById?.containsKey("snap_$parentId") == true -> "$parentId"
                                    else -> "$parentId (expired — no longer retained)"
                                },
                            )
                            DetailRow(
                                "Refs",
                                node.refs.takeIf { it.isNotEmpty() }?.joinToString(", ") { it.display }
                                    ?: "None — kept only by a metadata version, not by a branch or tag",
                            )
                            DetailRow(
                                "Sequence Number",
                                node.data.sequenceNumber?.toString()
                                    ?: "0 — a v1 snapshot records none, and the format reads it as 0",
                            )
                            DetailRow("Schema ID", "${node.data.schemaId ?: "N/A"}")
                            DetailRow("Timestamp", formatTimestamp(node.data.timestampMs))
                            val manifestList = node.data.manifestList
                            val manifestListLabel = if (manifestList == null) "N/A" else "${manifestList.substringAfterLast("/")} ($manifestList)"
                            DetailRow("Manifest List", manifestListLabel)
                            DetailRow("Resolved", pathResolutionLabel(node.pathResolution))
                        }

                        RecursiveDataTableSection(node = node, graphModel = currentGraph)

                        node.change?.let { CommitSection(it) }

                        TotalsSection(node)

                        DeleteReachSection(node, children)

                        if (node.data.summary.isNotEmpty()) {
                            Section("Summary") {
                                // Copied out of the snapshot as the writer left them. The section
                                // above is where the ones describing this commit are checked
                                // against the manifests it wrote; the rest — engine name, app id,
                                // the running totals — have nothing here to check them against.
                                Text(
                                    "Written by whatever engine made the commit, and read back verbatim — " +
                                        "nothing here recomputed them. \"What this commit did\" above checks " +
                                        "the figures describing this commit against the manifests it wrote, " +
                                        "and \"What this commit left\" the running totals against its closure.",
                                    fontSize = TypeScale.small,
                                    color = colors.onSurfaceVariant,
                                    modifier = Modifier.padding(bottom = 4.dp)
                                )
                                DetailTable {
                                    DetailRow("Key", "Value", isHeader = true)
                                    node.data.summary.toSortedMap().forEach { (k, v) ->
                                        DetailRow(k, v)
                                    }
                                }
                            }
                        }

                        val manifestChildren = children
                            .filterIsInstance<GraphNode.ManifestNode>()
                            .sortedWith(
                                compareBy(
                                    { it.data.effectiveSequenceNumber },
                                    { it.data.effectiveMinSequenceNumber },
                                    { manifestContentRank(it.data.content) },
                                    { it.data.manifestPath ?: "" }
                                )
                            )

                        if (manifestChildren.isNotEmpty()) {
                            Section("Manifest List Rows") {
                                Text(
                                    "One row per manifest this snapshot lists, in apply order. The six count " +
                                        "columns are what the manifest list claims; \"Summary\" is that claim " +
                                        "checked against the entries of the manifest itself, which is a check " +
                                        "nothing on the read path performs.",
                                    fontSize = TypeScale.small,
                                    color = colors.onSurfaceVariant,
                                    modifier = Modifier.padding(bottom = 4.dp)
                                )
                                WideTable(
                                    headers = listOf(
                                        "Apply Order",
                                        "Summary",
                                        "Manifest Path",
                                        "Content",
                                        "Manifest Length",
                                        "Partition Spec ID",
                                        "Sequence Number",
                                        "Min Sequence Number",
                                        "Added Snapshot ID",
                                        "Added Files",
                                        "Existing Files",
                                        "Deleted Files",
                                        "Added Rows",
                                        "Existing Rows",
                                        "Deleted Rows"
                                    ),
                                    rows = manifestChildren.mapIndexed { index, manifestNode ->
                                        val manifest = manifestNode.data
                                        val tallies = manifestTallies(manifest, manifestNode.entries.map { it.entry })
                                        val checkable = tallies.filter { it.agrees != null }
                                        listOf(
                                            "${index + 1}",
                                            when {
                                                manifestNode.entries.isEmpty() -> "no entries read"
                                                checkable.isEmpty() -> "nothing recorded"
                                                checkable.all { it.agrees == true } -> "matches"
                                                else -> "${checkable.count { it.agrees == false }} of " +
                                                    "${checkable.size} DIFFER"
                                            },
                                            normalizeText(manifest.manifestPath),
                                            if (manifest.content == 1) "Deletes (1)" else "Data (0)",
                                            "${manifest.manifestLength ?: "N/A"}",
                                            "${manifest.partitionSpecId ?: "N/A"}",
                                            manifest.sequenceNumber?.toString() ?: "0 (v1)",
                                            "${manifest.minSequenceNumber ?: "N/A"}",
                                            "${manifest.addedSnapshotId ?: "N/A"}",
                                            "${manifest.addedFilesCount ?: 0}",
                                            "${manifest.existingFilesCount ?: 0}",
                                            "${manifest.deletedFilesCount ?: 0}",
                                            "${manifest.addedRowsCount ?: 0}",
                                            "${manifest.existingRowsCount ?: 0}",
                                            "${manifest.deletedRowsCount ?: 0}"
                                        )
                                    }
                                )
                            }
                        }
                    }

                    is GraphNode.ManifestNode -> {
                        DetailTable {
                            val contentType = when (val c = node.data.content) {
                                1 -> "Deletes ($c)"
                                0 -> "Data ($c)"
                                else -> "Unknown ($c)"
                            }
                            DetailRow("Property", "Value", isHeader = true)
                            DetailRow("Content Type", contentType)
                            // A v1 manifest list records neither; the format reads both as 0.
                            DetailRow(
                                "Sequence Num.",
                                node.data.sequenceNumber?.toString() ?: "0 — a v1 manifest list records none, read as 0",
                            )
                            DetailRow(
                                "Min Sequence Num.",
                                node.data.minSequenceNumber?.toString() ?: "0 — a v1 manifest list records none, read as 0",
                            )
                            DetailRow("Partition Spec ID", "${node.data.partitionSpecId ?: "N/A"}")
                            DetailRow("Added Snapshot", "${node.data.addedSnapshotId ?: "N/A"}")
                            DetailRow("Manifest Length", "${node.data.manifestLength ?: 0} bytes")
                            val manifestPath = node.data.manifestPath
                            val manifestPathLabel = if (manifestPath == null) "N/A" else "${manifestPath.substringAfterLast("/")} ($manifestPath)"
                            DetailRow("Path", manifestPathLabel, copyable = true)
                            DetailRow("Resolved", pathResolutionLabel(node.pathResolution))
                        }

                        RecursiveDataTableSection(node = node, graphModel = currentGraph)

                        // Every entry, not just the ones the graph drew. The graph caps child
                        // nodes per manifest so a manifest holding thousands of files stays
                        // readable; the inspector is a table and has no such constraint.
                        val manifestEntries = node.entries

                        // What the reader's filter did to this one manifest, next to the bounds it
                        // did it with. The table node says how many were skipped; this says why
                        // this one was, which is the question asked from here.
                        if (!scanFilter.isEmpty()) {
                            val result = evaluatePruning(node.partitionSummaries, scanFilter)
                            Section(
                                if (result.isSkipped) "Scan Pruning — this manifest would be skipped"
                                else "Scan Pruning — this manifest would be read"
                            ) {
                                val skippedColor = verdictSkippedColor()
                                val unevaluatedColor = verdictUnevaluatedColor()
                                WideTable(
                                    headers = listOf("This term", "Condition", "Field", "Because"),
                                    columnWidths = listOf(120.dp, 120.dp, 90.dp, 320.dp),
                                    rows = result.outcomes.map { outcome ->
                                        listOf(
                                            // "cannot" on its own named no object: cannot what? The
                                            // column says what each term did to this manifest, so
                                            // every value has to be a complete answer to that.
                                            when (outcome.effect) {
                                                TermEffect.SKIPS -> "skips it"
                                                TermEffect.KEEPS -> "cannot skip it"
                                                TermEffect.NOT_EVALUATED -> "not evaluated"
                                            },
                                            outcome.predicate.toString(),
                                            outcome.fieldName ?: "—",
                                            outcome.reason,
                                        )
                                    },
                                    leadCellColors = result.outcomes.map { outcome ->
                                        when (outcome.effect) {
                                            TermEffect.SKIPS -> skippedColor
                                            TermEffect.KEEPS -> null
                                            TermEffect.NOT_EVALUATED -> unevaluatedColor
                                        }
                                    },
                                )
                            }
                        }

                        // Outside the entries check on purpose: a manifest list claiming three
                        // added files over a manifest that yielded no entries is the case most
                        // worth seeing, and it is the case where there is nothing below to look at.
                        Section("Recorded Summary") {
                            Text(
                                "The manifest list carries these counts so a scan can plan without opening this " +
                                    "manifest, and nothing on the read path checks them. Each one sits beside the " +
                                    "same figure counted from the entries.",
                                fontSize = TypeScale.small,
                                color = colors.onSurfaceVariant,
                                modifier = Modifier.padding(bottom = 4.dp)
                            )
                            val tallies = manifestTallies(node.data, manifestEntries.map { it.entry })
                            WideTable(
                                // The verdict leads, as it does on the pruning tables: the reader is
                                // here to find out whether anything disagrees, not to read six pairs
                                // of numbers and compare them by eye.
                                headers = listOf("Agrees", "Figure", "In the entries", "Recorded"),
                                columnWidths = listOf(110.dp, 150.dp, 130.dp, 130.dp),
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
                                // Agreement is the ordinary case and stays neutral — colouring every
                                // row spends the attention this table needs for the one that differs.
                                leadCellColors = tallies.map { tally ->
                                    when (tally.agrees) {
                                        true -> null
                                        false -> colors.error
                                        null -> verdictUnevaluatedColor()
                                    }
                                },
                            )
                        }

                        ManifestLedgerSection(manifestEntries)

                        if (manifestEntries.isNotEmpty()) {
                            Spacer(Modifier.height(16.dp))
                            // Above the entries on purpose: this is what a planner reads to decide
                            // whether to open the manifest at all, so it is the answer to "would
                            // my query touch this file" — a question asked before any entry is.
                            val summaries = node.partitionSummaries
                            if (summaries.isNotEmpty()) {
                                Section("Partition Ranges (${formatCount(summaries.size)})") {
                                    Text(
                                        "The bounds a scan intersects with a partition predicate to decide whether to " +
                                            "open this manifest. One row per partition field, covering every file in it.",
                                        fontSize = TypeScale.small,
                                        color = colors.onSurfaceVariant,
                                        modifier = Modifier.padding(bottom = 4.dp)
                                    )
                                    WideTable(
                                        headers = listOf(
                                            "Field", "Lower", "Upper", "Holds", "Nulls", "NaNs",
                                            "Transform", "Result Type"
                                        ),
                                        columnWidths = listOf(
                                            150.dp, 150.dp, 150.dp, 110.dp, 70.dp, 70.dp, 120.dp, 120.dp
                                        ),
                                        rows = summaries.map { summary ->
                                            listOf(
                                                summary.field.name ?: "N/A",
                                                summary.humanLower ?: "N/A",
                                                summary.humanUpper ?: "N/A",
                                                // A manifest whose bounds meet holds exactly one
                                                // partition, which is what a well-clustered write
                                                // produces and what makes pruning effective.
                                                if (summary.isSingleValue) "one partition" else "a range",
                                                if (summary.containsNull) "yes" else "no",
                                                summary.containsNan?.let { if (it) "yes" else "no" } ?: "not recorded",
                                                summary.field.transformName.ifEmpty { "N/A" },
                                                summary.type.typeName,
                                            )
                                        }
                                    )
                                }
                                Spacer(Modifier.height(16.dp))
                            }

                            Section("Manifest Entries (${formatCount(manifestEntries.size)})") {
                                WideTable(
                                    headers = listOf(
                                        "Apply Order",
                                        "Simple ID",
                                        "Status",
                                        "Content",
                                        "Snapshot ID",
                                        "Sequence Number",
                                        "File Sequence Number",
                                        "File Path",
                                        "Format",
                                        "Record Count",
                                        "File Size Bytes",
                                        "Partition",
                                        "Column Sizes",
                                        "Value Counts",
                                        "Null Value Counts",
                                        "NaN Value Counts",
                                        "Lower Bounds",
                                        "Upper Bounds",
                                        "Key Metadata",
                                        "Split Offsets",
                                        "Equality IDs",
                                        "Sort Order ID"
                                    ),
                                    // Sized to the content. At a uniform width the partition tuple
                                    // and the bounds maps each wrapped to the line cap, and a row is
                                    // as tall as its tallest cell — so every row of this table was
                                    // eight lines high and two entries did not fit on a screen.
                                    columnWidths = listOf(
                                        90.dp, 80.dp, 110.dp, 100.dp, 170.dp, 120.dp, 150.dp,
                                        320.dp, 90.dp, 110.dp, 120.dp, 420.dp,
                                        160.dp, 160.dp, 170.dp, 170.dp, 220.dp, 220.dp,
                                        160.dp, 130.dp, 110.dp, 110.dp,
                                    ),
                                    rows = manifestEntries.mapIndexed { index, view ->
                                        val data = view.entry.dataFile ?: DataFile(filePath = "unknown")
                                        val status = when (view.entry.status) {
                                            ManifestEntryStatus.EXISTING -> "EXISTING (0)"
                                            ManifestEntryStatus.ADDED -> "ADDED (1)"
                                            ManifestEntryStatus.DELETED -> "DELETED (2)"
                                            else -> "Unknown (${view.entry.status})"
                                        }
                                        val content = when (data.content ?: DataFileContent.DATA) {
                                            DataFileContent.POSITION_DELETES -> "Position Delete (1)"
                                            DataFileContent.EQUALITY_DELETES -> "Equality Delete (2)"
                                            else -> "Data (0)"
                                        }
                                        listOf(
                                            "${index + 1}",
                                            "${view.simpleId}",
                                            status,
                                            content,
                                            "${view.entry.snapshotId ?: "N/A"}",
                                            "${effectiveSequenceNumber(view.entry, node.data.sequenceNumber)}",
                                            "${view.entry.fileSequenceNumber ?: "N/A"}",
                                            normalizeText(data.filePath),
                                            data.fileFormat ?: "N/A",
                                            formatCount(data.recordCount ?: 0L),
                                            formatBytes(data.fileSizeInBytes ?: 0L),
                                            partitionCell(view.partition),
                                            kvLongs(data.columnSizes),
                                            kvLongs(data.valueCounts),
                                            kvLongs(data.nullValueCounts),
                                            kvLongs(data.nanValueCounts),
                                            kvBytes(data.lowerBounds),
                                            kvBytes(data.upperBounds),
                                            data.keyMetadata?.toHexShort() ?: "N/A",
                                            longs(data.splitOffsets),
                                            data.equalityIds?.joinToString(", ") ?: "N/A",
                                            "${data.sortOrderId ?: "N/A"}"
                                        )
                                    }
                                )
                            }
                        }
                    }

                    is GraphNode.FileNode -> {
                        DetailTable {
                            val contentType = when (val c = node.data.content ?: 0) {
                                1 -> "Position Delete ($c)"
                                2 -> "Equality Delete ($c)"
                                else -> "Data ($c)"
                            }
                            val status = when (val s = node.entry.status) {
                                0 -> "EXISTING ($s)"
                                1 -> "ADDED ($s)"
                                2 -> "DELETED ($s)"
                                else -> "Unknown ($s)"
                            }
                            DetailRow("Property", "Value", isHeader = true)
                            DetailRow("Simple ID", "${node.simpleId}")
                            DetailRow("Content Type", contentType)
                            DetailRow("Status", status)
                            DetailRow("Snapshot ID", "${node.entry.snapshotId ?: "N/A"}")
                            // Inherited from the manifest when the entry records none, which is
                            // the ordinary case — and said out loud, because a number the file did
                            // not write is a different fact from one it did. A v1 manifest has no
                            // number to inherit, and the format reads that as 0.
                            DetailRow(
                                "Sequence Num.",
                                "${node.sequenceNumber}" + when {
                                    node.sequenceDefaulted -> " (v1 manifest — none recorded, read as 0)"
                                    node.sequenceInherited -> " (inherited from the manifest)"
                                    else -> ""
                                },
                            )
                            DetailRow("File Seq. Num.", "${node.entry.fileSequenceNumber ?: "N/A"}")
                            DetailRow("File Format", "${node.data.fileFormat ?: "N/A"}")
                            DetailRow("Record Count", "${node.data.recordCount ?: 0}")
                            DetailRow("File Size", "${node.data.fileSizeInBytes ?: 0} bytes")
                            DetailRow("Sort Order ID", "${node.data.sortOrderId ?: "N/A"}")
                            DetailRow("Split Offsets", longs(node.data.splitOffsets))
                            DetailRow("Equality IDs", node.data.equalityIds?.joinToString(", ") ?: "N/A")
                            val filePath = node.data.filePath
                            DetailRow("Path", "${filePath ?: "N/A"}", copyable = true)
                            DetailRow("Reading", node.localPath ?: "N/A", copyable = true)
                            DetailRow("Resolved", dataFileResolutionLabel(node.pathResolution))
                        }

                        // The partition tuple, decoded against the spec this file's manifest was
                        // written with. Value and Stored differ for the ordinal time transforms —
                        // a `year` partition for 2024 holds 54 — so both are shown: one says what
                        // the file contains, the other what it means.
                        val partition = node.partition
                        if (partition == null) {
                            Section("Partition") {
                                Text(
                                    "This manifest carried no partition spec, so the partition tuple " +
                                        "cannot be decoded. That is not the same as an unpartitioned table.",
                                    fontSize = TypeScale.small,
                                    color = colors.onSurfaceVariant,
                                )
                            }
                        } else if (partition.isUnpartitioned) {
                            Section("Partition") {
                                Text(
                                    "This table is not partitioned — its spec has no fields.",
                                    fontSize = TypeScale.small,
                                    color = colors.onSurfaceVariant,
                                )
                            }
                        } else {
                            Section("Partition (${formatCount(partition.values.size)})") {
                                Text(
                                    "Iceberg stores the transform's result, not the source value. " +
                                        "Value is how Iceberg renders it; Stored is what is on disk.",
                                    fontSize = TypeScale.small,
                                    color = colors.onSurfaceVariant,
                                    modifier = Modifier.padding(bottom = 4.dp)
                                )
                                WideTable(
                                    headers = listOf(
                                        "Field", "Value", "Stored", "Transform",
                                        "Source Column", "Result Type", "Field ID", "Raw"
                                    ),
                                    columnWidths = listOf(
                                        150.dp, 170.dp, 150.dp, 120.dp, 140.dp, 120.dp, 70.dp, 180.dp
                                    ),
                                    rows = partition.values.map { value ->
                                        listOf(
                                            value.field.name ?: "N/A",
                                            value.human,
                                            boundDisplay(value.stored),
                                            value.field.transformName.ifEmpty { "N/A" },
                                            value.field.sourceId?.let { sourceId ->
                                                node.schema?.nameOf(sourceId) ?: "field $sourceId"
                                            } ?: "N/A",
                                            value.type.typeName,
                                            "${value.field.fieldId ?: "N/A"}",
                                            value.stored.raw.takeIf { it.isNotEmpty() }?.toHexShort() ?: "N/A",
                                        )
                                    }
                                )
                            }
                        }

                        // What this delete file applies to. Asked of every delete file, and the
                        // honest answer is different for each of the three kinds — including one
                        // where the answer is that the format does not record it. Leaving the
                        // relationship blank would read as "nothing", which is a different claim.
                        val deleteContent = node.data.content
                        if (deleteContent == DataFileContent.POSITION_DELETES ||
                            deleteContent == DataFileContent.EQUALITY_DELETES
                        ) {
                            Section("What this deletes from") {
                                val referenced = node.data.referencedDataFile
                                DetailTable {
                                    DetailRow("Property", "Value", isHeader = true)
                                    when {
                                        deleteContent == DataFileContent.EQUALITY_DELETES -> {
                                            DetailRow("Applies by", "Predicate over the equality field ids below")
                                            DetailRow(
                                                "Equality Field IDs",
                                                node.data.equalityIds?.joinToString(", ") { id ->
                                                    node.schema?.nameOf(id)?.let { "$id ($it)" } ?: "$id"
                                                } ?: "N/A",
                                            )
                                            DetailRow(
                                                "Target Files",
                                                "Not recorded. An equality delete matches rows by value across " +
                                                    "every data file in its scope, so the format stores no link " +
                                                    "from it to any particular file.",
                                            )
                                        }
                                        referenced != null -> {
                                            DetailRow("Applies by", "Row position, as a v3 deletion vector")
                                            DetailRow("Referenced Data File", referenced)
                                            DetailRow(
                                                "Vector Location",
                                                "offset ${node.data.contentOffset ?: "N/A"}, " +
                                                    "${node.data.contentSizeInBytes?.let { formatBytes(it) } ?: "N/A"} " +
                                                    "inside the Puffin blob above",
                                            )
                                        }
                                        else -> {
                                            DetailRow("Applies by", "Row position")
                                            // The exact targets are inside the file, but the range
                                            // of them is not: the manifest records bounds on this
                                            // file's own file_path column, and that is what a
                                            // planner prunes with before opening anything.
                                            val targets = deleteTargetsOf(node.data)
                                            DetailRow(
                                                "Target Files",
                                                "Named row by row inside this file — its file_path column names " +
                                                    "the data files and pos the rows, read them below. The " +
                                                    "manifest records the range those names fall in, which is " +
                                                    "what a scan prunes with without opening this file.",
                                            )
                                            DetailRow(
                                                "Recorded Range",
                                                when {
                                                    targets.namesOneFile ->
                                                        "${targets.onlyPath} — the bounds meet, so this file " +
                                                            "targets exactly one data file"
                                                    targets.low != null ->
                                                        "${targets.low} … ${targets.high}"
                                                    else ->
                                                        "Not recorded. Without bounds on file_path a scan cannot " +
                                                            "rule this delete file out of any data file."
                                                },
                                            )
                                        }
                                    }
                                }
                                if (deleteContent == DataFileContent.POSITION_DELETES && referenced == null) {
                                    PositionalDeleteTargets(node)
                                }
                            }
                            DeletionVectorSection(node)
                        }

                        DeletesReachingSection(node, currentGraph)

                        // The five per-column statistics maps, pivoted into one row per column and
                        // decoded against the schema this file's manifest was written with. Stored
                        // as parallel maps keyed by field id, they are unreadable in their raw form.
                        val columnStats = node.columnStats
                        if (columnStats.isNotEmpty()) {
                            Section("Column Statistics (${formatCount(columnStats.size)})") {
                                Text(
                                    if (node.schema == null) {
                                        "This manifest carried no schema, so bounds are shown as raw bytes. " +
                                            "Decoding without a type would produce a plausible wrong value."
                                    } else {
                                        "A bound is a byte array in the manifest, keyed by field id, with no " +
                                            "type beside it. It is read as the type that field id has in the " +
                                            "schema this manifest carries under its own Avro `schema` key — the " +
                                            "manifest's schema, not the table's current one, because a file " +
                                            "written before a column was widened still describes itself by the " +
                                            "type in force then. The field id and the bytes sit next to each " +
                                            "decoded value so the reading can be checked rather than trusted."
                                    },
                                    fontSize = TypeScale.small,
                                    color = colors.onSurfaceVariant,
                                    modifier = Modifier.padding(bottom = 4.dp)
                                )
                                WideTable(
                                    // Each decoded bound is followed by the bytes it was decoded from.
                                    // The two were at opposite ends of the table, which put the value
                                    // on screen and its evidence four columns past the panel edge.
                                    headers = listOf(
                                        "Column", "Field ID", "Type",
                                        "Lower Bound", "Lower (raw)", "Upper Bound", "Upper (raw)",
                                        "Values", "Nulls", "NaNs", "Column Size"
                                    ),
                                    columnWidths = listOf(
                                        150.dp, 70.dp, 110.dp,
                                        180.dp, 150.dp, 180.dp, 150.dp,
                                        80.dp, 90.dp, 70.dp, 100.dp
                                    ),
                                    rows = columnStats.map { stat ->
                                        listOf(
                                            stat.displayName,
                                            "${stat.fieldId}",
                                            stat.type?.typeName ?: "unknown",
                                            boundDisplay(stat.lowerBound),
                                            stat.lowerBound?.raw?.toHexShort() ?: "N/A",
                                            boundDisplay(stat.upperBound),
                                            stat.upperBound?.raw?.toHexShort() ?: "N/A",
                                            stat.valueCount?.let { formatCount(it) } ?: "N/A",
                                            stat.nullValueCount?.let { formatCount(it) }
                                                ?.plus(if (stat.isAllNull) " (all)" else "") ?: "N/A",
                                            stat.nanValueCount?.let { formatCount(it) } ?: "N/A",
                                            stat.columnSizeBytes?.let { formatBytes(it) } ?: "N/A",
                                        )
                                    }
                                )
                            }
                        }

                        RecursiveDataTableSection(node = node, graphModel = currentGraph)
                    }

                    is GraphNode.RowNode -> {
                        DetailTable {
                            val typeStr = when (node.content) {
                                1 -> "Position Delete Row"
                                2 -> "Equality Delete Row"
                                else -> "Data Row"
                            }
                            DetailRow("Column", "Value ($typeStr)", isHeader = true)
                            DetailRow("file_no", node.data["file_no"]?.toString() ?: "N/A")
                            DetailRow("row_idx", node.data["row_idx"]?.toString() ?: "N/A")
                            // Physical position, and whether a v3 deletion vector removes it.
                            // `row_idx` is this tool's counter over the sample; the position is
                            // the file's own, and it is the one a delete addresses.
                            node.filePosition?.let { position ->
                                DetailRow("Position in file", position.toString())
                                DetailRow(
                                    "Deleted",
                                    if (node.isDeletedByVector) {
                                        "yes — a deletion vector marks this position"
                                    } else {
                                        "not by a deletion vector"
                                    },
                                )
                            }
                            node.data.entries
                                .filterNot { it.key == "file_no" || it.key == "row_idx" }
                                .sortedBy { it.key }
                                .forEach { (k, v) -> DetailRow(k, "$v") }
                        }

                        RecursiveDataTableSection(node = node, graphModel = currentGraph)
                    }

                    is GraphNode.ErrorNode -> {
                        DetailTable {
                            DetailRow("Property", "Value", isHeader = true)
                            DetailRow("Title", node.title)
                            DetailRow("Stage", node.stage)
                            DetailRow("Path", node.path, copyable = true)
                            DetailRow("Message", node.message)
                        }
                        Spacer(Modifier.height(12.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Section("Stack Trace") {
                                Spacer(Modifier.weight(1f))
                                val trace = node.stackTrace
                                if (!trace.isNullOrBlank()) {
                                    TextButton(
                                        onClick = {
                                            val clipboard = java.awt.Toolkit.getDefaultToolkit().systemClipboard
                                            clipboard.setContents(java.awt.datatransfer.StringSelection(trace), null)
                                        },
                                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                                        modifier = Modifier.height(24.dp)
                                    ) {
                                        Text("Copy", fontSize = TypeScale.small)
                                    }
                                }
                            }
                        }
                        val stackScroll = rememberScrollState()
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .heightIn(max = 320.dp)
                                .clip(androidx.compose.foundation.shape.RoundedCornerShape(4.dp))
                                .border(1.dp, colors.outlineVariant, androidx.compose.foundation.shape.RoundedCornerShape(4.dp))
                                .background(colors.surfaceVariant)
                                .verticalScroll(stackScroll)
                                .padding(8.dp)
                        ) {
                            Text(
                                text = node.stackTrace ?: "N/A",
                                fontFamily = FontFamily.Monospace,
                                fontSize = TypeScale.small
                            )
                        }
                    }
                    is GraphNode.PaimonSnapshotNode -> {
                        DetailTable {
                            DetailRow("Property", "Value", isHeader = true)
                            DetailRow("Snapshot ID", "${node.data.id ?: "N/A"}")
                            // Which snapshot/ the file came from: a branch's ids are its own, so
                            // this is the row that tells its snapshot 2 from main's.
                            DetailRow("Branch", node.branch ?: "$MAIN_BRANCH — the table's own snapshot/")
                            // A tag is a copy of the snapshot file under tag/, and after an expiry
                            // it can be the only copy — which is a different thing from a commit a
                            // reader can time-travel to by id, so the panel says which it is.
                            DetailRow("Tags", node.tags.joinToString(", ").ifEmpty { "none" })
                            if (node.retainedByTagOnly) {
                                DetailRow("Retained By", "its tag only — expired from snapshot/, files kept on disk by the tag")
                            }
                            DetailRow("Version", "${node.data.version ?: "N/A"}")
                            DetailRow("Schema ID", "${node.data.schemaId ?: "N/A"}")
                            DetailRow("Commit Kind", node.commitKind ?: "N/A")
                            DetailRow("Commit User", node.data.commitUser ?: "N/A", copyable = true)
                            DetailRow("Commit Identifier", "${node.data.commitIdentifier ?: "N/A"}")
                            DetailRow("Timestamp", ui.formatTimestamp(node.data.timeMillis))
                            // The three record counts are checked below, against the manifests.
                            DetailRow("Watermark", "${node.data.watermark ?: "N/A"}")
                            DetailRow("Base Manifest List", node.data.baseManifestList ?: "N/A", copyable = true)
                            DetailRow("Delta Manifest List", node.data.deltaManifestList ?: "N/A", copyable = true)
                            DetailRow("Changelog Manifest List", node.data.changelogManifestList ?: "N/A", copyable = true)
                            DetailRow("Index Manifest", node.data.indexManifest ?: "N/A", copyable = true)
                            DetailRow("Statistics File", node.data.statistics ?: "N/A", copyable = true)
                            // Written from snapshot version 3. Absent on an older table, which is
                            // a different thing from a table that has produced no rows yet.
                            DetailRow("Next Row ID", "${node.data.nextRowId ?: "not recorded"}")
                        }
                        PaimonRecordsSection(node)
                        PaimonIndexFilesSection(node)
                        PaimonStatisticsSection(node)
                        RecursiveDataTableSection(node = node, graphModel = currentGraph)
                    }
                    is GraphNode.PaimonSchemaNode -> {
                        DetailTable {
                            DetailRow("Property", "Value", isHeader = true)
                            DetailRow("Schema ID", "${node.data.id ?: "N/A"}")
                            DetailRow("Highest Field ID", "${node.data.highestFieldId ?: "N/A"}")
                            DetailRow("Partition Keys", node.data.partitionKeys.joinToString(", ").ifEmpty { "N/A" })
                            DetailRow("Primary Keys", node.data.primaryKeys.joinToString(", ").ifEmpty { "N/A" })
                            DetailRow("Comment", node.data.comment ?: "N/A")
                        }
                        if (node.data.fields.isNotEmpty()) {
                            Section("Fields") {
                                DetailTable {
                                    DetailRow("Name", "Type", isHeader = true)
                                    node.data.fields.forEach { field ->
                                        DetailRow(field.name ?: "?", field.type ?: "?")
                                    }
                                }
                            }
                        }
                        if (node.data.options.isNotEmpty()) {
                            Section("Options") {
                                DetailTable {
                                    DetailRow("Key", "Value", isHeader = true)
                                    node.data.options.forEach { (k, v) -> DetailRow(k, v) }
                                }
                            }
                        }
                    }
                    is GraphNode.PaimonManifestListNode -> {
                        DetailTable {
                            DetailRow("Property", "Value", isHeader = true)
                            DetailRow("Kind", node.kind)
                            DetailRow("Manifests", formatCount(node.manifestCount))
                            DetailRow("Path", node.localPath ?: "N/A", copyable = true)
                        }
                        RecursiveDataTableSection(node = node, graphModel = currentGraph)
                    }
                    is GraphNode.PaimonManifestNode -> {
                        DetailTable {
                            DetailRow("Property", "Value", isHeader = true)
                            DetailRow("File Name", node.data.fileName ?: "N/A", copyable = true)
                            DetailRow("File Size", "${node.data.fileSize ?: "N/A"}")
                            DetailRow("Added Files", "${node.data.numAddedFiles ?: "N/A"}")
                            DetailRow("Deleted Files", "${node.data.numDeletedFiles ?: "N/A"}")
                            DetailRow("Schema ID", "${node.data.schemaId ?: "N/A"}")
                        }
                        // The same section the Iceberg manifest has, for the same reason: a scan
                        // plans against these without opening the manifest, and nothing on the
                        // read path checks them. Paimon records ranges as well as counts, and the
                        // partition minimum is per column — see paimonManifestTallies.
                        Section("Recorded Summary") {
                            Text(
                                "The manifest list carries these so a scan can plan without opening this " +
                                    "manifest — the entry counts, the bucket and level ranges, and a per-column " +
                                    "minimum and maximum over the entries' partitions. Each sits beside the same " +
                                    "figure folded from the entries.",
                                fontSize = TypeScale.small,
                                color = colors.onSurfaceVariant,
                                modifier = Modifier.padding(bottom = 4.dp),
                            )
                            val tallies = paimonManifestTallies(node.data, node.entries, node.partitionMin, node.partitionMax)
                            WideTable(
                                headers = listOf("Agrees", "Figure", "In the entries", "Recorded"),
                                columnWidths = listOf(110.dp, 170.dp, 160.dp, 160.dp),
                                rows = tallies.map { tally ->
                                    listOf(
                                        when (tally.agrees) {
                                            true -> "yes"
                                            false -> "NO"
                                            null -> "nothing to check"
                                        },
                                        tally.label,
                                        tally.counted ?: "no entries",
                                        tally.recorded ?: "not recorded",
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
                        PaimonReplayTraceSection(node)
                        RecursiveDataTableSection(node = node, graphModel = currentGraph)
                    }
                    is GraphNode.PaimonDataFileNode -> {
                        val file = node.entry.file
                        DetailTable {
                            DetailRow("Property", "Value", isHeader = true)
                            DetailRow("File Name", file?.fileName ?: "N/A", copyable = true)
                            DetailRow("Kind", if (node.operationKind == 1) "DELETE" else "ADD")
                            // Decoded from the entry's _PARTITION, and it is the directory the
                            // file lives under: the value a reader wants beside the text Paimon
                            // wrote in the path, which for a date is its epoch day.
                            val partition = node.partition
                            DetailRow(
                                "Partition",
                                when {
                                    partition == null -> "not decoded — the entry's partition could not be read against the schema"
                                    partition.values.isEmpty() -> "none — the table is unpartitioned"
                                    else -> partition.display + " (${partition.path})"
                                },
                            )
                            DetailRow("Bucket", "${node.bucket ?: "N/A"}")
                            DetailRow("Total Buckets", "${node.entry.totalBuckets ?: "N/A"}")
                            DetailRow("LSM Level", "${node.level ?: "N/A"}")
                            DetailRow("File Size", "${file?.fileSize ?: "N/A"}")
                            DetailRow("Row Count", "${file?.rowCount ?: "N/A"}")
                            DetailRow("Schema ID", "${file?.schemaId ?: "N/A"}")
                            DetailRow("Min Seq", "${file?.minSequenceNumber ?: "N/A"}")
                            DetailRow("Max Seq", "${file?.maxSequenceNumber ?: "N/A"}")
                            DetailRow("Creation Time", ui.formatTimestamp(file?.creationTime))
                            // The trimmed primary key's range — which keys a lookup can find in
                            // this file — and two facts about how the file was written.
                            val keyMin = node.keyMin
                            val keyMax = node.keyMax
                            DetailRow(
                                "Key Range",
                                when {
                                    keyMin == null || keyMax == null -> "none — no primary key outside the partition, or not decoded"
                                    else -> keyMin.joinToString(", ") { "${it.name}=${it.display}" } +
                                        " .. " + keyMax.joinToString(", ") { "${it.name}=${it.display}" }
                                },
                            )
                            DetailRow(
                                "Delete Rows",
                                file?.deleteRowCount?.let { "${formatCount(it)} — rows of kind -D/-U inside the file, not rows a vector marks" } ?: "N/A",
                            )
                            DetailRow(
                                "Source",
                                when (file?.fileSource) {
                                    PaimonFileSource.APPEND -> "APPEND — written by a commit"
                                    PaimonFileSource.COMPACT -> "COMPACT — written by a compaction"
                                    null -> "N/A"
                                    else -> "${file.fileSource}"
                                },
                            )
                            file?.externalPath?.let { DetailRow("External Path", it, copyable = true) }
                            file?.firstRowId?.let { DetailRow("First Row ID", "$it") }
                            // Where the file index lives is decided by its size against
                            // file-index.in-manifest-threshold: beside the data file and named in
                            // _EXTRA_FILES, or carried in the entry. Both are stated, and "none" is
                            // an answer — a table with no file-index.* property has none.
                            val embedded = file?.embeddedFileIndex
                            val indexFiles = file?.extraFiles.orEmpty().filter { it.endsWith(".index") }
                            DetailRow(
                                "File Index",
                                when {
                                    embedded != null -> "embedded in the manifest entry, ${formatBytesExact(embedded.size.toLong())}"
                                    indexFiles.isNotEmpty() -> indexFiles.joinToString(", ") { "$it, beside the data file" }
                                    else -> "none"
                                },
                            )
                            if (!file?.extraFiles.isNullOrEmpty()) DetailRow("Extra Files", file?.extraFiles.orEmpty().joinToString(", "))
                        }
                        // The file's own bounds, the same section the Iceberg data file has: a
                        // scan skips a file whose bounds exclude the predicate without opening it.
                        val bounds = node.columnBounds
                        if (bounds != null) {
                            Section("Column Bounds (${formatCount(bounds.size)})") {
                                Text(
                                    "From the entry's _VALUE_STATS: a per-column minimum, maximum and null " +
                                        "count over the rows in this file, two BinaryRows decoded against the " +
                                        "schema the manifest names. A string bound is the whole value, not a " +
                                        "truncated prefix.",
                                    fontSize = TypeScale.small,
                                    color = colors.onSurfaceVariant,
                                    modifier = Modifier.padding(bottom = 4.dp),
                                )
                                WideTable(
                                    headers = listOf("Column", "Minimum", "Maximum", "Nulls", "Type"),
                                    columnWidths = listOf(140.dp, 180.dp, 180.dp, 70.dp, 150.dp),
                                    rows = bounds.map { bound ->
                                        listOf(
                                            bound.name,
                                            if (bound.decoded) bound.min?.toString() ?: "null" else "not decoded",
                                            if (bound.decoded) bound.max?.toString() ?: "null" else "not decoded",
                                            bound.nullCount?.let { formatCount(it) } ?: "N/A",
                                            bound.type,
                                        )
                                    },
                                )
                            }
                        }
                        RecursiveDataTableSection(node = node, graphModel = currentGraph)
                    }
                    is GraphNode.GroupNode -> {
                        Section("Not Drawn") {
                            Text(
                                "The graph draws a page of siblings at a time. This stands for the " +
                                    "${formatCount(node.memberCount)} ${node.kind.plural} after the ones above it, and for " +
                                    "everything below them — ${formatCount(node.hiddenNodeCount)} nodes in all. " +
                                    "The metadata behind them is read and counted either way: the table " +
                                    "summary and every parent's own figures cover the whole table, drawn or not.",
                                fontSize = TypeScale.small,
                                color = colors.onSurfaceVariant,
                                modifier = Modifier.padding(bottom = 8.dp),
                            )
                            if (node.hiddenErrorCount > 0) {
                                Text(
                                    "${formatCount(node.hiddenErrorCount)} read errors are inside this group. " +
                                        "Open it to see which files failed.",
                                    fontSize = TypeScale.small,
                                    fontWeight = FontWeight.SemiBold,
                                    color = colors.error,
                                    modifier = Modifier.padding(bottom = 8.dp),
                                )
                            }
                            // FlowRow, not Row: the second label grows with the count, and a Row
                            // would place the button that does not fit past the panel's edge, where
                            // Compose neither wraps it nor clips it.
                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Button(onClick = { onExpandGroup(node.id) }) {
                                    Text("Show the next page", fontSize = TypeScale.small)
                                }
                                // The whole tail, for the reader who would rather wait than click
                                // two hundred times. The count is on the button because it is what
                                // decides whether they want to; what it costs in nodes is the
                                // paragraph above.
                                OutlinedButton(onClick = { onExpandGroupFully(node) }) {
                                    Text(
                                        "Show all ${formatCount(node.memberCount)} ${node.kind.plural}",
                                        fontSize = TypeScale.small,
                                    )
                                }
                            }
                            Spacer(Modifier.height(12.dp))
                            DetailTable {
                                DetailRow("Property", "Value", isHeader = true)
                                DetailRow("Kind", node.kind.plural)
                                DetailRow("Siblings not drawn", formatCount(node.memberCount))
                                DetailRow("Nodes not drawn", formatCount(node.hiddenNodeCount))
                                DetailRow("Read errors inside", formatCount(node.hiddenErrorCount))
                                DetailRow("Page", "${node.pageIndex}")
                                DetailRow("Parent Node ID", node.parentId, copyable = true)
                            }
                        }
                    }
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
@Composable
private fun SnapshotComparison(from: ComparableSnapshot, to: ComparableSnapshot) {
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
private fun DeletionVectorSection(node: GraphNode.FileNode) {
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
private fun DeletesReachingSection(node: GraphNode.FileNode, graph: GraphModel) {
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
 * The snapshot file's three record counts against the manifests it names — `paimonRecordTallies`.
 * The total is checked against the replay's live rows, which is the same walk the comparison
 * runs; the delta the way the writer sums it, by entry; the changelog by its entries' rows. Read
 * off the node's deferred tallies, so opening the panel twice replays once.
 */
@Composable
private fun PaimonRecordsSection(node: GraphNode.PaimonSnapshotNode) {
    val colors = MaterialTheme.colorScheme
    val tallies = node.recordTallies.orEmpty()
    val disagreeing = tallies.count { it.agrees == false }
    val title = "Recorded Records" + if (disagreeing > 0) " — $disagreeing DISAGREE" else ""
    Section(title) {
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
private fun PaimonIndexFilesSection(node: GraphNode.PaimonSnapshotNode) {
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
private fun PaimonStatisticsSection(node: GraphNode.PaimonSnapshotNode) {
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
private fun DeleteReachSection(node: GraphNode.SnapshotNode, children: List<GraphNode>) {
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
@Composable
private fun TotalsSection(node: GraphNode.SnapshotNode) {
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
private fun CommitSection(change: SnapshotChange) {
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
private fun CountedSection(title: String, count: Int, nothing: String, content: @Composable () -> Unit) {
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
private fun SchemaEvolutionSection(metadataChildren: List<GraphNode.MetadataNode>) {
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
private fun PropertiesEvolutionSection(metadataChildren: List<GraphNode.MetadataNode>) {
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
