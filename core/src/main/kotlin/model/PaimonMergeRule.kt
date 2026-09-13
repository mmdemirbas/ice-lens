package model

/** Which of a key's records the merge engine keeps: the latest, the first, or all of them folded into one. */
enum class PaimonMergeKeeps { LATEST, FIRST, COMBINED }

/**
 * What a read of a primary-key table does with a key's records, read off the four merge
 * functions at release-1.3.1 and the scan that feeds them — the rules a merged row count and a
 * row lookup apply, and the reasons they sometimes cannot.
 *
 * `deduplicate` keeps the latest record by sequence number, and a `-D` or `-U` there removes the
 * key. `first-row` keeps the first and rejects a retraction outright unless `ignore-delete`.
 * `partial-update` folds every record's non-null columns into one row; a `-D` removes the key
 * only under `partial-update.remove-record-on-delete`, is skipped under `ignore-delete`, and is
 * rejected otherwise — and a sequence group retracts columns by group, which is not applied here.
 * `aggregation` folds by each column's function; a `-D` removes the key only under
 * `aggregation.remove-record-on-delete` and otherwise retracts from the aggregate, the row
 * staying. **And a batch read of a first-row table, or of any primary-key table with deletion
 * vectors, reads no level-0 file at all** (`DataTableBatchScan`, `batchScanSkipLevel0`): the
 * writer's forced compaction is supposed to have moved every level-0 record up, so records still
 * at level 0 are invisible to such a read — `fr` is the table where that shows.
 */
data class PaimonMergeRule(
    val engine: String,
    val keeps: PaimonMergeKeeps,
    /** The `_VALUE_KIND`s that, as a key's latest record, remove the key. */
    val removingKinds: Set<Int>,
    /** Retraction records are skipped by the merge (`ignore-delete`). */
    val retractionsIgnored: Boolean,
    /** A retraction record makes the merge function throw: a read of that key fails. */
    val retractionsRejected: Boolean,
    /** False where the rule is not applied here — partial-update sequence groups. */
    val applied: Boolean,
    /** A batch read of this table skips level-0 files. */
    val skipsLevel0: Boolean,
) {
    fun removes(kind: Int?): Boolean = kind != null && kind in removingKinds

    /** The rule in a sentence, for the panel. */
    fun describe(): String = buildString {
        append("merge-engine = $engine: ")
        append(
            when (keeps) {
                PaimonMergeKeeps.LATEST -> "the latest record by sequence number is the row"
                PaimonMergeKeeps.FIRST -> "the first record by sequence number is the row"
                PaimonMergeKeeps.COMBINED -> "every record is folded into one row"
            },
        )
        when {
            !applied -> append("; sequence groups retract by column group, which is not applied here")
            retractionsIgnored -> append("; retractions are ignored (ignore-delete)")
            retractionsRejected -> append("; a retraction makes the read fail")
            removingKinds.isNotEmpty() -> append("; a ${removingKinds.sorted().joinToString(" or ") { PaimonRowKind.describe(it).substringBefore(" (") }} as the latest record removes the key")
            else -> append("; a retraction is folded, the key stays")
        }
        if (skipsLevel0) append(". A batch read skips level-0 files")
    }
}

const val PAIMON_MERGE_ENGINE_KEY = "merge-engine"

/** The rule the table's options state, for a primary-key table; an append table merges nothing. */
fun paimonMergeRuleOf(options: Map<String, String>, hasPrimaryKey: Boolean): PaimonMergeRule {
    val engine = options[PAIMON_MERGE_ENGINE_KEY]?.trim()?.lowercase() ?: DEFAULT_PAIMON_MERGE_ENGINE
    fun flag(vararg keys: String) = keys.any { options[it]?.trim().equals("true", ignoreCase = true) }
    // `ignore-delete` falls back to the per-engine spellings older versions used.
    val ignoreDelete = flag("ignore-delete", "first-row.ignore-delete", "deduplicate.ignore-delete", "partial-update.ignore-delete")
    val deletionVectors = flag("deletion-vectors.enabled")
    val skipsLevel0 = hasPrimaryKey && (deletionVectors || engine == "first-row")
    return when (engine) {
        "first-row" -> PaimonMergeRule(engine, PaimonMergeKeeps.FIRST, emptySet(), ignoreDelete, !ignoreDelete, applied = true, skipsLevel0 = skipsLevel0)
        "partial-update" -> {
            val sequenceGroups = options.keys.any { it.startsWith("fields.") && it.endsWith(".sequence-group") }
            val removeOnDelete = flag("partial-update.remove-record-on-delete")
            PaimonMergeRule(
                engine, PaimonMergeKeeps.COMBINED,
                removingKinds = if (!ignoreDelete && removeOnDelete) setOf(PaimonRowKind.DELETE) else emptySet(),
                retractionsIgnored = ignoreDelete,
                retractionsRejected = !ignoreDelete && !removeOnDelete && !sequenceGroups,
                applied = !sequenceGroups,
                skipsLevel0 = skipsLevel0,
            )
        }
        "aggregation" -> PaimonMergeRule(
            engine, PaimonMergeKeeps.COMBINED,
            removingKinds = if (flag("aggregation.remove-record-on-delete")) setOf(PaimonRowKind.DELETE) else emptySet(),
            retractionsIgnored = false, retractionsRejected = false, applied = true, skipsLevel0 = skipsLevel0,
        )
        else -> PaimonMergeRule(
            engine, PaimonMergeKeeps.LATEST,
            removingKinds = if (ignoreDelete) emptySet() else setOf(PaimonRowKind.UPDATE_BEFORE, PaimonRowKind.DELETE),
            retractionsIgnored = ignoreDelete, retractionsRejected = false, applied = true, skipsLevel0 = skipsLevel0,
        )
    }
}
