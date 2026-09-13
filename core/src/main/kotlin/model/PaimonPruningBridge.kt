package model

import kotlinx.serialization.json.JsonPrimitive

/**
 * What a Paimon manifest and data file record, in the vocabulary the pruning rules are written in.
 *
 * `evaluatePruning` reads a [PartitionSummary] per partition field and `evaluateFilePruning` a
 * [ColumnStats] per column, both in Iceberg's terms — a transform, a field id, an [IcebergType]
 * to read a literal in. Paimon records the same two things with none of that vocabulary: a
 * manifest list entry's `_PARTITION_STATS` is a per-column minimum and maximum over the entries'
 * partitions, and a data file's `_VALUE_STATS` the same over its rows. A Paimon partition is
 * always the column's own value — there are no transforms — so every summary here is an identity
 * field, and the one thing the bridge decides is which Iceberg type each Paimon type reads as,
 * which is what `parseLiteral` needs and the only place a literal can be read wrong.
 *
 * Two rules the pruning side already follows are kept: a type this cannot compare is left out
 * rather than mapped to something plausible, and a value count is the file's row count — Paimon
 * records no per-column value count, but every row holds one value per column, so the two are
 * one figure and `IS NOT NULL` is proved empty when the null count reaches it.
 */

/** A Paimon type string as the Iceberg type its values compare as, or null for one that cannot be compared. */
fun paimonTypeAsIceberg(type: String): IcebergType? {
    val head = Regex("""^([A-Z]+)(?:\((\d+)(?:,\s*(\d+))?\))?""").find(type.trim().uppercase()) ?: return null
    val p1 = head.groupValues[2].toIntOrNull()
    val p2 = head.groupValues[3].toIntOrNull()
    return when (head.groupValues[1]) {
        "BOOLEAN" -> IcebergType.BooleanType
        "TINYINT", "SMALLINT", "INT" -> IcebergType.IntType
        "BIGINT" -> IcebergType.LongType
        "FLOAT" -> IcebergType.FloatType
        "DOUBLE" -> IcebergType.DoubleType
        "DATE" -> IcebergType.DateType
        "TIME" -> IcebergType.TimeType()
        "TIMESTAMP" -> IcebergType.TimestampType(withZone = type.uppercase().contains("WITH LOCAL TIME ZONE"))
        "DECIMAL" -> IcebergType.DecimalType(p1 ?: 10, p2 ?: 0)
        "CHAR", "VARCHAR", "STRING" -> IcebergType.StringType
        else -> null
    }
}

private fun decodedValue(value: Any?, type: IcebergType): DecodedValue? =
    value?.let { DecodedValue(display = it.toString(), value = it, type = type, raw = ByteArray(0)) }

/**
 * A Paimon manifest's partition range as one identity summary per partition key.
 *
 * Empty when the manifest recorded no range or the table is unpartitioned, which prunes nothing
 * and says so — the same answer an Iceberg manifest with no partition summaries gives.
 */
fun paimonPartitionSummaries(node: GraphNode.PaimonManifestNode): List<PartitionSummary> {
    val min = node.partitionMin ?: return emptyList()
    val max = node.partitionMax ?: return emptyList()
    val nullCounts = node.data.partitionStats?.nullCounts
    return min.values.indices.mapNotNull { index ->
        val low = min.values[index]
        val high = max.values.getOrNull(index) ?: return@mapNotNull null
        val type = paimonTypeAsIceberg(low.type) ?: return@mapNotNull null
        PartitionSummary(
            field = PartitionField(sourceId = index, fieldId = index, name = low.name, transform = JsonPrimitive("identity")),
            type = type,
            lower = decodedValue(low.value, type),
            upper = decodedValue(high.value, type),
            containsNull = (nullCounts?.getOrNull(index) ?: 0L) > 0L,
            containsNan = null,
            sourceName = low.name,
            sourceType = type,
        )
    }
}

/**
 * Why a Paimon table's file bounds are not consulted, or null where they are. Under
 * `data-evolution.enabled` a read stitches every file sharing a first row id, the freshest
 * file's column winning, so a file's own bounds describe values another file may have replaced —
 * `de`'s whole file records `b` in 1..2 where the read returns 11 and 22. Paimon 1.3.0 and later
 * prune no file by its statistics on such a table (`DataEvolutionFileStoreScan`, #6443); the
 * snapshot that wrote `de` still did, and a filtered read of it returned the unpatched row. The
 * option is read off the newest schema, since it is fixed at creation.
 */
fun paimonFileBoundsWithheld(graph: GraphModel): String? {
    val newest = graph.nodes.asSequence().filterIsInstance<GraphNode.PaimonSchemaNode>().maxByOrNull { it.data.id ?: -1 } ?: return null
    if (newest.data.options[PAIMON_DATA_EVOLUTION_KEY] != "true") return null
    return "this table is under data evolution: a read stitches files sharing a first row id and a " +
        "patch may replace the values a file's own bounds describe, so no file is ruled out by them"
}

/** A Paimon data file's column bounds as the statistics the file stage evaluates, one per decodable column. */
/**
 * [own] with a `<>` or `NOT LIKE` on a column that is null in every row marked as proved — the
 * one place the two formats' file stages read the same statistics differently. Paimon's
 * `NullFalseLeafBinaryFunction.test` answers false for any binary comparison when the null count
 * is the row count, `<>` included; Iceberg's `InclusiveMetricsEvaluator.notEq` answers "might
 * match" before it looks at the counts. The shared stage keeps Iceberg's reading, and this adds
 * Paimon's for its files.
 */
internal fun paimonAllNullNegations(own: FilePruneResult, filter: ScanFilter, stats: List<ColumnStats>): FilePruneResult {
    if (own.fate == FileFate.SKIPPED) return own
    var changed = false
    val outcomes = own.outcomes.map { o ->
        if (o.effect != TermEffect.NOT_EVALUATED || (o.predicate.op != PredicateOp.NOT_EQ && o.predicate.op != PredicateOp.NOT_LIKE)) return@map o
        val column = stats.firstOrNull { it.columnName.equals(o.predicate.column.trim(), ignoreCase = true) } ?: return@map o
        if (!column.isAllNull) return@map o
        changed = true
        o.copy(effect = TermEffect.SKIPS, fieldName = column.displayName, reason = "every value of ${column.displayName} here is null, and a Paimon scan answers `${o.predicate.op}` false for a file whose null count is its row count")
    }
    return if (changed) foldFileOutcomes(filter.pushNegation(), outcomes).copy(note = own.note) else own
}

fun paimonColumnStats(node: GraphNode.PaimonDataFileNode): List<ColumnStats> {
    val bounds = node.columnBounds ?: return emptyList()
    val rowCount = node.entry.file?.rowCount
    return bounds.mapNotNull { bound ->
        if (!bound.decoded) return@mapNotNull null
        val type = paimonTypeAsIceberg(bound.type) ?: return@mapNotNull null
        ColumnStats(
            fieldId = bound.fieldId ?: -1,
            columnName = bound.name,
            type = type,
            lowerBound = decodedValue(bound.min, type),
            upperBound = decodedValue(bound.max, type),
            valueCount = rowCount,
            nullValueCount = bound.nullCount,
            nanValueCount = null,
            columnSizeBytes = null,
        )
    }
}

/** A Paimon data file's `_KEY_STATS` as the statistics a key predicate is evaluated against, one per trimmed primary key. */
fun paimonKeyColumnStats(node: GraphNode.PaimonDataFileNode): List<ColumnStats> {
    val bounds = node.keyBounds ?: return emptyList()
    val rowCount = node.entry.file?.rowCount
    return bounds.mapNotNull { bound ->
        if (!bound.decoded) return@mapNotNull null
        val type = paimonTypeAsIceberg(bound.type) ?: return@mapNotNull null
        ColumnStats(
            fieldId = bound.fieldId ?: -1, columnName = bound.name, type = type,
            lowerBound = decodedValue(bound.min, type), upperBound = decodedValue(bound.max, type),
            valueCount = rowCount, nullValueCount = bound.nullCount, nanValueCount = null, columnSizeBytes = null,
        )
    }
}

/**
 * What a batch scan of a Paimon primary-key table consults, read off the newest schema — the
 * rule `KeyValueFileStoreScan` applies at release-1.3.1, in [evaluatePaimonPrimaryKeyFiles].
 * Null on an append table, whose files are pruned each by its own bounds.
 */
data class PaimonScanRule(
    /** The primary keys that are not partition keys, in primary-key order — the columns a predicate prunes a file on by itself. */
    val trimmedKeys: List<String>,
    val engine: String,
    /** A batch read skips level 0 (`first-row`, deletion vectors) and with it prunes every file by its value bounds on its own. */
    val skipsLevel0: Boolean,
    /** `partial-update` or `aggregation` without deletion vectors: no value bound is consulted, per file or per bucket. */
    val valueBoundsNever: Boolean,
    /** `deletion-vectors.enabled` — what lets the scan test an embedded file index, and the read read every file raw. */
    val deletionVectors: Boolean = false,
    /** `source.split.target-size` and `source.split.open-file-cost` — how the read packs a bucket's files into splits. */
    val splitTargetBytes: Long = 128L shl 20,
    val openFileCostBytes: Long = 4L shl 20,
) {
    fun isKey(column: String): Boolean = trimmedKeys.any { it.equals(column.trim(), ignoreCase = true) }

    /** The rule in a sentence, for the panel's file stage. */
    fun describe(): String {
        val keys = trimmedKeys.joinToString(", ")
        return when {
            skipsLevel0 -> "A primary-key table whose batch reads skip level 0 (merge-engine = $engine" +
                (if (engine != "first-row") ", deletion vectors" else "") + "): a level-0 file is never opened, and every " +
                "other file is ruled out by its key ($keys) and value bounds on its own"
            valueBoundsNever -> "A primary-key table under merge-engine = $engine without deletion vectors: a predicate on the key " +
                "($keys) rules a file out by its key bounds on its own, and no value bound is consulted, since the value a read " +
                "returns is assembled from several records"
            else -> "A primary-key table under merge-engine = $engine: a predicate on the key ($keys) rules a file out by its key " +
                "bounds on its own; the whole filter is then applied per bucket — file by file where the bucket's files are all at " +
                "one level above 0, and otherwise the bucket is read whole if any of its files may match and skipped whole if " +
                "none may, since a key's latest record can sit in a file whose bounds do not match"
        }
    }
}

fun paimonScanRule(graph: GraphModel): PaimonScanRule? {
    val schema = graph.nodes.asSequence().filterIsInstance<GraphNode.PaimonSchemaNode>().maxByOrNull { it.data.id ?: -1 }?.data ?: return null
    if (schema.primaryKeys.isEmpty()) return null
    val rule = paimonMergeRuleOf(schema.options, hasPrimaryKey = true)
    val deletionVectors = schema.options["deletion-vectors.enabled"]?.trim().equals("true", ignoreCase = true)
    return PaimonScanRule(
        trimmedKeys = schema.primaryKeys.filterNot { it in schema.partitionKeys },
        engine = rule.engine,
        skipsLevel0 = rule.skipsLevel0,
        valueBoundsNever = !deletionVectors && (rule.engine == "partial-update" || rule.engine == "aggregation"),
        deletionVectors = deletionVectors,
        splitTargetBytes = schema.options["source.split.target-size"]?.let(::parsePaimonMemoryBytes) ?: (128L shl 20),
        openFileCostBytes = schema.options["source.split.open-file-cost"]?.let(::parsePaimonMemoryBytes) ?: (4L shl 20),
    )
}

/**
 * The file stage of a primary-key table's batch scan, the way `KeyValueFileStoreScan` runs it
 * at release-1.3.1 — which is not per file, because a key's records can sit in several files
 * and the row a read returns is their merge. `PrimaryKeyFileStoreTable.nonPartitionFilterConsumer`
 * carries the case: file 1 inserts `key = a, value = 1`, file 2 updates it to `value = 2`, and
 * pruning `value = 1` per file opens file 1 alone and returns a row the table does not have.
 *
 * So the filter is split. Its top-level conjuncts that name trimmed primary keys only (Paimon's
 * `pickTransformFieldMapping(splitAnd(predicate))`) prune a file on its own against `_KEY_STATS`:
 * a file whose key range excludes the key holds no record of it, whatever the other files hold.
 * The whole filter is then applied **per bucket**, over the files the key stage left
 * (`filterWholeBucketByStats`): when the bucket's files cannot overlap — all at one level above
 * 0 — each is pruned by its own `_VALUE_STATS`; otherwise the bucket is read whole if any file
 * may match and skipped whole if none may; and under `partial-update` or `aggregation` without
 * deletion vectors it is never pruned by value at all, since the value a read returns is
 * assembled across records. A table whose batch reads skip level 0 (`first-row`, deletion
 * vectors) never opens a level-0 file and prunes the rest each by its own bounds
 * (`DataTableBatchScan`: `withLevelFilter(level > 0).enableValueFilter()`), the key stage
 * included. `docs/fixtures/paimon-scan-plans.scala` prints the plans this is held to.
 *
 * The bucket stage is over the latest snapshot's **live** files — what the scan plans over —
 * read off the table node's deferred read input. The graph also draws the entries a compaction
 * removed and the removal records themselves; those get their own bounds' verdict and a note,
 * since a scan never reaches them and their bounds must not decide a live file's bucket.
 */
fun evaluatePaimonPrimaryKeyFiles(
    graph: GraphModel,
    filter: ScanFilter,
    rule: PaimonScanRule,
    manifestSkipped: (String) -> Boolean,
): Map<String, FilePruneResult> {
    val normalized = filter.pushNegation()
    val keyFilter = keyConjunction(normalized, rule)
    val keyPredicates = keyFilter?.predicates().orEmpty().toSet()
    class Staged(val node: GraphNode.PaimonDataFileNode, val own: FilePruneResult)
    val results = mutableMapOf<String, FilePruneResult>()
    val staged = mutableListOf<Staged>()
    val live = graph.nodes.asSequence().filterIsInstance<GraphNode.TableNode>().firstOrNull()
        ?.paimonRowLookup?.value?.files?.map { it.fileName }?.toSet()
    val columnTypes by lazy { paimonColumnTypes(graph) }
    for (node in graph.nodes) {
        if (node !is GraphNode.PaimonDataFileNode) continue
        val isLive = live == null || (node.operationKind == PaimonEntryKind.ADD && node.entry.file?.fileName in live)
        // A key column's value bounds are its key bounds, so one list serves the whole filter —
        // except under a `stats-mode` that records no value bound for the key, where `_KEY_STATS`
        // still has one, which is why the key bounds lead.
        val keyStats = paimonKeyColumnStats(node)
        val stats = keyStats + paimonColumnStats(node).filter { v -> keyStats.none { it.columnName == v.columnName } }
        var own = paimonAllNullNegations(evaluateFilePruning(stats, filter), filter, stats)
        // The scan tests an embedded index beside the value bounds only under deletion vectors
        // (`KeyValueFileStore.newScan`); everything else about a file index waits for the read.
        if (rule.deletionVectors && node.entry.file?.embeddedFileIndex != null) {
            own = applyPaimonFileIndex(own, filter, node, FileIndexUse.AT_PLAN, columnTypes)
        }
        when {
            manifestSkipped(node.id) -> results[node.id] = own.copy(fate = FileFate.NOT_REACHED)
            !isLive -> results[node.id] = own.copy(
                note = "not live in the latest snapshot (removed by a later commit, or the removal record), so no scan plans over it",
            )
            node.unreadByBatchRead -> results[node.id] = unevaluatedFile(
                filter, "at level 0 of a table whose batch reads skip level 0, so never opened — see the file's own panel",
            ).copy(fate = FileFate.NOT_READ)
            rule.skipsLevel0 -> results[node.id] = own
            keyFilter != null && evaluateFilePruning(keyStats, keyFilter).fate == FileFate.SKIPPED -> results[node.id] = own.copy(fate = FileFate.SKIPPED)
            else -> staged += Staged(node, own)
        }
    }
    // The bucket stage, over what the key stage left, grouped as the scan groups them.
    staged.groupBy { it.node.partition?.display to it.node.bucket }.values.forEach { bucket ->
        val levels = bucket.map { it.node.level ?: 0 }.toSet()
        val overlapping = bucket.size > 1 && (0 in levels || levels.size > 1)
        when {
            rule.valueBoundsNever -> bucket.forEach { s ->
                val outcomes = s.own.outcomes.map { o ->
                    if (o.predicate in keyPredicates) o
                    else o.copy(
                        effect = TermEffect.NOT_EVALUATED,
                        reason = "not consulted: under merge-engine = ${rule.engine} without deletion vectors the value a read " +
                            "returns is assembled from several records, and no one file's bounds describe it",
                    )
                }
                val fate = if (outcomes.isNotEmpty() && outcomes.all { it.effect == TermEffect.NOT_EVALUATED }) FileFate.UNEVALUATED else FileFate.WOULD_BE_READ
                results[s.node.id] = FilePruneResult(outcomes, fate)
            }
            !overlapping -> bucket.forEach { results[it.node.id] = it.own }
            bucket.any { it.own.fate != FileFate.SKIPPED } -> bucket.forEach { s ->
                results[s.node.id] = if (s.own.fate != FileFate.SKIPPED) s.own else s.own.copy(
                    fate = FileFate.WOULD_BE_READ,
                    note = "read with its bucket: its files overlap (level 0, or several levels) and another may match, " +
                        "so every one is opened, or a read could return a key's older record",
                )
            }
            else -> bucket.forEach { s ->
                results[s.node.id] = s.own.copy(note = "no file of the bucket can hold a match, so the bucket is skipped whole")
            }
        }
        // The read stage: a file index is consulted by the raw read, which a primary-key table
        // gets only for a split it reads without merging — see [paimonRawConvertible]. A file
        // the plan kept with an index a merge read never opens says so.
        val kept = bucket.filter { results[it.node.id]?.fate.let { f -> f == FileFate.WOULD_BE_READ || f == FileFate.UNEVALUATED } }
        val raw = paimonRawConvertible(kept.map { it.node }, rule)
        for (s in kept) {
            if (!s.node.hasFileIndex) continue
            val result = results.getValue(s.node.id)
            results[s.node.id] = if (s.node.id in raw) applyPaimonFileIndex(result, filter, s.node, FileIndexUse.AT_READ, columnTypes)
            else result.copy(note = joinNotes(result.note, "its file index is not consulted: the file shares a key range with another, so the read merges them and opens no index"))
        }
    }
    return results
}

/**
 * The top-level conjuncts of [filter] naming trimmed primary keys only, as one filter — what
 * `pickTransformFieldMapping(splitAnd(predicate), …, trimmedPrimaryKeys)` keeps — or null when
 * there is none. A key inside an `OR` with a value column is not one: that conjunct names both.
 */
private fun keyConjunction(filter: ScanFilter, rule: PaimonScanRule): ScanFilter? {
    val conjuncts = if (filter is ScanFilter.And) filter.terms else listOf(filter)
    val onKeys = conjuncts.filter { c -> c.predicates().let { ps -> ps.isNotEmpty() && ps.all { rule.isKey(it.column) } } }
    return if (onKeys.isEmpty()) null else ScanFilter.And(onKeys)
}
