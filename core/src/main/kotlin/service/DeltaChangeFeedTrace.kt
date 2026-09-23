package service

import model.Changelog
import model.ChangelogFileRead
import model.ChangelogRecord
import model.DeltaChangeFeedInputs
import model.DeltaChangeSource
import model.DeltaChangeSourceKind
import model.DeltaDeletionVector
import model.IcebergSchemaModel
import model.IcebergType
import model.MappedField
import model.NameMapping
import model.NestedField
import model.PaimonRowKind
import model.ScanFilter
import model.deltaChangeKind
import model.toSql
import model.withConstants
import org.slf4j.LoggerFactory
import java.util.BitSet

/**
 * The change data feed of every traced version read for a filter — see [DeltaChangeFeedInputs]
 * for which files a version is read from. Each file is read through the lookup's own projection
 * ([FileProjection]) under the newest schema, with its partition values as constants, so the
 * filter names the columns the lookup names and a column-mapped file is placed by physical name
 * or id. A cdc file carries one more column, `_change_type`, which is read beside the cells; a
 * data file's rows are selected by position against the vectors the source names. Records per
 * file stop at [RowLookup.MAX_HITS_PER_FILE], said in the file's row.
 */
object DeltaChangeFeedTrace {
    private val log = LoggerFactory.getLogger(DeltaChangeFeedTrace::class.java)
    private const val CHANGE_TYPE = "_change_type"

    fun trace(inputs: DeltaChangeFeedInputs, filter: ScanFilter): Changelog {
        val read = inputs.read
        val predicate = filter.toSql { column -> read.schema.idOfPath(column)?.let(read.schema::typeOf) }
        val withChangeType = withChangeType(read.schema, read.mapping)
        val records = mutableListOf<ChangelogRecord>()
        val reads = mutableListOf<ChangelogFileRead>()
        for (version in inputs.versions) {
            for (source in version.sources) {
                val name = source.path.substringAfterLast('/')
                val rows = runCatching {
                    if (source.kind == DeltaChangeSourceKind.CHANGE_FILE) {
                        readRows(source, withChangeType.first.withConstants(source.constants), withChangeType.second, predicate.sql, predicate.params, positions = null)
                            .map { it[CHANGE_TYPE] as? String to it - CHANGE_TYPE }
                    } else {
                        rowsOf(inputs.tableRoot, source, read.schema.withConstants(source.constants), read.mapping, predicate.sql, predicate.params)
                    }
                }
                rows.onFailure { log.warn("Could not read {} for the change feed: {}", source.localPath, it.message) }
                reads += ChangelogFileRead(version.version, name, rows.getOrNull()?.size ?: 0, rows.exceptionOrNull()?.let { it.message ?: it::class.simpleName })
                rows.getOrNull()?.forEach { (changeType, cells) ->
                    val kind = deltaChangeKind(changeType)
                    records += ChangelogRecord(
                        snapshotId = version.version,
                        commitKind = version.operation,
                        kind = kind,
                        sequenceNumber = null,
                        fileName = name,
                        cells = cells - SampleRowReader.FILE_ROW_NUMBER,
                        label = changeType?.let { t -> kind?.let { "$t (${PaimonRowKind.symbol(it)})" } ?: t },
                    )
                }
            }
        }
        return Changelog(records, reads, inputs.capped, inputs.publishing, inputs.rule, unit = "version")
    }

    /** A data file's rows as the source publishes them: the change type, and the rows its vectors select. */
    private fun rowsOf(
        tableRoot: java.nio.file.Path, source: DeltaChangeSource, schema: IcebergSchemaModel, mapping: NameMapping,
        where: String, params: List<String>,
    ): List<Pair<String?, Map<String, Any?>>> {
        fun positions(vector: DeltaDeletionVector?): BitSet? = vector?.let {
            requireNotNull(DeltaGraphBuilder.readDeletionVectorPositions(tableRoot, it)) { "the deletion vector ${it.uniqueId} could not be read" }
        }
        return when (source.kind) {
            DeltaChangeSourceKind.INSERTED, DeltaChangeSourceKind.DELETED -> {
                val excluded = positions(source.excluded)
                val type = if (source.kind == DeltaChangeSourceKind.INSERTED) "insert" else "delete"
                readRows(source, schema, mapping, where, params, excluded?.let { e -> { p: Long -> !e.get(p.toInt()) } }).map { type to it }
            }
            DeltaChangeSourceKind.VECTOR_CHANGED -> {
                val older = positions(source.older) ?: BitSet()
                val newer = positions(source.newer) ?: BitSet()
                // Rows the new vector marks and the old did not are deleted; the reverse come back.
                val deleted = (newer.clone() as BitSet).apply { andNot(older) }
                val restored = (older.clone() as BitSet).apply { andNot(newer) }
                val touched = (deleted.clone() as BitSet).apply { or(restored) }
                if (touched.isEmpty) emptyList() else
                    readRows(source, schema, mapping, where, params, { p: Long -> touched.get(p.toInt()) }).map { row ->
                        val position = (row[SampleRowReader.FILE_ROW_NUMBER] as? Number)?.toInt()
                        (if (position != null && deleted.get(position)) "delete" else "insert") to row
                    }
            }
            DeltaChangeSourceKind.CHANGE_FILE -> error("a change file carries its own change type")
        }
    }

    /**
     * The file's rows matching [where] in file order, with `file_row_number`, kept where
     * [positions] says so. A position filter is applied here rather than in SQL, so the cap
     * counts the rows kept, not the rows read.
     */
    private fun readRows(
        source: DeltaChangeSource, schema: IcebergSchemaModel, mapping: NameMapping, where: String, params: List<String>,
        positions: ((Long) -> Boolean)?,
    ): List<Map<String, Any?>> {
        val (safePath, ext) = SampleRowReader.resolveForQuery(source.localPath)
        val projection = FileProjection.of(ext, SampleRowReader.fileColumnTreeOf(source.localPath), schema, mapping, rowNumber = true)
        val limit = if (positions == null) " LIMIT ${RowLookup.MAX_HITS_PER_FILE}" else ""
        return DuckDb.withConnection { conn ->
            conn.prepareStatement("SELECT * FROM ${projection.sql} WHERE $where ORDER BY ${SampleRowReader.FILE_ROW_NUMBER}$limit").use { pstmt ->
                var i = projection.bind(pstmt, 1, safePath)
                params.forEach { p -> pstmt.setString(i++, p) }
                pstmt.executeQuery().use { rs ->
                    val meta = rs.metaData
                    val rows = mutableListOf<Map<String, Any?>>()
                    while (rs.next() && rows.size < RowLookup.MAX_HITS_PER_FILE) {
                        val row = (1..meta.columnCount).associate { meta.getColumnName(it) to rs.getObject(it) }
                        val position = (row[SampleRowReader.FILE_ROW_NUMBER] as? Number)?.toLong()
                        if (positions == null || (position != null && positions(position))) rows += row
                    }
                    rows
                }
            }
        }
    }

    /** [schema] with `_change_type` as one more string column, placed by its name — a cdc file records it with no field id. */
    private fun withChangeType(schema: IcebergSchemaModel, mapping: NameMapping): Pair<IcebergSchemaModel, NameMapping> {
        val id = (schema.fieldsById.keys.maxOrNull() ?: 0) + 1
        return schema.copy(struct = IcebergType.StructType(schema.struct.fields + NestedField(id, CHANGE_TYPE, IcebergType.StringType, required = false))) to
            NameMapping(mapping.fields + MappedField(id, listOf(CHANGE_TYPE)))
    }
}
