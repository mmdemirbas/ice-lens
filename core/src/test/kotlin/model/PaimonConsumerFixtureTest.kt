package model

import service.PaimonGraphBuilder
import java.io.File
import java.nio.file.Files
import java.nio.file.Paths
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `example/paimon/db.db/cs`: a consumer standing on an old snapshot, and an expiry it held back,
 * written by Spark from `docs/fixtures/paimon-cs.sql`.
 *
 * A consumer is a streaming reader's bookmark — `consumer/consumer-<id>` holding the next
 * snapshot the reader will consume — and `expire_snapshots` will not expire that snapshot or any
 * after it. The script asked for `retain_max = 1` on a three-commit table and got two snapshots
 * back, which is the whole finding: a reader of `snapshot/` alone sees retention disobeyed for no
 * reason it can name, and the reason is one JSON file in a directory this tool used to skip.
 */
class PaimonConsumerFixtureTest {

    private val repoRoot: File = generateSequence(File(".").absoluteFile) { it.parentFile }
        .first { File(it, "settings.gradle.kts").isFile }

    private val tableDir = File(repoRoot, "example/paimon/db.db/cs")
    private val model = PaimonUnifiedTableModel(Paths.get(tableDir.absolutePath))

    @Test
    fun `the consumer is read, and its next snapshot is the one expiry stopped at`() {
        val consumer = model.consumers.single()
        assertEquals("reader", consumer.name)
        assertEquals(2L, consumer.metadata.nextSnapshot)
        assertEquals(tableDir.toPath().resolve("consumer").resolve("consumer-reader"), consumer.path)
        assertEquals(listOf(2L, 3L), model.snapshots.map { it.metadata.id }, "retain_max = 1 would have left 3 alone; the consumer kept 2")
        assertTrue(model.readErrors.isEmpty(), "${model.readErrors}")
    }

    /**
     * Snapshot 1's data file is still on disk — snapshot 2's base manifest lists it, and a kept
     * snapshot keeps what its base names (the same rule `tg` pinned) — so the bucket holds three
     * files and every one is referenced, the consumer's file with them.
     */
    @Test
    fun `nothing under the table is unreferenced, the consumer file included`() {
        assertEquals(3, Files.list(tableDir.toPath().resolve("bucket-0")).filter { !it.fileName.toString().startsWith(".") }.count().toInt())
        val report = findUnreferencedFiles(model)
        assertTrue(report.problems.isEmpty(), "${report.problems}")
        assertEquals(emptyList(), report.unreferenced.map { report.relativePathOf(it) })
        assertTrue(referencedFiles(model).any { it.endsWith("consumer/consumer-reader") }, "the consumer's file is named by the model")
        assertTrue(report.filesOnDisk >= 3 + 2 + 1 + 1, "data, snapshots, schema and the consumer were all walked: ${report.filesOnDisk}")
    }

    /** The table panel's row, and the two shapes of absence: no `consumer/` at all, and no such thing on Iceberg. */
    @Test
    fun `the summary lists the consumer and says its snapshot is there`() {
        val summary = PaimonGraphBuilder.buildTableSummary(model)
        assertEquals(listOf(ConsumerSummary("reader", "consumer/consumer-reader", 2L, nextSnapshotPresent = true)), summary.consumers)

        val ao = PaimonGraphBuilder.buildTableSummary(PaimonUnifiedTableModel(Paths.get(File(repoRoot, "example/paimon/db.db/ao").absolutePath)))
        assertEquals(emptyList(), ao.consumers, "a Paimon table with no consumer/ has an empty list, not null")
        val iceberg = service.IcebergGraphBuilder.buildGraph(UnifiedTableModel(Paths.get(File(repoRoot, "example/iceberg/default/test").absolutePath))).summary
        assertNull(iceberg.consumers, "Iceberg has no consumers to list")
    }

    /** A bookmark whose snapshot has gone is a reader that will fail, and the summary says so rather than trusting the file. */
    @Test
    fun `a consumer standing on an expired snapshot is reported as such`() {
        val behind = model.copy(consumers = model.consumers.map { it.copy(metadata = PaimonConsumer(nextSnapshot = 1L)) })
        val summary = PaimonGraphBuilder.buildTableSummary(behind)
        assertEquals(false, summary.consumers!!.single().nextSnapshotPresent)
    }
}
