package service

import model.PathResolution
import model.PredicateOp
import model.RowFate
import model.ScanFilter
import model.ScanPredicate
import model.UnifiedTableModel
import model.deleteReach
import model.liveFilesOf
import model.rowLookupInputOf
import java.io.File
import java.nio.file.Files
import java.nio.file.Paths
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * `example/iceberg/default/hdfsw`: an Iceberg table Spark wrote straight into HDFS
 * (`docs/fixtures/hdfsw.sql`, against `docs/fixtures/hdfs-lab.sh`), so every path its metadata
 * records is `hdfs://icelens-hdfs:8020/...` — the shape of a table on a real cluster, where every
 * other fixture records a container's `/wh`. Nothing on this classpath opens `hdfs://`, so no
 * recorded path is used as written: each resolves by the rule that discards the recorded
 * directory, which is what opens the copy here and the same table over WebHDFS
 * ([WebHdfsTableTest]). The expected rows are Spark's read, printed by the script: 1, 3, 4 and 5,
 * with 2 deleted by a positional delete that names its data file by the `hdfs://` path.
 */
class HdfsWrittenFixtureTest {

    private val repoRoot: File = generateSequence(File(".").absoluteFile) { it.parentFile }
        .first { File(it, "settings.gradle.kts").isFile }

    private val tableDir = Paths.get(File(repoRoot, "example/iceberg/default/hdfsw").absolutePath)
    private val model = UnifiedTableModel(tableDir)
    private val recordedRoot = "hdfs://icelens-hdfs:8020/warehouse/spark/default/hdfsw"

    @Test
    fun `every path the table records is on HDFS, and every one resolves to the copy`() {
        assertTrue(model.readErrors.isEmpty(), "${model.readErrors}")
        val current = model.metadatas.last()
        assertEquals(recordedRoot, current.metadata.location)
        val snapshots = current.snapshots
        assertEquals(3, snapshots.size)
        assertTrue(snapshots.all { it.metadata.manifestList!!.startsWith("$recordedRoot/metadata/") })
        val manifests = snapshots.flatMap { it.manifests }
        assertTrue(manifests.all { it.metadata.manifestPath!!.startsWith("$recordedRoot/metadata/") })
        val files = manifests.flatMap { it.dataFiles }
        assertEquals(5, files.map { it.metadata.dataFile?.filePath }.distinct().size)
        files.forEach { file ->
            assertTrue(file.metadata.dataFile!!.filePath!!.startsWith("$recordedRoot/data/region="), file.metadata.dataFile!!.filePath)
            assertTrue(file.pathResolution != PathResolution.RECORDED, "${file.path}")
            assertTrue(file.path.startsWith(tableDir.resolve("data")) && Files.isRegularFile(file.path), "${file.path}")
        }
    }

    @Test
    fun `the positional delete names its file by the hdfs path, and the read is Spark's`() {
        val current = model.metadatas.last()
        val snapshot = current.snapshots.single { it.metadata.snapshotId == current.metadata.currentSnapshotId }
        val input = assertNotNull(model.rowLookupInputOf(snapshot, liveFilesOf(snapshot), deleteReach(snapshot)))
        val hits = RowLookup.lookup(input, ScanFilter.Term(ScanPredicate("id", PredicateOp.GTE, "1")), emptySet()).hits
        assertEquals(
            mapOf(1 to RowFate.LIVE, 2 to RowFate.POSITION_DELETED, 3 to RowFate.LIVE, 4 to RowFate.LIVE, 5 to RowFate.LIVE),
            hits.associate { (it.cells["id"] as Number).toInt() to it.fate },
        )
        assertEquals(4L, LiveRowCount.count(input).live)
    }
}
