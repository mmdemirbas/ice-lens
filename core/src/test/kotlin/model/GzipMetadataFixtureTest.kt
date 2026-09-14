package model

import service.GraphLayoutService
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * `gzmeta`: the minimal table under `write.metadata.compression-codec = gzip`, whose every
 * `metadata.json` is gzip bytes named `v<N>.gz.metadata.json` — the codec's extension before
 * the suffix, which is how `TableMetadataParser.Codec.fromFileName` (1.8.1) reads it back,
 * beside the older `.metadata.json.gz` it stays compatible with. Every version read as a
 * `read-metadata-json` error before the name was consulted, and the table opened as nothing.
 */
class GzipMetadataFixtureTest {

    private val model = FixtureCatalog.icebergModel("gzmeta")

    @Test
    fun `the codec and the version are read off the name in both spellings`() {
        assertEquals(3, metadataVersionFromFileName("v3.gz.metadata.json"))
        assertEquals(3, metadataVersionFromFileName("v3.metadata.json.gz"))
        assertEquals(3, metadataVersionFromFileName("v3.metadata.json"))
        assertTrue(isGzipMetadataFileName("v3.gz.metadata.json") && isGzipMetadataFileName("00003-abc.metadata.json.gz"))
        assertTrue(!isGzipMetadataFileName("v3.metadata.json"))
        assertTrue(isMetadataFileName("v3.gz.metadata.json") && isMetadataFileName("v3.metadata.json.gz") && !isMetadataFileName("v3.metadata.json.crc"))
    }

    @Test
    fun `every compressed version reads, in version order, with its JSON decompressed for the panel`() {
        assertEquals(emptyList(), model.readErrors)
        assertEquals(listOf("v1.gz.metadata.json", "v2.gz.metadata.json", "v3.gz.metadata.json"), model.metadatas.map { it.path.fileName.toString() })
        assertEquals("3", model.versionHint)
        val newest = model.metadatas.last()
        assertEquals(2, newest.snapshots.size)
        assertEquals("gzip", newest.metadata.properties["write.metadata.compression-codec"])
        assertTrue(assertNotNull(newest.rawJson).trimStart().startsWith("{"), "the panel's JSON is the decompressed text")
        assertEquals(2, newest.metadata.snapshotLog.size)
    }

    @Test
    fun `the graph draws three metadata versions, two snapshots and their rows`() {
        val graph = GraphLayoutService.layoutGraph(model, showRows = true)
        assertTrue(graph.nodes.none { it is GraphNode.ErrorNode }, graph.nodes.filterIsInstance<GraphNode.ErrorNode>().toString())
        assertEquals(listOf(1, 2, 3), graph.nodes.filterIsInstance<GraphNode.MetadataNode>().mapNotNull { metadataVersionFromFileName(it.fileName) }.sorted())
        assertEquals(2, graph.nodes.filterIsInstance<GraphNode.SnapshotNode>().size)
        assertEquals(listOf("1", "2", "3"), graph.nodes.filterIsInstance<GraphNode.RowNode>().map { it.resolvedData["id"].toString() }.sorted())
    }
}
