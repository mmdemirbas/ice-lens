package service

import model.DeltaUnifiedTableModel
import model.FormatTableModel
import model.GraphNode
import model.PaimonUnifiedTableModel
import model.PredicateOp
import model.RowFate
import model.ScanFilter
import model.ScanPredicate
import model.UnifiedTableModel
import model.deleteReach
import model.liveFilesOf
import model.paimonRowLookupInput
import model.readInputAt
import model.readTableModel
import model.rowLookupInputOf
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.io.File
import java.nio.file.Files
import java.nio.file.Paths
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Reading tables off HDFS over WebHDFS, checked against the same tables on disk — the
 * [RemoteTableTest] shape against `docs/fixtures/hdfs-lab.sh` rather than MinIO.
 *
 * **The oracle is each fixture read twice.** The lab uploads `mor`, `dv` and `dplain` with the
 * `hdfs` client inside the container, so the bytes on HDFS are the bytes in `example/`, and every
 * figure the remote read produces has to be the one the local read produces — a decoder handed a
 * truncated or misordered response builds a model that is consistent and wrong.
 *
 * Every request names its user explicitly ([WebHdfs.setUsers]), so no test depends on the login
 * name or on `HADOOP_USER_NAME`. Skipped, not failed, when the lab is not up.
 */
class WebHdfsTableTest {

    private val repoRoot: File = generateSequence(File(".").absoluteFile) { it.parentFile }
        .first { File(it, "settings.gradle.kts").isFile }

    private val namenode = "webhdfs://127.0.0.1:9870"
    private val warehouse = "$namenode/warehouse"
    private val mor = "$warehouse/db/mor"

    private fun readAs(user: String) {
        WebHdfs.setUsers(mapOf(namenode to user))
    }

    private fun requireLab() {
        readAs("icelens")
        assumeTrue(
            runCatching { ObjectStorage.list("$mor/metadata").isNotEmpty() }.getOrDefault(false),
            "HDFS is not serving $mor — start it with docs/fixtures/hdfs-lab.sh up",
        )
    }

    @AfterTest
    fun forgetUsers() = WebHdfs.setUsers(emptyMap())

    private fun local(path: String) = Paths.get(File(repoRoot, path).absolutePath)

    @Test
    fun `the filesystem lists, stats and reads a table's metadata directory`() {
        requireLab()
        val metadata = StorageLocation.pathOf("$mor/metadata")
        assertTrue(Files.isDirectory(metadata))
        val localMetadata = local("example/iceberg/default/mor/metadata")
        val names = Files.list(metadata).use { it.map { p -> p.fileName.toString() }.toList() }.sorted()
        assertEquals(localMetadata.toFile().list()!!.filter { !it.startsWith(".") }.sorted(), names)

        val hint = metadata.resolve("version-hint.text")
        assertEquals("7", Files.readString(hint).trim())
        // A listing on HDFS carries the length and the modification time, which DuckDB's glob never did.
        assertEquals(Files.size(localMetadata.resolve("v7.metadata.json")), Files.size(metadata.resolve("v7.metadata.json")))
        assertTrue(Files.getLastModifiedTime(metadata.resolve("v7.metadata.json")).toMillis() > 0)
        assertTrue(Files.notExists(metadata.resolve("v99.metadata.json")))
    }

    @Test
    fun `a table read off HDFS is the table read from disk, down to the graph`() {
        requireLab()
        val localModel = UnifiedTableModel(local("example/iceberg/default/mor"))
        val remoteModel = UnifiedTableModel(StorageLocation.pathOf(mor))
        assertTrue(remoteModel.readErrors.isEmpty(), "${remoteModel.readErrors}")
        assertEquals(localModel.metadatas.map { it.path.fileName.toString() }, remoteModel.metadatas.map { it.path.fileName.toString() })
        fun entries(model: UnifiedTableModel) = model.metadatas.last().snapshots.flatMap { it.manifests }
            .flatMap { manifest -> manifest.dataFiles.map { it.metadata.dataFile?.recordCount ?: -1L } }.sorted()
        assertEquals(entries(localModel), entries(remoteModel))

        val localGraph = GraphLayoutService.layoutGraph(localModel, showRows = true, policy = AggregationPolicy.NONE)
        val remoteGraph = GraphLayoutService.layoutGraph(remoteModel, showRows = true, policy = AggregationPolicy.NONE)
        assertEquals(localGraph.nodes.groupingBy { it::class.simpleName }.eachCount(), remoteGraph.nodes.groupingBy { it::class.simpleName }.eachCount())
        assertEquals(localGraph.edges.size, remoteGraph.edges.size)
        assertTrue(remoteGraph.nodes.none { it is GraphNode.ErrorNode }, "${remoteGraph.nodes.filterIsInstance<GraphNode.ErrorNode>()}")
        // The row cards were read by DuckDB, through copies of the files on this machine.
        fun rows(graph: model.GraphModel) = graph.nodes.filterIsInstance<GraphNode.RowNode>().map { it.resolvedData - "local_file_path" }.sortedBy { it.toString() }
        assertTrue(rows(localGraph).isNotEmpty() && rows(localGraph).none { it.isEmpty() }, "${rows(localGraph)}")
        assertEquals(rows(localGraph), rows(remoteGraph))
    }

    @Test
    fun `each table is detected as its own format and opened as it`() {
        requireLab()
        val tables = mapOf(
            "mor" to ("example/iceberg/default/mor" to TableFormat.ICEBERG),
            "dv" to ("example/paimon/db.db/dv" to TableFormat.PAIMON),
            "dplain" to ("example/delta/dplain" to TableFormat.DELTA),
        )
        for ((name, expected) in tables) {
            val (localPath, format) = expected
            val url = "$warehouse/db/$name"
            assertEquals(format, TableFormatDetector.detect(StorageLocation.pathOf(url)), url)
            fun kinds(model: FormatTableModel) = GraphLayoutService.assembleGraph(model, showRows = false, policy = AggregationPolicy.NONE)
                .nodes.groupingBy { it::class.simpleName }.eachCount()
            assertEquals(kinds(readTableModel(local(localPath))), kinds(readTableModel(StorageLocation.pathOf(url))), url)
        }
        assertEquals(TableFormat.UNKNOWN, TableFormatDetector.detect(StorageLocation.pathOf("$warehouse/db/nothing-here")))
    }

    /**
     * The readers that run SQL over whole files — the live count, the merged count, the lookup —
     * answer as they do on disk, every file through a local copy.
     */
    @Test
    fun `the counts and the lookup read the files through local copies`() {
        requireLab()
        val localMor = UnifiedTableModel(local("example/iceberg/default/mor"))
        val remoteMor = UnifiedTableModel(StorageLocation.pathOf(mor))
        fun liveCounts(model: UnifiedTableModel) = model.metadatas.last().snapshots.filter { !it.expired }.map { snapshot ->
            LiveRowCount.count(assertNotNull(model.rowLookupInputOf(snapshot, liveFilesOf(snapshot), deleteReach(snapshot)))).live
        }
        assertEquals(liveCounts(localMor), liveCounts(remoteMor))
        assertEquals(5L, liveCounts(remoteMor).last())

        val localDv = readTableModel(local("example/paimon/db.db/dv")) as PaimonUnifiedTableModel
        val remoteDv = readTableModel(StorageLocation.pathOf("$warehouse/db/dv")) as PaimonUnifiedTableModel
        val merged = PaimonMergedCount.count(assertNotNull(remoteDv.paimonRowLookupInput()))
        assertEquals(PaimonMergedCount.count(assertNotNull(localDv.paimonRowLookupInput())).merged, merged.merged)
        assertNotNull(merged.merged)

        val localDelta = readTableModel(local("example/delta/dplain")) as DeltaUnifiedTableModel
        val remoteDelta = readTableModel(StorageLocation.pathOf("$warehouse/db/dplain")) as DeltaUnifiedTableModel
        assertEquals(localDelta.versions, remoteDelta.versions)
        val latest = remoteDelta.versions.last()
        val filter = ScanFilter.Term(ScanPredicate("id", PredicateOp.GTE, "0"))
        fun hits(model: DeltaUnifiedTableModel) = RowLookup.lookup(assertNotNull(model.readInputAt(latest)), filter, emptySet())
            .hits.map { it.cells["id"].toString() to it.fate }.sortedBy { it.first }
        assertEquals(hits(localDelta), hits(remoteDelta))
        assertTrue(hits(remoteDelta).isNotEmpty())
        assertEquals(
            LiveRowCount.count(assertNotNull(localDelta.readInputAt(latest))).live,
            LiveRowCount.count(assertNotNull(remoteDelta.readInputAt(latest))).live,
        )
        assertTrue(WebHdfs.copiesMade > 0, "the SQL readers should have been handed local copies")
    }

    /**
     * The table Spark wrote into the lab, where it was written. Its recorded `hdfs://` paths open
     * through no provider, so each resolves under the WebHDFS table root — and the positional
     * delete, which names its data file by that recorded path, is still matched to it.
     */
    @Test
    fun `a table written on HDFS reads back over WebHDFS with the rows Spark read`() {
        requireLab()
        val table = "$warehouse/spark/default/hdfsw"
        val model = UnifiedTableModel(StorageLocation.pathOf(table))
        assertTrue(model.readErrors.isEmpty(), "${model.readErrors}")
        val current = model.metadatas.last()
        assertEquals("hdfs://icelens-hdfs:8020/warehouse/spark/default/hdfsw", current.metadata.location)
        val files = current.snapshots.flatMap { it.manifests }.flatMap { it.dataFiles }
        assertTrue(files.all { it.path.toString().startsWith("$table/data/region=") }, "${files.map { it.path }}")
        val snapshot = current.snapshots.single { it.metadata.snapshotId == current.metadata.currentSnapshotId }
        val input = assertNotNull(model.rowLookupInputOf(snapshot, liveFilesOf(snapshot), deleteReach(snapshot)))
        val fates = RowLookup.lookup(input, ScanFilter.Term(ScanPredicate("id", PredicateOp.GTE, "1")), emptySet()).hits
            .associate { (it.cells["id"] as Number).toInt() to it.fate }
        assertEquals(
            mapOf(1 to RowFate.LIVE, 2 to RowFate.POSITION_DELETED, 3 to RowFate.LIVE, 4 to RowFate.LIVE, 5 to RowFate.LIVE),
            fates,
        )
        assertEquals(4L, LiveRowCount.count(input).live)
    }

    @Test
    fun `a warehouse is walked to its tables, passing over a directory the user may not list`() {
        requireLab()
        assertEquals(
            mapOf(
                "$warehouse/db/dplain" to TableFormat.DELTA, "$warehouse/db/dv" to TableFormat.PAIMON,
                "$warehouse/db/mor" to TableFormat.ICEBERG, "$warehouse/spark/default/hdfsw" to TableFormat.ICEBERG,
            ),
            ObjectStorage.globTables(warehouse),
        )
        readAs("hadoop")
        assertEquals(TableFormat.ICEBERG, ObjectStorage.globTables(warehouse)["$warehouse/private/mor"])
        assertEquals(
            (1..7).map { "$mor/metadata/v$it.metadata.json" },
            ObjectStorage.glob("$mor/metadata/v?.metadata.json").sorted(),
        )
        assertEquals(
            File(repoRoot, "example/iceberg/default/mor").walk().count { it.isFile && !it.name.startsWith(".") },
            ObjectStorage.glob("$mor/**").size,
        )
    }

    @Test
    fun `a path the user may not read is a refusal naming the user, not an absent table`() {
        requireLab()
        readAs("intruder")
        val failure = runCatching { ObjectStorage.list("$warehouse/private/mor/metadata") }.exceptionOrNull()
        assertTrue(failure is WebHdfs.PermissionDenied, "got ${failure?.javaClass?.name}: $failure")
        assertTrue("'intruder'" in failure.message.orEmpty() && "Permission denied" in failure.message.orEmpty(), failure.message)
        // At the root of a scan the refusal is thrown, not read as a warehouse holding nothing.
        assertTrue(runCatching { ObjectStorage.globTables("$warehouse/private") }.exceptionOrNull() is WebHdfs.PermissionDenied)

        // A user given for the table alone opens it, though the directory it sits in is one only
        // that user may list and every other path is read as someone who may not: the table's
        // own root is asked about as the table's user, never through its parent's listing.
        WebHdfs.setUsers(mapOf("$warehouse/private/mor" to "hadoop", namenode to "intruder"))
        assertTrue(Files.isDirectory(StorageLocation.pathOf("$warehouse/private/mor")))
        assertEquals(
            listOf("version-hint.text", "v7.metadata.json"),
            ObjectStorage.glob("$warehouse/private/mor/metadata/*").map { it.substringAfterLast('/') }
                .filter { it == "version-hint.text" || it == "v7.metadata.json" }.sortedDescending(),
        )
        assertEquals(TableFormat.ICEBERG, TableFormatDetector.detect(StorageLocation.pathOf("$warehouse/private/mor")))
        assertTrue(UnifiedTableModel(StorageLocation.pathOf("$warehouse/private/mor")).readErrors.isEmpty())
    }

    @Test
    fun `a missing path is nothing there, and a closed port says which namenode it could not reach`() {
        requireLab()
        assertEquals(emptyList(), ObjectStorage.list("$warehouse/db/nothing-here"))
        val missing = runCatching { ObjectStorage.readBytes("$warehouse/db/nothing-here/v1.metadata.json") }.exceptionOrNull()
        assertTrue("Nothing at" in missing?.message.orEmpty(), "${missing?.message}")

        val closed = runCatching { ObjectStorage.list("webhdfs://127.0.0.1:1/warehouse") }.exceptionOrNull()
        assertTrue(closed is ObjectStorage.ObjectStorageException, "got $closed")
        assertTrue("Could not reach the namenode at 127.0.0.1:1" in closed.message.orEmpty(), closed.message)
    }

    /** The refusals WebHDFS answers with, as the namenode writes them — no lab needed. */
    @Test
    fun `each refusal the namenode answers with is said in terms of what to do`() {
        readAs("intruder")
        fun said(status: Int, exception: String, message: String) = WebHdfs.failure(
            "$warehouse/private",
            status,
            """{"RemoteException":{"exception":"$exception","javaClassName":"org.apache.hadoop.$exception","message":"$message"}}""",
        )
        val denied = said(403, "AccessControlException", "Permission denied: user=intruder, access=EXECUTE")
        assertTrue(denied is WebHdfs.PermissionDenied && "as 'intruder'" in denied.message.orEmpty(), denied.message)
        assertTrue("standby" in said(403, "StandbyException", "Operation category READ is not supported in state standby").message.orEmpty())
        assertTrue("Kerberos" in said(401, "", "").message.orEmpty())
        assertTrue("check the path" in said(404, "FileNotFoundException", "File does not exist").message.orEmpty())
        assertTrue("HTTP 500 IOException: boom" in WebHdfs.failure(mor, 500, """{"RemoteException":{"exception":"IOException","message":"boom"}}""").message.orEmpty())
        assertEquals("intruder", WebHdfs.userFor("$mor/data"))
        assertEquals(WebHdfs.defaultUser, WebHdfs.userFor("webhdfs://elsewhere:9870/x"))
    }

    /**
     * A user is scoped to the location it was given, the longest one holding a path winning —
     * two locations on one namenode read as two users, as two buckets on one store take two keys.
     */
    @Test
    fun `a user is scoped to its location, and the nearest location decides`() {
        WebHdfs.setUsers(mapOf("$warehouse/db/" to "etl", "$warehouse/private" to "hadoop", namenode to "icelens"))
        assertEquals("etl", WebHdfs.userFor("$mor/metadata/v1.metadata.json"))
        assertEquals("etl", WebHdfs.userFor("$warehouse/db"))
        assertEquals("hadoop", WebHdfs.userFor("$warehouse/private/mor"))
        assertEquals("icelens", WebHdfs.userFor("$warehouse/dbx/mor"), "a sibling sharing a prefix is not under it")
        assertEquals("icelens", WebHdfs.userFor("WEBHDFS://127.0.0.1:9870/warehouse"))
        assertEquals(WebHdfs.defaultUser, WebHdfs.userFor("webhdfs://127.0.0.1:9871/warehouse/db"))
    }
}
