package model

/**
 * Paimon's `metadata.stats-mode` and its siblings, read the way
 * `StatsCollectorFactories.createStatsFactories` reads them at release-1.3.1 — the twin of
 * [metricsConfigOf] — and whether a data file's `_VALUE_STATS` are the shape they say.
 *
 * Per value column, in schema order: `fields.<name>.stats-mode` first; a system column
 * (`_KEY_*`, `_SEQUENCE_NUMBER`, `_VALUE_KIND`) is `truncate(128)` whatever the table says and
 * is not counted, which is why `_KEY_STATS` keep bounds under a `none` table (`sm`); then, past
 * `metadata.stats-keep-first-n-columns` (default -1, unlimited) columns — the ones with a field
 * mode counted — `none`; else the level's mode, `metadata.stats-mode.per.level` (`0:none`) for
 * the level a key-value file is written to and `metadata.stats-mode` (`truncate(16)`) where
 * unlisted. What each mode records (`SimpleColStatsCollector`): nothing under `none`, and under
 * the dense store (`metadata.stats-dense-store`, default true) the column is left out of
 * `_VALUE_STATS` and unnamed in `_VALUE_STATS_COLS`; the null count alone under `counts`; the
 * null count and bounds cut to N characters, the upper one incremented, under `truncate(N)`;
 * everything under `full`. A file's `_SCHEMA_ID` names the options it was written under, since
 * `SET TBLPROPERTIES` writes a new schema.
 *
 * **The level that decides is the one the file was written to, not the one it sits at.** An
 * upgrade moves a lone level-0 run to a higher level without rewriting it (`psl`: the insert's
 * file at level 5 with level 0's nothing), so an `APPEND`-sourced file is judged at level 0 and
 * a `COMPACT`-sourced one at its level. Two more rules the corpus showed before the source did
 * (`ParquetSimpleStatsExtractor.toFieldStats`): a nested column — `ROW`, `ARRAY`, `MAP`,
 * `MULTISET` — records nothing under any mode, since the Parquet column statistics are keyed
 * by leaf path and none is found under its name (`pne`); and a timestamp past precision 6
 * records the null count and no bounds. A data-evolution patch file holds only its
 * `_WRITE_COLS` and is judged on those. `data-file.thin-mode` and `changelog-file.stats-mode`
 * are not modelled; a changelog file is not judged.
 */
data class PaimonStatsModeCheck(
    val column: String,
    val configured: ColumnMetricsMode,
    val recorded: String,
    /** Null where the shape cannot be judged — an all-null column, or statistics that could not be placed. */
    val agrees: Boolean?,
    val reason: String,
)

const val PAIMON_STATS_MODE_OPTION = "metadata.stats-mode"
const val PAIMON_STATS_MODE_PER_LEVEL_OPTION = "metadata.stats-mode.per.level"
const val PAIMON_STATS_KEEP_FIRST_N_OPTION = "metadata.stats-keep-first-n-columns"
const val PAIMON_STATS_DENSE_STORE_OPTION = "metadata.stats-dense-store"
private const val PAIMON_FIELDS_PREFIX = "fields."
private const val PAIMON_STATS_MODE_SUFFIX = ".stats-mode"

/** The mode each value column of [schema] is written under, at [writeLevel], with what set it. */
fun paimonStatsModes(schema: PaimonSchema, writeLevel: Int): Map<String, ColumnMetricsMode> {
    val options = schema.options
    val perLevel = options[PAIMON_STATS_MODE_PER_LEVEL_OPTION]?.split(',')?.mapNotNull { pair ->
        val (level, mode) = pair.split(':', limit = 2).map { it.trim() }.takeIf { it.size == 2 } ?: return@mapNotNull null
        level.toIntOrNull()?.let { it to mode }
    }?.toMap().orEmpty()
    val tableMode = options[PAIMON_STATS_MODE_OPTION]
    val levelDefault: ColumnMetricsMode = perLevel[writeLevel]?.let { spelled ->
        ColumnMetricsMode(MetricsMode.parse(spelled) ?: METRICS_DEFAULT_MODE, "$PAIMON_STATS_MODE_PER_LEVEL_OPTION = ${options[PAIMON_STATS_MODE_PER_LEVEL_OPTION]}, level $writeLevel")
    } ?: tableMode?.let { ColumnMetricsMode(MetricsMode.parse(it) ?: METRICS_DEFAULT_MODE, "$PAIMON_STATS_MODE_OPTION = $it") }
        ?: ColumnMetricsMode(METRICS_DEFAULT_MODE, "the default, nothing configured")
    val keepFirst = options[PAIMON_STATS_KEEP_FIRST_N_OPTION]?.toIntOrNull() ?: -1

    var counted = 0
    return schema.fields.mapNotNull { it.name }.associateWith { name ->
        val fieldMode = options["$PAIMON_FIELDS_PREFIX$name$PAIMON_STATS_MODE_SUFFIX"]
        val mode = when {
            fieldMode != null -> ColumnMetricsMode(MetricsMode.parse(fieldMode) ?: METRICS_DEFAULT_MODE, "$PAIMON_FIELDS_PREFIX$name$PAIMON_STATS_MODE_SUFFIX = $fieldMode")
            keepFirst >= 0 && counted >= keepFirst -> ColumnMetricsMode(MetricsMode.None, "past the first $keepFirst columns ($PAIMON_STATS_KEEP_FIRST_N_OPTION = $keepFirst)")
            else -> levelDefault
        }
        counted++
        mode
    }
}

/** The level a file's statistics were collected at: level 0 for a file a commit wrote, its own for a compaction's. */
fun paimonStatsWriteLevel(file: PaimonDataFileMeta): Int =
    if (file.fileSource == PaimonFileSource.COMPACT) file.level ?: 0 else 0

/**
 * Every value column of the file's schema against what its `_VALUE_STATS` record. [columnBounds]
 * is the decoded statistics, null where they could not be placed on the schema.
 */
fun paimonStatsModeChecks(file: PaimonDataFileMeta, schema: PaimonSchema?, columnBounds: List<PaimonColumnBounds>?, changelog: Boolean = false): List<PaimonStatsModeCheck> {
    if (schema == null || changelog) return emptyList()
    val writeLevel = paimonStatsWriteLevel(file)
    val partialColumns = file.writeCols?.toSet()?.takeIf { file.isPartialUnder(schema) }
    val modes = paimonStatsModes(schema, writeLevel).filterKeys { partialColumns == null || it in partialColumns }
    val byName = columnBounds?.associateBy { it.name }
    val typesByName = schema.fields.associate { it.name to it.type }
    val rows = file.rowCount
    return modes.map { (name, configured) ->
        val bounds = byName?.get(name)
        val type = typesByName[name].orEmpty().trim().uppercase()
        val nested = type.startsWith("ROW") || type.startsWith("ARRAY") || type.startsWith("MAP") || type.startsWith("MULTISET")
        val timestampPrecision = Regex("^TIMESTAMP\\((\\d+)\\)").find(type)?.groupValues?.get(1)?.toIntOrNull()
        val boundsNeverRecorded = nested || (timestampPrecision != null && timestampPrecision > 6)
        val hasCounts = bounds?.nullCount != null
        // A bound of a type this does not decode is still a bound the writer recorded.
        val hasBounds = bounds != null && (bounds.min != null || bounds.max != null || !bounds.decoded)
        val recorded = when {
            hasBounds && hasCounts -> "counts and bounds"
            hasBounds -> "bounds"
            hasCounts -> "counts"
            else -> "nothing"
        }
        val mode = configured.mode
        if (byName == null) {
            return@map PaimonStatsModeCheck(name, configured, "not placed", null, "the file's statistics could not be placed on its schema")
        }
        val allNull = rows != null && (rows == 0L || bounds?.nullCount == rows)
        val problems = mutableListOf<String>()
        var note: String? = null
        if (nested) {
            if (hasCounts || hasBounds) problems += "statistics recorded for a nested column, which the Parquet extractor finds none for"
            else note = "nothing: a nested column records no statistics under any mode"
        } else {
            if (hasCounts && !mode.recordsCounts) problems += "counts recorded under ${mode.spelled}"
            if (!hasCounts && mode.recordsCounts) problems += "no counts recorded under ${mode.spelled}"
            if (hasBounds && !mode.recordsBounds) problems += "bounds recorded under ${mode.spelled}"
            if (hasBounds && boundsNeverRecorded) problems += "bounds recorded for a timestamp past precision 6, which the Parquet extractor keeps none for"
            if (!hasBounds && mode.recordsBounds && hasCounts && !allNull && !boundsNeverRecorded) problems += "no bounds recorded under ${mode.spelled}"
            if (boundsNeverRecorded && mode.recordsBounds && !hasBounds) note = "counts only: a timestamp past precision 6 records no bounds under any mode"
            if (mode is MetricsMode.Truncate && hasBounds) {
                val longest = listOfNotNull(bounds?.min, bounds?.max).mapNotNull { (it as? String)?.codePointCount(0, it.length) }.maxOrNull()
                if (longest != null && longest > mode.length) problems += "a bound of $longest characters under ${mode.spelled}"
            }
        }
        val undecidable = !nested && !boundsNeverRecorded && !hasBounds && mode.recordsBounds && hasCounts && allNull
        val levelNote = if (writeLevel != (file.level ?: 0)) " (written at level $writeLevel, upgraded to ${file.level})" else ""
        PaimonStatsModeCheck(
            column = name, configured = configured, recorded = recorded,
            agrees = if (problems.isNotEmpty()) false else if (undecidable) null else true,
            reason = when {
                problems.isNotEmpty() -> problems.joinToString("; ") + levelNote
                undecidable -> "every value is null, so no bound is recorded under any mode"
                note != null -> note + levelNote
                else -> "as configured$levelNote"
            },
        )
    }
}
