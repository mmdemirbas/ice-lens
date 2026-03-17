package ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import model.GraphNode
import java.text.NumberFormat
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private fun manifestContentLabel(content: Int?): String =
    if (content == 1) "DELETE" else "DATA"

private fun fileContentLabel(content: Int?): String = when (content ?: 0) {
    1 -> "POS DELETE"
    2 -> "EQ DELETE"
    else -> "DATA"
}

private fun rowContentLabel(content: Int): String = when (content) {
    1 -> "POS DELETE ROW"
    2 -> "EQ DELETE ROW"
    else -> "DATA ROW"
}

private fun rowStatusShortLabel(content: Int): String = when (content) {
    1 -> "POS DEL"
    2 -> "EQ DEL"
    else -> "DATA"
}

private fun rowCardDetailEntries(node: GraphNode.RowNode): List<Map.Entry<String, Any>> {
    val metaKeys = setOf("file_no", "row_idx", "target_file", "target_file_no", "local_file_path")
    val filtered = node.resolvedData.entries.filter { (key, _) ->
        key !in metaKeys &&
            !(node.content == 1 && (key == "file_path" || key == "pos" || key == "position"))
    }
    if (filtered.isEmpty()) return emptyList()
    if (node.content != 0) return filtered

    val identifierSet = node.identifierFields.toSet()
    if (identifierSet.isEmpty()) return filtered

    val nonIdentifiers = filtered.filter { (key, _) -> key !in identifierSet }
    val identifiers = filtered.filter { (key, _) -> key in identifierSet }
    return if (nonIdentifiers.isNotEmpty()) nonIdentifiers + identifiers else filtered
}

private fun isPrimaryMetadataFile(fileName: String): Boolean =
    model.metadataVersionFromFileName(fileName) != null

private fun fileNameFromPath(path: String?): String {
    val raw = path?.trim().orEmpty()
    if (raw.isEmpty()) return "N/A"
    val normalized = raw.removeSuffix("/").removeSuffix("\\")
    val candidate = normalized.substringAfterLast('/').substringAfterLast('\\')
    return candidate.ifEmpty { normalized }
}

private fun formatCount(value: Long?): String =
    value?.let { NumberFormat.getIntegerInstance(Locale.US).format(it) } ?: "N/A"

private fun formatBytes(bytes: Long?): String {
    if (bytes == null) return "N/A"
    val units = arrayOf("B", "KB", "MB", "GB", "TB")
    var value = bytes.toDouble()
    var unitIndex = 0
    while (value >= 1024.0 && unitIndex < units.lastIndex) {
        value /= 1024.0
        unitIndex++
    }
    val compact = if (unitIndex == 0) String.format(Locale.US, "%.0f", value) else String.format(Locale.US, "%.1f", value)
    return "${formatCount(bytes)} bytes ($compact ${units[unitIndex]})"
}

@Composable
fun nodeCardTextPrimary(): Color =
    if (isDarkSurface(MaterialTheme.colorScheme.surface)) Color(0xFFE2E6EC) else Color(0xFF1A1C1E)

@Composable
fun nodeCardTextSecondary(): Color =
    if (isDarkSurface(MaterialTheme.colorScheme.surface)) Color(0xFFAEB5BF) else Color(0xFF3D4652)

fun getGraphNodeColor(node: GraphNode, dark: Boolean = false): Color = when (node) {
    is GraphNode.TableNode    -> if (dark) Color(0xFF4E3B32) else Color(0xFFD7CCC8)
    is GraphNode.MetadataNode -> if (isPrimaryMetadataFile(node.fileName))
        (if (dark) Color(0xFF4A2858) else Color(0xFFE1BEE7))
    else
        (if (dark) Color(0xFF3D3548) else Color(0xFFD7CFDE))
    is GraphNode.SnapshotNode -> if (dark) Color(0xFF1A3A5C) else Color(0xFFBBDEFB)
    is GraphNode.ManifestNode -> when (node.data.content) {
        1    -> if (dark) Color(0xFF5C2020) else Color(0xFFFFCDD2)
        else -> if (dark) Color(0xFF1E3D20) else Color(0xFFC8E6C9)
    }
    is GraphNode.FileNode     -> when (node.data.content ?: 0) {
        1    -> if (dark) Color(0xFF5C2020) else Color(0xFFFFCDD2)
        2    -> if (dark) Color(0xFF4A4020) else Color(0xFFFFF59D)
        else -> if (dark) Color(0xFF1E3D20) else Color(0xFFC8E6C9)
    }
    is GraphNode.RowNode      -> when (node.content) {
        1    -> if (dark) Color(0xFF5C2020) else Color(0xFFFFCDD2)
        2    -> if (dark) Color(0xFF4A4020) else Color(0xFFFFF59D)
        else -> if (dark) Color(0xFF1E3D20) else Color(0xFFC8E6C9)
    }
    is GraphNode.ErrorNode    -> if (dark) Color(0xFF4A1010) else Color(0xFFFFEBEE)
}

fun getGraphNodeBorderColor(node: GraphNode, dark: Boolean = false): Color = when (node) {
    is GraphNode.TableNode    -> if (dark) Color(0xFFBCAAA4) else Color(0xFF5D4037)
    is GraphNode.MetadataNode -> if (isPrimaryMetadataFile(node.fileName))
        (if (dark) Color(0xFFCE93D8) else Color(0xFF8E24AA))
    else
        (if (dark) Color(0xFFB0A4BA) else Color(0xFF6F6180))
    is GraphNode.SnapshotNode -> if (dark) Color(0xFF64B5F6) else Color(0xFF1976D2)
    is GraphNode.ManifestNode -> when (node.data.content) {
        1    -> if (dark) Color(0xFFEF9A9A) else Color(0xFFD32F2F)
        else -> if (dark) Color(0xFF81C784) else Color(0xFF388E3C)
    }
    is GraphNode.FileNode     -> when (node.data.content ?: 0) {
        1    -> if (dark) Color(0xFFEF9A9A) else Color(0xFFD32F2F)
        2    -> if (dark) Color(0xFFE6C56A) else Color(0xFFB26A00)
        else -> if (dark) Color(0xFF81C784) else Color(0xFF388E3C)
    }
    is GraphNode.RowNode      -> when (node.content) {
        1    -> if (dark) Color(0xFFEF9A9A) else Color(0xFFD32F2F)
        2    -> if (dark) Color(0xFFE6C56A) else Color(0xFFB26A00)
        else -> if (dark) Color(0xFF81C784) else Color(0xFF388E3C)
    }
    is GraphNode.ErrorNode    -> if (dark) Color(0xFFEF5350) else Color(0xFFB71C1C)
}

private val timestampFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS")
    .withZone(ZoneId.systemDefault())
private val utcTimestampFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS")
    .withZone(ZoneId.of("UTC"))

fun formatTimestamp(ms: Long?): String {
    if (ms == null) return "N/A"
    return try {
        val localZone = ZoneId.systemDefault().id
        val local = timestampFormatter.format(Instant.ofEpochMilli(ms))
        val utc = utcTimestampFormatter.format(Instant.ofEpochMilli(ms))
        "Local: $local ($localZone)\nUTC:   $utc (UTC)\nEpoch: $ms"
    } catch (e: Exception) {
        "$ms (Error)"
    }
}

@Composable
fun DetailTable(
    modifier: Modifier = Modifier,
    isDark: Boolean = false,
    content: @Composable () -> Unit
) {
    val colors = MaterialTheme.colorScheme
    val borderColor = if (isDark) colors.outline else colors.outlineVariant
    Column(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(4.dp))
            .border(1.dp, borderColor, RoundedCornerShape(4.dp))
    ) {
        content()
    }
}

@Composable
fun DetailRow(key: String, value: String, isHeader: Boolean = false, isDark: Boolean = false) {
    val colors = MaterialTheme.colorScheme
    val bgColor = if (isHeader) {
        if (isDark) colors.inverseSurface.copy(alpha = 0.58f) else colors.surfaceVariant
    } else {
        Color.Transparent
    }
    val keyColor = if (isDark) {
        if (isHeader) colors.inverseOnSurface else colors.inverseOnSurface.copy(alpha = 0.82f)
    } else {
        if (isHeader) colors.onSurface else colors.onSurfaceVariant
    }
    val valColor = if (isDark) colors.inverseOnSurface else colors.onSurface
    val dividerColor = if (isDark) colors.inverseOnSurface.copy(alpha = 0.28f) else colors.outlineVariant

    Row(
        Modifier
            .fillMaxWidth()
            .background(bgColor)
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = key,
            modifier = Modifier.weight(0.20f),
            fontSize = 11.sp,
            fontWeight = if (isHeader) FontWeight.Bold else FontWeight.Medium,
            color = keyColor
        )
        Box(Modifier.width(1.dp).height(12.dp).background(dividerColor))
        Spacer(Modifier.width(8.dp))
        Text(
            text = value,
            modifier = Modifier.weight(0.68f),
            fontSize = 11.sp,
            fontWeight = if (isHeader) FontWeight.Bold else FontWeight.Normal,
            fontFamily = if (isHeader) null else FontFamily.Monospace,
            color = valColor,
            maxLines = 5,
            overflow = TextOverflow.Ellipsis
        )
    }
    HorizontalDivider(color = dividerColor, thickness = 0.5.dp)
}

@Composable
fun NodeTooltip(node: GraphNode) {
    val colors = MaterialTheme.colorScheme
    Column(
        modifier = Modifier
            .background(colors.inverseSurface.copy(alpha = 0.92f), RoundedCornerShape(4.dp))
            .border(1.dp, colors.outline, RoundedCornerShape(4.dp))
            .padding(8.dp)
            .width(IntrinsicSize.Max)
            .defaultMinSize(minWidth = 250.dp)
    ) {
        val title = when (node) {
            is GraphNode.TableNode -> "Table"
            is GraphNode.MetadataNode -> "METADATA ${node.simpleId}"
            is GraphNode.SnapshotNode -> "SNAPSHOT ${node.simpleId}"
            is GraphNode.ManifestNode -> "MANIFEST ${node.simpleId}: ${manifestContentLabel(node.data.content)}"
            is GraphNode.FileNode -> "FILE ${node.simpleId}: ${fileContentLabel(node.data.content)}"
            is GraphNode.RowNode -> rowContentLabel(node.content)
            is GraphNode.ErrorNode -> "ERROR"
        }
        
        Text(
            text = title.uppercase(),
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            color = colors.inverseOnSurface,
            modifier = Modifier.padding(bottom = 4.dp)
        )
        
        DetailTable(isDark = true) {
            when (node) {
                is GraphNode.TableNode -> {
                    DetailRow("Name", node.summary.tableName, isDark = true)
                    DetailRow("Metadata", "${node.summary.metadataFileCount}", isDark = true)
                    DetailRow("Snapshots", "${node.summary.snapshotCount}", isDark = true)
                    DetailRow("Manifests", "${node.summary.manifestCount}", isDark = true)
                    DetailRow("Data Files", "${node.summary.manifestEntryCount}", isDark = true)
                }
                is GraphNode.MetadataNode -> {
                    DetailRow("File Name", node.fileName, isDark = true)
                    DetailRow("Format", "${node.data.formatVersion ?: "N/A"}", isDark = true)
                    DetailRow("Last Updated", formatTimestamp(node.data.lastUpdatedMs), isDark = true)
                    DetailRow("Snapshots", "${node.data.snapshots.size}", isDark = true)
                }
                is GraphNode.SnapshotNode -> {
                    val manifestListPath = node.data.manifestList
                    DetailRow("File Name", fileNameFromPath(manifestListPath), isDark = true)
                    DetailRow("Snapshot ID", "${node.data.snapshotId ?: "N/A"}", isDark = true)
                    DetailRow("Operation", node.data.summary["operation"] ?: "N/A", isDark = true)
                    DetailRow("Timestamp", formatTimestamp(node.data.timestampMs), isDark = true)
                }
                is GraphNode.ManifestNode -> {
                    val manifestPath = node.data.manifestPath
                    DetailRow("File Name", fileNameFromPath(manifestPath), isDark = true)
                    DetailRow("Added", "${node.data.addedFilesCount} files", isDark = true)
                    DetailRow("Deleted", "${node.data.deletedFilesCount} files", isDark = true)
                    DetailRow("Sequence", "${node.data.sequenceNumber ?: "N/A"}", isDark = true)
                }
                is GraphNode.FileNode -> {
                    val filePath = node.data.filePath
                    DetailRow("File Name", fileNameFromPath(filePath), isDark = true)
                    DetailRow("Format", node.data.fileFormat ?: "N/A", isDark = true)
                    DetailRow("Records", formatCount(node.data.recordCount), isDark = true)
                    DetailRow("Size", formatBytes(node.data.fileSizeInBytes), isDark = true)
                }
                is GraphNode.RowNode -> {
                    node.resolvedData.entries.take(5).forEach { (k, v) ->
                        DetailRow(k, v.toString(), isDark = true)
                    }
                    if (node.resolvedData.size > 5) {
                        DetailRow("...", "and ${node.resolvedData.size - 5} more", isDark = true)
                    }
                }
                is GraphNode.ErrorNode -> {
                    DetailRow("Title", node.title, isDark = true)
                    DetailRow("Stage", node.stage, isDark = true)
                    DetailRow("Path", node.path, isDark = true)
                    DetailRow("Message", node.message, isDark = true)
                    if (!node.stackTrace.isNullOrBlank()) {
                        DetailRow("Stack Trace", node.stackTrace, isDark = true)
                    }
                }
            }
        }
    }
}

@Composable
fun TableCard(node: GraphNode.TableNode, isSelected: Boolean = false) {
    val selectionBorderColor = selectionHighlightColor()
    val borderWidth = if (isSelected) 6.dp else 2.dp
    val borderColor = if (isSelected) selectionBorderColor else getGraphNodeBorderColor(node, isDarkSurface(MaterialTheme.colorScheme.surface))
    Box(
        modifier = Modifier
            .size(node.width.dp, node.height.dp)
            .background(getGraphNodeColor(node, isDarkSurface(MaterialTheme.colorScheme.surface)), RoundedCornerShape(10.dp))
            .border(BorderStroke(borderWidth, borderColor), RoundedCornerShape(10.dp))
            .padding(8.dp)
    ) {
        Column {
            Text(
                "TABLE",
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                color = nodeCardTextSecondary()
            )
            Text(node.summary.tableName, fontWeight = FontWeight.Bold, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, color = nodeCardTextPrimary())
            Text("Metadata: ${node.summary.metadataFileCount}", fontSize = 11.sp, color = nodeCardTextPrimary())
            Text("Snapshots: ${node.summary.snapshotCount}", fontSize = 11.sp, color = nodeCardTextPrimary())
            Text("Current Version: ${node.summary.currentMetadataVersion ?: "N/A"}", fontSize = 10.sp, color = nodeCardTextSecondary())
        }
    }
}

@Composable
fun MetadataCard(node: GraphNode.MetadataNode, isSelected: Boolean = false) {
    val selectionBorderColor = selectionHighlightColor()
    val borderWidth = if (isSelected) 6.dp else 2.dp
    val borderColor = if (isSelected) selectionBorderColor else getGraphNodeBorderColor(node, isDarkSurface(MaterialTheme.colorScheme.surface))
    val metadataId = node.simpleId.toString()
    Box(
        modifier = Modifier
        .size(node.width.dp, node.height.dp)
        .background(getGraphNodeColor(node, isDarkSurface(MaterialTheme.colorScheme.surface)), RoundedCornerShape(8.dp))
        .border(BorderStroke(borderWidth, borderColor), RoundedCornerShape(8.dp))
        .padding(8.dp)) {
        Column {
            Text(
                "METADATA $metadataId",
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                color = nodeCardTextSecondary()
            )
            Text(node.fileName, fontWeight = FontWeight.Bold, fontSize = 12.sp, color = nodeCardTextPrimary())
            Text("Format V${node.data.formatVersion}", fontSize = 11.sp, color = nodeCardTextPrimary())
            Text("Snapshots: ${node.data.snapshots.size}", fontSize = 11.sp, color = nodeCardTextPrimary())
            Text("Current Snap: ${node.data.currentSnapshotId ?: "None"}", fontSize = 10.sp, color = nodeCardTextSecondary())
        }
    }
}

@Composable
fun SnapshotCard(node: GraphNode.SnapshotNode, isSelected: Boolean = false) {
    val selectionBorderColor = selectionHighlightColor()
    val borderWidth = if (isSelected) 6.dp else 2.dp
    val borderColor = if (isSelected) selectionBorderColor else getGraphNodeBorderColor(node, isDarkSurface(MaterialTheme.colorScheme.surface))
    val fileName = fileNameFromPath(node.localPath ?: node.data.manifestList)
    Box(
        modifier = Modifier
        .size(node.width.dp, node.height.dp)
        .background(getGraphNodeColor(node, isDarkSurface(MaterialTheme.colorScheme.surface)), RoundedCornerShape(8.dp))
        .border(BorderStroke(borderWidth, borderColor), RoundedCornerShape(8.dp))
        .padding(8.dp)) {
        Column {
            Text("SNAPSHOT ${node.simpleId}", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = nodeCardTextSecondary())
            Text(fileName, fontSize = 9.sp, maxLines = 3, overflow = TextOverflow.Ellipsis, color = nodeCardTextPrimary())
        }
    }
}

@Composable
fun ManifestCard(node: GraphNode.ManifestNode, isSelected: Boolean = false) {
    val selectionBorderColor = selectionHighlightColor()
    val color = getGraphNodeColor(node, isDarkSurface(MaterialTheme.colorScheme.surface))
    val borderColor = if (isSelected) selectionBorderColor else getGraphNodeBorderColor(node, isDarkSurface(MaterialTheme.colorScheme.surface))
    val borderWidth = if (isSelected) 6.dp else 2.dp
    val contentLabel = manifestContentLabel(node.data.content)
    val fileName = fileNameFromPath(node.localPath ?: node.data.manifestPath)

    Box(
        modifier = Modifier
        .size(node.width.dp, node.height.dp)
        .background(color, RoundedCornerShape(8.dp))
        .border(BorderStroke(borderWidth, borderColor), RoundedCornerShape(8.dp))
        .padding(8.dp)) {
        Column {
            Text(
                "MANIFEST ${node.simpleId}: $contentLabel",
                fontSize = 9.sp,
                fontWeight = FontWeight.Bold,
                color = nodeCardTextSecondary()
            )
            Text(fileName, fontSize = 9.sp, maxLines = 3, overflow = TextOverflow.Ellipsis, color = nodeCardTextPrimary())
        }
    }
}

@Composable
fun FileCard(node: GraphNode.FileNode, isSelected: Boolean = false) {
    val selectionBorderColor = selectionHighlightColor()
    val label = "FILE ${node.simpleId}: ${fileContentLabel(node.data.content)}"
    val borderWidth = if (isSelected) 5.dp else 1.dp
    val borderColor = if (isSelected) selectionBorderColor else getGraphNodeBorderColor(node, isDarkSurface(MaterialTheme.colorScheme.surface))
    val fileName = fileNameFromPath(node.localPath ?: node.data.filePath)

    Box(
        modifier = Modifier
        .size(node.width.dp, node.height.dp)
        .background(getGraphNodeColor(node, isDarkSurface(MaterialTheme.colorScheme.surface)), RoundedCornerShape(4.dp))
        .border(BorderStroke(borderWidth, borderColor), RoundedCornerShape(4.dp))
        .padding(4.dp)) {
        Column {
            Text(
                label,
                fontSize = 8.sp,
                fontWeight = FontWeight.Bold,
                color = nodeCardTextSecondary()
            )
            Text(fileName, fontSize = 8.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, color = nodeCardTextPrimary())
            Text("${node.data.recordCount} rows", fontSize = 10.sp, color = nodeCardTextPrimary())
        }
    }
}

@Composable
fun RowCard(node: GraphNode.RowNode, isSelected: Boolean = false) {
    val selectionBorderColor = selectionHighlightColor()
    val borderWidth = if (isSelected) 5.dp else 1.dp
    val borderColor = if (isSelected) selectionBorderColor else getGraphNodeBorderColor(node, isDarkSurface(MaterialTheme.colorScheme.surface))
    val resolved = node.resolvedData
    val fileNo = resolved["file_no"]?.toString() ?: "?"
    val rowIdx = resolved["row_idx"]?.toString() ?: "?"
    val targetFileNo = resolved["target_file_no"]?.toString()
    val targetRowPos = resolved["pos"]?.toString() ?: resolved["position"]?.toString()
    val detailEntries = rowCardDetailEntries(node)
    Box(
        modifier = Modifier
        .size(node.width.dp, node.height.dp)
        .background(getGraphNodeColor(node, isDarkSurface(MaterialTheme.colorScheme.surface)), RoundedCornerShape(4.dp))
        .border(BorderStroke(borderWidth, borderColor), RoundedCornerShape(4.dp))
        .padding(6.dp)) {
        Column(Modifier.fillMaxSize()) {
            Text(
                "ROW $fileNo.$rowIdx: ${rowStatusShortLabel(node.content)}",
                fontSize = 9.sp,
                fontWeight = FontWeight.Bold,
                color = nodeCardTextSecondary()
            )
            if (node.content == 1 && (targetFileNo != null || targetRowPos != null)) {
                val targetLabel = "${targetFileNo ?: "?"}.${targetRowPos ?: "?"}"
                Text(
                    "Target: $targetLabel",
                    fontSize = 9.sp,
                    color = nodeCardTextSecondary()
                )
            }
            Spacer(Modifier.height(2.dp))
            detailEntries
                .take(3)
                .forEach { (k, v) ->
                Text(
                    text = "$k: $v",
                    fontSize = 10.sp,
                    color = nodeCardTextPrimary(),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            if (detailEntries.size > 3) {
                Text("...", fontSize = 10.sp, color = nodeCardTextSecondary())
            }
        }
    }
}

@Composable
fun ErrorCard(node: GraphNode.ErrorNode, isSelected: Boolean = false) {
    val selectionBorderColor = selectionHighlightColor()
    val borderWidth = if (isSelected) 5.dp else 2.dp
    val borderColor = if (isSelected) selectionBorderColor else getGraphNodeBorderColor(node, isDarkSurface(MaterialTheme.colorScheme.surface))
    Box(
        modifier = Modifier
            .size(node.width.dp, node.height.dp)
            .background(getGraphNodeColor(node, isDarkSurface(MaterialTheme.colorScheme.surface)), RoundedCornerShape(6.dp))
            .border(BorderStroke(borderWidth, borderColor), RoundedCornerShape(6.dp))
            .padding(6.dp)
    ) {
        Column(Modifier.fillMaxSize()) {
            Text("ERROR", fontSize = 9.sp, fontWeight = FontWeight.Bold, color = Color(0xFFB71C1C))
            Text(node.title, fontSize = 10.sp, fontWeight = FontWeight.SemiBold, color = nodeCardTextPrimary(), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("Stage: ${node.stage}", fontSize = 9.sp, color = nodeCardTextSecondary(), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(node.path, fontSize = 9.sp, color = nodeCardTextSecondary(), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(node.message, fontSize = 10.sp, color = nodeCardTextPrimary(), maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}
