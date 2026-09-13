package model

import service.GraphLayoutService
import java.io.File
import java.nio.file.Paths
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Scan pruning over a Paimon table, which until the bridge existed answered "would be read" for
 * everything because it saw no manifests and no files.
 *
 * The oracle is the script that wrote `pt`: every row's partition and key are known, so which
 * files a predicate can rule out is known before anything is evaluated. Both stages are checked
 * — a manifest by the per-column partition range its list records, a file by its own bounds —
 * and the direction that matters is that no file holding a matching row is ever skipped.
 */
class PaimonScanPruningTest {

    private val repoRoot: File = generateSequence(File(".").absoluteFile) { it.parentFile }
        .first { File(it, "settings.gradle.kts").isFile }

    private val graph = GraphLayoutService.layoutGraph(
        PaimonUnifiedTableModel(Paths.get(File(repoRoot, "example/paimon/db.db/pt").absolutePath)),
        showRows = false,
    )

    private val files = graph.nodes.filterIsInstance<GraphNode.PaimonDataFileNode>()
    private val manifests = graph.nodes.filterIsInstance<GraphNode.PaimonManifestNode>()

    /** The files a plan would read, named by the partition and key range the script gave them. */
    private fun read(plan: ScanPlan): Set<String> = files
        .filter { plan.files[it.id]?.fate == FileFate.WOULD_BE_READ }
        .map { "${it.partition!!.display} k=${it.keyMin!!.single().value}..${it.keyMax!!.single().value}" }
        .toSet()

    @Test
    fun `the prunable columns are the two partition keys and every column with bounds`() {
        val columns = prunableColumns(graph).associateBy { it.name }
        assertEquals(setOf("dt", "region", "k", "v"), columns.keys)
        assertEquals(listOf("identity"), columns.getValue("dt").transforms)
        assertEquals(listOf("identity"), columns.getValue("region").transforms)
        assertTrue(columns.getValue("dt").prunesManifests && columns.getValue("dt").prunesFiles)
        assertTrue(!columns.getValue("k").prunesManifests && columns.getValue("k").prunesFiles)
        assertEquals(IcebergType.DateType, columns.getValue("dt").type)
        assertEquals(IcebergType.IntType, columns.getValue("k").type)
    }

    /**
     * A date the first two commits never wrote: their manifests are ruled out by the partition
     * range, so their five files are never reached, and inside the third commit's manifest the
     * file in the other partition is ruled out by its own `dt` bound.
     */
    @Test
    fun `a partition predicate rules out manifests first and then files inside the one it keeps`() {
        val plan = evaluateScan(graph, listOf(ScanPredicate("dt", PredicateOp.EQ, "2024-03-07")))
        assertEquals(3, manifests.size)
        assertEquals(2, plan.skippedManifests, "the first two commits hold nothing on 2024-03-07")
        assertEquals(5, plan.unreachedFiles)
        assertEquals(1, plan.skippedFiles, "the 2024-03-05 file in the third manifest")
        assertEquals(setOf("dt=2024-03-07, region=eu k=9..9"), read(plan))
    }

    /** A predicate no partition turns on prunes no manifest and still eliminates most files. */
    @Test
    fun `a key predicate prunes files by their own bounds and no manifest`() {
        val plan = evaluateScan(graph, listOf(ScanPredicate("k", PredicateOp.GT, "8")))
        assertEquals(0, plan.skippedManifests)
        assertEquals(0, plan.unreachedFiles)
        assertEquals(
            setOf("dt=2024-03-07, region=eu k=9..9", "dt=2024-03-05, region=north-america k=10..10"),
            read(plan),
        )
        assertEquals(5, plan.skippedFiles)
    }

    /** The manifest whose recorded minimum is a partition none of its entries has still keeps the right files. */
    @Test
    fun `a string partition predicate reads every file that can hold the value and no other`() {
        val plan = evaluateScan(graph, listOf(ScanPredicate("region", PredicateOp.EQ, "north-america")))
        assertEquals(1, plan.skippedManifests, "the second commit wrote eu only")
        assertEquals(
            setOf(
                "dt=2024-03-05, region=north-america k=3..3",
                "dt=2024-03-05, region=north-america k=10..10",
                "dt=2024-03-06, region=north-america k=5..6",
            ),
            read(plan),
        )
    }

    /** Null counts are recorded, and a column with none records exactly that. */
    @Test
    fun `IS NULL on a column with no nulls skips every file, IS NOT NULL reads every file`() {
        val isNull = evaluateScan(graph, listOf(ScanPredicate("v", PredicateOp.IS_NULL)))
        assertEquals(7, isNull.skippedFiles)
        assertEquals(emptySet(), read(isNull))
        val notNull = evaluateScan(graph, listOf(ScanPredicate("v", PredicateOp.IS_NOT_NULL)))
        assertEquals(7, notNull.readFiles)
    }

    /**
     * The property that must hold whatever the predicate: a file holding a matching row is read.
     * Every key the script wrote, as an equality, against the file that holds it.
     */
    @Test
    fun `no file holding a matching key is ever pruned`() {
        (1..10).forEach { k ->
            val plan = evaluateScan(graph, listOf(ScanPredicate("k", PredicateOp.EQ, "$k")))
            val holders = files.filter { f -> (f.keyMin!!.single().value as Int) <= k && k <= (f.keyMax!!.single().value as Int) }
            assertTrue(holders.isNotEmpty(), "k=$k")
            holders.forEach { f ->
                assertEquals(FileFate.WOULD_BE_READ, plan.files[f.id]?.fate, "k=$k in ${f.partition!!.display}")
            }
        }
    }

    /**
     * `de` is under data evolution, where a file's own `b` bounds (1..2) describe values its
     * patch replaced with 11 and 22 — pruning on them would skip the file a read of `b = 11`
     * needs. Paimon 1.3.0+ consults no file's statistics on such a table (#6443), so the file
     * stage declines with the reason, and the manifest stage — partition ranges, which a patch
     * cannot change — is untouched.
     */
    @Test
    fun `a data-evolution table's file bounds are not consulted, and the reason is on the plan`() {
        val de = GraphLayoutService.layoutGraph(
            PaimonUnifiedTableModel(Paths.get(File(repoRoot, "example/paimon/db.db/de").absolutePath)),
            showRows = false,
        )
        val plan = evaluateScan(de, listOf(ScanPredicate("b", PredicateOp.EQ, "11")))
        val fates = de.nodes.filterIsInstance<GraphNode.PaimonDataFileNode>().map { plan.files[it.id]!!.fate }.toSet()
        assertEquals(setOf(FileFate.UNEVALUATED), fates, "no file skipped by a bound a patch may have replaced")
        assertTrue(plan.fileBoundsWithheld!!.contains("data evolution"))
        assertTrue(plan.files.values.all { r -> r.outcomes.all { it.effect == TermEffect.NOT_EVALUATED && it.reason == plan.fileBoundsWithheld } })
        assertEquals(0, plan.skippedManifests)
        assertEquals(null, evaluateScan(graph, listOf(ScanPredicate("k", PredicateOp.EQ, "1"))).fileBoundsWithheld, "pt is not under data evolution")
    }
}
