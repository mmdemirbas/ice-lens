package ui

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import model.DeferredRead
import model.FileEvent
import model.FileHistory

/** The rows a file's history prints before it says how many more there are. */
internal const val MAX_FILE_HISTORY_ROWS = 500

/**
 * A file's life across the retained snapshots — see [FileHistory] — on both formats' file panels.
 * It is the answer to "my query says this file is missing" and "why is this file still on
 * disk": which commit removed it, and how many retained snapshots still list it live, which is
 * what keeps it on disk. The line leads and the table explains it; the removal is the one row
 * coloured, since a column of "live" with one "removed here" in it has to be findable without
 * reading every row. A snapshot the table no longer retains can be neither credited nor blamed,
 * and the footer says how many of those there are not.
 */
@Composable
internal fun FileHistorySection(history: DeferredRead<FileHistory>) {
    val colors = MaterialTheme.colorScheme
    if (!history.isPresent) {
        Section("File History") {
            Text(
                "Not tracked for a changelog file — it is the change stream, and no snapshot lists it as the table's contents.",
                fontSize = TypeScale.small,
                color = colors.onSurfaceVariant,
            )
        }
        return
    }
    val h = history.value
    val title = "File History" + when {
        h == null -> ""
        h.liveNow -> " — live now"
        else -> " — not live now"
    }
    CountedSection(title, h?.snapshots?.size ?: 0, "retained snapshot lists this file") {
        if (h == null) return@CountedSection
        Text(
            "Every retained snapshot that lists the file, in commit order, with what the commit did to " +
                "it. A file stays on disk while any retained snapshot lists it live, whatever the " +
                "current snapshot says; an expiry frees it once none does.",
            fontSize = TypeScale.small,
            color = colors.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 4.dp),
        )
        Text(
            h.describe.replaceFirstChar { it.uppercase() },
            fontSize = TypeScale.body,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(bottom = 6.dp),
        )
        val shown = h.snapshots.take(MAX_FILE_HISTORY_ROWS)
        WideTable(
            headers = listOf("Verdict", "Snapshot", "Operation", "Time"),
            columnWidths = listOf(150.dp, 190.dp, 110.dp, 170.dp),
            rows = shown.map { e ->
                listOf(
                    e.event?.label ?: "live",
                    "${e.snapshotId}" + if (e.isCurrent) " (current)" else "",
                    e.operation ?: "N/A",
                    formatAppTimestamp(e.timestampMs),
                )
            },
            leadCellColors = shown.map { if (it.event == FileEvent.REMOVED) verdictSkippedColor() else null },
        )
        if (h.snapshots.size > shown.size) {
            Text(
                "… and ${formatCount(h.snapshots.size - shown.size)} more snapshots.",
                fontSize = TypeScale.small,
                color = colors.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
        Text(
            "Listed by ${formatCounted(h.snapshots.size, "snapshot")} of the ${formatCount(h.retainedSnapshotCount)} retained; " +
                "a snapshot the table no longer retains can be neither credited nor blamed.",
            fontSize = TypeScale.small,
            color = colors.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}
