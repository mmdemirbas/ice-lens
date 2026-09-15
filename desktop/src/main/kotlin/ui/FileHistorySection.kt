package ui

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import model.DeferredRead
import model.ExpiryOptions
import model.FileEvent
import model.FileHistory
import model.GraphModel
import model.GraphNode
import model.IcebergMaintenanceInput
import model.PaimonExpiryOptions
import model.planExpiry
import model.planExpiryFiles

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
internal fun FileHistorySection(history: DeferredRead<FileHistory>, graph: GraphModel) {
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
        val nowMs = expiryClock()
        val expiry = remember(h, nowMs) { expiryLineFor(h, graph, nowMs) }
        if (expiry != null) {
            Text(expiry, fontSize = TypeScale.small, color = colors.onSurfaceVariant, modifier = Modifier.padding(bottom = 6.dp))
        }
        val shown = h.snapshots.take(MAX_FILE_HISTORY_ROWS)
        WideTable(
            headers = listOf("Verdict", "Snapshot", "Operation", "Time"),
            // 250dp holds a 19-digit Iceberg id with ` (expired)` after it on one line.
            columnWidths = listOf(150.dp, 250.dp, 110.dp, 170.dp),
            rows = shown.map { e ->
                listOf(
                    e.event?.label ?: "live",
                    "${e.snapshotId}" + when {
                        e.isCurrent -> " (current)"
                        e.expired -> " (expired)"
                        e.changelogOnly -> " (changelog only)"
                        else -> ""
                    },
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
            "Listed by ${formatCounted(h.retainedListing.size, "snapshot")} of the ${formatCount(h.retainedSnapshotCount)} retained" +
                (if (h.snapshots.size > h.retainedListing.size) "; an expired commit is credited from the manifest it wrote, which a retained snapshot still carries." else "; a snapshot the table no longer retains can be neither credited nor blamed."),
            fontSize = TypeScale.small,
            color = colors.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

/**
 * Whether the expiry the table panel plans — `older_than = now` on Iceberg, `retain_min = 1`
 * with `older_than = now` on Paimon — would free this file, from the same [planExpiryFiles] the
 * `Expiry Files` sections draw. Only for a file that is not live now: an expiry keeps the current
 * snapshot, so a live file is never freed. Null on a Paimon branch, whose snapshot ids are its
 * own and not the ones main's expiry is planned over.
 */
private fun expiryLineFor(h: FileHistory, graph: GraphModel, nowMs: Long): String? {
    if (h.liveNow || h.branch != null) return null
    val table = graph.nodeById["table_root"] as? GraphNode.TableNode ?: return null
    val paimonExpiry = table.summary.paimonExpiry
    val call: String
    val freed: Boolean
    val kept: String?
    if (paimonExpiry == null) {
        val meta = (table.maintenance.value as? IcebergMaintenanceInput)?.metadata ?: return null
        val plan = meta.planExpiry(ExpiryOptions(nowMs = nowMs, olderThanMs = nowMs))
        val files = table.expiryFiles.value?.planExpiryFiles(plan.removed.toSet()) ?: return null
        call = "expire_snapshots(older_than = now)"
        freed = h.fileKey in files.paths
        val retained = plan.snapshots.filter { it.retained }.associateBy { it.snapshotId }
        kept = h.liveIn.firstNotNullOfOrNull { e -> retained[e.snapshotId]?.let { "snapshot ${e.snapshotId} lists it live and is kept — ${it.describeKeptBy()}" } }
            ?: "a retained snapshot still reaches it (${files.cleanup.label})"
    } else {
        val plan = runCatching { paimonExpiry.planExpiry(PaimonExpiryOptions(nowMs = nowMs, retainMin = 1, olderThanMs = nowMs)) }.getOrNull() ?: return null
        val files = table.paimonExpiryFiles.value?.planExpiryFiles(plan.removed.map { it.snapshotId }.toSet()) ?: return null
        call = "expire_snapshots(retain_min = 1, older_than = now)"
        freed = h.fileKey in files.names
        val protecting = files.protectedByTag.firstOrNull { it.name == h.fileKey }
        val changelog = h.liveIn.firstOrNull { it.changelogOnly }
        kept = when {
            protecting != null -> "tag ${protecting.tag} (snapshot ${protecting.tagSnapshotId}) still holds it"
            changelog != null -> "long-lived changelog ${changelog.snapshotId} still lists it, for expire_changelogs to free"
            else -> "a snapshot that lists it live is kept"
        }
    }
    return if (freed) "An $call run now would free it." else "An $call run now would not free it: $kept."
}
