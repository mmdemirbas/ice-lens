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
    /**
     * The same distinct count read out of the Puffin file itself, when it was opened.
     *
     * Null means the file was not read, which is not the same as a disagreement — see [agrees].
     * `metadata.json` carries a *copy* of the blob metadata precisely so a planner never has to
     * open the file, which is also what lets the two drift: a statistics file deleted by an
     * orphan-file cleanup leaves the record behind, and nothing on the read path notices.
     */
    val fileNdv: Long?,
    /** How many bytes the blob occupies, after compression. Only the file knows this. */
    val compressedLength: Long?,
    /** The blob's compression codec. Only the file knows this. */
    val codec: String?,
    /** Everything the blob declares, `ndv` included — the panel shows what it does not have a column for. */
    val properties: Map<String, String>,
    /**
     * Whether the Puffin file was opened at all.
     *
     * Carried rather than inferred from the other three being null, because "the file was not
     * read" and "the file was read and holds no such blob" are different answers that would
     * otherwise look identical — and the second is the one worth seeing.
     */
    val fileRead: Boolean = false,
) {
    /**
     * Whether the file agreed with the record. Null when the file was not read.
     *
     * A blob the record names and the file does not hold counts as a disagreement rather than as
     * an absence, because that is what it is: the table is telling a planner about statistics that
     * are not there.
     */
    val agrees: Boolean? = if (!fileRead) null else fileNdv == ndv
}

/**
 * The Puffin footer of one statistics file, and where it was found — or why it was not.
 *
 * The recorded `statistics-path` is resolved by the same recorded-first rule a manifest list is
 * (`resolveRecordedOrRelative`), which is what opens the file for a table copied down from object
 * storage or written by a container at some `/wh` prefix. [resolution] says which rule ran, for
 * the same reason `UnifiedManifest.pathResolution` does: a reader told a file is missing needs to
 * know which path was actually looked at.
 */
data class StatisticsFileFooter(
    val localPath: String,
    val resolution: PathResolution,
    val footer: PuffinFileMetadata?,
    val problem: String?,
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
fun statisticsRows(
    metadata: TableMetadata,
    footerOf: (StatisticsFile) -> PuffinFileMetadata? = { null },
): List<StatisticsBlobRow> {
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
        // Matched on the field ids, which is the only thing that identifies a blob across the two
        // records — neither carries an id of its own, and their order is not guaranteed to agree.
        val footer = footerOf(file)
        val footerRead = footer != null
        val inFile = footer?.blobs.orEmpty().associateBy { it.fields }
        file.blobMetadata.map { blob ->
            val counterpart = inFile[blob.fields]
            StatisticsBlobRow(
                fileName = name,
                column = namesFor(blob.snapshotId ?: file.snapshotId, blob.fields).ifBlank { "—" },
                type = blob.type.orEmpty().ifBlank { "—" },
                ndv = blob.properties["ndv"]?.toLongOrNull(),
                snapshotId = blob.snapshotId ?: file.snapshotId,
                sequenceNumber = blob.sequenceNumber,
                fileNdv = counterpart?.properties?.get("ndv")?.toLongOrNull(),
                compressedLength = counterpart?.length,
                codec = counterpart?.compressionCodec,
                properties = blob.properties,
                fileRead = footerRead,
            )
        }
    }
}
