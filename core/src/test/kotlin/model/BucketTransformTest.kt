package model

import service.GraphLayoutService
import java.io.File
import java.nio.file.Paths
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The bucket transform, checked against the buckets Spark actually wrote.
 *
 * A hash re-implemented from a spec agrees with itself long before it agrees with the writer, and
 * a wrong bucket number would prune a manifest holding the rows — a silent wrong answer. So this
 * is not a test of the spec text. It takes the `id_bucket` value Iceberg recorded in each data
 * file's partition tuple and requires [BucketTransform] to reproduce it from the `id` bound the
 * same file records about itself. Nothing here produced either number.
 *
 * `respec` is the fixture that makes it a real check: the same table is bucketed at 4 and then
 * re-bucketed at 8, so the same ids appear under both. An implementation that applied the modulus
 * at the wrong point, or hashed the wrong bytes, can match one and not both.
 */
class BucketTransformTest {

    private val repoRoot: File = generateSequence(File(".").absoluteFile) { it.parentFile }
        .first { File(it, "settings.gradle.kts").isFile }

    private fun graph(fixture: String) = GraphLayoutService.layoutGraph(
        UnifiedTableModel(Paths.get(File(repoRoot, "example/iceberg/default/$fixture").absolutePath)),
        showRows = false,
    )

    /** A file's recorded bucket, and the single source value it holds, where it holds only one. */
    private data class Sample(val fixture: String, val file: String, val recorded: Int, val source: Any?, val buckets: Int)

    /**
     * Every data file whose `id` bound is single-valued, paired with the bucket it was put in.
     *
     * Single-valued because a file holding ids 3 and 7 says nothing about which of them decided
     * the bucket — Iceberg writes one file per bucket per partition, so every row in it shares a
     * bucket, but the *bounds* only pin the value when they agree. Spark writes one row per data
     * file for small inserts here, so most of them do.
     */
    private fun samples(fixture: String): List<Sample> =
        graph(fixture).nodes.filterIsInstance<GraphNode.FileNode>()
            .filter { it.data.content == DataFileContent.DATA }
            .mapNotNull { node ->
                val partition = node.partition ?: return@mapNotNull null
                val bucketField = partition.values.firstOrNull { bucketCount(it.field.transformName) != null }
                    ?: return@mapNotNull null
                val buckets = bucketCount(bucketField.field.transformName) ?: return@mapNotNull null
                val recorded = bucketField.stored.value as? Int ?: return@mapNotNull null

                val sourceId = bucketField.field.sourceId
                val stats = node.columnStats.firstOrNull { it.fieldId == sourceId } ?: return@mapNotNull null
                val low = stats.lowerBound?.value ?: return@mapNotNull null
                val high = stats.upperBound?.value ?: return@mapNotNull null
                if (low != high) return@mapNotNull null

                Sample(fixture, fileNameOf(node), recorded, low, buckets)
            }

    private fun fileNameOf(node: GraphNode.FileNode): String =
        node.data.filePath?.substringAfterLast('/') ?: node.id

    @Test
    fun `every recorded bucket is reproduced from the value the file holds`() {
        val samples = samples("parted") + samples("respec")
        assertTrue(
            samples.size >= 6,
            "the two bucketed fixtures should yield several single-valued files to check; " +
                "got ${samples.size} — if the fixtures changed, this test is measuring nothing",
        )

        println("bucket oracle: ${samples.size} single-valued files across parted and respec, " +
            "bucket counts ${samples.map { it.buckets }.toSortedSet()}")
        val wrong = samples.mapNotNull { sample ->
            val computed = BucketTransform.bucketOf(sample.source, sample.buckets)
            if (computed == sample.recorded) {
                null
            } else {
                "${sample.fixture}/${sample.file}: id=${sample.source} at bucket[${sample.buckets}] " +
                    "computed $computed, Iceberg recorded ${sample.recorded}"
            }
        }
        assertEquals(emptyList(), wrong, "these buckets do not match what the writer recorded")
    }

    /**
     * The same ids under two bucket counts, which is what `respec` was written for.
     *
     * Asserted rather than left implicit in the sweep above: if the re-bucketed spec ever stopped
     * being read, the sweep would still pass on `bucket[4]` alone and the modulus would be
     * unchecked.
     */
    @Test
    fun `respec exercises both of its bucket counts`() {
        val counts = samples("respec").map { it.buckets }.toSet()
        assertEquals(
            setOf(4, 8),
            counts,
            "respec replaces bucket(4, id) with bucket(8, id); both specs should reach this test",
        )
    }

    /** A value with no bucket transform is declined, not given a plausible number. */
    @Test
    fun `types the spec does not bucket return null`() {
        assertNull(BucketTransform.bucketOf(null, 4), "a null is in no bucket")
        assertNull(BucketTransform.bucketOf(true, 4), "boolean has no bucket transform")
        assertNull(BucketTransform.bucketOf(1.5f, 4), "float has no bucket transform")
        assertNull(BucketTransform.bucketOf(1.5, 4), "double has no bucket transform")
        assertNull(BucketTransform.bucketOf(42, 0), "a bucket count of zero is not a partitioning")
    }

    /** Every result is a valid bucket index — the sign-bit mask, not an absolute value. */
    @Test
    fun `a bucket is always in range, including for values that hash negative`() {
        val buckets = 8
        val results = (-100_000L..100_000L step 977).map { value ->
            val bucket = BucketTransform.bucketOf(value, buckets)
            assertTrue(
                bucket != null && bucket in 0 until buckets,
                "long $value bucketed to $bucket, outside 0..${buckets - 1}",
            )
            bucket
        }
        assertTrue(
            results.toSet().size > 1,
            "every value landing in one bucket would mean the hash is not being read",
        )
    }

    /** A string and a long that look alike do not share a bucket — the bytes hashed differ. */
    @Test
    fun `the value's type decides the bytes hashed`() {
        val asLong = BucketTransform.bucketOf(42L, 64)
        val asText = BucketTransform.bucketOf("42", 64)
        assertTrue(
            asLong != asText,
            "hashing 8 little-endian bytes and hashing two UTF-8 characters cannot agree here; " +
                "both gave $asLong, which means one branch is not being taken",
        )
    }
}
