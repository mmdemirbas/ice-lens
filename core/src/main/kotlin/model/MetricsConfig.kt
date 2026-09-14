package model

/**
 * Which statistics a writer records for each column — `write.metadata.metrics.*` read the way
 * `MetricsConfig.from` reads it at 1.8.1 — and whether a data file's recorded statistics are the
 * shape that configuration says.
 *
 * A scan prunes on a column's bounds and counts, so a column with none is a column no filter
 * can skip a file on, and the reason is almost always this configuration rather than the file:
 * a `write.metadata.metrics.default = counts` set for a wide table, a per-column `none`, or the
 * limit nobody set — past `write.metadata.metrics.max-inferred-column-defaults` (100) top-level
 * columns, every later column records nothing, with no property saying so. The file panel says
 * which rule gave each column its mode, and the check says whether the file was written under
 * it: a file written before a property change keeps the older shape, which is the ordinary way
 * two files of one table differ.
 *
 * The rules, in order (`MetricsConfig.from(props, schema, order)`): the configured default, else
 * `truncate(16)` for a table of at most the inferred limit's top-level columns, else `truncate(16)`
 * for the first that many columns — their nested leaves included — and `none` for the rest; a
 * sort column whose transform preserves order is promoted to `truncate(16)` where the default is
 * `none` or `counts`; then `write.metadata.metrics.column.<dotted name>` overrides, an invalid
 * spelling falling back to the default with a warning in the writer's log. What each mode
 * records (`ParquetUtil.footerMetrics`): nothing under `none`; sizes, value counts, null counts
 * and NaN counts under `counts`; those and bounds under `truncate(N)`, a string or binary bound
 * cut to N code points with the upper one's last incremented; those and full bounds under `full`.
 */
sealed class MetricsMode {
    object None : MetricsMode()
    object Counts : MetricsMode()
    data class Truncate(val length: Int) : MetricsMode()
    object Full : MetricsMode()

    /** The property spelling: `none`, `counts`, `truncate(16)`, `full`. */
    val spelled: String
        get() = when (this) {
            None -> "none"
            Counts -> "counts"
            is Truncate -> "truncate($length)"
            Full -> "full"
        }

    val recordsCounts: Boolean get() = this != None
    val recordsBounds: Boolean get() = this is Truncate || this == Full

    companion object {
        private val TRUNCATE = Regex("truncate\\((\\d+)\\)")

        /** `MetricsModes.fromString`: case-insensitive; null for a spelling it refuses. */
        fun parse(text: String): MetricsMode? {
            val mode = text.trim().lowercase()
            return when (mode) {
                "none" -> None
                "counts" -> Counts
                "full" -> Full
                else -> TRUNCATE.matchEntire(mode)?.groupValues?.get(1)?.toIntOrNull()?.let { Truncate(it) }
            }
        }
    }
}

/** A column's mode and the rule that gave it — the answer to "why does this column record no bounds". */
data class ColumnMetricsMode(val mode: MetricsMode, val setBy: String)

const val METRICS_DEFAULT_PROPERTY = "write.metadata.metrics.default"
const val METRICS_COLUMN_PROPERTY_PREFIX = "write.metadata.metrics.column."
const val METRICS_MAX_INFERRED_PROPERTY = "write.metadata.metrics.max-inferred-column-defaults"
const val METRICS_MAX_INFERRED_DEFAULT = 100
val METRICS_DEFAULT_MODE: MetricsMode = MetricsMode.Truncate(16)

data class MetricsConfig(
    val default: ColumnMetricsMode,
    /** By the column's dotted path, only the columns some rule set apart from the default. */
    val columns: Map<String, ColumnMetricsMode>,
) {
    fun modeOf(columnPath: String): ColumnMetricsMode = columns[columnPath] ?: default
}

/**
 * The configuration in force under a metadata version's properties, current schema and default
 * sort order — the three inputs `MetricsConfig.forTable` reads.
 */
fun metricsConfigOf(properties: Map<String, String>, schema: IcebergSchemaModel?, sortOrder: SortOrder?): MetricsConfig {
    val columns = mutableMapOf<String, ColumnMetricsMode>()
    val maxInferred = properties[METRICS_MAX_INFERRED_PROPERTY]?.toIntOrNull()?.takeIf { it >= 0 } ?: METRICS_MAX_INFERRED_DEFAULT
    val configured = properties[METRICS_DEFAULT_PROPERTY]
    val topLevel = schema?.struct?.fields.orEmpty()

    val default: ColumnMetricsMode = when {
        configured != null -> {
            val parsed = MetricsMode.parse(configured)
            if (parsed != null) ColumnMetricsMode(parsed, "$METRICS_DEFAULT_PROPERTY = $configured")
            else ColumnMetricsMode(METRICS_DEFAULT_MODE, "the default, $METRICS_DEFAULT_PROPERTY = $configured being no mode the writer accepts")
        }
        schema == null || topLevel.size <= maxInferred -> ColumnMetricsMode(METRICS_DEFAULT_MODE, "the default, nothing configured")
        else -> {
            // The first `maxInferred` top-level columns keep the default, every leaf under them
            // included (`TypeUtil.getProjectedIds` over the sub-schema); the rest record nothing.
            val within = "within the first $maxInferred columns ($METRICS_MAX_INFERRED_PROPERTY" +
                (if (METRICS_MAX_INFERRED_PROPERTY in properties) " = $maxInferred)" else " left at $maxInferred)")
            val leading = topLevel.take(maxInferred).map { it.id }.toSet()
            schema.pathsById.forEach { (id, path) ->
                val top = path.substringBefore('.')
                if (topLevel.firstOrNull { it.name == top }?.id in leading) columns[path] = ColumnMetricsMode(METRICS_DEFAULT_MODE, within)
            }
            ColumnMetricsMode(
                MetricsMode.None,
                "past the first $maxInferred columns ($METRICS_MAX_INFERRED_PROPERTY" +
                    (if (METRICS_MAX_INFERRED_PROPERTY in properties) " = $maxInferred)" else " left at $maxInferred, and nothing configured)"),
            )
        }
    }

    // A sort column is promoted where the default records no bounds, since the order is what the
    // bounds are for — only where its transform preserves order (`SortOrderUtil.orderPreservingSortedColumns`).
    if (default.mode == MetricsMode.None || default.mode == MetricsMode.Counts) {
        sortOrder?.fields.orEmpty().filter { preservesOrder(it.transformName) }.forEach { field ->
            val path = field.sourceId?.let { schema?.pathOf(it) } ?: return@forEach
            columns[path] = ColumnMetricsMode(METRICS_DEFAULT_MODE, "the sort column, promoted from ${default.mode.spelled}")
        }
    }

    properties.forEach { (key, value) ->
        if (!key.startsWith(METRICS_COLUMN_PROPERTY_PREFIX)) return@forEach
        val path = key.removePrefix(METRICS_COLUMN_PROPERTY_PREFIX)
        val parsed = MetricsMode.parse(value)
        columns[path] =
            if (parsed != null) ColumnMetricsMode(parsed, "$key = $value")
            else ColumnMetricsMode(default.mode, "the default, $key = $value being no mode the writer accepts")
    }
    return MetricsConfig(default, columns)
}

/** `Transform.preservesOrder()` at 1.8.1: identity, the time transforms and truncate; not bucket or void. */
private fun preservesOrder(transform: String): Boolean =
    transform.isEmpty() || transform == "identity" || transform in setOf("year", "month", "day", "hour") || transform.startsWith("truncate")

/**
 * The configuration each snapshot's files were written under: the lowest metadata version that
 * lists the snapshot is the one that committed it, and its properties, current schema and
 * default sort order are what `MetricsConfig.forTable` read at that write. A snapshot no
 * version lists — expired, its versions gone — gets the newest version's, said so.
 */
class MetricsConfigHistory(versions: List<TableMetadata>) {
    private val ordered = versions
    private val byVersion = mutableMapOf<Int, Pair<MetricsConfig, IcebergSchemaModel?>>()
    private val bySnapshot = mutableMapOf<Long, MetricsConfigAt>()

    private fun configOf(index: Int): Pair<MetricsConfig, IcebergSchemaModel?> = byVersion.getOrPut(index) {
        val md = ordered[index]
        val schema = md.currentSchemaModel()
        metricsConfigOf(md.properties, schema, md.defaultSortOrderId?.let { id -> md.sortOrders.firstOrNull { it.orderId == id } }) to schema
    }

    private fun at(index: Int, source: String): MetricsConfigAt = configOf(index).let { (config, schema) -> MetricsConfigAt(config, schema, source) }

    /** The configuration and schema a file this snapshot added was written under, and which version they came from. */
    fun at(snapshotId: Long?): MetricsConfigAt? {
        if (ordered.isEmpty()) return null
        if (snapshotId == null) return at(ordered.lastIndex, "the newest metadata, the entry naming no snapshot")
        return bySnapshot.getOrPut(snapshotId) {
            val index = ordered.indexOfFirst { md -> md.snapshots.any { it.snapshotId == snapshotId } }
            if (index >= 0) at(index, "the metadata that committed snapshot $snapshotId")
            else at(ordered.lastIndex, "the newest metadata, no retained version listing snapshot $snapshotId")
        }
    }
}

/** What a file was written under: the configuration, the schema of the time — a column added later has nothing to record — and where both came from. */
data class MetricsConfigAt(val config: MetricsConfig, val schema: IcebergSchemaModel?, val source: String)

/** One column of one file: the mode the configuration gave it, and whether the recorded statistics are that shape. */
data class MetricsModeCheck(
    val column: String,
    val fieldId: Int,
    val configured: ColumnMetricsMode,
    /** What the file records for the column, in the mode vocabulary: `nothing`, `counts`, `counts and bounds`. */
    val recorded: String,
    /** Null where the shape cannot be judged — an all-null column records no bounds under any mode. */
    val agrees: Boolean?,
    val reason: String,
)

/**
 * Every leaf of [schema] against what [columnStats] records for it under [config].
 *
 * Three rules sit beside the modes, each seen on the corpus before it was read. A leaf under a
 * list or a map gets counts and never bounds — `ParquetUtil.shouldStoreBounds` keeps bounds
 * only for a column reached through structs alone (`deep`'s `tags.element`, `props.key`,
 * `props.value`). A positional delete file's `file_path` and `pos` are `full` by
 * `MetricsConfig.forPositionDelete`, and `PositionDeleteWriter.metrics` then strips their
 * counts, and their bounds too when the file references more than one data file — so the
 * shape is bounds or nothing, never counts. And the Avro writer records no column metrics
 * under any mode (1.8.1), so an Avro file is not judged, nor is a Puffin container.
 */
fun metricsModeChecks(columnStats: List<ColumnStats>, schema: IcebergSchemaModel?, config: MetricsConfig, file: DataFile): List<MetricsModeCheck> {
    if (schema == null) return emptyList()
    if (file.fileFormat.equals("avro", ignoreCase = true) || file.fileFormat.equals("puffin", ignoreCase = true)) return emptyList()
    val statsById = columnStats.associateBy { it.fieldId }
    val positional = file.content == DataFileContent.POSITION_DELETES
    val underContainer = idsUnderContainers(schema.struct)
    val leaves = schema.pathsById.filter { (id, _) -> schema.fieldsById[id]?.type?.let { it !is IcebergType.StructType && it !is IcebergType.ListType && it !is IcebergType.MapType } == true }
    // An equality delete holds its equality columns and no others, so only those can record anything.
    val equality = file.equalityIds?.toSet()?.takeIf { file.content == DataFileContent.EQUALITY_DELETES }
    val columns = if (positional) {
        listOf(DELETE_FILE_PATH_FIELD_ID to "file_path", DELETE_POS_FIELD_ID to "pos")
    } else {
        leaves.entries.filter { equality == null || it.key in equality }.sortedBy { it.key }.map { it.key to it.value }
    }
    return columns.map { (id, path) ->
        val configured = if (positional) ColumnMetricsMode(MetricsMode.Full, "a positional delete's own columns, always full") else config.modeOf(path)
        val stats = statsById[id]
        val hasCounts = stats?.valueCount != null || stats?.nullValueCount != null
        val hasBounds = stats?.lowerBound != null || stats?.upperBound != null
        val recorded = when {
            hasBounds && hasCounts -> "counts and bounds"
            hasBounds -> "bounds"
            hasCounts -> "counts"
            else -> "nothing"
        }
        val mode = configured.mode
        val type = schema.fieldsById[id]?.type
        val allNull = stats?.isAllNull == true || (stats?.valueCount == 0L)
        val problems = mutableListOf<String>()
        var note: String? = null
        if (positional) {
            if (hasCounts) problems += "counts recorded, which PositionDeleteWriter strips from file_path and pos"
            note = if (hasBounds) "bounds kept: the file references one data file" else "bounds stripped: the file references several data files"
        } else {
            if (hasCounts && !mode.recordsCounts) problems += "counts recorded under ${mode.spelled}"
            if (!hasCounts && mode.recordsCounts) problems += "no counts recorded under ${mode.spelled}"
            if (hasBounds && !mode.recordsBounds) problems += "bounds recorded under ${mode.spelled}"
            if (hasBounds && id in underContainer) problems += "bounds recorded under a list or map, where Iceberg stores none"
            if (!hasBounds && mode.recordsBounds && hasCounts && !allNull && id !in underContainer) problems += "no bounds recorded under ${mode.spelled}"
            if (id in underContainer && mode.recordsBounds) note = "counts only: a leaf under a list or map records no bounds under any mode"
        }
        if (mode is MetricsMode.Truncate && hasBounds && (type == IcebergType.StringType || type == IcebergType.BinaryType)) {
            val longest = listOfNotNull(stats?.lowerBound?.value, stats?.upperBound?.value).mapNotNull { (it as? String)?.codePointCount(0, it.length) ?: (it as? ByteArray)?.size }.maxOrNull()
            if (longest != null && longest > mode.length) problems += "a bound of $longest characters under ${mode.spelled}"
        }
        val undecidable = !positional && !hasBounds && mode.recordsBounds && hasCounts && allNull && id !in underContainer
        MetricsModeCheck(
            column = path, fieldId = id, configured = configured, recorded = recorded,
            agrees = if (problems.isNotEmpty()) false else if (undecidable) null else true,
            reason = when {
                problems.isNotEmpty() -> problems.joinToString("; ")
                undecidable -> "every value is null, so no bound is recorded under any mode"
                note != null -> note
                else -> "as configured"
            },
        )
    }
}

/** The ids of every field reached through a list or a map — the ones `ParquetUtil.shouldStoreBounds` refuses bounds for. */
private fun idsUnderContainers(struct: IcebergType.StructType): Set<Int> {
    val ids = mutableSetOf<Int>()
    fun walk(type: IcebergType, id: Int?, inside: Boolean) {
        if (inside && id != null) ids += id
        when (type) {
            is IcebergType.StructType -> type.fields.forEach { walk(it.type, it.id, inside) }
            is IcebergType.ListType -> walk(type.element, type.elementId, true)
            is IcebergType.MapType -> { walk(type.key, type.keyId, true); walk(type.value, type.valueId, true) }
            else -> Unit
        }
    }
    walk(struct, null, false)
    return ids
}
