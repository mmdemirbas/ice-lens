package service

import model.ActualColumnStats
import model.NameMapping
import model.RecordedColumnStats
import model.placeFileColumns
import model.StatsCheckResult
import model.checkStats
import model.normalizeDuckValue
import org.slf4j.LoggerFactory

/**
 * Counts a data file's column figures through DuckDB and puts them beside the recorded ones —
 * see [model.checkStats]. One statement per file: `count(*)` and, per column the file holds
 * that the metadata records a statistic for, its `min`, `max` and null count, NaNs kept out of
 * a floating column's bounds and counted apart, which is how both writers record them.
 *
 * A column is found by **field id** where the file carries one and the statistic names one —
 * an Iceberg Parquet file keeps its ids in the schema, so a column renamed since the file was
 * written is still the same column — and by name otherwise, which is the Paimon case.
 */
object StatsCheckReader {
    private val logger = LoggerFactory.getLogger(StatsCheckReader::class.java)

    fun check(filePath: String, recorded: List<RecordedColumnStats>, recordedRows: Long?, nameMapping: NameMapping? = null): StatsCheckResult {
        val (safePath, ext) = SampleRowReader.resolveForQuery(filePath)
        return DuckDb.withConnection { conn ->
            // The file's own columns, with their DuckDB types.
            val columns = linkedMapOf<String, String>()   // name -> DuckDB type
            conn.prepareStatement("DESCRIBE SELECT * FROM ${SampleRowReader.readerCall(ext)}").use { st ->
                st.setString(1, safePath)
                st.executeQuery().use { rs -> while (rs.next()) columns[rs.getString("column_name")] = rs.getString("column_type") }
            }
            // Which file column each recorded statistic is about: the file's own field ids, the
            // name mapping for a column recording none, and the current name last — a file
            // written by neither Iceberg nor a migration, which nothing else can place.
            val nameById = placeFileColumns(SampleRowReader.fileColumnsOf(filePath), nameMapping).filterValues { it in columns }
            val resolved = recorded.mapNotNull { r ->
                val name = r.fieldId?.let(nameById::get) ?: columns.keys.firstOrNull { it.equals(r.name, ignoreCase = true) } ?: return@mapNotNull null
                r.name to name
            }.toMap()
            val terms = StringBuilder("SELECT count(*)")
            val fileColumns = resolved.values.distinct()
            fileColumns.forEach { name ->
                val q = "\"" + name.replace("\"", "\"\"") + "\""
                val floating = columns[name]?.uppercase() in setOf("FLOAT", "DOUBLE", "REAL")
                if (floating) {
                    terms.append(", min($q) FILTER (WHERE NOT isnan($q)), max($q) FILTER (WHERE NOT isnan($q)), count(*) - count($q), count(*) FILTER (WHERE isnan($q))")
                } else {
                    terms.append(", min($q), max($q), count(*) - count($q), NULL")
                }
            }
            terms.append(" FROM ${SampleRowReader.readerCall(ext)}")
            val actual = linkedMapOf<String, ActualColumnStats>()
            var rows = 0L
            conn.prepareStatement(terms.toString()).use { st ->
                st.setString(1, safePath)
                st.executeQuery().use { rs ->
                    rs.next()
                    rows = rs.getLong(1)
                    fileColumns.forEachIndexed { i, name ->
                        val base = 2 + i * 4
                        val nan = rs.getObject(base + 3)
                        fun value(col: Int): Any? = normalizeDuckValue(rs.getObject(col))
                        actual[name] = ActualColumnStats(
                            min = value(base),
                            max = value(base + 1),
                            nullCount = rs.getLong(base + 2),
                            nanCount = (nan as? Number)?.toLong(),
                        )
                    }
                }
            }
            checkStats(recorded, resolved.mapValues { (_, fileName) -> actual.getValue(fileName) }, rows, recordedRows)
        }
    }
}
