package model

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * Delta's row tracking (`PROTOCOL.md`, "Row Tracking"), read the way `drt` shows delta-spark 3.2.1
 * writes it. Every `add` a commit writes records `baseRowId` and `defaultRowCommitVersion`, and a
 * row's id is the base plus its position in the file — **unless the file materialised it**: a
 * rewrite (an `UPDATE`, a compaction) copies each row's id and last commit version into two
 * columns of the new file, named in the table's configuration
 * (`delta.rowTracking.materializedRowIdColumnName`, `…RowCommitVersionColumnName`), so a row
 * keeps its id across files while the file itself gets a fresh `baseRowId`. A null in the
 * materialised column means the default. The `delta.rowTracking` domain holds
 * `rowIdHighWaterMark`, the highest id handed out, which the next commit's fresh ids start above.
 */
const val DELTA_ROW_ID = "_row_id"
const val DELTA_ROW_COMMIT_VERSION = "_row_commit_version"
const val DELTA_ROW_TRACKING_DOMAIN = "delta.rowTracking"

/** `delta.enableRowTracking`. */
val DeltaMetadata.rowTrackingEnabled: Boolean get() = configuration["delta.enableRowTracking"].equals("true", ignoreCase = true)
val DeltaMetadata.materializedRowIdColumn: String? get() = configuration["delta.rowTracking.materializedRowIdColumnName"]
val DeltaMetadata.materializedRowCommitVersionColumn: String? get() = configuration["delta.rowTracking.materializedRowCommitVersionColumnName"]

/** `rowIdHighWaterMark` from a `delta.rowTracking` domain's configuration JSON; null where it records none. */
fun DeltaDomainMetadata.rowIdHighWaterMark(): Long? =
    if (domain != DELTA_ROW_TRACKING_DOMAIN || removed) null
    else configuration?.let { runCatching { Json.parseToJsonElement(it).jsonObject["rowIdHighWaterMark"]?.jsonPrimitive?.longOrNull }.getOrNull() }

/**
 * A sampled row's cells with its row id and last commit version put in, under [DELTA_ROW_ID] and
 * [DELTA_ROW_COMMIT_VERSION]: the materialised column's value where the file holds one, else the
 * file's base plus [position] and its default commit version. The materialised columns are
 * dropped — their names are the table's internal ones, and the value is now under the name a
 * reader asks for (`_metadata.row_id`). Cells are returned as given where the file records no
 * base, which is every file of a table without row tracking.
 */
fun deltaRowLineage(
    cells: Map<String, Any?>,
    position: Long?,
    baseRowId: Long?,
    defaultRowCommitVersion: Long?,
    metadata: DeltaMetadata?,
): Map<String, Any?> {
    if (baseRowId == null && defaultRowCommitVersion == null) return cells
    val idColumn = metadata?.materializedRowIdColumn
    val versionColumn = metadata?.materializedRowCommitVersionColumn
    // A DuckDB null reaches a card as the text "null", so absent has two spellings.
    fun materialised(column: String?): Long? = column?.let { cells[it] }?.let { v ->
        when (v) {
            is Number -> v.toLong()
            is String -> v.toLongOrNull()
            else -> null
        }
    }
    val rowId = materialised(idColumn) ?: if (baseRowId != null && position != null) baseRowId + position else null
    val commitVersion = materialised(versionColumn) ?: defaultRowCommitVersion
    return buildMap {
        cells.forEach { (k, v) -> if (k != idColumn && k != versionColumn) put(k, v) }
        rowId?.let { put(DELTA_ROW_ID, it) }
        commitVersion?.let { put(DELTA_ROW_COMMIT_VERSION, it) }
    }
}

/**
 * A commit's `rowIdHighWaterMark` against the ids it handed out: the mark before it — the state
 * the commit was written over — or, above that, the last id of each file it added with a fresh
 * `baseRowId` (`baseRowId + numRecords - 1`). Nothing where the commit writes no mark, or where it
 * adds files and the state before it cannot be rebuilt (a log cleaned past it), since the mark
 * the ids started above is then not known.
 */
fun deltaRowIdTallies(commit: DeltaCommit, stateBefore: () -> DeltaState?): List<CommitTally> {
    val recorded = commit.actions.firstNotNullOfOrNull { it.domainMetadata?.rowIdHighWaterMark() } ?: return emptyList()
    val before = if (commit.version == 0L) null else (stateBefore() ?: return emptyList())
    val previous = before?.domains?.get(DELTA_ROW_TRACKING_DOMAIN)?.rowIdHighWaterMark() ?: -1L
    val assigned = commit.adds.mapNotNull { add ->
        val base = add.baseRowId ?: return@mapNotNull null
        val rows = add.parsedStats?.numRecords ?: return@mapNotNull null
        base + rows - 1
    }
    return listOf(CommitTally("rowIdHighWaterMark", recorded, maxOf(previous, assigned.maxOrNull() ?: previous)))
}
