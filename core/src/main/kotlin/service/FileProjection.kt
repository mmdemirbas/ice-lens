package service

import model.FileColumn
import model.IcebergSchemaModel
import model.IcebergType
import model.NameMapping
import model.duckDbTypeOf
import model.placeFileColumns
import model.quoteSqlIdentifier
import model.topLevel
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
 * comes along where the format has one, and its name when asked (`filename`), which a
 * `UNION ALL` over a bucket's files tells the files apart by. Without a schema the file is
 * read as it is.
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
        /** The projection over a file's flat columns — a Paimon file's placement, whose nested types are read as they are. */
        fun of(
            ext: String,
            fileColumns: Map<String, Int?>,
            schema: IcebergSchemaModel?,
            mapping: NameMapping?,
            rowNumber: Boolean = false,
            alias: String = "s",
            filename: Boolean = false,
        ): FileProjection = of(ext, fileColumns.map { (name, id) -> FileColumn.leaf(name, id) }, schema, mapping, rowNumber, alias, filename)

        /**
         * The projection over a file's column tree ([SampleRowReader.fileColumnTreeOf]), so a
         * struct's fields are placed by id too: a field renamed inside a struct is read under
         * its new name and one added since the file was written is `NULL`, through
         * `struct_pack` over the file's own — a list's or a map's elements through
         * `list_transform` — and a nested column the file already has the schema's shape of is
         * left as it is.
         */
        fun of(
            ext: String,
            columns: List<FileColumn>,
            schema: IcebergSchemaModel?,
            mapping: NameMapping?,
            rowNumber: Boolean = false,
            alias: String = "s",
            filename: Boolean = false,
        ): FileProjection {
            val reader = SampleRowReader.readerCall(ext, rowNumber = rowNumber, filename = filename)
            if (schema == null || columns.isEmpty()) return FileProjection("(SELECT * FROM $reader) $alias", emptyList())
            val placed = placeFileColumns(columns.topLevel(), mapping)
            val byName = columns.associateBy { it.name }
            val defaults = mutableListOf<String>()
            val select = schema.struct.fields.map { field ->
                val alias = quoteSqlIdentifier(field.name)
                val fileColumn = placed[field.id]
                val default = field.showDefault(field.initialDefault)
                val type = duckDbTypeOf(field.type)
                when {
                    fileColumn != null -> {
                        val expr = "f.${quoteSqlIdentifier(fileColumn)}"
                        "${byName[fileColumn]?.let { nestedSql(expr, field.type, it, 0) } ?: expr} AS $alias"
                    }
                    default != null && type != null -> { defaults += default; "CAST(? AS $type) AS $alias" }
                    else -> "NULL AS $alias"
                }
            } + (if (filename) listOf("f.filename") else emptyList()) +
                (if (rowNumber && SampleRowReader.hasRowPositions(ext)) listOf("f.${SampleRowReader.FILE_ROW_NUMBER}") else emptyList())
            return FileProjection("(SELECT ${select.joinToString(", ")} FROM $reader f) $alias", defaults)
        }

        /**
         * [expr] read as [type] through the file's [column], or null where the file's shape is
         * the schema's and the expression stands as it is. [depth] names the lambda variables,
         * one per nesting level.
         */
        private fun nestedSql(expr: String, type: IcebergType, column: FileColumn, depth: Int): String? = when (type) {
            is IcebergType.StructType -> {
                if (column.kind != FileColumn.Kind.STRUCT) null else {
                    var changed = false
                    val fields = type.fields.map { f ->
                        val child = column.child(f.id)
                        val value = when {
                            child == null -> { changed = true; nullOf(f.type) }
                            else -> {
                                if (child.name != f.name) changed = true
                                val inner = "$expr.${quoteSqlIdentifier(child.name)}"
                                nestedSql(inner, f.type, child, depth)?.also { changed = true } ?: inner
                            }
                        }
                        "${quoteSqlIdentifier(f.name)} := $value"
                    }
                    if (changed) "struct_pack(${fields.joinToString(", ")})" else null
                }
            }
            is IcebergType.ListType -> {
                val element = column.children.singleOrNull()
                if (column.kind != FileColumn.Kind.LIST || element == null) null else {
                    val x = "x$depth"
                    nestedSql(x, type.element, element, depth + 1)?.let { "list_transform($expr, lambda $x: $it)" }
                }
            }
            is IcebergType.MapType -> {
                if (column.kind != FileColumn.Kind.MAP || column.children.size != 2) null else {
                    val e = "e$depth"
                    val key = column.children[0].let { nestedSql("$e.key", type.key, it, depth + 1) }
                    val value = column.children[1].let { nestedSql("$e.value", type.value, it, depth + 1) }
                    if (key == null && value == null) null else
                        "map_from_entries(list_transform(map_entries($expr), lambda $e: struct_pack(key := ${key ?: "$e.key"}, value := ${value ?: "$e.value"})))"
                }
            }
            else -> null
        }

        /** A typed `NULL` for a field the file lacks, so a struct's field has the column's type and not a bare null's. */
        private fun nullOf(type: IcebergType): String = duckDbTypeSql(type)?.let { "CAST(NULL AS $it)" } ?: "NULL"

        /** [duckDbTypeOf] over nested types too: `STRUCT("a" INTEGER)`, `VARCHAR[]`, `MAP(VARCHAR, INTEGER)`. */
        private fun duckDbTypeSql(type: IcebergType): String? = when (type) {
            is IcebergType.StructType -> type.fields.map { f -> duckDbTypeSql(f.type)?.let { "${quoteSqlIdentifier(f.name)} $it" } ?: return null }.let { "STRUCT(${it.joinToString(", ")})" }
            is IcebergType.ListType -> duckDbTypeSql(type.element)?.let { "$it[]" }
            is IcebergType.MapType -> duckDbTypeSql(type.key)?.let { k -> duckDbTypeSql(type.value)?.let { v -> "MAP($k, $v)" } }
            else -> duckDbTypeOf(type)
        }
    }
}
