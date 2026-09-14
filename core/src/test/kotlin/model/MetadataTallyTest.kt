package model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Every metadata file's figures against its contents on every engine-written table, then each
 * figure moved by hand — the ids the next DDL allocates from, which no reader checks, and the
 * ones a reader refuses the table on.
 */
class MetadataTallyTest {

    @Test
    fun `every checked-in Iceberg metadata version's figures agree with its contents`() {
        var checked = 0
        val nothing = mutableMapOf<String, Int>()
        for (fixture in FixtureCatalog.iceberg) {
            for (um in FixtureCatalog.icebergModel(fixture).metadatas) {
                for (t in metadataTallies(um.metadata)) {
                    assertTrue(t.agrees != false, "$fixture/${um.path.fileName}: ${t.label} recorded ${t.recorded}, folded ${t.counted}")
                    if (t.agrees == true) checked++ else nothing.merge(t.label, 1, Int::plus)
                }
            }
        }
        assertTrue(checked >= 1_000, "$checked figures agreed")
        // What has nothing to check: a v1 version's last-partition-id, a main ref a v1 table does not keep,
        // and the figures a first version with no snapshot or log entry has no folded side for.
        assertTrue(nothing.keys.all { it in setOf("refs.main", "last-sequence-number", "last-updated-ms", "current-snapshot-id", "next-row-id") }, "$nothing")
    }

    @Test
    fun `every checked-in Paimon schema and snapshot agree with their files`() {
        var checked = 0
        var lengths = 0
        var indexLengths = 0
        for (fixture in FixtureCatalog.paimon) {
            val model = FixtureCatalog.paimonModel(fixture)
            val lines = listOf(Triple(model.schemas, model.snapshots + model.tagOnlySnapshots, model.tagOnlySnapshots)) +
                model.branches.map { Triple(it.schemas, it.snapshots + it.tagOnlySnapshots, it.tagOnlySnapshots) }
            for ((schemas, snapshots, tagOnly) in lines) {
                for (schema in schemas) for (t in paimonSchemaTallies(schema)) {
                    assertEquals(true, t.agrees, "$fixture/schema-${schema.id}: ${t.recorded} recorded, ${t.counted} folded")
                    checked++
                }
                val ids = schemas.mapNotNull { it.id }.toSet()
                for (s in snapshots) for (t in paimonSnapshotTallies(s.metadata, ids, s.sizesOnDisk) + paimonIndexFileTallies(s.indexFiles, s.sizesOnDisk)) {
                    // The one figure with nothing to compare is a tag-only snapshot's changelog list, which the expiry deleted (`tg`, `pea`).
                    if (t.agrees == null) {
                        assertTrue(t.label == "changelogManifestListSize" && s in tagOnly, "$fixture/snapshot-${s.metadata.id}: ${t.label} ${t.counted}")
                        continue
                    }
                    assertEquals(true, t.agrees, "$fixture/snapshot-${s.metadata.id}: ${t.label} ${t.recorded} recorded, ${t.counted}")
                    checked++
                    if (t.label.endsWith("ManifestListSize")) lengths++
                    if (t.label.endsWith("_FILE_SIZE")) indexLengths++
                }
            }
        }
        assertTrue(checked >= 100, "$checked figures agreed")
        assertTrue(lengths >= 100, "$lengths manifest list lengths agreed")
        assertTrue(indexLengths >= 4, "$indexLengths index file lengths agreed — dv, ad and test each list at least one")
    }

    @Test
    fun `a manifest list length or an index file length off by one is named, and a deleted list has nothing to compare`() {
        val dv = FixtureCatalog.paimonModel("dv")
        val withIndex = dv.snapshots.last { it.indexFiles.isNotEmpty() }
        assertEquals(true, paimonSnapshotTallies(withIndex.metadata, setOf(0), withIndex.sizesOnDisk).single { it.label == "baseManifestListSize" }.agrees)
        val indexes = paimonIndexFileTallies(withIndex.indexFiles, withIndex.sizesOnDisk)
        assertTrue(indexes.isNotEmpty() && indexes.all { it.agrees == true }, indexes.toString())

        val longer = withIndex.metadata.copy(baseManifestListSize = withIndex.metadata.baseManifestListSize!! + 1)
        assertEquals(false, paimonSnapshotTallies(longer, setOf(0), withIndex.sizesOnDisk).single { it.label == "baseManifestListSize" }.agrees)
        val shorter = withIndex.indexFiles.map { it.copy(fileSize = it.fileSize!! - 1) }
        assertTrue(paimonIndexFileTallies(shorter, withIndex.sizesOnDisk).all { it.agrees == false })

        // `tg`'s tag keeps a snapshot whose changelog list the expiry deleted: recorded, not there, nothing to compare.
        val tagged = FixtureCatalog.paimonModel("tg").tagOnlySnapshots.single()
        val changelog = paimonSnapshotTallies(tagged.metadata, setOf(0), tagged.sizesOnDisk).single { it.label == "changelogManifestListSize" }
        assertEquals(null, changelog.agrees)
        assertEquals("file not there", changelog.counted)
        assertTrue(tagged.metadata.changelogManifestListSize!! > 0)
    }

    @Test
    fun `a last-column-id below the highest field id is the one disagreement no reader would report`() {
        val newest = FixtureCatalog.icebergModel("deep").metadatas.last().metadata
        val highest = newest.schemas.flatMap { tableSchemaModel(it).fieldsById.keys }.max()
        assertTrue(highest >= 10, "deep's nested leaves reach id $highest")
        val short = metadataTallies(newest.copy(lastColumnId = highest - 1)).single { it.label == "last-column-id" }
        assertEquals(false, short.agrees)
        assertEquals("${highest - 1}" to "$highest", short.recorded to short.counted)
        assertTrue(short.consequence.contains("nothing checks it"), short.consequence)
        // Equal is enough: the next id is last + 1.
        assertEquals(true, metadataTallies(newest.copy(lastColumnId = highest)).single { it.label == "last-column-id" }.agrees)

        val partitioned = FixtureCatalog.icebergModel("respec").metadatas.last().metadata
        val highestPartition = partitioned.partitionSpecs.flatMap { it.fields.mapNotNull { f -> f.fieldId } }.max()
        assertEquals(false, metadataTallies(partitioned.copy(lastPartitionId = highestPartition - 1)).single { it.label == "last-partition-id" }.agrees)
    }

    @Test
    fun `the figures a reader refuses the table on are each named`() {
        val newest = FixtureCatalog.icebergModel("branched").metadatas.last().metadata
        fun tally(m: TableMetadata, label: String) = metadataTallies(m).single { it.label == label }
        val topSequence = newest.snapshots.mapNotNull { it.sequenceNumber }.max()
        assertEquals(false, tally(newest.copy(lastSequenceNumber = topSequence - 1), "last-sequence-number").agrees)
        val newestLog = newest.snapshotLog.mapNotNull { it.timestampMs }.max()
        assertEquals(true, tally(newest.copy(lastUpdatedMs = newestLog - 59_000), "last-updated-ms").agrees, "a minute of clock skew is allowed")
        assertEquals(false, tally(newest.copy(lastUpdatedMs = newestLog - 61_000), "last-updated-ms").agrees)
        assertEquals(false, tally(newest.copy(currentSnapshotId = 42L), "current-snapshot-id").agrees)
        assertEquals(false, tally(newest.copy(currentSnapshotId = newest.snapshots.first { it.snapshotId != newest.refs["main"]?.snapshotId }.snapshotId), "refs.main").agrees)
        assertEquals(false, tally(newest.copy(refs = newest.refs + ("stale" to SnapshotRef(snapshotId = 42L, type = "tag"))), "refs.stale").agrees)
        assertEquals(false, tally(newest.copy(currentSchemaId = 99), "current-schema-id").agrees)
        val v1 = FixtureCatalog.icebergModel("v1").metadatas.first().metadata
        assertNull(tally(v1, "refs.main").agrees, "a v1 table keeps no refs, and that is allowed")
        assertNull(tally(v1.copy(lastPartitionId = null), "last-partition-id").agrees, "v1 may leave last-partition-id to the reader")
        // An unpartitioned v2 table records 999, which is what an empty spec folds to.
        val unpartitioned = FixtureCatalog.icebergModel("test").metadatas.last().metadata
        assertEquals("999" to "999", tally(unpartitioned, "last-partition-id").let { it.recorded to it.counted })
        // A child at its parent's sequence number: the shape that applies deletes to the wrong files.
        val child = newest.snapshots.first { it.parentSnapshotId != null && newest.snapshots.any { p -> p.snapshotId == it.parentSnapshotId } }
        val parent = newest.snapshots.first { it.snapshotId == child.parentSnapshotId }
        val flat = newest.copy(snapshots = newest.snapshots.map { if (it.snapshotId == child.snapshotId) it.copy(sequenceNumber = parent.sequenceNumber) else it })
        assertEquals(false, tally(flat, "snapshot sequence order").agrees)
        assertEquals(true, tally(newest, "snapshot sequence order").agrees)
        // Two entries two minutes apart, written the wrong way round; a fixture's own entries are seconds apart, inside the tolerance either way.
        val first = newest.snapshotLog.first()
        val swapped = newest.copy(snapshotLog = listOf(first.copy(timestampMs = first.timestampMs!! + 120_000), first))
        assertEquals(false, tally(swapped, "snapshot-log order").agrees)
        assertEquals(true, tally(newest.copy(snapshotLog = newest.snapshotLog.reversed()), "snapshot-log order").agrees, "seconds apart is inside the minute Iceberg allows")
    }

    @Test
    fun `a Paimon highestFieldId below a nested field's id, and a snapshot naming no schema file, are named`() {
        val nested = FixtureCatalog.paimonModel("pne").schemas.last()
        val highest = nested.highestFieldId!!
        assertEquals(false, paimonSchemaTallies(nested.copy(highestFieldId = highest - 1)).single().agrees)
        val snapshot = FixtureCatalog.paimonModel("pne").snapshots.last().metadata
        assertEquals(false, paimonSnapshotTallies(snapshot, setOf(0)).single { it.label == "schemaId" }.agrees)
    }
}
