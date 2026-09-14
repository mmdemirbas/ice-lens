package model

/**
 * A metadata file's own figures against the same figures folded from its contents — the
 * `manifestTallies` rule at the top of the table, and the one place a disagreement is not a
 * wrong panel but a table the engine refuses, or one whose next DDL hands out an id twice.
 *
 * Iceberg 1.8.1 checks some of these as it opens `metadata.json` and none of the rest.
 * `TableMetadata`'s constructor refuses a snapshot whose `sequence-number` is above
 * `last-sequence-number`, a `current-snapshot-id` no snapshot has, a `main` ref pointing
 * elsewhere than the current snapshot, a ref naming a snapshot the list lacks, and a
 * `last-updated-ms` more than a minute before the last log entry; the parser refuses a
 * `current-schema-id` no schema has. **`last-column-id` and `last-partition-id` are never
 * checked**, and they are the ones that corrupt: `SchemaUpdate` hands the next added column
 * `last-column-id + 1` and `TableMetadata.Builder` the next partition field
 * `last-partition-id + 1`, so a figure below the highest id in use gives a new column or field
 * an id an old one already has — silently, until a file written under the old id is read
 * under the new column. Paimon's `highestFieldId` is the same figure (`SchemaManager` assigns
 * from `highestFieldId + 1`, release-1.3.1) with the same consequence.
 */
data class MetadataTally(
    val label: String,
    /** The figure as recorded — `N/A` where the writer omitted it (v1 leaves `last-partition-id` to the reader). */
    val recorded: String,
    /** The same figure folded from the file's contents. */
    val counted: String,
    /** Null when either side has nothing to say. */
    val agrees: Boolean?,
    /** What the figure decides — what a reader or the next commit does with it. */
    val consequence: String,
)

/** A minute: what `TableMetadata` allows a timestamp to sit before the entry it should follow, for clock skew between committers. */
private const val CLOCK_SKEW_MS = 60_000L

/** `PartitionSpec.PARTITION_DATA_ID_START - 1`: what an unpartitioned spec reports as its last assigned field id. */
private const val UNPARTITIONED_LAST_FIELD_ID = 999

fun metadataTallies(metadata: TableMetadata): List<MetadataTally> {
    val tallies = mutableListOf<MetadataTally>()
    fun tally(label: String, recorded: Any?, counted: Any?, agrees: Boolean?, consequence: String) {
        tallies += MetadataTally(label, recorded?.toString() ?: "N/A", counted?.toString() ?: "N/A", agrees, consequence)
    }
    fun atLeast(recorded: Long?, counted: Long?): Boolean? = if (recorded == null || counted == null) null else recorded >= counted

    // Ids the next DDL allocates from: never checked by a reader, and the figure that corrupts when short.
    val highestField = metadata.schemas.flatMap { tableSchemaModel(it).fieldsById.keys }.maxOrNull()
    tally(
        "last-column-id", metadata.lastColumnId, highestField, atLeast(metadata.lastColumnId?.toLong(), highestField?.toLong()),
        "the next ADD COLUMN takes last-column-id + 1, so a figure below the highest field id gives a new column an id already in use — nothing checks it",
    )
    // An unpartitioned spec's last assigned field id is 999 (`PartitionSpec.lastAssignedFieldId`, the first field taking 1000).
    val highestPartitionField = metadata.partitionSpecs.takeIf { it.isNotEmpty() }
        ?.let { specs -> maxOf(UNPARTITIONED_LAST_FIELD_ID, specs.flatMap { spec -> spec.fields.mapNotNull { it.fieldId } }.maxOrNull() ?: UNPARTITIONED_LAST_FIELD_ID) }
    tally(
        "last-partition-id", metadata.lastPartitionId, highestPartitionField, atLeast(metadata.lastPartitionId?.toLong(), highestPartitionField?.toLong()),
        "the next ADD PARTITION FIELD takes last-partition-id + 1, so a figure below the highest partition field id reuses one — nothing checks it; v1 may omit it",
    )

    // What a reader refuses the table on.
    val highestSequence = metadata.snapshots.mapNotNull { it.sequenceNumber }.maxOrNull()
    tally(
        "last-sequence-number", metadata.lastSequenceNumber, highestSequence, atLeast(metadata.lastSequenceNumber, highestSequence),
        "Iceberg refuses the table when a snapshot's sequence number is above it; the next commit takes last-sequence-number + 1",
    )
    val newestLog = (metadata.snapshotLog.mapNotNull { it.timestampMs } + metadata.metadataLog.mapNotNull { it.timestampMs }).maxOrNull()
    tally(
        "last-updated-ms", metadata.lastUpdatedMs, newestLog,
        if (metadata.lastUpdatedMs == null || newestLog == null) null else metadata.lastUpdatedMs >= newestLog - CLOCK_SKEW_MS,
        "Iceberg refuses the table when it is more than a minute before the last snapshot-log or metadata-log entry; the newest entry is the folded side",
    )
    val snapshotIds = metadata.snapshots.mapNotNull { it.snapshotId }.toSet()
    val current = metadata.currentSnapshotId?.takeIf { it >= 0 }
    tally(
        "current-snapshot-id", current ?: "none", if (current == null) "none" else if (current in snapshotIds) "listed in snapshots" else "not in snapshots",
        current?.let { it in snapshotIds },
        "Iceberg refuses the table when no snapshot has this id (\"Cannot find current version\")",
    )
    val main = metadata.refs["main"]?.snapshotId
    tally(
        "refs.main", main ?: "no main ref", current ?: "no current snapshot",
        when {
            main == null -> null   // a v1 table records no refs, and a current snapshot without a main ref is allowed
            current == null -> false
            else -> main == current
        },
        "Iceberg refuses the table when the main ref and current-snapshot-id name different snapshots, or main exists with no current snapshot",
    )
    metadata.refs.forEach { (name, ref) ->
        if (name == "main") return@forEach
        val id = ref.snapshotId
        tally("refs.$name", id, if (id != null && id in snapshotIds) "listed in snapshots" else "not in snapshots", id?.let { it in snapshotIds }, "Iceberg refuses the table when a ref names a snapshot the list lacks")
    }
    val schemaIds = metadata.schemas.mapNotNull { it.schemaId }.toSet()
    tally(
        "current-schema-id", metadata.currentSchemaId, if (metadata.currentSchemaId in schemaIds) "listed in schemas" else "not in schemas",
        metadata.currentSchemaId?.let { it in schemaIds },
        "Iceberg refuses the table when no schema has this id (\"Cannot find schema with current-schema-id\")",
    )
    val specIds = metadata.partitionSpecs.mapNotNull { it.specId }.toSet()
    tally(
        "default-spec-id", metadata.defaultSpecId, if (metadata.defaultSpecId in specIds) "listed in partition-specs" else "not in partition-specs",
        metadata.defaultSpecId?.let { it in specIds },
        "the spec every write is planned under; a missing one fails the first write rather than the open",
    )
    val orderIds = metadata.sortOrders.mapNotNull { it.orderId }.toSet()
    tally(
        "default-sort-order-id", metadata.defaultSortOrderId, if (metadata.defaultSortOrderId in orderIds) "listed in sort-orders" else "not in sort-orders",
        metadata.defaultSortOrderId?.let { it in orderIds },
        "the order a write sorts under; a missing one fails the first write rather than the open",
    )
    // v3 row lineage: the next id handed out, against the furthest any commit reached.
    val furthestRow = metadata.snapshots.mapNotNull { s -> s.firstRowId?.let { f -> f + (s.addedRows ?: 0L) } }.maxOrNull()
    if (metadata.nextRowId != null || furthestRow != null) {
        tally(
            "next-row-id", metadata.nextRowId, furthestRow, atLeast(metadata.nextRowId, furthestRow),
            "the next commit allocates row ids from here, so a figure below a commit's first-row-id + added-rows hands ids out twice — nothing checks it",
        )
    }
    // Every commit takes last-sequence-number + 1, so a child's number is above its parent's on
    // every line; a delete applies to data files at or below its own number, which is what breaks
    // when a child sits at or below its parent. v1 tables keep every snapshot at 0.
    val bySnapshot = metadata.snapshots.associateBy { it.snapshotId }
    val parented = metadata.snapshots.filter { s -> s.sequenceNumber != null && bySnapshot[s.parentSnapshotId]?.sequenceNumber != null }
    if (parented.isNotEmpty() && (metadata.formatVersion ?: 1) >= 2) {
        val below = parented.filter { s -> s.sequenceNumber!! <= bySnapshot.getValue(s.parentSnapshotId).sequenceNumber!! }
        tally(
            "snapshot sequence order", "${parented.size} commits with a retained parent",
            if (below.isEmpty()) "every child above its parent" else "snapshot ${below.first().snapshotId} at ${below.first().sequenceNumber} under parent's ${bySnapshot.getValue(below.first().parentSnapshotId).sequenceNumber}",
            below.isEmpty(),
            "a delete file applies to data files at or below its own sequence number, and every commit takes last-sequence-number + 1 — a child at or below its parent applies deletes to the wrong files; nothing checks it",
        )
    }
    // The logs are read in order, and a reader refuses one that runs backwards by more than a minute.
    fun outOfOrder(times: List<Long>): Int? = times.zipWithNext().indexOfFirst { (a, b) -> b - a < -CLOCK_SKEW_MS }.takeIf { it >= 0 }
    val snapshotLogTimes = metadata.snapshotLog.mapNotNull { it.timestampMs }
    if (snapshotLogTimes.size > 1) {
        val bad = outOfOrder(snapshotLogTimes)
        tally("snapshot-log order", "${snapshotLogTimes.size} entries", if (bad == null) "in time order" else "entry ${bad + 2} is earlier than entry ${bad + 1}", bad == null, "Iceberg refuses the table when an entry is more than a minute before the one preceding it")
    }
    return tallies
}

/** A Paimon schema's `highestFieldId` against the highest id its fields carry, nested row fields included — what `SchemaManager` assigns the next column from. */
fun paimonSchemaTallies(schema: PaimonSchema): List<MetadataTally> {
    fun ids(fields: List<PaimonField>): List<Int> = fields.flatMap { f -> listOfNotNull(f.id) + nestedIds(f.dataType) }
    val highest = ids(schema.fields).maxOrNull()
    val recorded = schema.highestFieldId
    return listOf(
        MetadataTally(
            "highestFieldId", recorded?.toString() ?: "N/A", highest?.toString() ?: "N/A",
            if (recorded == null || highest == null) null else recorded >= highest,
            "the next ADD COLUMN takes highestFieldId + 1 (SchemaManager, release-1.3.1), so a figure below the highest field id gives a new column an id already in use — nothing checks it",
        ),
    )
}

private fun nestedIds(type: PaimonType?): List<Int> = when (type) {
    is PaimonType.Row -> type.fields.flatMap { f -> listOfNotNull(f.id) + nestedIds(f.dataType) }
    is PaimonType.Array -> nestedIds(type.element)
    is PaimonType.Multiset -> nestedIds(type.element)
    is PaimonType.Map -> nestedIds(type.key) + nestedIds(type.value)
    else -> emptyList()
}

/**
 * A Paimon snapshot's `schemaId` against the schema files present — the schema every file of the
 * commit is read under — and the lengths it records for its manifest lists against the files:
 * `ManifestList.read` opens a list at `baseManifestListSize` and siblings (release-1.3.1,
 * `ObjectsFile.read(fileName, fileSize)`), the `_FILE_SIZE` rule one level up. A list the
 * snapshot names but [sizes] does not hold — deleted, as a tag-only snapshot's changelog list
 * is — has nothing to compare and is not counted.
 */
fun paimonSnapshotTallies(
    snapshot: PaimonSnapshot,
    schemaIds: Set<Int>,
    sizes: PaimonSnapshotSizes = PaimonSnapshotSizes(),
): List<MetadataTally> {
    val id = snapshot.schemaId
    val tallies = mutableListOf(
        MetadataTally(
            "schemaId", id?.toString() ?: "N/A", if (id != null && id.toInt() in schemaIds) "schema file present" else "no such schema file",
            id?.let { it.toInt() in schemaIds },
            "the schema the commit's files are read under; Paimon fails the read of the snapshot without it",
        ),
    )
    val listRule = "the length the list is opened and read to (ManifestList.read); a figure short of the file leaves manifests unread, one past it fails the read"
    fun list(label: String, named: String?, recorded: Long?, onDisk: Long?) {
        if (!named.isNullOrBlank()) tallies += lengthTally(label, recorded, onDisk, listRule)
    }
    list("baseManifestListSize", snapshot.baseManifestList, snapshot.baseManifestListSize, sizes.baseManifestList)
    list("deltaManifestListSize", snapshot.deltaManifestList, snapshot.deltaManifestListSize, sizes.deltaManifestList)
    list("changelogManifestListSize", snapshot.changelogManifestList, snapshot.changelogManifestListSize, sizes.changelogManifestList)
    return tallies
}

/**
 * Each index file's `_FILE_SIZE` in the snapshot's index manifest against the file. Kept apart
 * from [paimonSnapshotTallies] because the snapshot panel draws these on the index file's own
 * row, where a figure about a file belongs; the integrity check and the IDE strip fold both.
 */
fun paimonIndexFileTallies(indexFiles: List<PaimonIndexManifestEntry>, sizes: PaimonSnapshotSizes): List<MetadataTally> =
    indexFiles.mapNotNull { f ->
        val name = f.fileName?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
        lengthTally(
            "index/$name _FILE_SIZE", f.fileSize, sizes.indexFiles[name],
            "what the expiry plan and the metrics charge the index file at; the vector reader opens it by offset and length, so a wrong figure misreports and does not misread",
        )
    }

/** A recorded length against the file's, or nothing to compare when the file is not there. */
private fun lengthTally(label: String, recorded: Long?, onDisk: Long?, consequence: String) = MetadataTally(
    label, recorded?.toString() ?: "N/A", onDisk?.let { "$it in the file" } ?: "file not there",
    if (recorded == null || onDisk == null) null else recorded == onDisk, consequence,
)
