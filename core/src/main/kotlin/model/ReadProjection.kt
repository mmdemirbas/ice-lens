package model

/**
 * A sampled row as a read of the table returns it — the file's cells projected onto the
 * **current** schema by field id, which is the rule every Iceberg reader applies and the one
 * the row card does not: the card prints the file's own columns under the file's own names.
 * The two differ on any table that evolved after the file was written. A column renamed since
 * is read under its new name; a column dropped since is not read at all, whatever the file
 * holds; a column added since is absent from the file and reads as its `initial-default` (v3)
 * or null; a column promoted since (`int` → `long`) reads as the wider type, the value unchanged.
 * `evolved` and `defaults` are the fixtures — a 1.10 read of `defaults` returns `eu 0` for rows
 * written before either column existed, and that printout is what [ProjectedRow] is held to.
 *
 * Top-level fields only: a struct's inner fields evolve by the same rule, and the card shows a
 * struct whole. A file column with no field id is placed through the table's [NameMapping] —
 * what `add_files` and `migrate` set for the files they register, which record none — and one
 * the mapping names nowhere either is left unmatched and said so.
 */
data class ProjectedRow(
    val cells: List<ProjectedCell>,
    /** File columns the current schema has no field for — a read returns none of them. */
    val dropped: List<DroppedCell>,
    /** File columns recording no field id, which nothing here can place. */
    val unmatched: List<String>,
) {
    /** Whether a read returns anything other than the file's columns under the file's names. */
    val differsFromFile: Boolean
        get() = dropped.isNotEmpty() || unmatched.isNotEmpty() || cells.any { it.source != ProjectedCellSource.FILE || it.fileColumn != it.name || it.viaMapping || it.rebuilt }

    val describe: String
        get() {
            val notes = buildList {
                cells.count { it.source == ProjectedCellSource.INITIAL_DEFAULT }.takeIf { it > 0 }?.let { add("$it from an initial default") }
                cells.count { it.source == ProjectedCellSource.ABSENT }.takeIf { it > 0 }?.let { add("$it absent from the file, read as null") }
                cells.count { it.source == ProjectedCellSource.FILE && it.fileColumn != it.name }.takeIf { it > 0 }?.let { add("$it renamed since the file was written") }
                cells.count { it.viaMapping }.takeIf { it > 0 }?.let { add("$it placed by the name mapping, the file recording no field ids") }
                cells.count { it.rebuilt }.takeIf { it > 0 }?.let { add("$it rebuilt inside, a field renamed or added within it since the file was written") }
                dropped.size.takeIf { it > 0 }?.let { add("$it of the file's columns dropped from the table") }
                unmatched.size.takeIf { it > 0 }?.let { add("$it of the file's columns without a field id") }
            }
            return if (notes.isEmpty()) "a read returns the row as the file holds it" else "a read returns ${cells.size} columns: " + notes.joinToString("; ")
        }
}

enum class ProjectedCellSource(val label: String) {
    FILE("the file"),
    INITIAL_DEFAULT("the column's initial default"),
    ABSENT("absent from the file; null"),
}

data class ProjectedCell(
    val fieldId: Int,
    /** The current schema's name for the column. */
    val name: String,
    val type: String,
    /** The value as the row's cell holds it — a DuckDB value, or the default's JSON text. */
    val value: String,
    val source: ProjectedCellSource,
    /** The file's name for the column, when the value came from the file. */
    val fileColumn: String? = null,
    /** Whether the file column was placed through the name mapping rather than by an id it records. */
    val viaMapping: Boolean = false,
    /** Whether a struct inside the value was rebuilt by id — a field renamed or added within it since the file was written. */
    val rebuilt: Boolean = false,
)

data class DroppedCell(val fileColumn: String, val fieldId: Int, val value: String)

/** Iceberg's reserved metadata columns a v3 file may carry (`_row_id`, `_last_updated_sequence_number`, …), which no table schema names. */
private fun isMetadataColumn(name: String) = name.startsWith("_")

/**
 * [cells] as a read under [schema] returns them, the file's columns placed by [fileColumns]
 * (file column name → the field id it records, or null — from the file's own footer or header)
 * and, for a column recording none, by [mapping] — see [placeFileColumns].
 */
fun projectRow(cells: Map<String, Any?>, fileColumns: Map<String, Int?>, schema: IcebergSchemaModel, mapping: NameMapping? = null): ProjectedRow =
    projectRow(cells, fileColumns.map { (name, id) -> FileColumn.leaf(name, id) }, schema, mapping)

/**
 * The same over the file's column tree ([FileColumn]), so a struct's fields are placed by id
 * too: a DuckDB struct value is rebuilt under the schema's field names where the file's shape
 * is not the schema's — a field renamed inside the struct under its new name, one the file
 * predates as null — and left as DuckDB prints it where it is.
 */
fun projectRow(cells: Map<String, Any?>, columns: List<FileColumn>, schema: IcebergSchemaModel, mapping: NameMapping? = null): ProjectedRow {
    val fileColumns = columns.topLevel()
    val byName = columns.associateBy { it.name }
    val fileColumnById = placeFileColumns(fileColumns.filterKeys { it in cells }, mapping)
    val projected = schema.struct.fields.map { field ->
        val fileColumn = fileColumnById[field.id]
        when {
            fileColumn != null -> {
                val rebuilt = byName[fileColumn]?.let { projectValue(cells[fileColumn], field.type, it) }
                ProjectedCell(
                    field.id, field.name, field.type.typeName, rebuilt ?: cells[fileColumn].toString(),
                    ProjectedCellSource.FILE, fileColumn, viaMapping = fileColumns[fileColumn] == null, rebuilt = rebuilt != null,
                )
            }
            field.initialDefault != null -> ProjectedCell(field.id, field.name, field.type.typeName, field.showDefault(field.initialDefault) ?: "null", ProjectedCellSource.INITIAL_DEFAULT)
            else -> ProjectedCell(field.id, field.name, field.type.typeName, "null", ProjectedCellSource.ABSENT)
        }
    }
    val placed = projected.mapNotNull { it.fileColumn }.toSet()
    val idOf = { name: String -> fileColumns[name] ?: mapping?.fieldIdOf(name) }
    val dropped = cells.keys.filter { it !in placed && !isMetadataColumn(it) && idOf(it) != null }
        .map { DroppedCell(it, idOf(it)!!, cells[it].toString()) }
    val unmatched = cells.keys.filter { it !in placed && !isMetadataColumn(it) && idOf(it) == null }
    return ProjectedRow(projected, dropped, unmatched)
}

/**
 * [value] as text under [type] through the file's [column], rebuilt by id in DuckDB's own
 * spelling where the file's shape is not the schema's — or null where it is, and the value
 * stands as DuckDB prints it. A struct arrives as a `java.sql.Struct` whose attributes are in
 * the file's field order, a list as a `java.sql.Array`; a map is left as it prints.
 */
private fun projectValue(value: Any?, type: IcebergType, column: FileColumn): String? = when {
    value == null -> null
    type is IcebergType.StructType && column.kind == FileColumn.Kind.STRUCT && value is java.sql.Struct && structDiffers(type, column) -> {
        val attributes = runCatching { value.attributes }.getOrNull()
        if (attributes == null) null else {
            val byId = column.children.mapIndexedNotNull { i, child -> child.fieldId?.let { it to (child to attributes.getOrNull(i)) } }.toMap()
            type.fields.joinToString(", ", "{", "}") { f ->
                val (child, inner) = byId[f.id] ?: (null to null)
                "'${f.name}': " + when {
                    child == null || inner == null -> "NULL"
                    else -> projectValue(inner, f.type, child) ?: inner.toString()
                }
            }
        }
    }
    type is IcebergType.ListType && column.kind == FileColumn.Kind.LIST && value is java.sql.Array && column.children.size == 1 &&
        type.element is IcebergType.StructType && structDiffers(type.element, column.children.single()) -> {
        val element = column.children.single()
        runCatching { (value.array as? Array<*>)?.toList() }.getOrNull()
            ?.joinToString(", ", "[", "]") { if (it == null) "NULL" else projectValue(it, type.element, element) ?: it.toString() }
    }
    else -> null
}

/** Whether a struct's fields, by id, are not the file's — a rename, a field the file lacks, or a nested one that differs. */
private fun structDiffers(type: IcebergType.StructType, column: FileColumn): Boolean = type.fields.any { f ->
    val child = column.child(f.id) ?: return@any true
    child.name != f.name || (f.type is IcebergType.StructType && child.kind == FileColumn.Kind.STRUCT && structDiffers(f.type, child)) ||
        (f.type is IcebergType.ListType && child.kind == FileColumn.Kind.LIST && f.type.element is IcebergType.StructType && child.children.size == 1 && structDiffers(f.type.element, child.children.single()))
}
