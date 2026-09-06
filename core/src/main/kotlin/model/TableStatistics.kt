package model

/**
 * A statistics blob as a row a reader can scan: which column, how many distinct values, from when.
 *
 * The list in `metadata.json` is a list of *files*, each holding a list of blobs, and the question
 * a reader arrives with is about neither — it is "how many distinct values does this column have",
 * which is one row per blob with its field ids resolved to a name. So the two levels are flattened
 * here rather than in a panel, because it is knowledge about the artifacts and not about a screen.
 */
data class StatisticsBlobRow(
    /** The statistics file's own name, so several files stay tellable apart. */
    val fileName: String,
    /** The resolved column name, or the raw field ids when the schema does not name them. */
    val column: String,
    val type: String,
    /** The distinct count the blob records, when it records one. */
    val ndv: Long?,
    val snapshotId: Long?,
    val sequenceNumber: Long?,
    /** Everything the blob declares, `ndv` included — the panel shows what it does not have a column for. */
    val properties: Map<String, String>,
)

/**
 * Every statistics blob in [metadata], flattened and resolved.
 *
 * **A blob's field ids are resolved against the schema its own snapshot used**, never against the
 * table's current one — the same rule that makes a manifest decode against the partition spec it
 * records rather than the table's. A statistics file describes one commit, and a column added,
 * renamed or dropped after that commit would otherwise put a name against a figure that was never
 * measured for it. The current schema is the fallback, then any schema at all, because a name that
 * came from the wrong schema is still better than a bare field id — and the id is what is shown
 * when no schema names it, rather than a guess.
 */
fun statisticsRows(metadata: TableMetadata): List<StatisticsBlobRow> {
    val schemasById = metadata.schemas.mapNotNull { schema -> schema.schemaId?.let { it to schema } }.toMap()
    val schemaIdOfSnapshot = metadata.snapshots
        .mapNotNull { snapshot -> snapshot.snapshotId?.let { id -> snapshot.schemaId?.let { id to it } } }
        .toMap()

    fun namesFor(snapshotId: Long?, fields: List<Int>): String {
        val preferred = snapshotId?.let { schemaIdOfSnapshot[it] }?.let { schemasById[it] }
            ?: metadata.currentSchemaId?.let { schemasById[it] }
            ?: metadata.schemas.firstOrNull()
        val byId = preferred?.fields.orEmpty().mapNotNull { field -> field.id?.let { it to field.name } }.toMap()
        return fields.joinToString(", ") { id -> byId[id] ?: "field $id" }
    }

    return metadata.statistics.flatMap { file ->
        val name = file.statisticsPath?.substringAfterLast('/').orEmpty()
        file.blobMetadata.map { blob ->
            StatisticsBlobRow(
                fileName = name,
                column = namesFor(blob.snapshotId ?: file.snapshotId, blob.fields).ifBlank { "—" },
                type = blob.type.orEmpty().ifBlank { "—" },
                ndv = blob.properties["ndv"]?.toLongOrNull(),
                snapshotId = blob.snapshotId ?: file.snapshotId,
                sequenceNumber = blob.sequenceNumber,
                properties = blob.properties,
            )
        }
    }
}
