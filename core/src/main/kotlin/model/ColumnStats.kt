package model

/**
 * One column's per-file statistics, as a manifest records them, with the byte-encoded bounds
 * decoded into values.
 *
 * A manifest stores these as five parallel maps keyed by field id — `column_sizes`,
 * `value_counts`, `null_value_counts`, `nan_value_counts`, `lower_bounds`, `upper_bounds` — which
 * is efficient to write and unreadable to a person. Pivoting them into one row per column is what
 * turns "what does this file contain" from an exercise in cross-referencing into a table.
 *
 * [type] and [columnName] come from the schema the *manifest* carries, so a file written before a
 * schema change is described by the schema it was actually written against.
 */
data class ColumnStats(
    val fieldId: Int,
    val columnName: String?,
    val type: IcebergType?,
    val lowerBound: DecodedValue?,
    val upperBound: DecodedValue?,
    val valueCount: Long?,
    val nullValueCount: Long?,
    val nanValueCount: Long?,
    val columnSizeBytes: Long?,
) {
    /** Display name, falling back to the field id when the schema does not describe it. */
    val displayName: String get() = columnName ?: "field $fieldId"

    /**
     * True when every value in this file is null. Worth surfacing: it is the common explanation
     * for a column whose bounds are absent while its value count is not.
     */
    val isAllNull: Boolean
        get() = valueCount != null && nullValueCount != null && valueCount > 0 && nullValueCount == valueCount
}

/**
 * Pivots a data file's statistics maps into one [ColumnStats] per column mentioned by any of them.
 *
 * [schema] should be the one carried by the manifest holding this entry. Passing null still
 * produces rows — counts and sizes need no type — but bounds are left undecoded, which is
 * honest about what is known rather than guessing a type.
 */
fun columnStatsFor(dataFile: DataFile, schema: IcebergSchemaModel?): List<ColumnStats> {
    fun longsById(pairs: List<KeyValuePairLong>?): Map<Int, Long> =
        pairs?.associate { it.key to it.value }.orEmpty()

    fun bytesById(pairs: List<KeyValuePairBytes>?): Map<Int, ByteArray> =
        pairs?.associate { it.key to it.value }.orEmpty()

    val sizes = longsById(dataFile.columnSizes)
    val values = longsById(dataFile.valueCounts)
    val nulls = longsById(dataFile.nullValueCounts)
    val nans = longsById(dataFile.nanValueCounts)
    val lowers = bytesById(dataFile.lowerBounds)
    val uppers = bytesById(dataFile.upperBounds)

    // A column appears if any map mentions it — a file may carry bounds without counts, or
    // counts without bounds, and dropping either would hide the asymmetry from the reader.
    val fieldIds = (sizes.keys + values.keys + nulls.keys + nans.keys + lowers.keys + uppers.keys)
        .toSortedSet()

    return fieldIds.map { fieldId ->
        val type = schema?.typeOf(fieldId)
        ColumnStats(
            fieldId = fieldId,
            columnName = schema?.nameOf(fieldId),
            type = type,
            lowerBound = lowers[fieldId]?.let { bytes -> type?.let { decodeSingleValue(bytes, it) } },
            upperBound = uppers[fieldId]?.let { bytes -> type?.let { decodeSingleValue(bytes, it) } },
            valueCount = values[fieldId],
            nullValueCount = nulls[fieldId],
            nanValueCount = nans[fieldId],
            columnSizeBytes = sizes[fieldId],
        )
    }
}
