package model

import service.GraphLayoutService
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * A file's bloom-filter index in the scan plan, held to what Paimon does with it.
 *
 * Two oracles from `docs/fixtures/paimon-scan-plans.scala`, run 2026-09-14 on the 1.3 jar: the
 * files each plan opens, and — for every file the plan kept — whether its split is read raw and
 * what `FileIndexPredicate` over its index (embedded, or the `.index` file beside it) answers,
 * which is the question `RawFileSplitRead` asks before opening the file. Together they say which
 * files a read actually reads rows from: the plan's files, less those a raw read's index rules
 * out. `fa` is the append table, where the embedded index is tested at planning and the index
 * file at read; `fi` is the primary-key table, where neither is tested at planning and the read
 * consults an index only on a split holding one file — which its two small files are not, until
 * the key stage leaves one alone.
 */
class PaimonFileIndexPruningTest {

    private fun graphOf(fixture: String): GraphModel = GraphLayoutService.layoutGraph(FixtureCatalog.paimonModel(fixture), showRows = false)

    private val fa1 = "data-609c9615-ab32-492a-97ad-877b0be8568a-0.parquet"   // k 1..3, .index beside it
    private val fa2 = "data-bb8cf341-0c49-40e3-a995-5f9fab42118f-0.parquet"   // k 4, 6, index embedded
    private val fi1 = "data-c207bd15-64eb-4893-a09f-c6ef8e5d7693-0.parquet"   // k 1..3, .index beside it
    private val fi2 = "data-f13e0a5f-77b4-4f0f-909a-bdc2152578d2-0.parquet"   // k 4..5, index embedded
    private val ft1 = "data-831f46cf-d7a2-4d48-a916-d55bc4e917bf-0.parquet"   // three rows, temporal bloom filters embedded
    private val fb1 = "data-78cf96d7-9512-4747-8693-dbc3c5d6ad40-0.parquet"   // red, green, red, null; n 1..4; bitmap embedded
    private val fb2 = "data-0601ba10-ed0a-4763-b7de-f03655fc7c77-0.parquet"   // red, red; n 5..6; .index beside it
    private val fb3 = "data-02d4b3e1-d6fa-4f0b-9a20-3932c2cf06d4-0.parquet"   // null, null; n 7..8; .index beside it
    private val fbs1 = "data-a6bfbe8c-557a-4b07-a514-e33bbf1aff52-0.parquet"  // n -5, 3, 10, null; bsi embedded
    private val fbs2 = "data-9aec0cc6-8d86-462b-832c-701daf318d13-0.parquet"  // a thousand rows, n 100..1099; .index beside it
    private val fbs3 = "data-93aa7e5d-3634-4ce3-a95a-5bfcfb8e8e59-0.parquet"  // every row 7; .index beside it
    private val fbs4 = "data-45f79f1b-a157-4ecb-9a2f-bf4fada5db8c-0.parquet"  // every value null; .index beside it
    private val frb1 = "data-adebd5bf-cf86-44c2-af1b-75073ff05533-0.parquet"  // n -5, 3, 10, null, 3; s alpha, bravo, charlie, null, bravo; range bitmap embedded
    private val frb2 = "data-57249fd9-5816-4bde-8307-55b7c7b3b8c1-0.parquet"  // a thousand rows, n 100..1099, s v0000..v0999; .index beside it
    private val frb3 = "data-62d80f60-80d2-4d8d-bc0a-c5a3427edd18-0.parquet"  // every row 7 / 'seven' / true; .index beside it
    private val frb4 = "data-e26ca310-b75a-42d9-805b-e90652db74e0-0.parquet"  // every value null, three rows; .index beside it

    /** [planned] is what the plan opened; [rawSkipped] the planned files a raw read's index then ruled out; [merged] the planned files read through a merge, index unconsulted. */
    private class Case(val fixture: String, val filter: String, val planned: Set<String>, val rawSkipped: Set<String> = emptySet(), val merged: Set<String> = emptySet())

    private val cases = listOf(
        Case("fa", "v = 'dog'", setOf(fa1), rawSkipped = setOf(fa1)),
        Case("fa", "k = 5", emptySet()),
        Case("fa", "v = 'delta'", setOf(fa1, fa2), rawSkipped = setOf(fa1)),
        Case("fa", "k = 2", setOf(fa1)),
        Case("fa", "v = 'the quick brown fox jumps over the lazy dog'", setOf(fa1)),
        Case("fa", "v = 'bob'", setOf(fa1), rawSkipped = setOf(fa1)),
        Case("fa", "k = 5 AND v = 'delta'", emptySet()),
        Case("fi", "v = 'dog'", setOf(fi1, fi2), merged = setOf(fi1, fi2)),
        Case("fi", "k = 4 AND v = 'dog'", setOf(fi2), rawSkipped = setOf(fi2)),
        Case("fi", "k = 2 AND v = 'bob'", setOf(fi1), rawSkipped = setOf(fi1)),
        Case("fi", "v = 'beta'", setOf(fi1, fi2), merged = setOf(fi1, fi2)),
        // ft: the temporal hashes. The two negatives inside the file's bounds are what the index alone rules out.
        Case("ft", "ts = '2024-03-05 10:00:00.123456'", setOf(ft1)),
        Case("ft", "ts = '2024-03-06 11:30:00'", setOf(ft1)),
        Case("ft", "ts = '2024-03-07 00:00:00.000001'", setOf(ft1)),
        Case("ft", "ts = '2024-03-07 00:00:00'", emptySet()),
        Case("ft", "lz = '2024-03-05 10:00:00.123456'", setOf(ft1)),
        Case("ft", "lz = '2024-03-06T11:30:00Z'", setOf(ft1)),
        Case("ft", "lz = '2024-03-07 00:00:00.000001'", setOf(ft1)),
        Case("ft", "lz = '2024-03-06T11:30:00.001Z'", emptySet()),
        Case("ft", "d = '2024-03-05'", setOf(ft1)),
        Case("ft", "d = '2024-03-06'", setOf(ft1)),
        Case("ft", "d = '2024-03-07'", setOf(ft1)),
        // fb: the bitmap index. The plan settles most negatives by the statistics first — an all-null
        // column matches no value and no `<>`, bounds that meet settle a `<>` — and the dictionary is
        // what rules `orange` out of file 1, whose bounds are green..red.
        Case("fb", "c = 'red'", setOf(fb1, fb2)),
        Case("fb", "c = 'green'", setOf(fb1)),
        Case("fb", "c = 'orange'", emptySet()),
        Case("fb", "c <> 'red'", setOf(fb1)),
        Case("fb", "c IS NULL", setOf(fb1, fb3)),
        Case("fb", "c IS NOT NULL", setOf(fb1, fb2)),
        Case("fb", "n = 5", setOf(fb2)),
        Case("fb", "n = 9", emptySet()),
        Case("fb", "n IN (2, 6)", setOf(fb1, fb2)),
        Case("fb", "n = 5 AND c = 'green'", emptySet()),
        Case("fb", "n <> 7", setOf(fb1, fb2, fb3)),
        // fbs: the bit-sliced index. File 1's is embedded, so the plan itself skips it where its
        // slices rule the rows out — `n BETWEEN 4 AND 6` inside its -5..10 bounds, `n = 3 AND
        // amt = 99.99` whose two terms' rows are different rows — and files 2..4 are pruned by
        // the statistics alone at plan time, their `.index` files being the read's.
        Case("fbs", "n < 0", setOf(fbs1)),
        Case("fbs", "n BETWEEN 4 AND 6", emptySet()),
        Case("fbs", "n IN (4, 5, 6)", emptySet()),
        Case("fbs", "n BETWEEN 4 AND 8", setOf(fbs3)),
        Case("fbs", "n <> 7", setOf(fbs1, fbs2)),
        Case("fbs", "n IS NULL", setOf(fbs1, fbs4)),
        Case("fbs", "amt BETWEEN 1 AND 50", setOf(fbs2, fbs3)),
        Case("fbs", "d BETWEEN '2024-03-02' AND '2024-03-04'", emptySet()),
        Case("fbs", "ts BETWEEN '2024-03-01 10:00:00.000002' AND '2024-03-01 10:00:00.000003'", emptySet()),
        Case("fbs", "n = 3 AND amt = 99.99", emptySet()),
        Case("fbs", "n = 3 OR amt = 99.99", setOf(fbs1)),
        // frb: the range bitmap, on the types the bsi refuses too. File 1's is embedded, so the
        // plan skips it where the dictionary and the slices do; file 3's one value and file 4's
        // nulls are the statistics' skips, and `b = false` on file 3 is the index's own.
        Case("frb", "n BETWEEN 4 AND 6", emptySet()),
        Case("frb", "s > 'bravo'", setOf(frb1, frb2, frb3)),
        Case("frb", "s BETWEEN 'b' AND 'c'", setOf(frb1)),
        Case("frb", "x < 0", setOf(frb1, frb2)),
        Case("frb", "b = false", setOf(frb1, frb2)),
        Case("frb", "n = 3 AND s = 'charlie'", emptySet()),
        Case("frb", "n <> 7", setOf(frb1, frb2)),
        Case("frb", "s IS NULL", setOf(frb1, frb4)),
    )

    private fun fileNodes(graph: GraphModel) = graph.nodes.filterIsInstance<GraphNode.PaimonDataFileNode>()
        .filter { it.operationKind == PaimonEntryKind.ADD }.associateBy { it.entry.file!!.fileName!! }

    @Test
    fun `every file a read reads rows from is would-be-read, and every file the index rules out is skipped where Paimon skips it`() {
        val graphs = cases.map { it.fixture }.distinct().associateWith { graphOf(it) }
        for (case in cases) {
            val graph = graphs.getValue(case.fixture)
            val plan = evaluateScan(graph, (parseScanFilter(case.filter) as ScanFilterParse.Parsed).filter)
            val nodes = fileNodes(graph)
            assertEquals(when (case.fixture) { "ft" -> 1; "fb" -> 3; "fbs", "frb" -> 4; else -> 2 }, nodes.size, case.fixture)
            for ((name, node) in nodes) {
                val result = plan.files.getValue(node.id)
                val label = "${case.fixture}: ${case.filter} — $name: $result"
                when {
                    name !in case.planned -> assertEquals(FileFate.SKIPPED, result.fate, label)
                    name in case.rawSkipped -> {
                        assertEquals(FileFate.SKIPPED, result.fate, label)
                        assertTrue(result.note.orEmpty().contains("when read") == (node.entry.file?.embeddedFileIndex == null || case.fixture == "fi"), label)
                    }
                    else -> {
                        assertTrue(result.fate == FileFate.WOULD_BE_READ || result.fate == FileFate.UNEVALUATED, label)
                        if (name in case.merged) assertTrue(result.note.orEmpty().contains("not consulted"), label)
                    }
                }
            }
        }
    }

    /**
     * The bitmap's own verdicts through the pruning path, where the statistics decide nothing:
     * `c <> 'red'` on file 2 is settled by bounds that meet, so the term is asked of the index only
     * where the bounds could not answer — `c = 'orange'` on file 1, and the reason says so.
     */
    @Test
    fun `a bitmap index rules a value out by its dictionary, and a not-equal out only when every row holds the value`() {
        val graph = graphOf("fb")
        val nodes = fileNodes(graph)
        fun outcome(filter: String, file: String) = evaluateScan(graph, (parseScanFilter(filter) as ScanFilterParse.Parsed).filter)
            .files.getValue(nodes.getValue(file).id)
        val orange = outcome("c = 'orange'", fb1)
        assertEquals(FileFate.SKIPPED, orange.fate)
        assertTrue(orange.byIndex, "$orange")
        assertTrue(orange.outcomes.single().reason.contains("bitmap index (embedded in the entry, tested when the scan plans) lists no 'orange'"), orange.outcomes.single().reason)
        val red = outcome("c = 'red'", fb1)
        assertEquals(FileFate.WOULD_BE_READ, red.fate)
        assertTrue(red.outcomes.single().reason.contains("inside"), "the bounds answered first, and the index is not asked after them: ${red.outcomes.single().reason}")
        // File 3 is all null: `=` and `<>` are both settled by the statistics before any index is asked.
        val nullNotEq = outcome("c <> 'red'", fb3)
        assertEquals(FileFate.SKIPPED, nullNotEq.fate)
        assertFalse(nullNotEq.byIndex)
        assertTrue(nullNotEq.outcomes.single().reason.contains("null count is its row count"), nullNotEq.outcomes.single().reason)
        assertEquals(FileFate.SKIPPED, outcome("c = 'red'", fb3).fate)
        assertEquals(FileFate.SKIPPED, outcome("c < 'zzz'", fb3).fate)
        // n = 9 on file 2: inside no bounds anywhere, so the statistics settle it; n = 6 AND c = 'green' is a `.index` skip — the read's, not the plan's.
        val readSkip = outcome("n = 6 AND c = 'green'", fb2)
        assertEquals(FileFate.SKIPPED, readSkip.fate)
        assertFalse(readSkip.byIndex, "bounds that meet settle `c = 'green'` on an all-red file: $readSkip")
    }

    /**
     * The bit-sliced index through the pruning path, and the fold across terms that only it
     * needs: `n >= 4` keeps a row of file 1 and `n <= 6` keeps two, neither ruling the file out,
     * and the rows are different rows — which is the skip `FileIndexPredicate` finds by and-ing
     * the two bitmaps, and the one no per-term verdict can. The same across columns.
     */
    @Test
    fun `a bit-sliced index answers a comparison per row, and the terms' rows are met before the file is kept`() {
        val graph = graphOf("fbs")
        val nodes = fileNodes(graph)
        fun outcome(filter: String, file: String) = evaluateScan(graph, (parseScanFilter(filter) as ScanFilterParse.Parsed).filter)
            .files.getValue(nodes.getValue(file).id)
        val negative = outcome("n < 0", fbs1)
        assertEquals(FileFate.WOULD_BE_READ, negative.fate)
        assertTrue(negative.outcomes.single().reason.contains("reaches below"), "the bounds answered first, and the index is not asked after them: ${negative.outcomes.single().reason}")
        val gap = outcome("n BETWEEN 4 AND 6", fbs1)
        assertEquals(FileFate.SKIPPED, gap.fate)
        assertTrue(gap.byIndex, "$gap")
        assertTrue(gap.outcomes.none { it.effect == TermEffect.SKIPS }, "no term alone: $gap")
        assertTrue(gap.note.orEmpty().contains("meet in none"), "$gap")
        assertTrue(gap.outcomes.any { it.reason.contains("has 1 of its 4 rows >= '4'") } && gap.outcomes.any { it.reason.contains("has 2 of its 4 rows <= '6'") }, "$gap")
        val across = outcome("n = 3 AND amt = 99.99", fbs1)
        assertEquals(FileFate.SKIPPED, across.fate)
        assertTrue(across.byIndex && across.note.orEmpty().contains("meet in none"), "$across")
        assertEquals(FileFate.WOULD_BE_READ, outcome("n = 3 OR amt = 99.99", fbs1).fate)
        // The microsecond gap inside file 1's ts bounds is the slices' own skip — a millisecond
        // mapping would put both literals and both rows in one millisecond — and file 1's index
        // is embedded, so it is the plan's skip and not the read's.
        val micro = outcome("ts BETWEEN '2024-03-01 10:00:00.000002' AND '2024-03-01 10:00:00.000003'", fbs1)
        assertEquals(FileFate.SKIPPED, micro.fate)
        assertTrue(micro.byIndex && micro.note.orEmpty().contains("meet in none"), "$micro")
        assertTrue(micro.note.orEmpty().contains("when read").not(), "file 1's index is embedded and tested at plan time: ${micro.note}")
        // File 3, every row 7: `n <> 7` is settled by bounds that meet; `amt <> 7.00` on the .index is the slices' own
        // answer — every non-null row is 7.00 — and the read's, since the file's index is beside it.
        val allSeven = outcome("amt <> 7.00", fbs3)
        assertEquals(FileFate.SKIPPED, allSeven.fate)
        assertFalse(allSeven.byIndex, "bounds that meet settle it before the index is asked: $allSeven")
        // File 4, every value null: `n IS NOT NULL` is the statistics' (null count is the row count), `n IS NULL` keeps it.
        assertEquals(FileFate.SKIPPED, outcome("n IS NOT NULL", fbs4).fate)
        assertEquals(FileFate.WOULD_BE_READ, outcome("n IS NULL", fbs4).fate)
    }

    /**
     * The range bitmap through the pruning path: a string order and a float order the bounds keep
     * inside a file's range, answered per row and folded across the terms; a key the dictionary
     * lacks inside the bounds, which is the index's own skip — at read, since file 2's index is
     * beside it; and a boolean, whose bounds settle a term before the index is asked.
     */
    @Test
    fun `a range bitmap answers a comparison per row on a string, a double and a boolean`() {
        val graph = graphOf("frb")
        val nodes = fileNodes(graph)
        fun outcome(filter: String, file: String) = evaluateScan(graph, (parseScanFilter(filter) as ScanFilterParse.Parsed).filter)
            .files.getValue(nodes.getValue(file).id)
        val above = outcome("s > 'bravo'", frb1)
        assertEquals(FileFate.WOULD_BE_READ, above.fate)
        assertTrue(above.outcomes.single().reason.contains("reaches above"), "the bounds answered first, and the index is not asked after them: ${above.outcomes.single().reason}")
        val gap = outcome("n BETWEEN 4 AND 6", frb1)
        assertEquals(FileFate.SKIPPED, gap.fate)
        assertTrue(gap.byIndex && gap.note.orEmpty().contains("meet in none"), "$gap")
        assertTrue(gap.outcomes.none { it.effect == TermEffect.SKIPS }, "no term alone: $gap")
        assertTrue(gap.outcomes.any { it.reason.contains("range bitmap (embedded in the entry) has 1 of its 5 rows >= '4'") } && gap.outcomes.any { it.reason.contains("has 3 of its 5 rows <= '6'") }, "$gap")
        val prefix = outcome("s BETWEEN 'b' AND 'c'", frb1)
        assertEquals(FileFate.WOULD_BE_READ, prefix.fate)
        assertFalse(prefix.byIndex, "the two terms' rows meet in bravo's two: $prefix")
        assertEquals(FileFate.WOULD_BE_READ, outcome("x < 0", frb1).fate)
        val across = outcome("n = 3 AND s = 'charlie'", frb1)
        assertEquals(FileFate.SKIPPED, across.fate)
        assertTrue(across.byIndex && across.note.orEmpty().contains("meet in none"), "$across")
        assertTrue(across.outcomes.any { it.reason.contains("has 2 of its 5 rows = '3'") } && across.outcomes.any { it.reason.contains("has 1 of its 5 rows = 'charlie'") }, "$across")
        // File 3, every row true: a boolean has bounds too, and true..true settles `b = false` before the index is asked.
        val allTrue = outcome("b = false", frb3)
        assertEquals(FileFate.SKIPPED, allTrue.fate)
        assertFalse(allTrue.byIndex, "$allTrue")
        assertEquals(FileFate.WOULD_BE_READ, outcome("b = false", frb2).fate)
        // File 2, x at eighths from -10: 0.3 sits inside the bounds and the dictionary has no such key —
        // the index's own skip, and the read's, since the file's index is beside it. A string between
        // two entries the same, and `>=` on it is answered from the code it would take.
        val eighths = outcome("x = 0.3", frb2)
        assertEquals(FileFate.SKIPPED, eighths.fate)
        assertTrue(eighths.byIndex && eighths.note.orEmpty().contains("when read"), "$eighths")
        assertTrue(eighths.outcomes.single().reason.contains("range bitmap (in the .index beside it, opened by the read) has no row = '0.3' among its 1000"), eighths.outcomes.single().reason)
        assertEquals(FileFate.WOULD_BE_READ, outcome("x = 0.375", frb2).fate)
        val between = outcome("s = 'v0500a'", frb2)
        assertEquals(FileFate.SKIPPED, between.fate)
        assertTrue(between.byIndex && between.outcomes.single().reason.contains("has no row = 'v0500a' among its 1000"), "$between")
        assertEquals(FileFate.WOULD_BE_READ, outcome("s >= 'v0500a'", frb2).fate)
        assertEquals(FileFate.SKIPPED, outcome("s >= 'v0500a' AND s < 'v0501'", frb2).fate, "499 rows at or above v0500a, none below v0501")
        // File 4, every value null: `IS NOT NULL` is the statistics' skip, `IS NULL` keeps it.
        assertEquals(FileFate.SKIPPED, outcome("s IS NOT NULL", frb4).fate)
        assertEquals(FileFate.WOULD_BE_READ, outcome("s IS NULL", frb4).fate)
    }

    @Test
    fun `the reason names the filter, where it sits and when it is asked`() {
        val graph = graphOf("fa")
        val nodes = fileNodes(graph)
        val plan = evaluateScan(graph, (parseScanFilter("v = 'dog'") as ScanFilterParse.Parsed).filter)
        val embedded = plan.files.getValue(nodes.getValue(fa2).id).outcomes.single()
        assertEquals(TermEffect.SKIPS, embedded.effect)
        assertEquals("v's bloom filter (embedded in the entry, tested when the scan plans) has no 'dog'", embedded.reason)
        val beside = plan.files.getValue(nodes.getValue(fa1).id).outcomes.single()
        assertEquals("v's bloom filter (in the .index beside it, opened by the read) has no 'dog'", beside.reason)
        assertEquals("skipped when read, not when planned: the plan lists the file, the read opens its index and none of its rows", plan.files.getValue(nodes.getValue(fa1).id).note)
        assertEquals(2, plan.indexSkippedFiles)
        // `k = 5`: the first file's bounds (1..3) rule it out, the second's (4..6) do not and its filter does — one of each.
        val k5 = evaluateScan(graph, (parseScanFilter("k = 5") as ScanFilterParse.Parsed).filter)
        assertEquals(2, k5.skippedFiles)
        assertEquals(1, k5.indexSkippedFiles)
        // A value the bounds keep and the filter may hold is read, with the bounds' own reason left in place.
        val kept = evaluateScan(graph, (parseScanFilter("v = 'delta'") as ScanFilterParse.Parsed).filter).files.getValue(nodes.getValue(fa2).id)
        assertEquals(FileFate.WOULD_BE_READ, kept.fate)
        assertEquals(TermEffect.KEEPS, kept.outcomes.single().effect)
    }

    @Test
    fun `a primary-key table's file reads raw only when the plan leaves it alone in its bucket`() {
        val graph = graphOf("fi")
        val rule = paimonScanRule(graph)!!
        val nodes = fileNodes(graph).values.toList()
        // Two level-0 files with disjoint key ranges are two sections in one split, so neither is raw...
        assertEquals(emptySet(), paimonRawConvertible(nodes, rule))
        // ...and each is, on its own.
        for (node in nodes) assertEquals(setOf(node.id), paimonRawConvertible(listOf(node), rule))
        // A split the size option cannot hold both sections of reads each raw: the one-file bins.
        assertEquals(nodes.map { it.id }.toSet(), paimonRawConvertible(nodes, rule.copy(splitTargetBytes = 1, openFileCostBytes = 1)))
    }
}
