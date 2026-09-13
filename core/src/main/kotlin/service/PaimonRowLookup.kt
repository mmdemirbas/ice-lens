package service

import model.DEFAULT_PAIMON_MERGE_ENGINE
import model.DeletionVector
import model.LookupFileOutcome
import model.PaimonLookupFile
import model.PaimonRowKind
import model.PaimonRowLookupInput
import model.RowFate
import model.RowHit
import model.RowLookupResult
import model.ScanFilter
import model.duckDbTypeOf
import model.paimonTypeAsIceberg
import model.quoteSqlIdentifier
import model.toSql
import org.slf4j.LoggerFactory
import java.nio.file.Paths

/**
 * Finding rows in a Paimon table and deciding their fate — see [PaimonRowLookupInput] for what
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

    private class Raw(val file: PaimonLookupFile, val position: Long?, val cells: Map<String, Any?>)

    /** What the bucket holds for a key: its latest sequence number, the file holding it, that record's kind, and how many records. */
    private class KeyState(val latest: Long, val holder: String, val latestKind: Int?, val records: Long)

    /**
     * Reads the files the filter leaves — every live data file whose name is not in
     * [ruledOut] — and decides each hit.
     */
    fun lookup(input: PaimonRowLookupInput, filter: ScanFilter, ruledOut: Set<String>): RowLookupResult {
        val candidates = input.files.filter { it.fileName !in ruledOut }
        val toRead = candidates.take(RowLookup.MAX_FILES)
        val predicate = filter.toSql { column -> input.schema.fields.firstOrNull { it.name == column }?.type?.let(::paimonTypeAsIceberg) }
        val outcomes = mutableListOf<LookupFileOutcome>()
        val raws = mutableListOf<Raw>()
        for (file in toRead) {
            val rows = runCatching { readMatches(file, predicate.sql, predicate.params) }
            val error = rows.exceptionOrNull()
            if (error != null) {
                logger.warn("Could not read {}: {}", file.localPath, error.message)
                outcomes += LookupFileOutcome(file.fileName, 0, error.message ?: error.toString())
                continue
            }
            val matched = rows.getOrThrow()
            outcomes += LookupFileOutcome(file.fileName, matched.size)
            matched.forEach { cells ->
                val position = (cells[SampleRowReader.FILE_ROW_NUMBER] as? Number)?.toLong()
                raws += Raw(file, position, cells - SampleRowReader.FILE_ROW_NUMBER)
            }
        }
        val keyColumns = input.trimmedPrimaryKeys.map { KEY_PREFIX + it }
        val keyStates = if (input.hasPrimaryKey) keyStatesFor(input, raws, keyColumns) else emptyMap()
        val vectors = mutableMapOf<String, DeletionVector?>()
        val hits = raws.map { raw -> decide(input, raw, keyColumns, keyStates[raw.file.partition to raw.file.bucket].orEmpty(), vectors) }
        return RowLookupResult(outcomes, input.files.size - candidates.size, candidates.size - toRead.size, hits)
    }

    private fun readMatches(file: PaimonLookupFile, where: String, params: List<String>): List<Map<String, Any?>> {
        val (safePath, ext) = SampleRowReader.resolveForQuery(file.localPath)
        val source = if (ext == "parquet") "read_parquet(?, file_row_number = true)" else "read_parquet(?)"
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

    private fun keyOf(cells: Map<String, Any?>, keyColumns: List<String>): List<String?> = keyColumns.map { cells[it]?.toString() }

    /**
     * For every bucket the hits fall in, what that bucket holds for each hit key — one statement
     * per bucket over all of its live files, restricted to the keys asked about. Keys are bound
     * as text and cast to the column's type, the same rule the filter's literals follow.
     */
    private fun keyStatesFor(
        input: PaimonRowLookupInput,
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
            runCatching { queryKeyStates(files, keyColumns, casts, keys) }
                .onFailure { logger.warn("Could not read bucket {}: {}", scope, it.message) }
                .getOrDefault(emptyMap())
        }
    }

    private fun queryKeyStates(
        files: List<PaimonLookupFile>,
        keyColumns: List<String>,
        casts: List<String?>,
        keys: List<List<String?>>,
    ): Map<List<String?>, KeyState> {
        val quoted = keyColumns.map(::quoteSqlIdentifier)
        val keyList = quoted.joinToString(", ")
        val branches = files.joinToString(" UNION ALL ") {
            "SELECT $keyList, ${quoteSqlIdentifier(SEQUENCE_NUMBER)} AS s, ${quoteSqlIdentifier(PaimonRowKind.COLUMN)} AS kind, filename AS f " +
                "FROM read_parquet(?, filename = true)"
        }
        val tuple = "(" + casts.joinToString(", ") { cast -> if (cast == null) "?" else "CAST(? AS $cast)" } + ")"
        val inList = keys.joinToString(", ") { tuple }
        val sql = "SELECT $keyList, max(s), arg_max(f, s), arg_max(kind, s), count(*) FROM ($branches) " +
            "WHERE ($keyList) IN ($inList) GROUP BY $keyList"
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
                            latest = rs.getLong(n + 1),
                            holder = rs.getString(n + 2).substringAfterLast('/'),
                            latestKind = (rs.getObject(n + 3) as? Number)?.toInt(),
                            records = rs.getLong(n + 4),
                        )
                    }
                    states
                }
            }
        }
    }

    private fun decide(
        input: PaimonRowLookupInput,
        raw: Raw,
        keyColumns: List<String>,
        bucketKeys: Map<List<String?>, KeyState>,
        vectors: MutableMap<String, DeletionVector?>,
    ): RowHit {
        val file = raw.file
        val cells = raw.cells
        val position = raw.position
        var note: String? = null
        val vectorRef = input.vectorFor(file.fileName)
        if (vectorRef != null) {
            if (position == null) return RowHit(file.fileName, null, cells, RowFate.UNKNOWN, note = "no position: DuckDB numbers rows in Parquet only")
            val vector = vectors.getOrPut(file.fileName) {
                runCatching { PaimonDeletionVectorReader.read(Paths.get(vectorRef.indexLocalPath), vectorRef.offset, vectorRef.length, file.fileName, vectorRef.cardinality) }
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

        val kind = (cells[PaimonRowKind.COLUMN] as? Number)?.toInt()
        if (kind != null && PaimonRowKind.isRetraction(kind)) {
            return RowHit(file.fileName, position, cells, RowFate.RETRACTION, note = "${PaimonRowKind.describe(kind)}, not a row")
        }
        val sequence = (cells[SEQUENCE_NUMBER] as? Number)?.toLong()
            ?: return RowHit(file.fileName, position, cells, RowFate.UNKNOWN, note = "the record carries no $SEQUENCE_NUMBER")
        val state = bucketKeys[keyOf(cells, keyColumns)]
            ?: return RowHit(file.fileName, position, cells, RowFate.UNKNOWN, note = "the bucket's files could not be read for the key")
        return when {
            state.records <= 1 -> RowHit(file.fileName, position, cells, RowFate.LIVE, note = note)
            input.mergeEngine != DEFAULT_PAIMON_MERGE_ENGINE -> RowHit(
                file.fileName, position, cells, RowFate.UNKNOWN,
                note = "merge-engine = ${input.mergeEngine} combines the key's ${state.records} records; not applied here",
            )
            state.latest > sequence -> RowHit(
                file.fileName, position, cells, RowFate.SUPERSEDED, state.holder,
                note = "sequence $sequence, then ${state.latest}" +
                    (state.latestKind?.takeIf { PaimonRowKind.isRetraction(it) }?.let { ": ${PaimonRowKind.describe(it)}" } ?: ""),
            )
            else -> RowHit(file.fileName, position, cells, RowFate.LIVE, note = note)
        }
    }
}
