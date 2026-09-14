package model

import service.PuffinReader
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Paths
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The theta sketch behind a statistics blob, decoded, and held to the `ndv` Iceberg read off
 * the same sketch when it wrote the blob (`NDVSketchUtil`: `(long) sketch.getEstimate()`).
 *
 * `ndv` (`docs/fixtures/ndv.sql`) carries the four shapes a compact sketch is serialised in:
 * 20,000 distinct ids past the 4,096 nominal entries — an estimate, three preamble longs with θ
 * — seven buckets exact, one value as a single-item sketch, an all-null column as an empty one.
 * `stats`' four are all exact. What the fixture is for is the first row: the record says
 * `ndv 20158` of a column holding 20,000, and only the sketch says that figure is a
 * projection from 4,096 hashes rather than a count.
 */
class ThetaSketchFixtureTest {

    private fun sketchesOf(fixture: String): Map<String, Pair<PuffinBlobMetadata, ThetaSketch>> {
        val model = FixtureCatalog.icebergModel(fixture)
        val meta = model.metadatas.last().metadata
        val file = meta.statistics.single()
        val path = Paths.get(FixtureCatalog.icebergDir(fixture).absolutePath, "metadata", assertNotNull(file.statisticsPath).substringAfterLast('/'))
        val footer = PuffinReader.readFooter(path)
        val names = meta.schemas.first().fields.associate { it.id to it.name }
        return footer.blobs.associate { blob ->
            names.getValue(blob.fields.single())!! to (blob to ThetaSketch.decode(PuffinReader.readBlob(path, blob)))
        }
    }

    @Test
    fun `the four shapes of a compact sketch decode, and each gives the ndv its blob records`() {
        val sketches = sketchesOf("ndv")
        assertEquals(setOf("id", "bucket", "one", "nothing"), sketches.keys)
        for ((column, pair) in sketches) {
            val (blob, sketch) = pair
            assertEquals("zstd", blob.compressionCodec, column)
            assertEquals(blob.properties.getValue("ndv").toLong(), sketch.ndv, "$column: ${sketch.describe()}")
        }
        val id = sketches.getValue("id").second
        assertTrue(!id.exact && !id.empty && !id.singleItem, id.toString())
        assertTrue(id.theta < 1.0 && id.retainedEntries in 3_000..8_192, "past the nominal entries the sketch keeps the hashes below θ: ${id.describe()}")
        assertEquals(20_158L, id.ndv, "an estimate of a column holding 20,000 distinct values")
        assertTrue(id.describe().startsWith("estimate, ") && id.describe().contains("hashes kept below θ 0."), id.describe())
        val bucket = sketches.getValue("bucket").second
        assertEquals(ThetaSketch(7, 1.0, empty = false, singleItem = false, ordered = true), bucket)
        assertEquals("exact, 7 hashes", bucket.describe())
        val one = sketches.getValue("one").second
        assertTrue(one.singleItem && one.exact && one.ndv == 1L, one.toString())
        assertEquals("single value", one.describe())
        val nothing = sketches.getValue("nothing").second
        assertTrue(nothing.empty && nothing.exact && nothing.ndv == 0L && nothing.retainedEntries == 0, nothing.toString())
        assertEquals("empty", nothing.describe())
    }

    @Test
    fun `stats' four sketches are exact and agree with their records, on the panel's rows and in the whole-table check`() {
        for ((column, pair) in sketchesOf("stats")) {
            val (blob, sketch) = pair
            assertTrue(sketch.exact, column)
            assertEquals(blob.properties.getValue("ndv").toLong(), sketch.ndv, column)
            assertEquals(sketch.retainedEntries.toLong(), sketch.ndv, "exact means every distinct value's hash is kept: $column")
        }
        for (fixture in listOf("stats", "ndv")) {
            val graph = service.GraphLayoutService.layoutGraph(FixtureCatalog.icebergModel(fixture), showRows = false)
            val metadata = graph.nodes.filterIsInstance<GraphNode.MetadataNode>().single { it.data.statistics.isNotEmpty() }
            val footers = assertNotNull(metadata.statisticsFooters.value)
            val rows = statisticsRows(
                metadata.data,
                footerOf = { file -> footers[file.statisticsPath]?.footer },
                sketchOf = { file, fields -> footers[file.statisticsPath]?.sketches?.get(fields) },
            )
            assertEquals(4, rows.size, fixture)
            assertTrue(rows.all { it.sketch != null && it.sketchAgrees == true && it.sketchProblem == null }, "$fixture: $rows")
            val check = assertNotNull(graph.nodes.filterIsInstance<GraphNode.TableNode>().single().statisticsFiles.value).single()
            assertEquals(8, check.figures, "$fixture: four properties and four sketches")
            assertEquals(emptyList(), check.findings, fixture)
        }
    }

    /**
     * A sketch whose estimate is not the recorded figure is a finding of its own, apart from
     * the file's `ndv` property: the property is what the writer read off the sketch, so the
     * two can only differ when the blob was rewritten or mislabelled since.
     */
    @Test
    fun `a sketch whose estimate is not the recorded ndv is a finding, and one that cannot be decoded is said`() {
        val model = FixtureCatalog.icebergModel("ndv")
        val file = model.metadatas.last().metadata.statistics.single()
        val recorded = assertNotNull(file.statisticsPath)
        val path = Paths.get(FixtureCatalog.icebergDir("ndv").absolutePath, "metadata", recorded.substringAfterLast('/'))
        val footer = PuffinReader.readFooter(path)
        val real = footer.blobs.associate { it.fields to ThetaSketchRead(ThetaSketch.decode(PuffinReader.readBlob(path, it))) }
        // The id sketch with its θ halved reads as twice the distinct values; the bucket sketch not decoded.
        val idFields = footer.blobs.single { it.fields == listOf(1) }.fields
        val halved = real.getValue(idFields).sketch!!.let { it.copy(theta = it.theta / 2) }
        val planted = real + mapOf(idFields to ThetaSketchRead(halved), listOf(2) to ThetaSketchRead(null, "serialization version 4 is not the 3 this reads"))
        val check = model.checkStatisticsFiles(mapOf(recorded to StatisticsFileFooter(path.toString(), PathResolution.RECORDED, footer, null, planted)), emptyMap()).single()
        assertEquals(4 + 3, check.figures, "four properties, three sketches decoded")
        val finding = check.findings.single()
        assertEquals("ndv against the sketch", finding.figure)
        assertEquals("20158", finding.recorded)
        assertTrue(finding.counted.endsWith("gives ${halved.ndv}") && halved.ndv in 40_000L..41_000L, finding.counted)
        val rows = statisticsRows(model.metadatas.last().metadata, { footer }, { _, fields -> planted[fields] })
        val bucket = rows.single { it.column == "bucket" }
        assertEquals(null, bucket.sketch); assertEquals(null, bucket.sketchAgrees)
        assertEquals("serialization version 4 is not the 3 this reads", bucket.sketchProblem)
    }

    /** The preamble read by hand, both byte orders, and the refusals: written from `PreambleUtil`'s map, not from the fixture. */
    @Test
    fun `the preamble decodes in either byte order, and a version or family this does not read is refused by name`() {
        fun bytes(preLongs: Int, flags: Int, retained: Int = 0, theta: Long = Long.MAX_VALUE, order: ByteOrder = ByteOrder.LITTLE_ENDIAN, hashes: Int = retained): ByteArray {
            val buffer = ByteBuffer.allocate(preLongs * 8 + hashes * 8).order(order)
            buffer.put(0, preLongs.toByte()); buffer.put(1, 3); buffer.put(2, 3); buffer.put(5, flags.toByte())
            if (preLongs >= 2) { buffer.putInt(8, retained); buffer.putFloat(12, 1.0f) }
            if (preLongs >= 3) buffer.putLong(16, theta)
            for (i in 0 until hashes) buffer.putLong(preLongs * 8 + i * 8, (i + 1) * 1_000L)
            return buffer.array()
        }
        assertEquals(ThetaSketch(0, 1.0, empty = true, singleItem = false, ordered = false), ThetaSketch.decode(bytes(1, flags = 4 or 8)))
        assertEquals(ThetaSketch(1, 1.0, empty = false, singleItem = true, ordered = true), ThetaSketch.decode(bytes(1, flags = 8 or 16 or 32, hashes = 1)))
        assertEquals(ThetaSketch(5, 1.0, empty = false, singleItem = false, ordered = true), ThetaSketch.decode(bytes(2, flags = 8 or 16, retained = 5)))
        val estimating = ThetaSketch.decode(bytes(3, flags = 8 or 16, retained = 4_096, theta = Long.MAX_VALUE / 4))
        assertEquals(4_096, estimating.retainedEntries); assertEquals(0.25, estimating.theta, 1e-12); assertEquals(16_384L, estimating.ndv)
        assertTrue(!estimating.exact)
        // The big-endian flag says the writer's byte order, and the figures are read in it.
        val big = ThetaSketch.decode(bytes(3, flags = 1 or 8 or 16, retained = 4_096, theta = Long.MAX_VALUE / 4, order = ByteOrder.BIG_ENDIAN))
        assertEquals(estimating.copy(), big.copy())
        assertTrue(assertFailsWith<IllegalArgumentException> { ThetaSketch.decode(bytes(2, flags = 8, retained = 5).also { it[1] = 4 }) }.message!!.contains("version 4"))
        assertTrue(assertFailsWith<IllegalArgumentException> { ThetaSketch.decode(bytes(2, flags = 8, retained = 5).also { it[2] = 2 }) }.message!!.contains("family 2"))
        assertTrue(assertFailsWith<IllegalArgumentException> { ThetaSketch.decode(bytes(2, flags = 8, retained = 5, hashes = 2)) }.message!!.contains("cannot hold 5 hashes"))
    }
}
