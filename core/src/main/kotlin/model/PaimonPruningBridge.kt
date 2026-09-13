package model

import kotlinx.serialization.json.JsonPrimitive

/**
 * What a Paimon manifest and data file record, in the vocabulary the pruning rules are written in.
 *
 * `evaluatePruning` reads a [PartitionSummary] per partition field and `evaluateFilePruning` a
 * [ColumnStats] per column, both in Iceberg's terms — a transform, a field id, an [IcebergType]
 * to read a literal in. Paimon records the same two things with none of that vocabulary: a
 * manifest list entry's `_PARTITION_STATS` is a per-column minimum and maximum over the entries'
 * partitions, and a data file's `_VALUE_STATS` the same over its rows. A Paimon partition is
 * always the column's own value — there are no transforms — so every summary here is an identity
 * field, and the one thing the bridge decides is which Iceberg type each Paimon type reads as,
 * which is what `parseLiteral` needs and the only place a literal can be read wrong.
 *
 * Two rules the pruning side already follows are kept: a type this cannot compare is left out
 * rather than mapped to something plausible, and a value count is the file's row count — Paimon
 * records no per-column value count, but every row holds one value per column, so the two are
 * one figure and `IS NOT NULL` is proved empty when the null count reaches it.
 */

/** A Paimon type string as the Iceberg type its values compare as, or null for one that cannot be compared. */
fun paimonTypeAsIceberg(type: String): IcebergType? {
    val head = Regex("""^([A-Z]+)(?:\((\d+)(?:,\s*(\d+))?\))?""").find(type.trim().uppercase()) ?: return null
    val p1 = head.groupValues[2].toIntOrNull()
    val p2 = head.groupValues[3].toIntOrNull()
    return when (head.groupValues[1]) {
        "BOOLEAN" -> IcebergType.BooleanType
        "TINYINT", "SMALLINT", "INT" -> IcebergType.IntType
        "BIGINT" -> IcebergType.LongType
        "FLOAT" -> IcebergType.FloatType
        "DOUBLE" -> IcebergType.DoubleType
        "DATE" -> IcebergType.DateType
        "TIME" -> IcebergType.TimeType()
        "TIMESTAMP" -> IcebergType.TimestampType(withZone = type.uppercase().contains("WITH LOCAL TIME ZONE"))
        "DECIMAL" -> IcebergType.DecimalType(p1 ?: 10, p2 ?: 0)
        "CHAR", "VARCHAR", "STRING" -> IcebergType.StringType
        else -> null
    }
}

private fun decodedValue(value: Any?, type: IcebergType): DecodedValue? =
    value?.let { DecodedValue(display = it.toString(), value = it, type = type, raw = ByteArray(0)) }

/**
 * A Paimon manifest's partition range as one identity summary per partition key.
 *
 * Empty when the manifest recorded no range or the table is unpartitioned, which prunes nothing
 * and says so — the same answer an Iceberg manifest with no partition summaries gives.
 */
fun paimonPartitionSummaries(node: GraphNode.PaimonManifestNode): List<PartitionSummary> {
    val min = node.partitionMin ?: return emptyList()
    val max = node.partitionMax ?: return emptyList()
    val nullCounts = node.data.partitionStats?.nullCounts
    return min.values.indices.mapNotNull { index ->
        val low = min.values[index]
        val high = max.values.getOrNull(index) ?: return@mapNotNull null
        val type = paimonTypeAsIceberg(low.type) ?: return@mapNotNull null
        PartitionSummary(
            field = PartitionField(sourceId = index, fieldId = index, name = low.name, transform = JsonPrimitive("identity")),
            type = type,
            lower = decodedValue(low.value, type),
            upper = decodedValue(high.value, type),
            containsNull = (nullCounts?.getOrNull(index) ?: 0L) > 0L,
            containsNan = null,
            sourceName = low.name,
            sourceType = type,
        )
    }
}

/**
 * Why a Paimon table's file bounds are not consulted, or null where they are. Under
 * `data-evolution.enabled` a read stitches every file sharing a first row id, the freshest
 * file's column winning, so a file's own bounds describe values another file may have replaced —
 * `de`'s whole file records `b` in 1..2 where the read returns 11 and 22. Paimon 1.3.0 and later
 * prune no file by its statistics on such a table (`DataEvolutionFileStoreScan`, #6443); the
 * snapshot that wrote `de` still did, and a filtered read of it returned the unpatched row. The
 * option is read off the newest schema, since it is fixed at creation.
 */
fun paimonFileBoundsWithheld(graph: GraphModel): String? {
    val newest = graph.nodes.asSequence().filterIsInstance<GraphNode.PaimonSchemaNode>().maxByOrNull { it.data.id ?: -1 } ?: return null
    if (newest.data.options[PAIMON_DATA_EVOLUTION_KEY] != "true") return null
    return "this table is under data evolution: a read stitches files sharing a first row id and a " +
        "patch may replace the values a file's own bounds describe, so no file is ruled out by them"
}

/** A Paimon data file's column bounds as the statistics the file stage evaluates, one per decodable column. */
fun paimonColumnStats(node: GraphNode.PaimonDataFileNode): List<ColumnStats> {
    val bounds = node.columnBounds ?: return emptyList()
    val rowCount = node.entry.file?.rowCount
    return bounds.mapNotNull { bound ->
        if (!bound.decoded) return@mapNotNull null
        val type = paimonTypeAsIceberg(bound.type) ?: return@mapNotNull null
        ColumnStats(
            fieldId = bound.fieldId ?: -1,
            columnName = bound.name,
            type = type,
            lowerBound = decodedValue(bound.min, type),
            upperBound = decodedValue(bound.max, type),
            valueCount = rowCount,
            nullValueCount = bound.nullCount,
            nanValueCount = null,
            columnSizeBytes = null,
        )
    }
}
