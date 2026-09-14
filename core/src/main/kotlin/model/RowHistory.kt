package model

/**
 * What a row lookup reads a snapshot with — [RowLookupInput] on Iceberg, [PaimonReadInput] on
 * Paimon. The marker the history trace dispatches on, so one list can hold either format's
 * snapshots and the trace itself has no format in it.
 */
sealed interface LookupInput

/** A retained snapshot on `main`, with what looking a row up in it takes. */
data class HistorySnapshot(
    val snapshotId: Long,
    val timestampMs: Long?,
    /** Iceberg's `summary.operation`; Paimon's `commitKind`. */
    val operation: String?,
    val input: LookupInput,
)

/**
 * The snapshots a row's history is traced through, newest first, and how many `main` holds —
 * the trace stops at [MAX_HISTORY_SNAPSHOTS], and a reader has to be told the horizon is the
 * cap and not the table's first commit. A `DeferredRead` on the table node: building each
 * entry walks that snapshot's closure (Iceberg) or replays it (Paimon).
 */
data class RowHistoryInputs(val snapshots: List<HistorySnapshot>, val onMain: Int) {
    val capped: Boolean get() = snapshots.size < onMain
}

/** Snapshots a trace reads; each one is a lookup, so the cap bounds file reads as much as walks. */
const val MAX_HISTORY_SNAPSHOTS = 20

/** What a commit did to the matching rows, against the commit before it. */
enum class RowChange(val label: String) {
    /** No live matching row before, one or more here. */
    APPEARED("appeared"),
    /** Live rows before and here, not the same rows. */
    CHANGED("changed"),
    /** Live rows before, none here. */
    GONE("gone"),
    UNCHANGED("unchanged"),
}

/** The lookup's answer at one snapshot. */
data class RowHistoryStep(val snapshot: HistorySnapshot, val result: RowLookupResult) {
    /**
     * The live rows as a read returns them, ordered so two steps compare as sets — the row's
     * own columns only, since a Paimon record's `_SEQUENCE_NUMBER` moves with a rewrite of the
     * same value, which is not a change a read shows.
     */
    val liveRows: List<Map<String, Any?>> by lazy {
        result.hits.filter { it.fate == RowFate.LIVE || it.fate == RowFate.MERGED }
            .map { hit -> hit.cells.filterKeys { !it.startsWith("_") } }
            .sortedBy { it.toString() }
    }
}

/**
 * A filter's matching rows traced through the retained snapshots on `main`, newest first —
 * the lookup run at each, and what each commit did to the rows against the one before it.
 * The question is "when did this row change", which the current snapshot's fate cannot
 * answer: a row deleted three commits ago and a row deleted by the last one look the same.
 */
data class RowHistory(val steps: List<RowHistoryStep>, val onMain: Int) {
    val capped: Boolean get() = steps.size < onMain

    /** [RowChange] per step, aligned with [steps]; null for the oldest traced, which has nothing older to stand against. */
    val changes: List<RowChange?> by lazy {
        steps.mapIndexed { i, step ->
            val older = steps.getOrNull(i + 1) ?: return@mapIndexed null
            val before = older.liveRows
            val now = step.liveRows
            when {
                before.isEmpty() && now.isEmpty() -> RowChange.UNCHANGED
                before.isEmpty() -> RowChange.APPEARED
                now.isEmpty() -> RowChange.GONE
                before == now -> RowChange.UNCHANGED
                else -> RowChange.CHANGED
            }
        }
    }

    /** The steps whose commit did something to the rows, newest first. */
    val changedSteps: List<RowHistoryStep> get() = steps.filterIndexed { i, _ -> changes[i] != null && changes[i] != RowChange.UNCHANGED }
}

/**
 * The current snapshot's ancestors on `main`, newest first, each with its lookup input —
 * the walk `currentAncestorIds` makes, with the closure walked and the deletes paired for each.
 * The lookup input reads every snapshot under the newest schema, so the rows compare column
 * for column across the trace.
 */
fun UnifiedTableModel.rowHistoryInputs(): RowHistoryInputs? {
    val newest = metadatas.lastOrNull()?.metadata ?: return null
    // The newest metadata's reading of each snapshot wins, which is the one that knows whether it expired.
    val byId = metadatas.flatMap { it.snapshots }.associateBy { it.metadata.snapshotId }
    val chain = newest.currentAncestorIds()
    val snapshots = chain.asSequence().take(MAX_HISTORY_SNAPSHOTS).mapNotNull { id ->
        val snapshot = byId[id]?.takeUnless { it.expired } ?: return@mapNotNull null
        val input = rowLookupInputOf(snapshot, liveFilesOf(snapshot), deleteReach(snapshot)) ?: return@mapNotNull null
        HistorySnapshot(id, snapshot.metadata.timestampMs, snapshot.metadata.summary["operation"], input)
    }.toList()
    return RowHistoryInputs(snapshots, chain.size)
}

/**
 * The snapshots under `snapshot/`, newest first, each with its read input off its own replay —
 * and every one read under the table's **newest** schema, the Iceberg rule: the question is
 * asked in the table's current names, and a file is placed by its own `_SCHEMA_ID` onto
 * whichever schema it is read under, so a column renamed between two commits (`der`) is one
 * column at every step and the rows compare column for column. Read under each snapshot's own
 * schema — a time travel's rule — the filter names a column the older schema lacks, and a row
 * that was only patched reads as appearing at the patch.
 */
fun PaimonUnifiedTableModel.rowHistoryInputs(): RowHistoryInputs? {
    if (snapshots.isEmpty()) return null
    val ordered = snapshots.sortedByDescending { it.metadata.id ?: Long.MIN_VALUE }
    val traced = ordered.asSequence().take(MAX_HISTORY_SNAPSHOTS).mapNotNull { snapshot ->
        val id = snapshot.metadata.id ?: return@mapNotNull null
        val input = paimonReadInputOf(snapshot, replayPaimonSnapshot(snapshot), latestSchema) ?: return@mapNotNull null
        HistorySnapshot(id, snapshot.metadata.timeMillis, snapshot.metadata.commitKind, input)
    }.toList()
    return RowHistoryInputs(traced, snapshots.size)
}
