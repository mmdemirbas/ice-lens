package model

/**
 * What reading a Paimon snapshot takes, off its replay: every live data file with where it is
 * opened from and the bucket it belongs to, every deletion vector the snapshot's index manifest
 * names, the schema the columns are typed by, and the merge engine that decides which of a
 * key's records is the row. Two readers share it — the row lookup (`service/PaimonRowLookup.kt`,
 * the latest snapshot's, on the table node) and the merged row count
 * (`service/PaimonMergedCount.kt`, any snapshot's, on its node) — and it is the part that needs
 * no file opened, a `DeferredRead` because it replays the snapshot.
 *
 * The question is the same one the Iceberg lookup answers, "is this row live, and if not, what
 * removed it", and the format answers it differently. A primary-key file holds every write as
 * a record — `_KEY_<col>` per trimmed primary key, `_SEQUENCE_NUMBER`, `_VALUE_KIND`, then the
 * value columns — so a record is the row only while no later write for its key exists in the
 * bucket and it is not itself the `-D` a delete writes; and with `deletion-vectors.enabled`
 * a record can be marked without either, by the vector the index file holds for its file.
 */
data class PaimonLookupFile(
    /** `_FILE_NAME` — what a vector range names, and what the panel prints. */
    val fileName: String,
    /** Where it is opened from. */
    val localPath: String,
    /** The decoded partition, `""` for an unpartitioned table — with [bucket], the merge scope. */
    val partition: String,
    val bucket: Int,
    val level: Int?,
    val recordCount: Long?,
    /** A data-evolution patch file (`_WRITE_COLS` set): columns of rows another file holds, no rows of its own. */
    val partial: Boolean = false,
    /** `_FIRST_ROW_ID` — what a data-evolution read stitches files by; null where the file records none. */
    val firstRowId: Long? = null,
    /** `_MAX_SEQUENCE_NUMBER` — the file whose column wins where two files of one split hold it. */
    val maxSequenceNumber: Long? = null,
    /** The schema the file's own `_SCHEMA_ID` names — what its columns are placed by; see [paimonFileColumns]. */
    val fileSchema: PaimonSchema? = null,
    /** `_WRITE_COLS` — the columns the file holds; null for every column of its schema. */
    val writeCols: List<String>? = null,
) {
    /** The file's format by its name — what decides which DuckDB table function reads it. */
    val extension: String get() = fileName.substringAfterLast('.', "").lowercase()
    /** Whether the file holds [column] — every column when `_WRITE_COLS` is not recorded. */
    fun holds(column: String): Boolean = writeCols?.contains(column) ?: true
}

/**
 * One `_DELETIONS_VECTORS_RANGES` entry with the index file it sits in — the coordinates
 * [service.PaimonDeletionVectorReader] reads a vector by, and the two figures the manifest
 * records without opening the file.
 */
data class PaimonVectorRange(
    val dataFileName: String,
    /** The index file holding it, where it is opened from. */
    val indexLocalPath: String,
    val indexFileName: String,
    val offset: Long,
    val length: Long,
    /** `_CARDINALITY`, the manifest's own figure. */
    val cardinality: Long?,
)

data class PaimonReadInput(
    val snapshotId: Long,
    val schema: PaimonSchema,
    /** The primary keys that are not partition keys, in primary-key order — the file's `_KEY_` columns. */
    val trimmedPrimaryKeys: List<String>,
    /** What a read does with a key's records — see [PaimonMergeRule]. */
    val rule: PaimonMergeRule,
    val files: List<PaimonLookupFile>,
    val vectors: List<PaimonVectorRange>,
) : LookupInput {
    val hasPrimaryKey: Boolean get() = schema.primaryKeys.isNotEmpty()
    val mergeEngine: String get() = rule.engine

    /** The live files a batch read of this table reads — every one, or the ones above level 0 where the rule skips it. */
    val readFiles: List<PaimonLookupFile> get() = if (rule.skipsLevel0) files.filter { (it.level ?: 0) > 0 } else files

    /** The live files a batch read of this table leaves unread — level 0 under [PaimonMergeRule.skipsLevel0]. */
    val skippedFiles: List<PaimonLookupFile> get() = if (rule.skipsLevel0) files.filter { (it.level ?: 0) == 0 } else emptyList()

    fun vectorFor(fileName: String): PaimonVectorRange? = vectors.firstOrNull { it.dataFileName == fileName }

    /** The read files sharing a file's partition and bucket — every file another record of the key could be in. */
    fun bucketOf(file: PaimonLookupFile): List<PaimonLookupFile> =
        readFiles.filter { it.partition == file.partition && it.bucket == file.bucket }

    /** `data-evolution.enabled` — a read stitches files by first row id, see [splits]. */
    val dataEvolution: Boolean get() = schema.options[PAIMON_DATA_EVOLUTION_KEY] == "true"

    /** The schema every file of a read is projected onto, system columns included — see [paimonSchemaAsIceberg]. */
    val readSchema: IcebergSchemaModel by lazy { paimonSchemaAsIceberg(schema, systemColumns = true) }

    /**
     * What a read opens as one unit, in the order it opens them: under data evolution, the read
     * files sharing a partition, bucket and first row id, freshest first by `_MAX_SEQUENCE_NUMBER`
     * — the rule `DataEvolutionSplitGenerator.split` groups by, with each column taken from the
     * first file of the split holding it — and every other file alone. A file recording no first
     * row id is alone whatever the table's options, which is what a compaction's output is.
     */
    val splits: List<List<PaimonLookupFile>> get() = splitsOf(readFiles)

    /** [splits] over any of the table's files — the lookup's, which reads the skipped level-0 files too. */
    fun splitsOf(files: List<PaimonLookupFile>): List<List<PaimonLookupFile>> {
        if (!dataEvolution) return files.map { listOf(it) }
        val (keyed, alone) = files.partition { it.firstRowId != null }
        val grouped = keyed.groupBy { Triple(it.partition, it.bucket, it.firstRowId) }.values
            .map { split -> split.sortedWith(compareByDescending<PaimonLookupFile> { it.maxSequenceNumber ?: Long.MIN_VALUE }.thenBy { it.fileName }) }
        return grouped + alone.map { listOf(it) }
    }
}

const val DEFAULT_PAIMON_MERGE_ENGINE = "deduplicate"
const val PAIMON_DATA_EVOLUTION_KEY = "data-evolution.enabled"

/** The read input for the latest snapshot on `main`, or null when the table has none. */
fun PaimonUnifiedTableModel.paimonRowLookupInput(): PaimonReadInput? =
    snapshots.lastOrNull()?.let { paimonReadInputOf(it, replayPaimonSnapshot(it)) }

/** The read input for one snapshot, from a replay already run — null where the snapshot names no id or schema. */
fun PaimonUnifiedTableModel.paimonReadInputOf(snapshot: PaimonUnifiedSnapshot, replay: PaimonReplay): PaimonReadInput? {
    val id = snapshot.metadata.id ?: return null
    val schema = snapshot.schema ?: return null
    val files = replay.liveEntries.values.mapNotNull { entry ->
        val meta = entry.metadata.file ?: return@mapNotNull null
        PaimonLookupFile(
            fileName = meta.fileName ?: return@mapNotNull null,
            localPath = entry.path.toString(),
            partition = entry.partition?.display.orEmpty(),
            bucket = entry.metadata.bucket ?: return@mapNotNull null,
            level = meta.level,
            recordCount = meta.rowCount,
            partial = entry.partial,
            firstRowId = meta.firstRowId,
            maxSequenceNumber = meta.maxSequenceNumber,
            fileSchema = entry.schema,
            writeCols = meta.writeCols,
        )
    }
    return PaimonReadInput(
        snapshotId = id,
        schema = schema,
        trimmedPrimaryKeys = schema.primaryKeys.filterNot { it in schema.partitionKeys },
        rule = paimonMergeRuleOf(schema.options, schema.primaryKeys.isNotEmpty()),
        files = files,
        vectors = vectorRangesOf(path, listOf(snapshot)).values.toList(),
    )
}

/**
 * The vector range each data file has as of the last of [snapshots] whose index manifest names
 * it, by file name — a later snapshot's range replaces an earlier one's, since a second delete
 * on the same file writes a new vector for it. Index files live under the table's `index/`
 * whichever branch committed them, the same rule as manifests.
 */
fun vectorRangesOf(tableRoot: java.nio.file.Path, snapshots: List<PaimonUnifiedSnapshot>): Map<String, PaimonVectorRange> {
    val indexDir = tableRoot.resolve("index")
    val ranges = LinkedHashMap<String, PaimonVectorRange>()
    snapshots.forEach { snapshot ->
        snapshot.indexFiles.filter { it.isDeletionVectorIndex }.forEach { index ->
            val indexName = index.fileName ?: return@forEach
            index.deletionVectorRanges.orEmpty().filterNotNull().forEach { range ->
                val dataFile = range.dataFileName ?: return@forEach
                val offset = range.offset?.toLong() ?: return@forEach
                val length = range.length?.toLong() ?: return@forEach
                ranges[dataFile] = PaimonVectorRange(dataFile, indexDir.resolve(indexName).toString(), indexName, offset, length, range.cardinality)
            }
        }
    }
    return ranges
}
