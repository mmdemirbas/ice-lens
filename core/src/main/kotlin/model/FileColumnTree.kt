package model

/**
 * A data file's column tree: each node's name, the field id it records, and what is under it
 * — a struct's fields, a list's element, a map's key and value. What a read places a *nested*
 * column by: Iceberg records an id on every leaf (`addr.city` is 6 whatever the struct is
 * called or the field is renamed to), and a Parquet file's `key_value` and `list` wrappers,
 * which carry none, are folded away so the tree has the shape the schema has.
 */
data class FileColumn(
    val name: String,
    val fieldId: Int?,
    val kind: Kind,
    val children: List<FileColumn> = emptyList(),
) {
    enum class Kind { PRIMITIVE, STRUCT, LIST, MAP }

    /** The child recording [fieldId], if any. */
    fun child(fieldId: Int): FileColumn? = children.firstOrNull { it.fieldId == fieldId }

    companion object {
        /** A flat column as the tree: a leaf, whatever the file has under it — the shape a Paimon file's placement gives. */
        fun leaf(name: String, fieldId: Int?) = FileColumn(name, fieldId, Kind.PRIMITIVE)
    }
}

/** The top-level columns with the id each records — what [placeFileColumns] takes. */
fun List<FileColumn>.topLevel(): Map<String, Int?> = associate { it.name to it.fieldId }
