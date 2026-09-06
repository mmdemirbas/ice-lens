package service

import java.io.File
import java.nio.file.Files
import java.nio.file.Paths
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import model.GraphNode
import model.UnifiedTableModel
import org.junit.jupiter.api.Assumptions.assumeTrue

/**
 * Reading a real Iceberg table out of object storage, checked against the same table on disk.
 *
 * **The oracle is the fixture itself, read twice.** `example/iceberg/default/mor` is a real
 * Spark-written table with positional deletes, a compaction and dangling deletes; the same bytes
 * are uploaded to MinIO by `docs/fixtures/minio-lab.sh`. Opening both and requiring the models to
 * agree is the only check that says the object-storage path reads a *table* rather than merely
 * returning bytes — a decoder fed truncated or misordered content produces a model that is
 * internally consistent and wrong, and every assertion written against the remote side alone
 * would pass.
 *
 * Skipped, not failed, when MinIO is not up: a container is not a thing every checkout has, and a
 * suite that goes red on a laptop without Docker teaches people to ignore it.
 */
class RemoteTableTest {

    private val repoRoot: File = generateSequence(File(".").absoluteFile) { it.parentFile }
        .first { File(it, "settings.gradle.kts").isFile }

    private val remoteRoot = "s3://warehouse/db/mor"

    /**
     * Points DuckDB at the lab and answers whether the fixture is actually there.
     *
     * The credentials are the lab's documented defaults against a container bound to loopback —
     * see the script's header. Nothing here reads a credential from the environment, because a
     * test that silently picks up a real AWS profile is a test that can spend money.
     */
    private fun labIsUp(): Boolean {
        DuckDb.setCredentials(
            listOf(
                ObjectStoreCredentials(
                    name = "icelens_lab", keyId = "minioadmin", secret = "minioadmin",
                    endpoint = "127.0.0.1:9000", useSsl = false, urlStyle = "path",
                    region = "us-east-1", scope = "s3://warehouse",
                )
            )
        )
        ObjectStorage.clearCache()
        return runCatching { ObjectStorage.list("$remoteRoot/metadata").isNotEmpty() }.getOrDefault(false)
    }

    private fun requireLab() = assumeTrue(
        labIsUp(),
        "MinIO is not serving $remoteRoot — start it with docs/fixtures/minio-lab.sh up",
    )

    private fun localModel() =
        UnifiedTableModel(Paths.get(File(repoRoot, "example/iceberg/default/mor").absolutePath))

    private fun remoteModel() = UnifiedTableModel(StorageLocation.pathOf(remoteRoot))

    /** The filesystem answers the four questions the model layer actually asks of it. */
    @Test
    fun `the filesystem lists, stats and reads a real table's metadata directory`() {
        requireLab()
        val metadata = StorageLocation.pathOf("$remoteRoot/metadata")

        assertTrue(Files.isDirectory(metadata), "metadata/ should be a directory")
        val names = Files.list(metadata).use { it.map { p -> p.fileName.toString() }.toList() }
        assertTrue(names.size > 5, "expected the metadata directory's files, got $names")
        assertTrue(names.any { it.endsWith(".metadata.json") })
        assertTrue(names.any { it.endsWith(".avro") })

        val versionHint = metadata.resolve("version-hint.text")
        assertTrue(Files.isRegularFile(versionHint))
        assertEquals("7", Files.readString(versionHint).trim())

        // And the negative, which is the one an object store gets wrong: a name that is not there
        // must be absent rather than an error, or every optional file reads as a failure.
        assertTrue(Files.notExists(metadata.resolve("v99.metadata.json")))
    }

    /**
     * The same table, opened both ways, describes itself identically.
     *
     * Compared on what the *table* says rather than on where its files are: the paths necessarily
     * differ, and the figures necessarily must not.
     */
    @Test
    fun `a table read from object storage matches the same table read from disk`() {
        requireLab()
        val local = localModel()
        val remote = remoteModel()

        assertEquals(local.metadatas.size, remote.metadatas.size, "metadata version count")
        assertEquals(local.versionHint, remote.versionHint)
        assertEquals(
            local.metadatas.map { it.path.fileName.toString() },
            remote.metadatas.map { it.path.fileName.toString() },
            "the same metadata files, in the same order",
        )

        val localCurrent = local.metadatas.last()
        val remoteCurrent = remote.metadatas.last()
        assertEquals(
            localCurrent.snapshots.map { it.metadata.snapshotId },
            remoteCurrent.snapshots.map { it.metadata.snapshotId },
            "the same snapshots",
        )
        assertEquals(
            localCurrent.snapshots.map { snapshot -> snapshot.manifests.size },
            remoteCurrent.snapshots.map { snapshot -> snapshot.manifests.size },
            "the same manifest count per snapshot",
        )
        // The entries inside the manifests, which is where a truncated or misordered read shows up.
        assertEquals(
            localCurrent.snapshots.flatMap { it.manifests }.sumOf { it.dataFiles.size },
            remoteCurrent.snapshots.flatMap { it.manifests }.sumOf { it.dataFiles.size },
            "the same number of manifest entries",
        )
        assertEquals(
            localCurrent.snapshots.flatMap { it.manifests }
                .flatMap { manifest -> manifest.dataFiles.map { it.metadata.dataFile?.recordCount ?: -1L } }.sorted(),
            remoteCurrent.snapshots.flatMap { it.manifests }
                .flatMap { manifest -> manifest.dataFiles.map { it.metadata.dataFile?.recordCount ?: -1L } }.sorted(),
            "the same record counts, decoded from the same Avro bytes",
        )
        assertTrue(remote.readErrors.isEmpty(), "the remote read reported errors: ${remote.readErrors}")
    }

    /**
     * The graph built from the remote table is the graph built from the local one.
     *
     * The end of the pipeline rather than the start, so anything the readers got subtly wrong has
     * had every chance to show up as a different node.
     */
    @Test
    fun `the graph is the same graph, whichever side the bytes came from`() {
        requireLab()
        val local = GraphLayoutService.layoutGraph(localModel(), showRows = false, policy = AggregationPolicy.NONE)
        val remote = GraphLayoutService.layoutGraph(remoteModel(), showRows = false, policy = AggregationPolicy.NONE)

        assertEquals(local.nodes.size, remote.nodes.size, "node count")
        assertEquals(local.edges.size, remote.edges.size, "edge count")
        assertEquals(
            local.nodes.groupingBy { it::class.simpleName }.eachCount(),
            remote.nodes.groupingBy { it::class.simpleName }.eachCount(),
            "the same node kinds in the same numbers",
        )
        assertTrue(
            local.nodes.none { it is GraphNode.ErrorNode } && remote.nodes.none { it is GraphNode.ErrorNode },
            "neither side should have produced an error node",
        )
    }

    /**
     * A data file's rows come back from object storage, which is the half a filesystem cannot do.
     *
     * `read_parquet` runs inside DuckDB against the same connection the metadata was read through,
     * which is the reason this route was chosen over installing an S3 filesystem: there is one
     * credential configuration, not two.
     */
    @Test
    fun `sample rows are read straight out of object storage`() {
        requireLab()
        val dataFile = ObjectStorage.list("$remoteRoot/data")
            .first { it.name.endsWith(".parquet") && !it.name.contains("deletes") }
        val rows = SampleRowReader.querySampleRows("$remoteRoot/data/${dataFile.name}")

        assertTrue(rows.isNotEmpty(), "no rows came back from $remoteRoot/data/${dataFile.name}")
        assertTrue(
            rows.all { SampleRowReader.FILE_ROW_NUMBER in it },
            "a remote Parquet read should still carry the row's own position",
        )
    }

    /**
     * A warehouse is scanned with two globs, not a directory walk.
     *
     * Walking a warehouse means a request per level per table. The marker each format is detected
     * by is a path shape, so one glob per format answers the same question in two round trips.
     */
    @Test
    fun `a remote warehouse lists its tables`() {
        requireLab()
        val tables = ObjectStorage.globTables("s3://warehouse/db")
        assertTrue("s3://warehouse/db/mor" in tables, "expected the mor table, found $tables")
    }

    /**
     * A refused key says so, and does not present as a missing table.
     *
     * This is the specific defect that decided the storage layer. Through an S3 `FileSystem`,
     * `Files.exists()` is specified to answer `false` rather than throw, so a 403 on a bucket came
     * back indistinguishable from "the table is not there" — the likeliest failure, reported as
     * the one thing it is not.
     */
    @Test
    fun `a wrong key is reported as a refusal, not as an absent table`() {
        requireLab()
        DuckDb.setCredentials(
            listOf(
                ObjectStoreCredentials(
                    name = "icelens_bad", keyId = "wrong", secret = "wrong",
                    endpoint = "127.0.0.1:9000", useSsl = false, urlStyle = "path",
                    region = "us-east-1", scope = "s3://warehouse",
                )
            )
        )
        ObjectStorage.clearCache()
        try {
            val failure = runCatching { ObjectStorage.list("$remoteRoot/metadata") }.exceptionOrNull()
            assertTrue(failure is ObjectStorage.ObjectStorageException, "got ${failure?.javaClass?.name}: $failure")
            val message = failure.message.orEmpty()
            assertTrue("Access denied" in message, "a 403 should be named as one, and said: $message")
            assertTrue("check the key" in message, "and should say what to do about it: $message")
        } finally {
            DuckDb.setCredentials(emptyList())
            ObjectStorage.clearCache()
        }
    }
}
