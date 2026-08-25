package model

import service.GraphLayoutService
import java.io.File
import java.nio.file.Paths
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A bucket field ruling a manifest out, which it declined to do until the hash had an oracle.
 *
 * [BucketTransformTest] settles that the bucket number is the writer's; this settles that the
 * pruning built on it reaches a verdict, and that it reaches the *right* one — which is not the
 * same thing. A hash that is correct and a comparison that is inverted both produce "SKIPPED" on
 * some manifest.
 *
 * So the check is a round trip against the fixture's own data: for every value the table actually
 * holds, the manifest containing it must be kept; a bucket the table has no manifest for must be
 * skipped by all of them.
 */
class BucketPruningTest {

    private val repoRoot: File = generateSequence(File(".").absoluteFile) { it.parentFile }
        .first { File(it, "settings.gradle.kts").isFile }

    private fun graph(fixture: String) = GraphLayoutService.layoutGraph(
        UnifiedTableModel(Paths.get(File(repoRoot, "example/iceberg/default/$fixture").absolutePath)),
        showRows = false,
    )

    private fun bucketSummaries(fixture: String) =
        graph(fixture).nodes.filterIsInstance<GraphNode.ManifestNode>()
            .flatMap { node -> node.partitionSummaries.map { node to it } }
            .filter { (_, summary) -> bucketCount(summary.field.transformName) != null }

    /** Every id the table holds, from the data files' own single-valued bounds. */
    private fun idsHeldBy(fixture: String): List<Int> =
        graph(fixture).nodes.filterIsInstance<GraphNode.FileNode>()
            .filter { it.data.content == DataFileContent.DATA }
            .mapNotNull { node ->
                val bucketField = node.partition?.values
                    ?.firstOrNull { bucketCount(it.field.transformName) != null } ?: return@mapNotNull null
                val stats = node.columnStats.firstOrNull { it.fieldId == bucketField.field.sourceId }
                    ?: return@mapNotNull null
                val low = stats.lowerBound?.value as? Int ?: return@mapNotNull null
                val high = stats.upperBound?.value as? Int ?: return@mapNotNull null
                low.takeIf { low == high }
            }
            .distinct()

    @Test
    fun `a bucket field now reaches a verdict on equality`() {
        val summaries = bucketSummaries("parted")
        assertTrue(summaries.isNotEmpty(), "parted is bucketed on id; the manifests should record it")

        val plan = evaluateScan(graph("parted"), listOf(ScanPredicate("id", PredicateOp.EQ, "1")))
        val bucketTerms = plan.manifests.values
            .flatMap { it.outcomes }
            .filter { bucketCount(it.transform.orEmpty()) != null }
        assertTrue(bucketTerms.isNotEmpty(), "the id predicate should reach the bucket field")
        assertTrue(
            bucketTerms.none { it.effect == TermEffect.NOT_EVALUATED },
            "equality on a bucket field is decidable now: ${bucketTerms.map { it.reason }}",
        )
    }

    /**
     * Every id the table holds keeps the manifest that holds it.
     *
     * This is the direction that catches an inverted comparison. A wrong sign still skips
     * *something*, so "some manifest was skipped" proves nothing on its own — but skipping a
     * manifest that holds the row is a scan returning fewer rows than the table has, which is the
     * failure this whole file exists to prevent.
     */
    @Test
    fun `no value the table holds is pruned away`() {
        val ids = idsHeldBy("parted")
        assertTrue(ids.size >= 2, "parted should hold several distinct ids; got $ids")

        val g = graph("parted")
        ids.forEach { id ->
            val plan = evaluateScan(g, listOf(ScanPredicate("id", PredicateOp.EQ, "$id")))
            val kept = plan.manifests.values.count { !it.isSkipped }
            assertTrue(
                kept > 0,
                "id = $id is in this table, and every manifest was skipped for it — the bucket " +
                    "comparison is eliminating rows that exist",
            )
        }
    }

    /**
     * A bucket the table has nothing in is skipped by every manifest that records a narrow range.
     *
     * Stated against the fixture's own summaries rather than a guessed id: whichever bucket the
     * literal lands in, a manifest whose recorded range excludes it must skip. If no manifest has
     * a narrow enough range the test says so rather than passing vacuously.
     */
    @Test
    fun `a literal outside a manifest's bucket range skips it`() {
        val g = graph("parted")
        val ids = idsHeldBy("parted")
        val buckets = bucketSummaries("parted").firstNotNullOfOrNull { (_, s) -> bucketCount(s.field.transformName) }
        assertTrue(buckets != null && buckets > 1, "parted should bucket into more than one")

        // A value from far outside the table's own ids, so its bucket is not one the fixture
        // deliberately populated. Which bucket it lands in is the hash's business, not this test's.
        val outsider = (ids.max() + 10_000)
        val plan = evaluateScan(g, listOf(ScanPredicate("id", PredicateOp.EQ, "$outsider")))
        val bucketTerms = plan.manifests.values
            .flatMap { it.outcomes }
            .filter { bucketCount(it.transform.orEmpty()) != null }

        assertTrue(bucketTerms.isNotEmpty(), "the predicate should have reached the bucket field")
        assertTrue(
            bucketTerms.all { it.effect != TermEffect.NOT_EVALUATED },
            "every bucket term should be decided: ${bucketTerms.map { it.reason }}",
        )
        assertTrue(
            bucketTerms.any { it.effect == TermEffect.SKIPS },
            "id = $outsider hashes into some bucket, and at least one manifest's recorded range " +
                "should exclude it: ${bucketTerms.map { "${it.effect} — ${it.reason}" }}",
        )
    }

    /** Everything other than equality still declines, because a bucket range is not a value range. */
    @Test
    fun `a range predicate on a bucket field still declines to evaluate`() {
        val g = graph("parted")
        listOf(PredicateOp.LT, PredicateOp.GT, PredicateOp.GTE, PredicateOp.NOT_EQ).forEach { op ->
            val plan = evaluateScan(g, listOf(ScanPredicate("id", op, "5")))
            val bucketTerms = plan.manifests.values
                .flatMap { it.outcomes }
                .filter { bucketCount(it.transform.orEmpty()) != null }
            assertTrue(bucketTerms.isNotEmpty(), "$op should still reach the bucket field")
            assertEquals(
                setOf(TermEffect.NOT_EVALUATED),
                bucketTerms.map { it.effect }.toSet(),
                "$op across a hash proves nothing, and claiming otherwise drops rows",
            )
        }
    }

    /** The column list offers a bucket column now, because it can rule something out. */
    @Test
    fun `a bucketed column is offered as prunable`() {
        val id = prunableColumns(graph("parted")).firstOrNull { it.name == "id" }
        assertTrue(id != null, "id is a partition column of parted")
        assertTrue(id.prunesManifests, "a bucket field prunes on equality, so it prunes")
    }
}
