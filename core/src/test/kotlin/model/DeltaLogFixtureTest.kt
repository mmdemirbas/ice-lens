package model

import service.DeltaCheckpointNaming
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The Delta log read and replayed, against the tables `docs/fixtures/delta/run.sh` wrote with
 * delta-spark 3.2.1. The oracle is each script's `.out`: the rows Spark read back and its
 * `DESCRIBE HISTORY` — nothing this code produced.
 */
class DeltaLogFixtureTest {

    private fun liveRows(state: DeltaState) = state.files.values.sumOf { it.parsedStats!!.numRecords!! } - state.deletedByVectors

    @Test
    fun `dplain replays to the three files Spark reads back, from the checkpoint and from the commits alike`() {
        val model = FixtureCatalog.deltaModel("dplain")
        assertEquals(emptyList(), model.readErrors)
        assertEquals((0L..5L).toList(), model.versions)
        assertEquals(listOf(3L), model.checkpoints.map { it.version })
        assertEquals(DeltaCheckpointNaming.CLASSIC, model.checkpoints.single().naming)
        assertEquals(3L, model.lastCheckpoint?.version)

        val current = model.current!!
        assertEquals(3L, current.fromCheckpoint, "the replay to 5 starts from the checkpoint at 3")
        assertEquals(listOf(4L, 5L), current.commitsReplayed)
        assertEquals(3, current.files.size)
        assertEquals(3L, liveRows(current), "rows 1 alpha, 3 charlie-updated, 4 delta")
        assertEquals(2, current.tombstones.size, "the DELETE's and the UPDATE's rewritten files")
        assertTrue(current.files.keys.all { Files.isRegularFile(model.resolve(it.path)) })

        // A checkpoint is the replay of the commits up to its version: two readings of one set.
        for (v in model.versions) {
            val fromCommits = model.stateFromCommits(v)!!
            val viaCheckpoint = model.stateAt(v).getOrThrow()
            assertEquals(fromCommits.files.keys, viaCheckpoint.files.keys, "live files at $v")
            assertEquals(fromCommits.tombstones.keys, viaCheckpoint.tombstones.keys, "tombstones at $v")
            assertEquals(fromCommits.metadata?.id, viaCheckpoint.metadata?.id)
            assertEquals(fromCommits.protocol, viaCheckpoint.protocol)
        }
        assertEquals(listOf("CREATE TABLE", "WRITE", "WRITE", "WRITE", "DELETE", "UPDATE"), model.commits.map { it.commitInfo?.operation })
    }

    @Test
    fun `ddv keys a file by its path and its vector, and a replaced vector is a remove and an add`() {
        val model = FixtureCatalog.deltaModel("ddv")
        assertEquals(emptyList(), model.readErrors)
        val current = model.current!!
        assertNull(current.fromCheckpoint)
        assertEquals(3, current.files.size, "the two inserts' files and the UPDATE's new file")
        assertEquals(listOf(2L, 2L), current.files.values.mapNotNull { it.deletionVector?.cardinality }.sorted())
        assertEquals(1000L, liveRows(current), "Spark's count(*)")

        // v4's DELETE on the first file removed (path, old vector) and added (path, new vector).
        val v4 = model.commitByVersion.getValue(4)
        assertEquals(v4.removes.single().path, v4.adds.single().path)
        assertTrue(v4.removes.single().key != v4.adds.single().key)
        assertEquals(setOf("deletionVectors"), current.protocol!!.features.toSet() intersect setOf("deletionVectors"))

        // Every vector sits in a file the Z85 name decodes to.
        for (dv in current.files.values.mapNotNull { it.deletionVector }) {
            val file = assertNotNull(dv.filePath(model.path))
            assertTrue(Files.isRegularFile(file), "$file")
        }
    }

    @Test
    fun `dpart records a null partition as null and its checkpoint as the replay`() {
        val model = FixtureCatalog.deltaModel("dpart")
        assertEquals(emptyList(), model.readErrors)
        val current = model.current!!
        assertEquals(2L, current.fromCheckpoint)
        assertEquals(listOf("region", "dt"), current.metadata!!.partitionColumns)
        assertEquals(5, current.files.size)
        assertEquals(5L, liveRows(current))
        assertEquals(1, current.files.values.count { it.partitionValues["region"] == null })
        assertEquals(model.stateFromCommits(2)!!.files.keys, current.files.keys)
        assertTrue(current.files.keys.all { Files.isRegularFile(model.resolve(it.path)) })
    }

    @Test
    fun `a version whose commit is gone and no checkpoint covers is reported, not replayed`() {
        val model = FixtureCatalog.deltaModel("dplain")
        val trimmed = DeltaUnifiedTableModel(model.path, model.listing, model.commits.filter { it.version != 1L }, model.lastCheckpoint, mutableListOf())
        val failure = trimmed.stateAt(2).exceptionOrNull()
        assertTrue(failure is DeltaVersionUnavailable, "$failure")
        assertTrue(trimmed.stateAt(4).isSuccess, "4 replays from the checkpoint at 3")
    }

    @Test
    fun `z85 decodes the reference vector`() {
        // ZeroMQ RFC 32: "HelloWorld" is the Z85 encoding of 0x86 0x4F 0xD2 0x6F 0xB5 0x59 0xF7 0x5B.
        assertEquals(listOf(0x86, 0x4F, 0xD2, 0x6F, 0xB5, 0x59, 0xF7, 0x5B), Z85.decode("HelloWorld").map { it.toInt() and 0xFF })
    }
}
