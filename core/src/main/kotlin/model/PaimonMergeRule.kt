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
 * rejected otherwise. With sequence groups (`fields.<seq>.sequence-group = <cols>`) a record
 * updates a group only when its sequence field is at or above the row's, a retraction retracts
 * its group's columns and the key stays — unless `partial-update.remove-record-on-sequence-group`
 * names the group's sequence field, when a `-D` at or above the row's value removes the key
 * (`retractWithSequenceGroup`); [sequenceGroupRemovals] carries those fields and the fold is
 * `service.PaimonSequenceGroups`. A group versioned by several fields (`fields.g1,g2.sequence-group`)
 * compares a record's tuple with the row's the way the generated comparator does — field by field
 * in the key's order, a null below every value, two nulls equal — and the option removes on a `-D`
 * whichever of the group's fields it names, since `retractWithSequenceGroup` walks the group's
 * fields and removes at the first named one (`sgm` is the fixture). **A key with no insert record is not a row** under every
 * engine but `aggregation`: `deduplicate` and `first-row` return the record they kept, which is
 * none, and `partial-update` answers `DELETE` while `meetInsert` is false — a key whose only
 * records are retractions ignored under `ignore-delete`, or retracting by group, is gone.
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
    /** A batch read of this table skips level-0 files. */
    val skipsLevel0: Boolean,
    /** A key with no `+I`/`+U` record among its records is not a row — every engine but aggregation. */
    val keyNeedsInsert: Boolean = true,
    /** `partial-update` with sequence groups: retractions retract by group and never remove on their kind. */
    val sequenceGroups: Boolean = false,
    /**
     * The sequence fields `partial-update.remove-record-on-sequence-group` names, each with the
     * fields of its group in declaration order — a `-D` whose value is at or above the row's on
     * any of them removes the key. Empty where the option is unset.
     */
    val sequenceGroupRemovals: List<List<String>> = emptyList(),
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
            sequenceGroups && sequenceGroupRemovals.isNotEmpty() -> append(
                "; a record updates a sequence group only at or above the row's ${sequenceGroupRemovals.joinToString(", ") { it.joinToString(",") }}, " +
                    "and a -D (delete) there removes the key (remove-record-on-sequence-group)",
            )
            sequenceGroups -> append("; a retraction retracts its sequence group's columns and the key stays")
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
        "first-row" -> PaimonMergeRule(engine, PaimonMergeKeeps.FIRST, emptySet(), ignoreDelete, !ignoreDelete, skipsLevel0 = skipsLevel0)
        "partial-update" -> {
            // `fields.<seq fields>.sequence-group = <cols>`: the key names the sequence fields.
            val groups = options.keys.filter { it.startsWith("fields.") && it.endsWith(".sequence-group") }
                .map { it.removePrefix("fields.").removeSuffix(".sequence-group").split(",").map(String::trim) }
            val removeOnDelete = flag("partial-update.remove-record-on-delete")
            val removeOnGroups = options["partial-update.remove-record-on-sequence-group"]?.split(",")?.map(String::trim)?.filter { it.isNotEmpty() }.orEmpty()
            val removals = removeOnGroups.mapNotNull { field -> groups.firstOrNull { field in it } }.distinct()
            PaimonMergeRule(
                engine, PaimonMergeKeeps.COMBINED,
                removingKinds = if (!ignoreDelete && removeOnDelete && groups.isEmpty()) setOf(PaimonRowKind.DELETE) else emptySet(),
                retractionsIgnored = ignoreDelete,
                retractionsRejected = !ignoreDelete && !removeOnDelete && groups.isEmpty(),
                skipsLevel0 = skipsLevel0,
                sequenceGroups = groups.isNotEmpty(),
                sequenceGroupRemovals = if (ignoreDelete) emptyList() else removals,
            )
        }
        "aggregation" -> PaimonMergeRule(
            engine, PaimonMergeKeeps.COMBINED,
            removingKinds = if (flag("aggregation.remove-record-on-delete")) setOf(PaimonRowKind.DELETE) else emptySet(),
            retractionsIgnored = false, retractionsRejected = false, skipsLevel0 = skipsLevel0,
            // A retraction retracts from the aggregate and the row stays, whether or not an insert preceded it.
            keyNeedsInsert = false,
        )
        else -> PaimonMergeRule(
            engine, PaimonMergeKeeps.LATEST,
            removingKinds = if (ignoreDelete) emptySet() else setOf(PaimonRowKind.UPDATE_BEFORE, PaimonRowKind.DELETE),
            retractionsIgnored = ignoreDelete, retractionsRejected = false, skipsLevel0 = skipsLevel0,
        )
    }
}
