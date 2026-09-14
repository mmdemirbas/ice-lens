package service

import model.ChangelogFileRead
import model.ChangelogRecord
import model.PaimonChangelog
import model.PaimonChangelogInputs
import model.PaimonRowKind
import model.PaimonSystemColumns
import model.ScanFilter
import model.toSql
import org.slf4j.LoggerFactory

/**
 * The changelog files of every traced snapshot read for a filter, in the order a streaming
 * reader receives them — snapshot by snapshot, each file in its listed order, each record in
 * file order — with its `_VALUE_KIND` and `_SEQUENCE_NUMBER` beside the row's cells.
 *
 * A changelog file is a key-value file like a data file (`_KEY_*`, `_SEQUENCE_NUMBER`,
 * `_VALUE_KIND`, the value columns), so it is read through the same projection the lookup
 * reads a data file with — placed by the ids its own schema gives its columns, under the
 * latest schema's names, the system columns passed through. The filter is the lookup's, so
 * `k = 2` reads the records published for key 2 and nothing else. Records per file stop at
 * [RowLookup.MAX_HITS_PER_FILE], said in the file's row.
 */
object PaimonChangelogTrace {
    private val log = LoggerFactory.getLogger(PaimonChangelogTrace::class.java)

    fun trace(inputs: PaimonChangelogInputs, filter: ScanFilter): PaimonChangelog {
        val predicate = filter.toSql { column -> inputs.readSchema.let { s -> s.idOfPath(column)?.let(s::typeOf) } }
        val records = mutableListOf<ChangelogRecord>()
        val reads = mutableListOf<ChangelogFileRead>()
        for (snapshot in inputs.snapshots) {
            for (file in snapshot.files) {
                val rows = runCatching { readRecords(file.localPath, PaimonRowLookup.projectionOf(file, inputs.readSchema, rowNumber = true), predicate.sql, predicate.params) }
                rows.onFailure { log.warn("Could not read changelog file {}: {}", file.localPath, it.message) }
                reads += ChangelogFileRead(snapshot.snapshotId, file.fileName, rows.getOrNull()?.size ?: 0, rows.exceptionOrNull()?.let { it.message ?: it::class.simpleName })
                rows.getOrNull()?.forEach { cells ->
                    val system = cells.filterKeys { it == PaimonRowKind.COLUMN || it == PaimonSystemColumns.SEQUENCE_NUMBER || it.startsWith(PaimonSystemColumns.KEY_PREFIX) || it == "file_row_number" }
                    records += ChangelogRecord(
                        snapshotId = snapshot.snapshotId,
                        commitKind = snapshot.commitKind,
                        kind = (cells[PaimonRowKind.COLUMN] as? Number)?.toInt(),
                        sequenceNumber = (cells[PaimonSystemColumns.SEQUENCE_NUMBER] as? Number)?.toLong(),
                        fileName = file.fileName,
                        cells = cells.filterKeys { it !in system.keys },
                    )
                }
            }
        }
        return PaimonChangelog(records, reads, inputs.capped, inputs.withChangelog, inputs.producerRule)
    }

    private fun readRecords(localPath: String, source: FileProjection, where: String, params: List<String>): List<Map<String, Any?>> {
        val (safePath, _) = SampleRowReader.resolveForQuery(localPath)
        return DuckDb.withConnection { conn ->
            conn.prepareStatement("SELECT * FROM ${source.sql} WHERE $where ORDER BY file_row_number LIMIT ${RowLookup.MAX_HITS_PER_FILE}").use { pstmt ->
                var i = source.bind(pstmt, 1, safePath)
                params.forEach { p -> pstmt.setString(i++, p) }
                pstmt.executeQuery().use { rs ->
                    val meta = rs.metaData
                    val rows = mutableListOf<Map<String, Any?>>()
                    while (rs.next()) rows += (1..meta.columnCount).associate { meta.getColumnName(it) to rs.getObject(it) }
                    rows
                }
            }
        }
    }
}
