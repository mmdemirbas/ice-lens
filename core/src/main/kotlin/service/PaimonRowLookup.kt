package service

import model.DeletionVector
import model.LookupFileOutcome
import model.PaimonLookupFile
import model.PaimonMergeKeeps
import model.PaimonRowKind
import model.PaimonReadInput
import model.RowFate
import model.RowHit
import model.RowLookupResult
import model.ScanFilter
import model.duckDbTypeOf
import model.paimonTypeAsIceberg
import model.quoteSqlIdentifier
import model.toSql
import org.slf4j.LoggerFactory

/**
 * Finding rows in a Paimon table and deciding their fate — see [PaimonReadInput] for what
 * it reads and why. The files the filter leaves are read the way the Iceberg lookup reads
 * them, one DuckDB statement each with every literal bound; what differs is what decides a hit.
 *
 * A primary-key file holds every write as a record, so a hit is a *version* of a row and three
 * things can make it not the row. The vector its index file holds for the file marks it by
 * position, which is the file's own statement and is asked first. It can be the `-D` a delete
 * writes, or the `-U` half of an update — a retraction, whose value columns are the row it
 * removed. Or a later write for its key exists in the bucket, which is the question the merge
 * engine answers on a read: under `deduplicate` the record with the highest `_SEQUENCE_NUMBER`
 * is the row and every other is shadowed. That last one needs the bucket's *other* files,
 * whether or not the filter left them — the later write may not match a filter on the value
 * columns, and `lk`'s updated key is exactly that — so the bucket is asked once per bucket the
 * hits fall in, for the hits' keys only. A merge engine that combines versions rather than
 * picking one is reported, not applied: a key with one live record is that record, and a key
 * with several is not decided here. An append table has no keys and no sequence, so a hit is
 * live unless a vector marks it.
 */
object PaimonRowLookup {
    private val logger = LoggerFactory.getLogger(PaimonRowLookup::class.java)

    const val KEY_PREFIX = "_KEY_"
    const val SEQUENCE_NUMBER = "_SEQUENCE_NUMBER"

    /** One matched record; [stitched] names the other files of its split that supplied columns, where a read stitches. */
    private class Raw(val file: PaimonLookupFile, val position: Long?, val cells: Map<String, Any?>, val stitched: String? = null)

    /**
     * What the bucket's read files hold for a key: its latest sequence number, the file holding
     * it, that record's kind, its first sequence number, how many records, and whether any is a
     * retraction.
     */
    class KeyState(
        val latest: Long,
        val holder: String,
        val latestKind: Int?,
        val first: Long,
        val firstHolder: String,
        val records: Long,
        val anyRetraction: Boolean,
        /** The sequence number of the last record whose kind removes the key under the rule, and its file; null where none. */
        val lastRemoval: Long?,
        val removalHolder: String?,
        val removalKind: Int?,
        /** The insert records after the last removal — what a folding engine builds the row from. */
        val folded: Long,
        /** Under `remove-record-on-sequence-group`, the sequence numbers of the `-D` records that removed the key as they were met. */
        val removals: Set<Long> = emptySet(),
    )

    /**
     * Reads the files the filter leaves — every live data file whose name is not in
     * [ruledOut] — and decides each hit.
     */
    fun lookup(input: PaimonReadInput, filter: ScanFilter, ruledOut: Set<String>): RowLookupResult {
        // A split is read whole when any file of it is left: under data evolution the filter's
        // columns may come from one file and the row's other columns from another, and a file the
        // bounds ruled out is still the one holding those. Level-0 files a batch read skips are
        // read too, alone, so a record in one is reported as skipped rather than absent.
        val keptSplits = input.splitsOf(input.files).filter { split -> split.any { it.fileName !in ruledOut } }
        val candidates = keptSplits.sumOf { it.size }
        val toRead = mutableListOf<List<PaimonLookupFile>>()
        var reading = 0
        for (split in keptSplits) {
            if (reading + split.size > RowLookup.MAX_FILES) break
            toRead += split
            reading += split.size
        }
        val columns = input.schema.fields.mapNotNull { it.name }
        val predicate = filter.toSql { column -> input.schema.fields.firstOrNull { it.name == column }?.type?.let(::paimonTypeAsIceberg) }
        val outcomes = mutableListOf<LookupFileOutcome>()
        val raws = mutableListOf<Raw>()
        for (split in toRead) {
            val base = split.firstOrNull { !it.partial } ?: split.last()
            val sources = if (split.size == 1) emptyMap() else columns.mapNotNull { c -> split.indexOfFirst { it.holds(c) }.takeIf { it >= 0 }?.let { c to it } }.toMap()
            val stitched = sources.entries.filter { split[it.value] != base }.groupBy({ split[it.value] }, { it.key })
                .entries.joinToString("; ") { (file, cols) -> "${cols.joinToString(", ")} from ${file.fileName}" }.ifEmpty { null }
            val rows = runCatching {
                if (split.size == 1) readMatches(base, predicate.sql, predicate.params) else readSplit(split, columns, sources, predicate.sql, predicate.params)
            }
            val error = rows.exceptionOrNull()
            if (error != null) {
                logger.warn("Could not read {}: {}", split.map { it.localPath }, error.message)
                split.forEach { outcomes += LookupFileOutcome(it.fileName, 0, error.message ?: error.toString()) }
                continue
            }
            val matched = rows.getOrThrow()
            split.forEach { outcomes += LookupFileOutcome(it.fileName, matched.size) }
            matched.forEach { cells ->
                val position = (cells[SampleRowReader.FILE_ROW_NUMBER] as? Number)?.toLong()
                raws += Raw(base, position, cells - SampleRowReader.FILE_ROW_NUMBER, stitched)
            }
        }
        val keyColumns = input.trimmedPrimaryKeys.map { KEY_PREFIX + it }
        val skipped = input.skippedFiles.map { it.fileName }.toSet()
        val keyStates = if (input.hasPrimaryKey) keyStatesFor(input, raws.filter { it.file.fileName !in skipped }, keyColumns) else emptyMap()
        val vectors = mutableMapOf<String, DeletionVector?>()
        val hits = raws.map { raw -> decide(input, raw, keyColumns, keyStates[raw.file.partition to raw.file.bucket].orEmpty(), vectors) }
        return RowLookupResult(
            outcomes, input.files.size - candidates, candidates - reading, hits,
            rule = if (input.hasPrimaryKey) input.rule.describe() else null,
            skippedFiles = input.skippedFiles.size,
            skippedRows = input.skippedFiles.sumOf { it.recordCount ?: 0L },
        )
    }

    private fun readMatches(file: PaimonLookupFile, where: String, params: List<String>): List<Map<String, Any?>> {
        val (safePath, ext) = SampleRowReader.resolveForQuery(file.localPath)
        val source = SampleRowReader.readerCall(ext, rowNumber = true)
        return DuckDb.withConnection { conn ->
            conn.prepareStatement("SELECT * FROM $source WHERE $where LIMIT ${RowLookup.MAX_HITS_PER_FILE}").use { pstmt ->
                pstmt.setString(1, safePath)
                params.forEachIndexed { i, p -> pstmt.setString(i + 2, p) }
                pstmt.executeQuery().use { rs ->
                    val meta = rs.metaData
                    val rows = mutableListOf<Map<String, Any?>>()
                    while (rs.next()) rows += (1..meta.columnCount).associate { meta.getColumnName(it) to rs.getObject(it) }
                    rows
                }
            }
        }
    }

    /** The columns of a stitched row and where each came from — see [stitchedRowAt]. */
    class StitchedRow(val cells: Map<String, Any?>, val sourceOf: Map<String, String>)

    /**
     * The row at [position] of [file] as a data-evolution read returns it — stitched with the
     * other files of its split, each column from the freshest file holding it — or null where
     * the file is read alone. What the row panel shows beside a whole file's own cells, whose
     * values on a patched column are the ones a patch replaced.
     */
    fun stitchedRowAt(input: PaimonReadInput, file: PaimonLookupFile, position: Long): StitchedRow? {
        val split = input.splitsOf(input.files).firstOrNull { s -> s.any { it.fileName == file.fileName } } ?: return null
        if (split.size < 2) return null
        val columns = input.schema.fields.mapNotNull { it.name }
        val sources = columns.mapNotNull { c -> split.indexOfFirst { it.holds(c) }.takeIf { it >= 0 }?.let { c to it } }.toMap()
        val rows = readSplit(split, columns, sources, "${SampleRowReader.FILE_ROW_NUMBER} = CAST(? AS BIGINT)", listOf(position.toString()))
        val cells = rows.firstOrNull()?.minus(SampleRowReader.FILE_ROW_NUMBER) ?: return null
        return StitchedRow(cells, sources.mapValues { (_, i) -> split[i].fileName })
    }

    /**
     * A data-evolution split as one statement: the files joined on their row number — every file
     * of a split holds the same rows in the same order, which `DataEvolutionSplitRead` checks by
     * row count — with each column taken from the file [sources] names for it, freshest first,
     * and a column no file holds as null. The filter runs over the stitched row, so a literal in
     * a patch finds the row whose other columns are in the file it patches.
     */
    private fun readSplit(
        split: List<PaimonLookupFile>,
        columns: List<String>,
        sources: Map<String, Int>,
        where: String,
        params: List<String>,
    ): List<Map<String, Any?>> {
        val paths = split.map { SampleRowReader.resolveForQuery(it.localPath) }
        require(paths.all { SampleRowReader.hasRowPositions(it.second) }) { "a split is stitched on row numbers, which DuckDB assigns in Parquet only" }
        val rowNumber = SampleRowReader.FILE_ROW_NUMBER
        val select = (listOf("f0.$rowNumber AS $rowNumber") + columns.map { c ->
            val i = sources[c]
            if (i == null) "NULL AS ${quoteSqlIdentifier(c)}" else "f$i.${quoteSqlIdentifier(c)} AS ${quoteSqlIdentifier(c)}"
        }).joinToString(", ")
        val from = split.indices.joinToString(" ") { i ->
            if (i == 0) "read_parquet(?, file_row_number = true, hive_partitioning = false) f0" else "JOIN read_parquet(?, file_row_number = true, hive_partitioning = false) f$i ON f$i.$rowNumber = f0.$rowNumber"
        }
        return DuckDb.withConnection { conn ->
            conn.prepareStatement("SELECT * FROM (SELECT $select FROM $from) s WHERE $where LIMIT ${RowLookup.MAX_HITS_PER_FILE}").use { pstmt ->
                paths.forEachIndexed { i, (path, _) -> pstmt.setString(i + 1, path) }
                params.forEachIndexed { i, p -> pstmt.setString(paths.size + i + 1, p) }
                pstmt.executeQuery().use { rs ->
                    val meta = rs.metaData
                    val rows = mutableListOf<Map<String, Any?>>()
                    while (rs.next()) rows += (1..meta.columnCount).associate { meta.getColumnName(it) to rs.getObject(it) }
                    rows
                }
            }
        }
    }

    private fun keyOf(cells: Map<String, Any?>, keyColumns: List<String>): List<String?> = keyColumns.map { cells[it]?.toString() }

    /**
     * For every bucket the hits fall in, what that bucket holds for each hit key — one statement
     * per bucket over all of its live files, restricted to the keys asked about. Keys are bound
     * as text and cast to the column's type, the same rule the filter's literals follow.
     */
    private fun keyStatesFor(
        input: PaimonReadInput,
        raws: List<Raw>,
        keyColumns: List<String>,
    ): Map<Pair<String, Int>, Map<List<String?>, KeyState>> {
        if (keyColumns.isEmpty()) return emptyMap()
        val casts = input.trimmedPrimaryKeys.map { name ->
            input.schema.fields.firstOrNull { it.name == name }?.type?.let(::paimonTypeAsIceberg)?.let(::duckDbTypeOf)
        }
        return raws.groupBy { it.file.partition to it.file.bucket }.mapValues { (scope, bucketRaws) ->
            val keys = bucketRaws.map { keyOf(it.cells, keyColumns) }.distinct()
            val files = input.bucketOf(bucketRaws.first().file)
            runCatching {
                val states = queryKeyStates(files, keyColumns, casts, keys, input.rule.removingKinds)
                if (input.rule.sequenceGroupRemovals.isNotEmpty()) withSequenceGroupRemovals(input, files, keyColumns, casts, keys, states) else states
            }
                .onFailure { logger.warn("Could not read bucket {}: {}", scope, it.message) }
                .getOrDefault(emptyMap())
        }
    }

    /**
     * The SQL states know no removal under sequence groups — a `-D` removes by its value on a
     * group, not by its kind — so the keys holding a `-D` are folded record by record
     * ([PaimonSequenceGroups]) and their states take the fold's last removal and the inserts
     * after it, the shape the SQL gives every other engine.
     */
    private fun withSequenceGroupRemovals(
        input: PaimonReadInput,
        files: List<PaimonLookupFile>,
        keyColumns: List<String>,
        casts: List<String?>,
        keys: List<List<String?>>,
        states: Map<List<String?>, KeyState>,
    ): Map<List<String?>, KeyState> {
        val folds = sequenceGroupRecords(input, files, keyColumns, casts, keys)
        if (folds.isEmpty()) return states
        val notNull = groupNullability(input)
        return states.mapValues { (key, state) ->
            val records = folds[key] ?: return@mapValues state
            val fold = PaimonSequenceGroups.fold(records, notNull)
            val last = fold.removals.lastOrNull() ?: return@mapValues state
            KeyState(
                latest = state.latest, holder = state.holder, latestKind = state.latestKind,
                first = state.first, firstHolder = state.firstHolder, records = state.records, anyRetraction = state.anyRetraction,
                lastRemoval = last.sequence, removalHolder = last.holder, removalKind = last.kind,
                folded = records.count { !PaimonRowKind.isRetraction(it.kind) && it.sequence > last.sequence }.toLong(),
                removals = fold.removals.map { it.sequence }.toSet(),
            )
        }
    }

    /** Per named group and field, whether the schema declares the column `NOT NULL`. */
    internal fun groupNullability(input: PaimonReadInput): List<List<Boolean>> = input.rule.sequenceGroupRemovals.map { group ->
        group.map { field -> input.schema.fields.firstOrNull { it.name == field }?.type?.uppercase()?.endsWith("NOT NULL") == true }
    }

    /**
     * Every record of the keys among [keys] that hold a `-D` in the bucket — or, with [keys]
     * empty, of every such key — in key and sequence order, with each record's value on the
     * named groups' fields. One `?` per file, then one per key column per key.
     */
    internal fun sequenceGroupRecords(
        input: PaimonReadInput,
        files: List<PaimonLookupFile>,
        keyColumns: List<String>,
        casts: List<String?>,
        keys: List<List<String?>>,
    ): Map<List<String?>, List<PaimonSequenceGroups.Record>> {
        val groups = input.rule.sequenceGroupRemovals
        val keyList = keyColumns.joinToString(", ", transform = ::quoteSqlIdentifier)
        val fields = groups.flatten().distinct()
        val fieldList = fields.joinToString("") { ", " + quoteSqlIdentifier(it) }
        val branches = files.joinToString(" UNION ALL ") { file ->
            "SELECT $keyList, ${quoteSqlIdentifier(SEQUENCE_NUMBER)} AS s, ${quoteSqlIdentifier(PaimonRowKind.COLUMN)} AS k, " +
                "filename AS f$fieldList FROM ${SampleRowReader.readerCall(file.extension, filename = true)}"
        }
        val tuple = "(" + casts.joinToString(", ") { cast -> if (cast == null) "?" else "CAST(? AS $cast)" } + ")"
        val asked = if (keys.isEmpty()) "" else " AND ($keyList) IN (${keys.joinToString(", ") { tuple }})"
        val sql = "WITH u AS ($branches) SELECT $keyList, s, k, f$fieldList FROM u " +
            "WHERE ($keyList) IN (SELECT $keyList FROM u WHERE k = ${PaimonRowKind.DELETE})$asked ORDER BY $keyList, s"
        return DuckDb.withConnection { conn ->
            conn.prepareStatement(sql).use { pstmt ->
                var i = 1
                files.forEach { pstmt.setString(i++, SampleRowReader.resolveForQuery(it.localPath).first) }
                keys.forEach { key -> key.forEach { pstmt.setString(i++, it) } }
                pstmt.executeQuery().use { rs ->
                    val n = keyColumns.size
                    val out = LinkedHashMap<List<String?>, MutableList<PaimonSequenceGroups.Record>>()
                    while (rs.next()) {
                        val key = (1..n).map { rs.getObject(it)?.toString() }
                        val values = fields.associateWith { rs.getObject(it) }
                        out.getOrPut(key) { mutableListOf() } += PaimonSequenceGroups.Record(
                            sequence = rs.getLong("s"),
                            kind = rs.getInt("k"),
                            holder = rs.getString("f").substringAfterLast('/'),
                            groups = groups.map { group -> group.map { values[it] } },
                        )
                    }
                    out
                }
            }
        }
    }

    private fun queryKeyStates(
        files: List<PaimonLookupFile>,
        keyColumns: List<String>,
        casts: List<String?>,
        keys: List<List<String?>>,
        removingKinds: Set<Int>,
    ): Map<List<String?>, KeyState> {
        val keyList = keyColumns.joinToString(", ", transform = ::quoteSqlIdentifier)
        val tuple = "(" + casts.joinToString(", ") { cast -> if (cast == null) "?" else "CAST(? AS $cast)" } + ")"
        val inList = keys.joinToString(", ") { tuple }
        val sql = "SELECT $keyList, latest, holder, kind, first, firstHolder, records, retracted, lastRemoval, removalHolder, removalKind, folded " +
            "FROM (${latestPerKeySql(files, keyColumns, removingKinds)}) " +
            "WHERE ($keyList) IN ($inList)"
        return DuckDb.withConnection { conn ->
            conn.prepareStatement(sql).use { pstmt ->
                var i = 1
                files.forEach { pstmt.setString(i++, SampleRowReader.resolveForQuery(it.localPath).first) }
                keys.forEach { key -> key.forEach { pstmt.setString(i++, it) } }
                pstmt.executeQuery().use { rs ->
                    val states = mutableMapOf<List<String?>, KeyState>()
                    val n = keyColumns.size
                    while (rs.next()) {
                        val key = (1..n).map { rs.getObject(it)?.toString() }
                        states[key] = KeyState(
                            latest = rs.getLong("latest"),
                            holder = rs.getString("holder").substringAfterLast('/'),
                            latestKind = (rs.getObject("kind") as? Number)?.toInt(),
                            first = rs.getObject("first")?.let { (it as Number).toLong() } ?: Long.MAX_VALUE,
                            firstHolder = rs.getString("firstHolder")?.substringAfterLast('/') ?: "",
                            records = rs.getLong("records"),
                            anyRetraction = rs.getBoolean("retracted"),
                            lastRemoval = rs.getObject("lastRemoval")?.let { (it as Number).toLong() },
                            removalHolder = rs.getString("removalHolder")?.substringAfterLast('/'),
                            removalKind = rs.getObject("removalKind")?.let { (it as Number).toInt() },
                            folded = rs.getLong("folded"),
                        )
                    }
                    states
                }
            }
        }
    }

    /**
     * The merge a read runs over a bucket, as SQL: one row per key with its latest sequence
     * number, the file and position holding that record, the record's kind, its first sequence
     * number, how many records the key has and whether any is a retraction — a `UNION ALL` over
     * the bucket's files with one `?` per file, in [files] order — and, for the [removingKinds]
     * the merge rule names, the last record of such a kind and its file. The key columns come
     * first, then `latest`, `holder`, `kind`, `pos`, `first`, `firstHolder`, `records`,
     * `retracted`, `lastRemoval`, `removalHolder`, `removalKind`, `folded`.
     */
    internal fun latestPerKeySql(files: List<PaimonLookupFile>, keyColumns: List<String>, removingKinds: Set<Int>): String {
        val keyList = keyColumns.joinToString(", ", transform = ::quoteSqlIdentifier)
        // An Avro file has no row number to select, and its `p` is null: the merged count checks
        // [SampleRowReader.hasRowPositions] before putting a position to a vector.
        val branches = files.joinToString(" UNION ALL ") { file ->
            val position = if (SampleRowReader.hasRowPositions(file.extension)) SampleRowReader.FILE_ROW_NUMBER else "CAST(NULL AS BIGINT)"
            "SELECT $keyList, ${quoteSqlIdentifier(SEQUENCE_NUMBER)} AS s, ${quoteSqlIdentifier(PaimonRowKind.COLUMN)} AS k, " +
                "filename AS f, $position AS p FROM ${SampleRowReader.readerCall(file.extension, rowNumber = true, filename = true)}"
        }
        val retractions = "(${PaimonRowKind.UPDATE_BEFORE}, ${PaimonRowKind.DELETE})"
        val removing = removingKinds.takeIf { it.isNotEmpty() }?.joinToString(", ", "(", ")") ?: "(-1)"
        // `lr` is the key's last removing record, as a window so the records after it can be
        // counted in the same pass: those are the ones a folding engine builds the row from.
        val windowed = "SELECT u.*, max(s) FILTER (WHERE k IN $removing) OVER (PARTITION BY $keyList) AS lr FROM ($branches) u"
        return "SELECT $keyList, max(s) AS latest, arg_max(f, s) AS holder, arg_max(k, s) AS kind, arg_max(p, s) AS pos, " +
            "min(s) FILTER (WHERE k NOT IN $retractions) AS first, arg_min(f, s) FILTER (WHERE k NOT IN $retractions) AS firstHolder, " +
            "count(*) AS records, bool_or(k IN $retractions) AS retracted, " +
            "max(lr) AS lastRemoval, arg_max(f, s) FILTER (WHERE k IN $removing) AS removalHolder, " +
            "arg_max(k, s) FILTER (WHERE k IN $removing) AS removalKind, " +
            "count(*) FILTER (WHERE k NOT IN $retractions AND s > coalesce(lr, -1)) AS folded " +
            "FROM ($windowed) GROUP BY $keyList"
    }

    private fun decide(
        input: PaimonReadInput,
        raw: Raw,
        keyColumns: List<String>,
        bucketKeys: Map<List<String?>, KeyState>,
        vectors: MutableMap<String, DeletionVector?>,
    ): RowHit {
        val file = raw.file
        val cells = raw.cells
        val position = raw.position
        var note: String? = raw.stitched
        val vectorRef = input.vectorFor(file.fileName)
        if (vectorRef != null) {
            if (position == null) return RowHit(file.fileName, null, cells, RowFate.UNKNOWN, note = "no position: DuckDB numbers rows in Parquet only")
            val vector = vectors.getOrPut(file.fileName) {
                runCatching { PaimonDeletionVectorReader.read(StorageLocation.pathOf(vectorRef.indexLocalPath), vectorRef.offset, vectorRef.length, file.fileName, vectorRef.cardinality) }
                    .onFailure { logger.warn("Could not read the vector in {}: {}", vectorRef.indexLocalPath, it.message) }
                    .getOrNull()
            }
            when {
                vector == null -> note = "a vector could not be read"
                position in vector.positions -> return RowHit(file.fileName, position, cells, RowFate.VECTOR_DELETED, vectorRef.indexFileName)
                vector.truncated -> return RowHit(file.fileName, position, cells, RowFate.UNKNOWN, vectorRef.indexFileName, "the vector holds more positions than were decoded")
            }
        }
        if (!input.hasPrimaryKey) return RowHit(file.fileName, position, cells, RowFate.LIVE, note = note)
        val rule = input.rule
        if (rule.skipsLevel0 && (file.level ?: 0) == 0) {
            return RowHit(file.fileName, position, cells, RowFate.SKIPPED, note = "at level 0, which a batch read of this table skips")
        }

        val kind = (cells[PaimonRowKind.COLUMN] as? Number)?.toInt()
        val sequence = (cells[SEQUENCE_NUMBER] as? Number)?.toLong()
        val state = bucketKeys[keyOf(cells, keyColumns)]
        if (kind != null && PaimonRowKind.isRetraction(kind)) {
            val fields = rule.sequenceGroupRemovals.joinToString(", ") { it.joinToString(",") }
            val how = when {
                rule.retractionsIgnored -> ", ignored under ignore-delete"
                rule.retractionsRejected -> ", which a read of this table fails on"
                rule.sequenceGroups && sequence != null && state?.removals?.contains(sequence) == true ->
                    ", at or above the row's $fields: removed the key (remove-record-on-sequence-group)"
                rule.sequenceGroups && kind == PaimonRowKind.DELETE && rule.sequenceGroupRemovals.isNotEmpty() ->
                    ", below the row's $fields or null there: retracts its group's columns, the key stays"
                rule.sequenceGroups -> ", retracts its sequence group's columns; the key stays"
                rule.keeps == PaimonMergeKeeps.COMBINED && !rule.removes(kind) -> ", folded into the row"
                else -> ", not a row"
            }
            return RowHit(file.fileName, position, cells, RowFate.RETRACTION, note = PaimonRowKind.describe(kind) + how)
        }
        if (sequence == null) return RowHit(file.fileName, position, cells, RowFate.UNKNOWN, note = "the record carries no $SEQUENCE_NUMBER")
        if (state == null) return RowHit(file.fileName, position, cells, RowFate.UNKNOWN, note = "the bucket's files could not be read for the key")
        // A removing retraction after this record: under deduplicate any later record shadows it
        // anyway; under a folding engine it is the one thing that does.
        val removal = state.lastRemoval?.takeIf { it > sequence }
        return when {
            state.anyRetraction && rule.retractionsRejected -> RowHit(
                file.fileName, position, cells, RowFate.UNKNOWN, note = "the key has a retraction, which a read of this table fails on",
            )
            state.records <= 1 -> RowHit(file.fileName, position, cells, RowFate.LIVE, note = note)
            removal != null -> RowHit(
                file.fileName, position, cells, RowFate.SUPERSEDED, state.removalHolder,
                note = "sequence $sequence, then $removal: ${PaimonRowKind.describe(state.removalKind ?: -1)}",
            )
            rule.keeps == PaimonMergeKeeps.LATEST -> if (state.latest > sequence) RowHit(
                file.fileName, position, cells, RowFate.SUPERSEDED, state.holder,
                note = "sequence $sequence, then ${state.latest}",
            ) else RowHit(file.fileName, position, cells, RowFate.LIVE, note = note)
            rule.keeps == PaimonMergeKeeps.FIRST -> if (state.first < sequence) RowHit(
                file.fileName, position, cells, RowFate.SUPERSEDED, state.firstHolder,
                note = "sequence $sequence; first-row keeps the record at ${state.first}",
            ) else RowHit(file.fileName, position, cells, RowFate.LIVE, note = note)
            state.folded <= 1 -> RowHit(file.fileName, position, cells, RowFate.LIVE, note = note)
            else -> RowHit(
                file.fileName, position, cells, RowFate.MERGED,
                note = "folded with ${state.folded - 1} other ${if (state.folded == 2L) "record" else "records"} into the key's row" + (note?.let { "; $it" } ?: ""),
            )
        }
    }
}
