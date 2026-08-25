package ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import model.DataFileContent
import model.GraphNode
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import java.text.NumberFormat
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private fun manifestContentLabel(content: Int?): String =
    if (content == 1) "DELETE" else "DATA"

/**
 * What a file node calls itself, in one place because three surfaces print it — the card, its
 * tooltip and the inspector's title — and a fourth case had to be added to all three.
 *
 * A v3 deletion vector and a v2 positional delete file both declare `content = 1`, so both read
 * `POS DELETE` unless the vector is named. They are not the same artifact: one is a Roaring bitmap
 * in a Puffin blob covering exactly one data file, the other is a Parquet file of
 * `(file_path, pos)` rows that may cover many. Telling them apart from the drawing is the point of
 * looking at the drawing.
 */
internal fun fileContentLabel(node: GraphNode.FileNode): String = when {
    node.isDeletionVector -> "DELETE VECTOR"
    else -> when (node.data.content ?: 0) {
        1 -> "POS DELETE"
        2 -> "EQ DELETE"
        else -> "DATA"
    }
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

/** Row count with a thousands separator and an ending that matches it. */
private fun rowCountLabel(count: Long?): String =
    if (count == 1L) "1 row" else "${formatCount(count)} rows"

/**
 * What a file card's row count means, which is not the same thing for the three kinds of file.
 *
 * `record_count` is the number of rows a **data** file holds, the number of positions a
 * **positional delete** file or a **deletion vector** removes, and the number of predicate tuples
 * an **equality delete** holds — and one equality tuple can remove thousands of rows. All three
 * read `1 row` before this existed, which says the wrong thing about two of them.
 */
private fun fileRowCountLabel(node: GraphNode.FileNode): String {
    val count = node.data.recordCount
    return when (node.data.content ?: DataFileContent.DATA) {
        DataFileContent.POSITION_DELETES -> "deletes ${rowCountLabel(count)}"
        DataFileContent.EQUALITY_DELETES ->
            if (count == 1L) "1 equality row" else "${formatCount(count)} equality rows"
        else -> rowCountLabel(count)
    }
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

@Composable
fun nodeCardTextPrimary(): Color =
    if (isDarkSurface(MaterialTheme.colorScheme.surface)) Color(0xFFE2E6EC) else Color(0xFF1A1C1E)

@Composable
fun nodeCardTextSecondary(): Color =
    if (isDarkSurface(MaterialTheme.colorScheme.surface)) Color(0xFFAEB5BF) else Color(0xFF3D4652)

/** Paired light/dark colors for node fill and border. */
private data class NodeColors(val lightFill: Long, val darkFill: Long, val lightBorder: Long, val darkBorder: Long)

// Reusable color palettes
/**
 * Ref chips on a snapshot card. Two colours because a branch moves and a tag does not, which is
 * the only distinction that changes what a reader can conclude from seeing one.
 */
private val RefBranchChip = Color(0x33000000)
private val RefTagChip = Color(0x1F000000)

private val BROWN       = NodeColors(0xFFD7CCC8, 0xFF4E3B32, 0xFF5D4037, 0xFFBCAAA4)
private val PURPLE      = NodeColors(0xFFE1BEE7, 0xFF4A2858, 0xFF8E24AA, 0xFFCE93D8)
private val PURPLE_MUTE = NodeColors(0xFFD7CFDE, 0xFF3D3548, 0xFF6F6180, 0xFFB0A4BA)
private val BLUE        = NodeColors(0xFFBBDEFB, 0xFF1A3A5C, 0xFF1976D2, 0xFF64B5F6)
private val GREEN       = NodeColors(0xFFC8E6C9, 0xFF1E3D20, 0xFF388E3C, 0xFF81C784)
private val RED         = NodeColors(0xFFFFCDD2, 0xFF5C2020, 0xFFD32F2F, 0xFFEF9A9A)
private val YELLOW      = NodeColors(0xFFFFF59D, 0xFF4A4020, 0xFFB26A00, 0xFFE6C56A)
private val ERROR       = NodeColors(0xFFFFEBEE, 0xFF4A1010, 0xFFB71C1C, 0xFFEF5350)
private val GREY        = NodeColors(0xFFE0E0E0, 0xFF3D3D3D, 0xFF616161, 0xFF9E9E9E)
private val TEAL        = NodeColors(0xFFB2DFDB, 0xFF2E4A3A, 0xFF00695C, 0xFF80CBC4)

private fun nodeColorScheme(node: GraphNode): NodeColors = when (node) {
    is GraphNode.TableNode    -> BROWN
    is GraphNode.MetadataNode -> if (isPrimaryMetadataFile(node.fileName)) PURPLE else PURPLE_MUTE
    is GraphNode.SnapshotNode -> BLUE
    is GraphNode.ManifestNode -> if (node.data.content == 1) RED else GREEN
    is GraphNode.FileNode     -> when (node.data.content ?: 0) { 1 -> RED; 2 -> YELLOW; else -> GREEN }
    is GraphNode.RowNode      -> when (node.content) { 1 -> RED; 2 -> YELLOW; else -> GREEN }
    is GraphNode.ErrorNode    -> ERROR
    is GraphNode.PaimonSnapshotNode -> when (node.commitKind) {
        "COMPACT" -> PURPLE; "OVERWRITE" -> YELLOW; "ANALYZE" -> TEAL; else -> BLUE
    }
    is GraphNode.PaimonSchemaNode       -> PURPLE
    is GraphNode.PaimonManifestListNode -> when (node.kind) { "delta" -> BLUE; "changelog" -> YELLOW; else -> GREY }
    is GraphNode.PaimonManifestNode     -> GREEN
    is GraphNode.PaimonDataFileNode     -> if (node.operationKind == 1) RED else GREEN
    // Deliberately neutral. A group is a statement about the drawing, not about the table, and
    // taking its siblings' colour would make it read as one more manifest or one more file.
    is GraphNode.GroupNode              -> GREY
}

fun getGraphNodeColor(node: GraphNode, dark: Boolean = false): Color {
    val c = nodeColorScheme(node)
    return Color(if (dark) c.darkFill else c.lightFill)
}

fun getGraphNodeBorderColor(node: GraphNode, dark: Boolean = false): Color {
    val c = nodeColorScheme(node)
    return Color(if (dark) c.darkBorder else c.lightBorder)
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

/** Single-line timestamp in local time — for fixed-width table cells where the 3-line form overflows. */
fun formatTimestampShort(ms: Long?): String {
    if (ms == null) return "N/A"
    return try {
        timestampFormatter.format(Instant.ofEpochMilli(ms))
    } catch (e: Exception) {
        "$ms"
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
fun DetailRow(key: String, value: String, isHeader: Boolean = false, isDark: Boolean = false, copyable: Boolean = false) {
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
            fontSize = TypeScale.small,
            fontWeight = if (isHeader) FontWeight.Bold else FontWeight.Medium,
            color = keyColor
        )
        Box(Modifier.width(1.dp).height(12.dp).background(dividerColor))
        Spacer(Modifier.width(8.dp))
        Text(
            text = value,
            modifier = Modifier.weight(if (copyable) 0.62f else 0.68f),
            fontSize = TypeScale.small,
            fontWeight = if (isHeader) FontWeight.Bold else FontWeight.Normal,
            fontFamily = if (isHeader) null else FontFamily.Monospace,
            color = valColor,
            maxLines = 5,
            overflow = TextOverflow.Ellipsis
        )
        if (copyable && value.isNotBlank() && value != "N/A") {
            IconButton(
                onClick = {
                    Toolkit.getDefaultToolkit().systemClipboard
                        .setContents(StringSelection(value), null)
                },
                modifier = Modifier.size(20.dp)
            ) {
                Icon(
                    Icons.Default.ContentCopy,
                    contentDescription = "Copy $key",
                    modifier = Modifier.size(12.dp),
                    tint = keyColor
                )
            }
        }
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
            is GraphNode.FileNode -> "FILE ${node.simpleId}: ${fileContentLabel(node)}"
            is GraphNode.RowNode -> rowContentLabel(node.content)
            is GraphNode.ErrorNode -> "ERROR"
            is GraphNode.PaimonSnapshotNode -> "PAIMON SNAPSHOT ${node.simpleId}"
            is GraphNode.PaimonSchemaNode -> "PAIMON SCHEMA ${node.simpleId}"
            is GraphNode.PaimonManifestListNode -> "PAIMON ${node.kind.uppercase()} MANIFEST LIST"
            is GraphNode.PaimonManifestNode -> "PAIMON MANIFEST ${node.simpleId}"
            is GraphNode.PaimonDataFileNode -> "PAIMON FILE ${node.simpleId}"
            is GraphNode.GroupNode -> "NOT DRAWN: ${node.kind.plural}"
        }
        
        Text(
            text = title.uppercase(),
            fontSize = TypeScale.micro,
            fontWeight = FontWeight.Bold,
            color = colors.inverseOnSurface,
            modifier = Modifier.padding(bottom = 4.dp)
        )
        
        DetailTable(isDark = true) {
            when (node) {
                is GraphNode.TableNode -> {
                    DetailRow("Name", node.summary.tableName, isDark = true)
                    DetailRow("Snapshots", formatCount(node.summary.snapshotCount), isDark = true)
                    // The card shows the table as it is now; "All Retained History" figures
                    // live in the inspector, where they can be labelled as such.
                    DetailRow("Data Files", formatCount(node.summary.current.dataFileCount), isDark = true)
                    DetailRow("Records", formatCount(node.summary.current.recordCount), isDark = true)
                    DetailRow("Size", formatBytes(node.summary.current.totalSizeBytes), isDark = true)
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
                    DetailRow("Entries", formatCount(node.entries.size), isDark = true)
                    DetailRow("Added", "${formatCount(node.data.addedFilesCount)} files", isDark = true)
                    DetailRow("Deleted", "${formatCount(node.data.deletedFilesCount)} files", isDark = true)
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
                    node.stackTrace?.takeIf { it.isNotBlank() }?.let { trace ->
                        DetailRow("Stack Trace", trace, isDark = true)
                    }
                }
                is GraphNode.PaimonSnapshotNode -> {
                    DetailRow("Snapshot ID", "${node.data.id ?: "N/A"}", isDark = true)
                    DetailRow("Commit Kind", node.commitKind ?: "N/A", isDark = true)
                    DetailRow("Timestamp", formatTimestamp(node.data.timeMillis), isDark = true)
                    DetailRow("Records", formatCount(node.data.totalRecordCount), isDark = true)
                }
                is GraphNode.PaimonSchemaNode -> {
                    DetailRow("Schema ID", "${node.data.id ?: "N/A"}", isDark = true)
                    DetailRow("Fields", "${node.data.fields.size}", isDark = true)
                    DetailRow("Primary Keys", node.data.primaryKeys.joinToString(", ").ifEmpty { "N/A" }, isDark = true)
                }
                is GraphNode.PaimonManifestListNode -> {
                    DetailRow("Kind", node.kind, isDark = true)
                }
                is GraphNode.PaimonManifestNode -> {
                    DetailRow("File", node.data.fileName ?: "N/A", isDark = true)
                    DetailRow("Entries", formatCount(node.entries.size), isDark = true)
                    DetailRow("Added", "${formatCount(node.data.numAddedFiles)} files", isDark = true)
                    DetailRow("Deleted", "${formatCount(node.data.numDeletedFiles)} files", isDark = true)
                }
                is GraphNode.PaimonDataFileNode -> {
                    DetailRow("File", node.entry.file?.fileName ?: "N/A", isDark = true)
                    DetailRow("Rows", formatCount(node.entry.file?.rowCount), isDark = true)
                    DetailRow("Level", "${node.level ?: "N/A"}", isDark = true)
                    DetailRow("Kind", if (node.operationKind == 1) "DELETE" else "ADD", isDark = true)
                }
                is GraphNode.GroupNode -> {
                    DetailRow(node.kind.plural.replaceFirstChar { it.uppercase() }, formatCount(node.memberCount), isDark = true)
                    DetailRow("Nodes hidden", formatCount(node.hiddenNodeCount), isDark = true)
                    if (node.hiddenErrorCount > 0) {
                        DetailRow("Read errors inside", formatCount(node.hiddenErrorCount), isDark = true)
                    }
                    DetailRow("Open", "Double-click, or use the inspector", isDark = true)
                }
            }
        }
    }
}

/**
 * A card's content column, with its text laid out by the font's own metrics instead of the
 * ambient line height.
 *
 * Material3's body style carries `lineHeight = 24.sp`, and a `Text` that overrides only
 * `fontSize` inherits it — so a 9 sp label occupies 24 dp, and a five-line card wants 136 dp
 * whatever its font sizes say. A node's declared height is what ELK reserves and what the card
 * is sized to, and Compose clips nothing, so the surplus lines were painted under the card's
 * own border and simply never seen: every table in the app was drawing a table card whose
 * snapshot count and current-version lines did not exist on screen.
 *
 * `TextUnit.Unspecified` makes each line as tall as its own font needs, which is the only
 * setting under which the declared heights below stay true as the text sizes change.
 */
@Composable
private fun CardColumn(
    padding: Dp = 8.dp,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val probe = LocalCardContentProbe.current
    CompositionLocalProvider(
        LocalTextStyle provides LocalTextStyle.current.copy(lineHeight = TextUnit.Unspecified),
    ) {
        Column(
            modifier
                // Before the padding, so what is reported is the whole a node has to declare,
                // not the text alone. A test drawing the card with slack reads the height the
                // content actually wanted; without slack this reports the clamped height, which
                // is the bug rather than a measurement of it.
                .then(if (probe == null) Modifier else Modifier.onSizeChanged { probe(it.height) })
                .padding(padding),
            content = content,
        )
    }
}

/**
 * Extra room, in dp, to draw a card with beyond what its node declares.
 *
 * Zero everywhere in the app. A test sets it so the card's content is measured unconstrained,
 * which is the only way to see a line the declared height would have dropped — the drop happens
 * where the `Column` runs out of constraint, and every `Text` clips itself to the size it was
 * measured at, so nothing is ever painted outside the card for a probe to find.
 */
internal val LocalCardHeightSlack = staticCompositionLocalOf { 0.dp }

/** Receives the height in pixels a card's content came to, including its padding. */
internal val LocalCardContentProbe = staticCompositionLocalOf<((Int) -> Unit)?> { null }

/** The box a card draws in: what its node declared, plus whatever slack a test asked for. */
@Composable
private fun Modifier.cardBox(node: GraphNode): Modifier =
    size(node.width.dp, node.height.dp + LocalCardHeightSlack.current)

@Composable
fun TableCard(node: GraphNode.TableNode, isSelected: Boolean = false) {
    val selectionBorderColor = selectionHighlightColor()
    val borderWidth = if (isSelected) 6.dp else 2.dp
    val borderColor = if (isSelected) selectionBorderColor else getGraphNodeBorderColor(node, isDarkSurface(MaterialTheme.colorScheme.surface))
    Box(
        modifier = Modifier
            .cardBox(node)
            .background(getGraphNodeColor(node, isDarkSurface(MaterialTheme.colorScheme.surface)), RoundedCornerShape(10.dp))
            .border(BorderStroke(borderWidth, borderColor), RoundedCornerShape(10.dp))
    ) {
        CardColumn(padding = 8.dp) {
            Text(
                "TABLE",
                fontSize = TypeScale.micro,
                fontWeight = FontWeight.Bold,
                color = nodeCardTextSecondary()
            )
            Text(node.summary.tableName, fontWeight = FontWeight.Bold, fontSize = TypeScale.body, maxLines = 1, overflow = TextOverflow.Ellipsis, color = nodeCardTextPrimary())
            Text("Metadata: ${node.summary.metadataFileCount}", fontSize = TypeScale.small, color = nodeCardTextPrimary())
            Text("Snapshots: ${node.summary.snapshotCount}", fontSize = TypeScale.small, color = nodeCardTextPrimary())
            Text("Current Version: ${node.summary.currentMetadataVersion ?: "N/A"}", fontSize = TypeScale.micro, color = nodeCardTextSecondary())
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
        .cardBox(node)
        .background(getGraphNodeColor(node, isDarkSurface(MaterialTheme.colorScheme.surface)), RoundedCornerShape(8.dp))
        .border(BorderStroke(borderWidth, borderColor), RoundedCornerShape(8.dp))) {
        CardColumn(padding = 8.dp) {
            Text(
                "METADATA $metadataId",
                fontSize = TypeScale.micro,
                fontWeight = FontWeight.Bold,
                color = nodeCardTextSecondary()
            )
            Text(node.fileName, fontWeight = FontWeight.Bold, fontSize = TypeScale.small, color = nodeCardTextPrimary())
            Text("Format V${node.data.formatVersion}", fontSize = TypeScale.small, color = nodeCardTextPrimary())
            Text("Snapshots: ${node.data.snapshots.size}", fontSize = TypeScale.small, color = nodeCardTextPrimary())
            Text("Current Snap: ${node.data.currentSnapshotId ?: "None"}", fontSize = TypeScale.micro, color = nodeCardTextSecondary())
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
        .cardBox(node)
        .background(getGraphNodeColor(node, isDarkSurface(MaterialTheme.colorScheme.surface)), RoundedCornerShape(8.dp))
        .border(BorderStroke(borderWidth, borderColor), RoundedCornerShape(8.dp))) {
        CardColumn(padding = 8.dp) {
            Text("SNAPSHOT ${node.simpleId}", fontSize = TypeScale.micro, fontWeight = FontWeight.Bold, color = nodeCardTextSecondary())
            Text(
                fileName,
                fontSize = TypeScale.micro,
                // The chips take a line back from the file name rather than from the card's edge.
                maxLines = if (node.refs.isEmpty()) 3 else 2,
                overflow = TextOverflow.Ellipsis,
                color = nodeCardTextPrimary(),
            )
            // A ref is why this snapshot is still here rather than expired, so it belongs on the
            // card. FlowRow, not Row: the names come from the table and a Row would place the
            // later ones past the card edge where nothing clips them and nobody sees them.
            if (node.refs.isNotEmpty()) {
                Spacer(Modifier.height(3.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(3.dp), maxLines = 2) {
                    node.refs.forEach { ref ->
                        Text(
                            ref.display,
                            fontSize = TypeScale.micro,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            color = nodeCardTextSecondary(),
                            modifier = Modifier
                                .background(
                                    if (ref.isBranch) RefBranchChip else RefTagChip,
                                    RoundedCornerShape(3.dp),
                                )
                                .padding(horizontal = 4.dp, vertical = 1.dp),
                        )
                    }
                }
            }
        }
    }
}

/**
 * [isPruned] marks a manifest the reader's scan filter would let a query skip.
 *
 * It says so in the title line rather than on a line of its own, because a card that gains a line
 * loses one under its own border — the node's declared height is what ELK reserved. The fade is
 * the second signal, never the only one: a colour change alone is invisible to a reader who
 * cannot see the colour, and "faded" could mean anything.
 */
@Composable
fun ManifestCard(node: GraphNode.ManifestNode, isSelected: Boolean = false, isPruned: Boolean = false) {
    val selectionBorderColor = selectionHighlightColor()
    val color = getGraphNodeColor(node, isDarkSurface(MaterialTheme.colorScheme.surface))
    val borderColor = if (isSelected) selectionBorderColor else getGraphNodeBorderColor(node, isDarkSurface(MaterialTheme.colorScheme.surface))
    val borderWidth = if (isSelected) 6.dp else 2.dp
    val contentLabel = manifestContentLabel(node.data.content)
    val fileName = fileNameFromPath(node.localPath ?: node.data.manifestPath)
    val fade = if (isPruned) 0.45f else 1f

    Box(
        modifier = Modifier
        .cardBox(node)
        .background(color.copy(alpha = color.alpha * fade), RoundedCornerShape(8.dp))
        .border(BorderStroke(borderWidth, borderColor.copy(alpha = borderColor.alpha * fade)), RoundedCornerShape(8.dp))) {
        CardColumn(padding = 8.dp) {
            Text(
                if (isPruned) "MANIFEST ${node.simpleId}: $contentLabel — SKIPPED"
                else "MANIFEST ${node.simpleId}: $contentLabel",
                fontSize = TypeScale.micro,
                fontWeight = FontWeight.Bold,
                color = nodeCardTextSecondary()
            )
            Text(fileName, fontSize = TypeScale.micro, maxLines = 3, overflow = TextOverflow.Ellipsis, color = nodeCardTextPrimary())
        }
    }
}

@Composable
fun FileCard(node: GraphNode.FileNode, isSelected: Boolean = false, isPruned: Boolean = false) {
    val selectionBorderColor = selectionHighlightColor()
    // Same treatment as a pruned manifest, and for the same reason: the word says which, the fade
    // says how much of the drawing the query does not touch, and neither alone is enough — colour
    // is never the only signal, and a label on forty cards is not a shape the eye can take in.
    val label = "FILE ${node.simpleId}: ${fileContentLabel(node)}" +
        if (isPruned) " — NOT READ" else ""
    val borderWidth = if (isSelected) 5.dp else 1.dp
    val borderColor = if (isSelected) selectionBorderColor else getGraphNodeBorderColor(node, isDarkSurface(MaterialTheme.colorScheme.surface))
    val fileName = fileNameFromPath(node.localPath ?: node.data.filePath)
    val color = getGraphNodeColor(node, isDarkSurface(MaterialTheme.colorScheme.surface))
    val fade = if (isPruned) 0.45f else 1f

    Box(
        modifier = Modifier
        .cardBox(node)
        .background(color.copy(alpha = color.alpha * fade), RoundedCornerShape(4.dp))
        .border(BorderStroke(borderWidth, borderColor.copy(alpha = borderColor.alpha * fade)), RoundedCornerShape(4.dp))) {
        CardColumn(padding = 4.dp) {
            Text(
                label,
                fontSize = TypeScale.micro,
                fontWeight = FontWeight.Bold,
                color = nodeCardTextSecondary()
            )
            Text(fileName, fontSize = TypeScale.micro, maxLines = 2, overflow = TextOverflow.Ellipsis, color = nodeCardTextPrimary())
            Text(fileRowCountLabel(node), fontSize = TypeScale.micro, color = nodeCardTextPrimary())
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
        .cardBox(node)
        .background(getGraphNodeColor(node, isDarkSurface(MaterialTheme.colorScheme.surface)), RoundedCornerShape(4.dp))
        .border(BorderStroke(borderWidth, borderColor), RoundedCornerShape(4.dp))
    ) {
        CardColumn(padding = 6.dp) {
            Text(
                "ROW $fileNo.$rowIdx: ${rowStatusShortLabel(node.content)}",
                fontSize = TypeScale.micro,
                fontWeight = FontWeight.Bold,
                color = nodeCardTextSecondary()
            )
            if (node.content == 1 && (targetFileNo != null || targetRowPos != null)) {
                val targetLabel = "${targetFileNo ?: "?"}.${targetRowPos ?: "?"}"
                Text(
                    "Target: $targetLabel",
                    fontSize = TypeScale.micro,
                    color = nodeCardTextSecondary()
                )
            }
            Spacer(Modifier.height(2.dp))
            detailEntries
                .take(3)
                .forEach { (k, v) ->
                Text(
                    text = "$k: $v",
                    fontSize = TypeScale.micro,
                    color = nodeCardTextPrimary(),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            if (detailEntries.size > 3) {
                Text("...", fontSize = TypeScale.micro, color = nodeCardTextSecondary())
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
            .cardBox(node)
            .background(getGraphNodeColor(node, isDarkSurface(MaterialTheme.colorScheme.surface)), RoundedCornerShape(6.dp))
            .border(BorderStroke(borderWidth, borderColor), RoundedCornerShape(6.dp))
    ) {
        CardColumn(padding = 6.dp) {
            Text("ERROR", fontSize = TypeScale.micro, fontWeight = FontWeight.Bold, color = Color(0xFFB71C1C))
            Text(node.title, fontSize = TypeScale.micro, fontWeight = FontWeight.SemiBold, color = nodeCardTextPrimary(), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("Stage: ${node.stage}", fontSize = TypeScale.micro, color = nodeCardTextSecondary(), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(node.path, fontSize = TypeScale.micro, color = nodeCardTextSecondary(), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(node.message, fontSize = TypeScale.micro, color = nodeCardTextPrimary(), maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

/**
 * The card for a run of siblings the graph is not drawing.
 *
 * Its whole job is to make the omission impossible to miss and easy to undo, so it leads with
 * the count and states the gesture. A card that only said "…" would be the silent cap this
 * feature exists to replace.
 *
 * A read error inside gets its own line, in the error colour: everything else here is a
 * statement about how much is not drawn, and that one is a statement about the table.
 */
@Composable
fun GroupCard(node: GraphNode.GroupNode, isSelected: Boolean = false) {
    val selectionBorderColor = selectionHighlightColor()
    val dark = isDarkSurface(MaterialTheme.colorScheme.surface)
    val borderWidth = if (isSelected) 5.dp else 2.dp
    val borderColor = if (isSelected) selectionBorderColor else getGraphNodeBorderColor(node, dark)
    Box(
        modifier = Modifier
            .cardBox(node)
            .background(getGraphNodeColor(node, dark), RoundedCornerShape(8.dp))
            .border(BorderStroke(borderWidth, borderColor), RoundedCornerShape(8.dp))
    ) {
        CardColumn(padding = 8.dp) {
            Text(
                "NOT DRAWN",
                fontSize = TypeScale.micro,
                fontWeight = FontWeight.Bold,
                color = nodeCardTextSecondary(),
            )
            Text(
                "${formatCount(node.memberCount)} more ${node.kind.plural}",
                fontSize = TypeScale.body,
                fontWeight = FontWeight.Bold,
                color = nodeCardTextPrimary(),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (node.hiddenNodeCount > node.memberCount) {
                Text(
                    "${formatCount(node.hiddenNodeCount)} nodes in total",
                    fontSize = TypeScale.micro,
                    color = nodeCardTextSecondary(),
                    maxLines = 1,
                )
            }
            if (node.hiddenErrorCount > 0) {
                Text(
                    "${formatCount(node.hiddenErrorCount)} read errors inside",
                    fontSize = TypeScale.micro,
                    fontWeight = FontWeight.SemiBold,
                    color = Color(0xFFB71C1C),
                    maxLines = 1,
                )
            }
            // A fixed gap rather than weight(1f): a column that fills its box reports the box's
            // height, not the height its content wanted, and that measurement is the whole of
            // what keeps a card inside the size its node declares.
            Spacer(Modifier.height(4.dp))
            Text(
                "Double-click to open",
                fontSize = TypeScale.micro,
                color = nodeCardTextSecondary(),
                maxLines = 1,
            )
        }
    }
}

/** Generic card for all Paimon node types. */
@Composable
fun PaimonNodeCard(node: GraphNode, isSelected: Boolean = false) {
    val selectionBorderColor = selectionHighlightColor()
    val dark = isDarkSurface(MaterialTheme.colorScheme.surface)
    val borderWidth = if (isSelected) 5.dp else 2.dp
    val borderColor = if (isSelected) selectionBorderColor else getGraphNodeBorderColor(node, dark)
    Box(
        modifier = Modifier
            .cardBox(node)
            .background(getGraphNodeColor(node, dark), RoundedCornerShape(8.dp))
            .border(BorderStroke(borderWidth, borderColor), RoundedCornerShape(8.dp))
    ) {
        CardColumn(padding = 6.dp) {
            when (node) {
                is GraphNode.PaimonSnapshotNode -> {
                    Text("PAIMON SNAP ${node.simpleId}", fontSize = TypeScale.micro, fontWeight = FontWeight.Bold, color = nodeCardTextSecondary())
                    Text(node.commitKind ?: "N/A", fontSize = TypeScale.small, fontWeight = FontWeight.Bold, color = nodeCardTextPrimary())
                    Text("ID: ${node.data.id ?: "?"}", fontSize = TypeScale.micro, color = nodeCardTextPrimary())
                    Text("Records: ${node.data.totalRecordCount ?: "?"}", fontSize = TypeScale.micro, color = nodeCardTextSecondary())
                }
                is GraphNode.PaimonSchemaNode -> {
                    Text("PAIMON SCHEMA ${node.simpleId}", fontSize = TypeScale.micro, fontWeight = FontWeight.Bold, color = nodeCardTextSecondary())
                    Text("${node.data.fields.size} fields", fontSize = TypeScale.small, color = nodeCardTextPrimary())
                    val keys = node.data.primaryKeys.joinToString(", ")
                    if (keys.isNotEmpty()) Text("PK: $keys", fontSize = TypeScale.micro, color = nodeCardTextSecondary(), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                is GraphNode.PaimonManifestListNode -> {
                    Text("PAIMON ${node.kind.uppercase()}", fontSize = TypeScale.micro, fontWeight = FontWeight.Bold, color = nodeCardTextSecondary())
                    Text("Manifest List", fontSize = TypeScale.small, fontWeight = FontWeight.Bold, color = nodeCardTextPrimary())
                }
                is GraphNode.PaimonManifestNode -> {
                    Text("PAIMON MANIFEST ${node.simpleId}", fontSize = TypeScale.micro, fontWeight = FontWeight.Bold, color = nodeCardTextSecondary())
                    Text(node.data.fileName ?: "N/A", fontSize = TypeScale.micro, maxLines = 2, overflow = TextOverflow.Ellipsis, color = nodeCardTextPrimary())
                    Text("+${node.data.numAddedFiles ?: 0} / -${node.data.numDeletedFiles ?: 0}", fontSize = TypeScale.micro, color = nodeCardTextSecondary())
                }
                is GraphNode.PaimonDataFileNode -> {
                    val kindLabel = if (node.operationKind == 1) "DEL" else "ADD"
                    Text("PAIMON FILE ${node.simpleId}: $kindLabel", fontSize = TypeScale.micro, fontWeight = FontWeight.Bold, color = nodeCardTextSecondary())
                    Text(node.entry.file?.fileName ?: "N/A", fontSize = TypeScale.micro, maxLines = 2, overflow = TextOverflow.Ellipsis, color = nodeCardTextPrimary())
                    val level = node.level
                    val rows = node.entry.file?.rowCount
                    Text(rowCountLabel(rows) + if (level != null) " L$level" else "", fontSize = TypeScale.micro, color = nodeCardTextPrimary())
                }
                // PaimonNodeCard is only invoked from Paimon dispatch; non-Paimon types here would
                // be a bug, so render the id but flag visibly with a "?" marker.
                else -> {
                    Text("?? ${node.id}", fontSize = TypeScale.micro, color = nodeCardTextPrimary())
                }
            }
        }
    }
}
