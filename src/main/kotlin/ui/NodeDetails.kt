package ui

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
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
import model.GraphModel
import model.GraphNode
import model.KeyValuePairBytes
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
}

private fun nodeTitle(node: GraphNode): String = when (node) {
    is GraphNode.TableNode -> "TABLE ${node.summary.tableName}"
    is GraphNode.MetadataNode -> "METADATA ${node.simpleId}"
    is GraphNode.SnapshotNode -> "SNAPSHOT ${node.simpleId}"
    is GraphNode.ManifestNode -> "MANIFEST ${node.simpleId}: ${if (node.data.content == 1) "DELETE" else "DATA"}"
    is GraphNode.FileNode -> "FILE ${node.simpleId}: ${when (node.data.content ?: 0) {
        1 -> "POS DELETE"
        2 -> "EQ DELETE"
        else -> "DATA"
    }}"
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

private fun longs(values: List<Long>?): String =
    if (values.isNullOrEmpty()) "[]" else values.joinToString(", ")

private fun currentSnapshotLabel(currentSnapshotId: Long?): String = when (currentSnapshotId) {
    null -> "None"
    -1L -> "None (-1)"
    else -> currentSnapshotId.toString()
}

private fun timelineContentRank(content: Int?): Int = when (content ?: 0) {
    2 -> 0
    0 -> 1
    1 -> 2
    else -> 3
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
    )
    val allDataColumns = rows
        .flatMap { descendant -> effectiveDataByRowId[descendant.rowNode.id].orEmpty().keys }
        .filterNot { it in metaColumns }
        .distinct()
    val orderedDataColumns = identifierFields.filter { it in allDataColumns } +
        allDataColumns.filterNot { it in identifierFields }.sorted()

    Spacer(Modifier.height(16.dp))
    SectionTitle("Changelog")

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
}

@Composable
private fun SectionTitle(title: String) {
    Text(title, fontWeight = FontWeight.Bold, fontSize = 14.sp)
    Spacer(Modifier.height(4.dp))
}

@Composable
private fun WideTable(
    headers: List<String>,
    rows: List<List<String>>,
    columnWidth: Dp = 180.dp,
) {
    val colors = MaterialTheme.colorScheme
    val horizontalState = rememberScrollState()
    val tableWidth = (headers.size * columnWidth.value).dp + ((headers.size - 1).coerceAtLeast(0) * 9).dp + 16.dp
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(horizontalState)
    ) {
        Column(
            Modifier
                .width(tableWidth)
                .clip(androidx.compose.foundation.shape.RoundedCornerShape(4.dp))
                .border(1.dp, colors.outlineVariant, androidx.compose.foundation.shape.RoundedCornerShape(4.dp))
        ) {
            WideTableRow(headers, headers.size, columnWidth, isHeader = true)
            rows.forEach { row -> WideTableRow(row, headers.size, columnWidth, isHeader = false) }
        }
    }
}

@Composable
private fun WideTableRow(cells: List<String>, columns: Int, columnWidth: Dp, isHeader: Boolean) {
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
            Text(
                text = cell,
                modifier = Modifier.width(columnWidth),
                fontSize = 11.sp,
                fontWeight = if (isHeader) FontWeight.Bold else FontWeight.Normal,
                fontFamily = if (isHeader) null else FontFamily.Monospace,
                maxLines = if (isHeader) 2 else 8,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
    HorizontalDivider(color = colors.outlineVariant, thickness = 0.5.dp)
}

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
fun NodeDetailsContent(graphModel: GraphModel?, selectedNodeIds: Set<String>) {
    val colors = MaterialTheme.colorScheme
    SelectionContainer {
        CompositionLocalProvider(LocalContentColor provides colors.onSurface) {
            if (selectedNodeIds.isEmpty()) {
                Text(
                    "Select a node to view details.",
                    fontSize = 12.sp,
                    color = colors.onSurfaceVariant,
                    modifier = Modifier.padding(8.dp)
                )
                return@CompositionLocalProvider
            }
            if (selectedNodeIds.size > 1) {
                val multiGraph = graphModel
                Column(Modifier.padding(8.dp)) {
                    Text("${selectedNodeIds.size} Nodes Selected", fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(8.dp))
                    if (multiGraph == null) {
                        Text("(graph not loaded)", fontSize = 12.sp, color = colors.onSurfaceVariant)
                    } else {
                        DetailTable {
                            DetailRow("Type / ID", "Key", isHeader = true)
                            selectedNodeIds.forEach { id ->
                                val n = multiGraph.nodeById[id] ?: return@forEach
                                DetailRow(nodeTitle(n), multiSelectKey(n))
                            }
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
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(nodeTitle(node), fontWeight = FontWeight.Bold, fontSize = 16.sp, modifier = Modifier.weight(1f))
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
                                Text("Reveal", fontSize = 11.sp)
                            }
                        }
                    }
                    Text("Node ID: ${node.id}", fontSize = 11.sp, color = colors.onSurfaceVariant)
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
                            DetailRow("version-hint.text", summary.versionHintText.ifBlank { "N/A" })
                            DetailRow("Table Created (Inferred)", formatTimestamp(summary.tableCreationMs))
                            DetailRow("Table Last Updated (Inferred)", formatTimestamp(summary.tableLastUpdateMs))
                            DetailRow("Last Updated (UI)", formatTimestamp(summary.lastUpdatedMs))
                        }

                        RecursiveDataTableSection(node = node, graphModel = currentGraph)

                        Spacer(Modifier.height(16.dp))
                        SectionTitle("Metadata Files")
                        DetailTable {
                            DetailRow("Metric", "Value", isHeader = true)
                            DetailRow("File Count", "${summary.metadataFileCount}")
                            DetailRow("Known", "${summary.metadataFileTimes.knownCount}")
                            DetailRow("Missing", "${summary.metadataFileTimes.missingCount}")
                            DetailRow("Oldest", formatTimestamp(summary.metadataFileTimes.oldestMs))
                            DetailRow("Latest", formatTimestamp(summary.metadataFileTimes.newestMs))
                        }

                        Spacer(Modifier.height(16.dp))
                        SectionTitle("Snapshot Manifest Lists")
                        DetailTable {
                            DetailRow("Metric", "Value", isHeader = true)
                            DetailRow("Unique Snapshots", "${summary.snapshotCount}")
                            DetailRow("File Count", "${summary.snapshotManifestListFileCount}")
                            DetailRow("Known", "${summary.snapshotManifestListFileTimes.knownCount}")
                            DetailRow("Missing", "${summary.snapshotManifestListFileTimes.missingCount}")
                            DetailRow("Oldest", formatTimestamp(summary.snapshotManifestListFileTimes.oldestMs))
                            DetailRow("Latest", formatTimestamp(summary.snapshotManifestListFileTimes.newestMs))
                        }

                        Spacer(Modifier.height(16.dp))
                        SectionTitle("Manifest Files")
                        DetailTable {
                            DetailRow("Metric", "Value", isHeader = true)
                            DetailRow("File Count", "${summary.manifestCount}")
                            DetailRow("Known", "${summary.manifestFileTimes.knownCount}")
                            DetailRow("Missing", "${summary.manifestFileTimes.missingCount}")
                            DetailRow("Breakdown", "${summary.dataManifestCount} data / ${summary.deleteManifestCount} delete")
                            DetailRow("Oldest", formatTimestamp(summary.manifestFileTimes.oldestMs))
                            DetailRow("Latest", formatTimestamp(summary.manifestFileTimes.newestMs))
                            DetailRow("Manifest Entries", "${summary.manifestEntryCount}")
                        }

                        Spacer(Modifier.height(16.dp))
                        SectionTitle("Data Files")
                        DetailTable {
                            DetailRow("Metric", "Value", isHeader = true)
                            DetailRow("File Count", "${summary.uniqueDataFileCount}")
                            DetailRow("Known", "${summary.dataFileTimes.knownCount}")
                            DetailRow("Missing", "${summary.dataFileTimes.missingCount}")
                            DetailRow("Breakdown", "${summary.dataFileCount} data / ${summary.posDeleteFileCount} pos-del / ${summary.eqDeleteFileCount} eq-del")
                            DetailRow("Oldest", formatTimestamp(summary.dataFileTimes.oldestMs))
                            DetailRow("Latest", formatTimestamp(summary.dataFileTimes.newestMs))
                            DetailRow("Total Records", "${summary.totalRecordCount}")
                        }

                        if (mergedMetadataRows.isNotEmpty()) {
                            Spacer(Modifier.height(16.dp))
                            SectionTitle("Metadata Nodes & Timeline")
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
                            DetailRow("Total Snapshots", "${node.data.snapshots.size}")
                            DetailRow("Total Schemas", "${node.data.schemas.size}")
                            DetailRow("Total Partition Specs", "${node.data.partitionSpecs.size}")
                            DetailRow("Total Sort Orders", "${node.data.sortOrders.size}")
                            DetailRow("Total Refs", "${node.data.refs.size}")
                            DetailRow("Statistics Entries", "${node.data.statistics.size}")
                            DetailRow("Partition Statistics Entries", "${node.data.partitionStatistics.size}")
                            DetailRow("Snapshot Log Entries", "${node.data.snapshotLog.size}")
                            DetailRow("Metadata Log Entries", "${node.data.metadataLog.size}")
                        }

                        RecursiveDataTableSection(node = node, graphModel = currentGraph)

                        if (node.data.properties.isNotEmpty()) {
                            Spacer(Modifier.height(16.dp))
                            SectionTitle("Properties")
                            DetailTable {
                                DetailRow("Key", "Value", isHeader = true)
                                node.data.properties.toSortedMap().forEach { (k, v) ->
                                    DetailRow(k, v)
                                }
                            }
                        }

                        if (node.data.schemas.isNotEmpty()) {
                            Spacer(Modifier.height(16.dp))
                            SectionTitle("Schemas")
                            node.data.schemas
                                .sortedBy { it.schemaId ?: Int.MAX_VALUE }
                                .forEach { schema ->
                                    Text(
                                        "Schema ${schema.schemaId ?: "Unknown"}",
                                        fontWeight = FontWeight.SemiBold,
                                        fontSize = 13.sp
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

                        if (node.data.partitionSpecs.isNotEmpty()) {
                            Spacer(Modifier.height(16.dp))
                            SectionTitle("Partition Specs")
                            node.data.partitionSpecs
                                .sortedBy { it.specId ?: Int.MAX_VALUE }
                                .forEach { spec ->
                                    Text(
                                        "Spec ${spec.specId ?: "Unknown"}",
                                        fontWeight = FontWeight.SemiBold,
                                        fontSize = 13.sp
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

                        if (node.data.sortOrders.isNotEmpty()) {
                            Spacer(Modifier.height(16.dp))
                            SectionTitle("Sort Orders")
                            node.data.sortOrders
                                .sortedBy { it.orderId ?: Int.MAX_VALUE }
                                .forEach { order ->
                                    Text(
                                        "Order ${order.orderId ?: "Unknown"}",
                                        fontWeight = FontWeight.SemiBold,
                                        fontSize = 13.sp
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

                        if (node.data.refs.isNotEmpty()) {
                            Spacer(Modifier.height(16.dp))
                            SectionTitle("Refs")
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

                        if (node.data.snapshots.isNotEmpty()) {
                            Spacer(Modifier.height(16.dp))
                            SectionTitle("Snapshots")
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
                                        "${snapshot.sequenceNumber ?: "N/A"}",
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

                        if (node.data.snapshotLog.isNotEmpty()) {
                            Spacer(Modifier.height(16.dp))
                            SectionTitle("Snapshot Log")
                            DetailTable {
                                DetailRow("Timestamp", "Snapshot ID", isHeader = true)
                                renderSnapshotLogRows(node.data.snapshotLog).forEach { row ->
                                    DetailRow(row.getOrElse(0) { "N/A" }, row.getOrElse(1) { "N/A" })
                                }
                            }
                        }

                        if (node.data.metadataLog.isNotEmpty()) {
                            Spacer(Modifier.height(16.dp))
                            SectionTitle("Metadata Log")
                            DetailTable {
                                DetailRow("Timestamp", "Metadata File", isHeader = true)
                                renderMetadataLogRows(node.data.metadataLog).forEach { row ->
                                    DetailRow(row.getOrElse(0) { "N/A" }, row.getOrElse(1) { "N/A" })
                                }
                            }
                        }

                        if (node.data.statistics.isNotEmpty()) {
                            Spacer(Modifier.height(16.dp))
                            SectionTitle("Statistics")
                            WideTable(
                                headers = listOf("Index", "Value"),
                                rows = node.data.statistics.mapIndexed { index, stat ->
                                    listOf("${index + 1}", normalizeText(stat.toString()))
                                }
                            )
                        }

                        if (node.data.partitionStatistics.isNotEmpty()) {
                            Spacer(Modifier.height(16.dp))
                            SectionTitle("Partition Statistics")
                            WideTable(
                                headers = listOf("Index", "Value"),
                                rows = node.data.partitionStatistics.mapIndexed { index, stat ->
                                    listOf("${index + 1}", normalizeText(stat.toString()))
                                }
                            )
                        }

                        Spacer(Modifier.height(16.dp))
                        SectionTitle("Raw metadata.json")
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
                                    fontSize = 11.sp
                                )
                            }
                        } else {
                            DetailTable {
                                DetailRow("Raw JSON", "N/A", isHeader = true)
                            }
                        }
                    }

                    is GraphNode.SnapshotNode -> {
                        DetailTable {
                            DetailRow("Property", "Value", isHeader = true)
                            DetailRow("Snapshot ID", "${node.data.snapshotId}")
                            DetailRow("Parent ID", "${node.data.parentSnapshotId ?: "None"}")
                            DetailRow("Sequence Number", "${node.data.sequenceNumber ?: "N/A"}")
                            DetailRow("Schema ID", "${node.data.schemaId ?: "N/A"}")
                            DetailRow("Timestamp", formatTimestamp(node.data.timestampMs))
                            val manifestList = node.data.manifestList
                            val manifestListLabel = if (manifestList == null) "N/A" else "${manifestList.substringAfterLast("/")} ($manifestList)"
                            DetailRow("Manifest List", manifestListLabel)
                        }

                        RecursiveDataTableSection(node = node, graphModel = currentGraph)

                        if (node.data.summary.isNotEmpty()) {
                            Spacer(Modifier.height(16.dp))
                            SectionTitle("Summary")
                            DetailTable {
                                DetailRow("Key", "Value", isHeader = true)
                                node.data.summary.toSortedMap().forEach { (k, v) ->
                                    DetailRow(k, v)
                                }
                            }
                        }

                        val manifestChildren = children
                            .filterIsInstance<GraphNode.ManifestNode>()
                            .sortedWith(
                                compareBy(
                                    { it.data.sequenceNumber ?: Int.MAX_VALUE },
                                    { it.data.minSequenceNumber ?: Int.MAX_VALUE },
                                    { manifestContentRank(it.data.content) },
                                    { it.data.manifestPath ?: "" }
                                )
                            )

                        if (manifestChildren.isNotEmpty()) {
                            Spacer(Modifier.height(16.dp))
                            SectionTitle("Manifest List Rows")
                            WideTable(
                                headers = listOf(
                                    "Apply Order",
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
                                    listOf(
                                        "${index + 1}",
                                        normalizeText(manifest.manifestPath),
                                        if (manifest.content == 1) "Deletes (1)" else "Data (0)",
                                        "${manifest.manifestLength ?: "N/A"}",
                                        "${manifest.partitionSpecId ?: "N/A"}",
                                        "${manifest.sequenceNumber ?: "N/A"}",
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

                    is GraphNode.ManifestNode -> {
                        DetailTable {
                            val contentType = when (val c = node.data.content) {
                                1 -> "Deletes ($c)"
                                0 -> "Data ($c)"
                                else -> "Unknown ($c)"
                            }
                            DetailRow("Property", "Value", isHeader = true)
                            DetailRow("Content Type", contentType)
                            DetailRow("Sequence Num.", "${node.data.sequenceNumber ?: "N/A"}")
                            DetailRow("Min Sequence Num.", "${node.data.minSequenceNumber ?: "N/A"}")
                            DetailRow("Partition Spec ID", "${node.data.partitionSpecId ?: "N/A"}")
                            DetailRow("Added Snapshot", "${node.data.addedSnapshotId ?: "N/A"}")
                            DetailRow("Added Files", "${node.data.addedFilesCount ?: 0}")
                            DetailRow("Existing Files", "${node.data.existingFilesCount ?: 0}")
                            DetailRow("Deleted Files", "${node.data.deletedFilesCount ?: 0}")
                            DetailRow("Added Rows", "${node.data.addedRowsCount ?: 0}")
                            DetailRow("Existing Rows", "${node.data.existingRowsCount ?: 0}")
                            DetailRow("Deleted Rows", "${node.data.deletedRowsCount ?: 0}")
                            DetailRow("Manifest Length", "${node.data.manifestLength ?: 0} bytes")
                            val manifestPath = node.data.manifestPath
                            val manifestPathLabel = if (manifestPath == null) "N/A" else "${manifestPath.substringAfterLast("/")} ($manifestPath)"
                            DetailRow("Path", manifestPathLabel, copyable = true)
                        }

                        RecursiveDataTableSection(node = node, graphModel = currentGraph)

                        val fileChildren = children
                            .filterIsInstance<GraphNode.FileNode>()
                            .sortedWith(
                                compareBy(
                                    { it.data.dataSequenceNumber ?: it.entry.sequenceNumber ?: Long.MAX_VALUE },
                                    { it.entry.fileSequenceNumber ?: Long.MAX_VALUE },
                                    { timelineContentRank(it.data.content) },
                                    { it.simpleId }
                                )
                            )

                        if (fileChildren.isNotEmpty()) {
                            Spacer(Modifier.height(16.dp))
                            SectionTitle("Manifest Entries (Data File Details)")
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
                                rows = fileChildren.mapIndexed { index, fileNode ->
                                    val data = fileNode.data
                                    val status = when (fileNode.entry.status) {
                                        0 -> "EXISTING (0)"
                                        1 -> "ADDED (1)"
                                        2 -> "DELETED (2)"
                                        else -> "Unknown (${fileNode.entry.status})"
                                    }
                                    val content = when (data.content ?: 0) {
                                        1 -> "Position Delete (1)"
                                        2 -> "Equality Delete (2)"
                                        else -> "Data (0)"
                                    }
                                    listOf(
                                        "${index + 1}",
                                        "${fileNode.simpleId}",
                                        status,
                                        content,
                                        "${fileNode.entry.snapshotId ?: "N/A"}",
                                        "${fileNode.entry.sequenceNumber ?: "N/A"}",
                                        "${fileNode.entry.fileSequenceNumber ?: "N/A"}",
                                        normalizeText(data.filePath),
                                        data.fileFormat ?: "N/A",
                                        "${data.recordCount ?: 0}",
                                        "${data.fileSizeInBytes ?: 0}",
                                        "N/A",
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
                            DetailRow("Sequence Num.", "${node.entry.sequenceNumber ?: "N/A"}")
                            DetailRow("File Seq. Num.", "${node.entry.fileSequenceNumber ?: "N/A"}")
                            DetailRow("File Format", "${node.data.fileFormat ?: "N/A"}")
                            DetailRow("Record Count", "${node.data.recordCount ?: 0}")
                            DetailRow("File Size", "${node.data.fileSizeInBytes ?: 0} bytes")
                            DetailRow("Sort Order ID", "${node.data.sortOrderId ?: "N/A"}")
                            DetailRow("Split Offsets", longs(node.data.splitOffsets))
                            DetailRow("Equality IDs", node.data.equalityIds?.joinToString(", ") ?: "N/A")
                            DetailRow("Partition", "N/A")
                            DetailRow("Column Sizes", kvLongs(node.data.columnSizes))
                            DetailRow("Value Counts", kvLongs(node.data.valueCounts))
                            DetailRow("Null Value Counts", kvLongs(node.data.nullValueCounts))
                            DetailRow("NaN Value Counts", kvLongs(node.data.nanValueCounts))
                            DetailRow("Lower Bounds", kvBytes(node.data.lowerBounds))
                            DetailRow("Upper Bounds", kvBytes(node.data.upperBounds))
                            val filePath = node.data.filePath
                            DetailRow("Path", "${filePath ?: "N/A"}", copyable = true)
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
                            SectionTitle("Stack Trace")
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
                                    Text("Copy", fontSize = 11.sp)
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
                                fontSize = 11.sp
                            )
                        }
                    }
                    is GraphNode.PaimonSnapshotNode -> {
                        DetailTable {
                            DetailRow("Property", "Value", isHeader = true)
                            DetailRow("Snapshot ID", "${node.data.id ?: "N/A"}")
                            DetailRow("Version", "${node.data.version ?: "N/A"}")
                            DetailRow("Schema ID", "${node.data.schemaId ?: "N/A"}")
                            DetailRow("Commit Kind", node.commitKind ?: "N/A")
                            DetailRow("Commit User", node.data.commitUser ?: "N/A", copyable = true)
                            DetailRow("Commit Identifier", "${node.data.commitIdentifier ?: "N/A"}")
                            DetailRow("Timestamp", ui.formatTimestamp(node.data.timeMillis))
                            DetailRow("Total Records", "${node.data.totalRecordCount ?: "N/A"}")
                            DetailRow("Delta Records", "${node.data.deltaRecordCount ?: "N/A"}")
                            DetailRow("Changelog Records", "${node.data.changelogRecordCount ?: "N/A"}")
                            DetailRow("Watermark", "${node.data.watermark ?: "N/A"}")
                            DetailRow("Base Manifest List", node.data.baseManifestList ?: "N/A", copyable = true)
                            DetailRow("Delta Manifest List", node.data.deltaManifestList ?: "N/A", copyable = true)
                            DetailRow("Changelog Manifest List", node.data.changelogManifestList ?: "N/A", copyable = true)
                            DetailRow("Index Manifest", node.data.indexManifest ?: "N/A", copyable = true)
                        }
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
                            Spacer(Modifier.height(12.dp))
                            SectionTitle("Fields")
                            DetailTable {
                                DetailRow("Name", "Type", isHeader = true)
                                node.data.fields.forEach { field ->
                                    DetailRow(field.name ?: "?", field.type ?: "?")
                                }
                            }
                        }
                        if (node.data.options.isNotEmpty()) {
                            Spacer(Modifier.height(12.dp))
                            SectionTitle("Options")
                            DetailTable {
                                DetailRow("Key", "Value", isHeader = true)
                                node.data.options.forEach { (k, v) -> DetailRow(k, v) }
                            }
                        }
                    }
                    is GraphNode.PaimonManifestListNode -> {
                        DetailTable {
                            DetailRow("Property", "Value", isHeader = true)
                            DetailRow("Kind", node.kind)
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
                        RecursiveDataTableSection(node = node, graphModel = currentGraph)
                    }
                    is GraphNode.PaimonDataFileNode -> {
                        val file = node.entry.file
                        DetailTable {
                            DetailRow("Property", "Value", isHeader = true)
                            DetailRow("File Name", file?.fileName ?: "N/A", copyable = true)
                            DetailRow("Kind", if (node.operationKind == 1) "DELETE" else "ADD")
                            DetailRow("Bucket", "${node.bucket ?: "N/A"}")
                            DetailRow("Total Buckets", "${node.entry.totalBuckets ?: "N/A"}")
                            DetailRow("LSM Level", "${node.level ?: "N/A"}")
                            DetailRow("File Size", "${file?.fileSize ?: "N/A"}")
                            DetailRow("Row Count", "${file?.rowCount ?: "N/A"}")
                            DetailRow("Schema ID", "${file?.schemaId ?: "N/A"}")
                            DetailRow("Min Seq", "${file?.minSequenceNumber ?: "N/A"}")
                            DetailRow("Max Seq", "${file?.maxSequenceNumber ?: "N/A"}")
                            DetailRow("Creation Time", ui.formatTimestamp(file?.creationTime))
                        }
                        RecursiveDataTableSection(node = node, graphModel = currentGraph)
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

    Spacer(Modifier.height(16.dp))
    SectionTitle("Schema Evolution")

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
        return
    }

    val colors = MaterialTheme.colorScheme

    changes.forEach { (fromId, toId, diffs) ->
        Text(
            "Schema $fromId \u2192 $toId",
            fontWeight = FontWeight.SemiBold,
            fontSize = 12.sp,
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
        fontSize = 12.sp,
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

    Spacer(Modifier.height(16.dp))
    SectionTitle("Table Properties")

    val colors = MaterialTheme.colorScheme

    // If only one metadata version, show a simple table
    if (versions.size == 1) {
        DetailTable {
            DetailRow("Key", "Value", isHeader = true)
            versions[0].props.toSortedMap().forEach { (k, v) ->
                DetailRow(k, v)
            }
        }
        return
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
            fontSize = 12.sp,
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
