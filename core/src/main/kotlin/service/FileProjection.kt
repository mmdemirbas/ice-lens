package service

import model.IcebergSchemaModel
import model.NameMapping
import model.duckDbTypeOf
import model.placeFileColumns
import model.quoteSqlIdentifier
import java.sql.PreparedStatement

/**
 * A data file read under the table's current schema — the `FROM` a lookup, a count or a check
 * runs its `WHERE` over, so the clause can name the columns the way the schema does and a file
 * written before a rename, or before a column existed, still answers.
 *
 * `SELECT * FROM read_parquet(?)` addresses a file by its own column names, which on a table
 * that has renamed a column since the file was written is not the name in the filter — a
 * lookup on `evolved`'s `note` came back as a DuckDB error on the two files that predate it,
 * where a read returns their rows with `note` null — and on a file registered by `add_files`
 * is not a name the file's footer ties to any field. So the source is a subquery that places
 * each file column by field id, the name mapping where the file records none
 * ([placeFileColumns]), and aliases it to the schema's name; a field the file lacks is its
 * `initial-default`, bound and cast to the column's type, or `NULL`. The file's row number
 * comes along where the format has one, and so does any column [passThrough] accepts under
 * its own name — a Paimon file's `_KEY_*`, `_SEQUENCE_NUMBER` and `_VALUE_KIND`, which the
 * lookup decides a record by and no schema names. Without a schema the file is read as it is.
 */
internal class FileProjection private constructor(
    /** The `FROM` text: a subquery under its alias, with `?` for the path and one `?` per bound default. */
    val sql: String,
    private val defaults: List<String>,
) {
    /** Binds the defaults and the path in the order the text names them; returns the next parameter index. */
    fun bind(statement: PreparedStatement, startIndex: Int, path: String): Int {
        var i = startIndex
        defaults.forEach { statement.setString(i++, it) }
        statement.setString(i++, path)
        return i
    }

    companion object {
        fun of(
            ext: String,
            fileColumns: Map<String, Int?>,
            schema: IcebergSchemaModel?,
            mapping: NameMapping?,
            rowNumber: Boolean = false,
            alias: String = "s",
            passThrough: (String) -> Boolean = { false },
        ): FileProjection {
            val reader = SampleRowReader.readerCall(ext, rowNumber = rowNumber)
            if (schema == null || fileColumns.isEmpty()) return FileProjection("(SELECT * FROM $reader) $alias", emptyList())
            val placed = placeFileColumns(fileColumns, mapping)
            val defaults = mutableListOf<String>()
            val select = schema.struct.fields.map { field ->
                val alias = quoteSqlIdentifier(field.name)
                val fileColumn = placed[field.id]
                val default = field.showDefault(field.initialDefault)
                val type = duckDbTypeOf(field.type)
                when {
                    fileColumn != null -> "f.${quoteSqlIdentifier(fileColumn)} AS $alias"
                    default != null && type != null -> { defaults += default; "CAST(? AS $type) AS $alias" }
                    else -> "NULL AS $alias"
                }
            } + fileColumns.keys.filter(passThrough).map { "f.${quoteSqlIdentifier(it)}" } +
                (if (rowNumber && SampleRowReader.hasRowPositions(ext)) listOf("f.${SampleRowReader.FILE_ROW_NUMBER}") else emptyList())
            return FileProjection("(SELECT ${select.joinToString(", ")} FROM $reader f) $alias", defaults)
        }
    }
}
