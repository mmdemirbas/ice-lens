package model

import service.GraphLayoutService
import java.io.File
import java.nio.file.Paths
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * File-level pruning: the stage that answers "why did my query read four hundred files".
 *
 * A manifest is ruled out by a partition summary, so only a partitioned column reaches it. A file
 * is ruled out by its own recorded `lower_bounds` / `upper_bounds`, which every column carries —
 * so the two stages prune on different things and the interesting cases are where they disagree.
 * Both fixtures used here are checked-in tables written by Iceberg, and every expected verdict is
 * derived from the bounds the file itself records rather than from what this evaluator produced.
 */
class FilePruningTest {

    private val repoRoot: File = generateSequence(File(".").absoluteFile) { it.parentFile }
        .first { File(it, "settings.gradle.kts").isFile }

    private fun graphFor(fixture: String): GraphModel {
        val dir = File(repoRoot, "example/iceberg/default/$fixture")
        assertTrue(dir.isDirectory, "fixture missing at $dir")
        return GraphLayoutService.layoutGraph(
            UnifiedTableModel(Paths.get(dir.absolutePath)), showRows = false,
        )
    }

    private fun GraphModel.files() = nodes.filterIsInstance<GraphNode.FileNode>()

    private fun GraphNode.FileNode.boundsOf(column: String): Pair<Any?, Any?>? =
        columnStats.firstOrNull { it.columnName == column }
            ?.let { it.lowerBound?.value to it.upperBound?.value }

    /**
     * The case the feature exists for: an unpartitioned table, where no predicate can rule out a
     * single manifest and file bounds still eliminate files.
     *
     * Before file-level pruning this table offered no filter columns at all — the panel said "this
     * table is not partitioned" and stopped, which is true about manifests and misleading about
     * the question the reader arrived with.
     */
    @Test
    fun `an unpartitioned table prunes no manifest and still prunes files`() {
        val graph = graphFor("test")
        assertEquals(1, graph.files().size, "the minimal fixture holds one data file")

        val columns = prunableColumns(graph)
        assertEquals(listOf("k", "v"), columns.map { it.name }, "every column with bounds is offered")
        assertTrue(columns.none { it.prunesManifests }, "nothing here is partitioned")
        assertTrue(columns.all { it.prunesFiles }, "and every column can still rule a file out")

        // k is recorded as 1 … 2 in the one file, so 5 is outside it.
        val plan = evaluateScan(graph, listOf(ScanPredicate("k", PredicateOp.EQ, "5")))
        assertEquals(0, plan.skippedManifests, "an unpartitioned table cannot skip a manifest")
        assertEquals(1, plan.skippedFiles)
        assertEquals(0, plan.readFiles)
    }

    /**
     * `parted` partitions `id` by `bucket[4]`, which is not order-preserving, so no range on `id`
     * can rule a manifest out — the manifest evaluator says so rather than guessing. The file
     * bounds are the plain source values, so the same predicate eliminates files.
     */
    @Test
    fun `a bucket-partitioned column prunes no manifest and still prunes files`() {
        val graph = graphFor("parted")
        val files = graph.files()
        assertEquals(4, files.size, "the partitioned fixture holds four data files")

        val predicate = ScanPredicate("id", PredicateOp.GT, "2")
        val plan = evaluateScan(graph, listOf(predicate))

        assertEquals(0, plan.skippedManifests, "bucket is not order-preserving, so no manifest goes")
        assertTrue(
            plan.manifests.values.all { it.isUnevaluated },
            "and the manifest stage says it could not evaluate, rather than 'would be read'",
        )

        // Expected purely from what each file records about id, not from what the evaluator said.
        val shouldSkip = files.filter { file ->
            val upper = file.boundsOf("id")?.second as? Int
            upper != null && upper <= 2
        }.map { it.id }.toSet()
        assertTrue(shouldSkip.isNotEmpty(), "the fixture should hold files entirely at or below id 2")
        assertTrue(shouldSkip.size < files.size, "and files above it, or this proves nothing")

        val skipped = plan.files.filterValues { it.fate == FileFate.SKIPPED }.keys
        assertEquals(shouldSkip, skipped)
        assertEquals(files.size - shouldSkip.size, plan.readFiles)
    }

    /**
     * A file inside a ruled-out manifest is not read, whatever its own bounds say — a scan never
     * opens the entry. Reporting it as `SKIPPED` would double-count it against the file stage and
     * credit the wrong term.
     */
    @Test
    fun `a file under a skipped manifest is not reached rather than skipped`() {
        val graph = graphFor("parted")
        // One day past the narrow manifest's range, so the day partition rules that manifest out.
        val plan = evaluateScan(graph, listOf(ScanPredicate("d", PredicateOp.GT, "2025-11-21")))
        assertTrue(plan.skippedManifests > 0, "this predicate should rule a manifest out")

        val skippedManifests = plan.manifests.filterValues { it.isSkipped }.keys
        val underSkipped = graph.edges
            .filter { it.affectsLayout && !it.isSibling && it.fromId in skippedManifests }
            .mapNotNull { edge -> graph.nodeById[edge.toId] as? GraphNode.FileNode }
            .map { it.id }
            .toSet()
        assertTrue(underSkipped.isNotEmpty(), "the skipped manifest should list files")

        underSkipped.forEach { fileId ->
            assertEquals(
                FileFate.NOT_REACHED, plan.files.getValue(fileId).fate,
                "$fileId sits under a manifest a scan ruled out",
            )
        }
    }

    @Test
    fun `every file lands in exactly one fate`() {
        val graph = graphFor("parted")
        val plan = evaluateScan(graph, listOf(ScanPredicate("d", PredicateOp.GT, "2025-11-21")))
        assertEquals(graph.files().size, plan.files.size, "every drawn file gets a verdict")
        assertEquals(
            plan.files.size,
            plan.readFiles + plan.skippedFiles + plan.unreachedFiles +
                plan.files.values.count { it.fate == FileFate.UNEVALUATED },
        )
    }

    /**
     * `evolved` widens `id` from `int` to `long` between manifests, so the literal is parsed
     * against each file's **own** manifest schema. A literal too large for the earlier type is not
     * an error and not a match: it is a term that could not be evaluated there.
     */
    @Test
    fun `a literal is read against the schema each file was written under`() {
        val graph = graphFor("evolved")
        val plan = evaluateScan(graph, listOf(ScanPredicate("id", PredicateOp.GT, "2500000000")))

        val byType = graph.files().associate { file ->
            file.id to file.columnStats.first { it.columnName == "id" }.type?.typeName
        }
        assertTrue(byType.values.contains("int") && byType.values.contains("long"), "evolved widens id")

        byType.forEach { (fileId, type) ->
            val outcome = plan.files.getValue(fileId).outcomes.single()
            if (type == "int") {
                assertEquals(
                    TermEffect.NOT_EVALUATED, outcome.effect,
                    "2500000000 is not an int, which is what id is in $fileId",
                )
            } else {
                assertTrue(
                    outcome.effect != TermEffect.NOT_EVALUATED,
                    "id is a long in $fileId, so the term should have been evaluated: ${outcome.reason}",
                )
            }
        }
    }

    /**
     * `evolved` adds columns partway through, so its earliest file records nothing about `label`.
     * That is not "would be read" and not a match — the file stage has to say it had nothing to
     * work with, or the reader reads a verdict that was never computed.
     */
    @Test
    fun `a column a file records nothing about is reported as not evaluated`() {
        val graph = graphFor("evolved")
        val plan = evaluateScan(graph, listOf(ScanPredicate("label", PredicateOp.EQ, "charlie")))

        val silent = graph.files().filter { file -> file.columnStats.none { it.columnName == "label" } }
        assertTrue(silent.isNotEmpty(), "the earliest file predates the label column")
        silent.forEach { file ->
            val result = plan.files.getValue(file.id)
            assertEquals(FileFate.UNEVALUATED, result.fate)
            assertTrue(
                result.outcomes.single().reason.contains("label"),
                "the reason should name the column it looked for: ${result.outcomes.single().reason}",
            )
        }
    }

    /**
     * The one term a manifest summary can never rule out and a file can.
     *
     * A summary records "contains a null somewhere"; a file records how many values it holds and
     * how many are null, so all-null is a fact. Built by hand because no checked-in fixture has an
     * all-null column — worth adding to `evolved` when it is next regenerated, since an added
     * column is all-null for every file written before it.
     */
    @Test
    fun `is not null eliminates a file whose column is entirely null`() {
        val allNull = ColumnStats(
            fieldId = 4, columnName = "label", type = IcebergType.StringType,
            lowerBound = null, upperBound = null,
            valueCount = 12, nullValueCount = 12, nanValueCount = null, columnSizeBytes = 40,
        )
        val someNull = allNull.copy(nullValueCount = 5)

        val gone = evaluateFilePruning(listOf(allNull), listOf(ScanPredicate("label", PredicateOp.IS_NOT_NULL)))
        assertEquals(FileFate.SKIPPED, gone.fate)
        assertTrue(gone.skippedBy!!.reason.contains("all 12"), gone.skippedBy!!.reason)

        val kept = evaluateFilePruning(listOf(someNull), listOf(ScanPredicate("label", PredicateOp.IS_NOT_NULL)))
        assertEquals(FileFate.WOULD_BE_READ, kept.fate)

        // And the mirror: a file recording no null at all holds nothing for `IS NULL`.
        val noNull = allNull.copy(nullValueCount = 0)
        assertEquals(
            FileFate.SKIPPED,
            evaluateFilePruning(listOf(noNull), listOf(ScanPredicate("label", PredicateOp.IS_NULL))).fate,
        )
    }

    @Test
    fun `an empty filter plans nothing`() {
        val plan = evaluateScan(graphFor("parted"), emptyList())
        assertTrue(plan.manifests.isEmpty() && plan.files.isEmpty())
    }
}
