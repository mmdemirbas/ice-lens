package ui

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import model.GraphNode
import service.PaimonMergedCount

/**
 * The rows a read of this snapshot returns — see [PaimonMergedCount]. Under the recorded record
 * counts because it is the figure those counts are not: `totalRecordCount` sums file rows, and
 * the question a reader brings to it is why `count(*)` says something else. An append table's
 * answer is the metadata's and is drawn at once; a primary-key table's is the merge over every
 * bucket's files, behind a click, since it is a read of the whole snapshot.
 */
@Composable
internal fun PaimonMergedCountSection(
    node: GraphNode.PaimonSnapshotNode,
    startRequested: Boolean = false,
    onSettled: () -> Unit = {},
) {
    val colors = MaterialTheme.colorScheme
    if (!node.readInput.isPresent) return
    val needsRead = node.hasPrimaryKey

    var requested by remember(node.id) { mutableStateOf(startRequested || !needsRead) }
    val outcome by produceState<Result<PaimonMergedCount.Result>?>(null, node.id, requested) {
        value = null
        if (requested) {
            value = withContext(Dispatchers.IO) {
                runCatching { PaimonMergedCount.count(requireNotNull(node.readInput.value) { "the snapshot names no schema" }) }
            }
            onSettled()
        }
    }
    val result = outcome?.getOrNull()
    val title = "Merged Rows" + (result?.merged?.let { " — ${formatCount(it)}" } ?: "")

    Section(title) {
        Text(
            if (needsRead) {
                "What SELECT count(*) returns as of this snapshot: the merge a read runs over each " +
                    "bucket's files — one record per key, the latest by sequence number — less the keys " +
                    "whose latest record is a -D or -U, less the keys whose latest record a deletion vector " +
                    "marks. The snapshot's totalRecordCount sums file rows and counts every one of those."
            } else {
                "What SELECT count(*) returns as of this snapshot: the files' rows less the rows their " +
                    "deletion vectors mark and less a patch file's, which are columns of rows another " +
                    "file holds — all recorded in the metadata, so no file is opened."
            },
            fontSize = TypeScale.small,
            color = colors.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 8.dp),
        )
        when {
            !requested -> OutlinedButton(onClick = { requested = true }) {
                Text("Merge the bucket files (${PaimonMergedCount.MAX_BUCKETS} buckets at most)")
            }
            outcome == null -> Text("Merging…", fontSize = TypeScale.small, color = colors.onSurfaceVariant)
            result == null -> Text(
                "Could not merge: ${outcome?.exceptionOrNull()?.message ?: "unknown error"}",
                fontSize = TypeScale.small,
                color = colors.error,
            )
            else -> ResultBody(result)
        }
    }
}

@Composable
private fun ResultBody(result: PaimonMergedCount.Result) {
    val colors = MaterialTheme.colorScheme
    if (!result.applied) {
        Text(
            "merge-engine = ${result.mergeEngine} combines a key's records rather than keeping one, and " +
                "that merge is not applied here; ${formatCount(result.fileRows)} rows are in the files.",
            fontSize = TypeScale.small,
            color = verdictUnevaluatedColor(),
        )
        return
    }
    val merged = result.merged
    Text(
        (merged?.let { "${formatCount(it)} rows" } ?: "Not settled") +
            " — ${formatCount(result.fileRows)} in the files" +
            (if (result.fromMetadata) "" else ", ${formatCount(result.buckets.sumOf { it.keys })} keys") +
            ", ${formatCount(result.retracted)} retracted, ${formatCount(result.vectorMarked)} marked by vectors" +
            (if (result.bucketsLeft > 0) ", ${result.bucketsLeft} buckets left unread by the cap" else "") +
            (if (result.failed > 0) ", ${result.failed} could not be read" else "") + ".",
        fontSize = TypeScale.small,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(bottom = 4.dp),
    )
    result.buckets.filter { it.error != null }.forEach {
        Text("Bucket ${it.bucket} of ${it.partition.ifEmpty { "the table" }}: ${it.error}", fontSize = TypeScale.small, color = colors.error)
    }
    if (result.buckets.size > 1) {
        WideTable(
            headers = listOf("Merged", "Partition", "Bucket", "Files", "File rows", "Keys", "Retracted", "Vector-marked"),
            columnWidths = listOf(110.dp, 320.dp, 70.dp, 70.dp, 100.dp, 100.dp, 100.dp, 120.dp),
            rows = result.buckets.map { b ->
                listOf(
                    if (b.error == null) formatCount(b.merged) else "—",
                    b.partition.ifEmpty { "—" },
                    b.bucket.toString(),
                    b.files.toString(),
                    formatCount(b.fileRows),
                    if (result.fromMetadata) "—" else formatCount(b.keys),
                    formatCount(b.retracted),
                    formatCount(b.vectorMarked),
                )
            },
            leadCellColors = result.buckets.map { if (it.error != null) colors.error else null },
        )
    }
}
