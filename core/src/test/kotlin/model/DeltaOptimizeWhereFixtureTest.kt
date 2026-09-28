package model

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `OPTIMIZE … WHERE`, held to `dow`'s log: every commit against the plan at the version before it
 * under the predicate the commit records — read back from Catalyst's `toString`, which is what the
 * log holds — and the two runs that wrote no commit against a plan of nothing. The null partition
 * is the case that separates an exact SQL reading from a naive one: `p <> 'x'` leaves it out.
 */
class DeltaOptimizeWhereFixtureTest {

    private val dow = FixtureCatalog.deltaModel("dow")

    private fun where(text: String): DeltaOptimizeWhere =
        DeltaOptimizeWhere(text, assertIs<ScanFilterParse.Parsed>(parseScanFilter(text, sparkLiterals = true), text).filter)

    /** The partitions a plan considers, as `p/d` with `null` for a null value. */
    private fun partitionsOf(plan: DeltaOptimizePlan): Set<String> =
        plan.files.map { f -> "${f.add.partitionValues["p"]}/${f.add.partitionValues["d"]}" }.toSet()

    private fun recordedPredicate(commit: DeltaCommit): List<String> =
        (commit.commitInfo?.operationParameters?.get("predicate") as? JsonPrimitive)?.contentOrNull
            ?.let { Json.parseToJsonElement(it).jsonArray.map { e -> e.jsonPrimitive.content } }
            .orEmpty()

    @Test
    fun `every OPTIMIZE on dow removes what the plan under its recorded predicate says`() {
        val runs = dow.commits.filter { it.commitInfo?.operation == "OPTIMIZE" }
        assertEquals(listOf(10L, 11L, 12L, 13L), runs.map { it.version })
        for (commit in runs) {
            val at = "dow v${commit.version}"
            val metrics = commit.commitInfo!!.operationMetrics!!
            val plan = assertNotNull(dow.planOptimizeBefore(commit), "$at: ${recordedPredicate(commit)} did not read back")
            assertNull(plan.refusal, at)
            assertEquals(commit.removes.map { it.key }.toSet(), plan.removed.map { it.add.key }.toSet(), at)
            assertEquals(metrics.getValue("numRemovedBytes").toLong(), plan.removedBytes, at)
            assertTrue(metrics.getValue("numAddedFiles").toInt() in plan.filesAdded, "$at: ${metrics["numAddedFiles"]} not in ${plan.filesAdded}")
            // v10's plan leaves the y vector outside the predicate and counts 0; v12's takes it and counts 1.
            assertEquals(metrics.getValue("numDeletionVectorsRemoved").toInt(), plan.deletionVectorsCounted, at)
        }
        val considered = runs.associate { it.version to partitionsOf(dow.planOptimizeBefore(it)!!) }
        assertEquals(setOf("x/2024-03-05", "x/2024-03-06"), considered[10L])
        assertEquals(setOf("x/2024-03-06", "null/2024-03-06"), considered[11L])
        assertEquals(setOf("y/2024-03-05"), considered[12L])
        assertEquals(setOf("x/2024-03-05", "x/2024-03-06", "null/2024-03-06"), considered[13L])
    }

    @Test
    fun `the recorded predicates read back as the filters the script typed`() {
        val recorded = dow.commits.filter { it.commitInfo?.operation == "OPTIMIZE" }.associate { it.version to recordedPredicate(it).single() }
        assertEquals(mapOf(
            10L to "('p = x)",
            11L to "('d >= 2024-03-06)",
            12L to "NOT ('p = x)",
            13L to "(NOT 'p LIKE %y OR isnull('p))",
        ), recorded)
        fun term(column: String, op: PredicateOp, literal: String = "") = ScanFilter.Term(ScanPredicate(column, op, literal))
        assertEquals(term("p", PredicateOp.EQ, "x"), parseCatalystPredicate(recorded.getValue(10L)))
        assertEquals(term("d", PredicateOp.GTE, "2024-03-06"), parseCatalystPredicate(recorded.getValue(11L)))
        assertEquals(ScanFilter.Not(term("p", PredicateOp.EQ, "x")), parseCatalystPredicate(recorded.getValue(12L)))
        assertEquals(
            ScanFilter.Or(listOf(ScanFilter.Not(term("p", PredicateOp.LIKE, "%y")), term("p", PredicateOp.IS_NULL))),
            parseCatalystPredicate(recorded.getValue(13L)),
        )
        // The typed clause and the recorded text select the same files at every run.
        val typed = mapOf(10L to "p = 'x'", 11L to "d >= '2024-03-06'", 12L to "p <> 'x'", 13L to "NOT (p LIKE '%y') OR p IS NULL")
        for ((v, text) in typed) {
            val zOrder = if (v >= 12L) listOf("a") else emptyList()
            assertEquals(
                partitionsOf(dow.planOptimizeBefore(dow.commits.single { it.version == v })!!),
                partitionsOf(dow.planOptimize(zOrder, where(text), version = v - 1).getOrThrow()),
                text,
            )
        }
    }

    @Test
    fun `a text Catalyst printed ambiguously, or in a shape not read here, is not read back`() {
        assertNull(parseCatalystPredicate("('p = hello world)"), "a literal with a space")
        assertNull(parseCatalystPredicate("('p RLIKE x)"))
        assertNull(parseCatalystPredicate("(upper('p) = Y)"))
        assertNull(parseCatalystPredicate("('p = x"), "unclosed")
        assertNull(parseCatalystPredicate("('p = null)"), "a comparison with null is null, not a filter")
        assertEquals(
            ScanFilter.Or(listOf(ScanFilter.Term(ScanPredicate("p", PredicateOp.EQ, "x")), ScanFilter.Term(ScanPredicate("p", PredicateOp.EQ, "y")))),
            parseCatalystPredicate("'p IN (x,y)"),
        )
        assertEquals(ScanFilter.Term(ScanPredicate("d", PredicateOp.LT, "2024-03-06")), parseCatalystPredicate("(2024-03-06 > 'd)"), "flipped")
        assertEquals(ScanFilter.Term(ScanPredicate("p", PredicateOp.IS_NULL)), parseCatalystPredicate("('p <=> null)"))
        assertEquals(ScanFilter.Term(ScanPredicate("p", PredicateOp.IS_NOT_NULL)), parseCatalystPredicate("isnotnull('p)"))
    }

    @Test
    fun `the two runs that wrote nothing plan nothing`() {
        // The script's second `p = 'x'` and its `p = 'q'` ran at version 10.
        val again = dow.planOptimize(where = where("p = 'x'"), version = 10).getOrThrow()
        assertFalse(again.commits)
        assertEquals(2, again.leftAlone.size)
        assertEquals("Writes nothing: 2 candidates, each alone in its bin.", again.headline)
        val none = dow.planOptimize(where = where("p = 'q'"), version = 10).getOrThrow()
        assertFalse(none.commits)
        assertTrue(none.files.isEmpty())
        assertEquals(6, none.outsideWhere)
        assertEquals("Writes nothing: no live file is in a partition WHERE p = 'q' matches.", none.headline)
    }

    @Test
    fun `a comparison with a null partition value is false, and so is its negation`() {
        val at = 11L
        fun kept(text: String) = partitionsOf(dow.planOptimize(where = where(text), version = at).getOrThrow())
        assertEquals(setOf("y/2024-03-05"), kept("p <> 'x'"))
        assertEquals(setOf("y/2024-03-05"), kept("NOT (p = 'x')"))
        assertEquals(setOf("y/2024-03-05"), kept("p NOT IN ('x')"))
        assertEquals(setOf("null/2024-03-06"), kept("p IS NULL"))
        assertEquals(setOf("y/2024-03-05", "null/2024-03-06"), kept("p IS NULL OR p <> 'x'"))
        assertEquals(setOf("x/2024-03-06", "null/2024-03-06"), kept("d > DATE '2024-03-05'"))
        assertEquals(setOf("x/2024-03-05", "x/2024-03-06"), kept("p LIKE 'x'"))
        assertEquals(setOf("y/2024-03-05"), kept("p LIKE '_'  AND NOT p LIKE 'x%'"))
        // A literal the column cannot read is a cast to null in Spark: nothing matches either way.
        assertEquals(emptySet(), kept("d = 'soon'"))
        assertEquals(emptySet(), kept("d <> 'soon'"))
        // The resolver ignores case, as the scratch run on a copy showed: `P = 'y'` considered one file.
        assertEquals(1, dow.planOptimize(where = where("P = 'y'")).getOrThrow().files.size)
    }

    @Test
    fun `a WHERE on a non-partition column, and any WHERE on a clustered table, is refused in the command's order`() {
        assertEquals(
            "Predicate references non-partition column 'a'. Only the partition columns may be referenced: [p, d]",
            dow.planOptimize(where = where("p = 'x' AND a = 1")).getOrThrow().refusal,
        )
        // The predicate is verified before the ZORDER BY columns.
        assertTrue(dow.planOptimize(listOf("p"), where("a = 1")).getOrThrow().refusal!!.startsWith("Predicate references"))
        val dcl = FixtureCatalog.deltaModel("dcl")
        assertEquals(
            "OPTIMIZE command for Delta table with clustering doesn't support partition predicates. Please remove the predicates: a = 1.",
            dcl.planOptimize(listOf("b"), where("a = 1")).getOrThrow().refusal,
        )
    }

    @Test
    fun `a clause Spark would read differently is refused where it is typed`() {
        val date = assertIs<ScanFilterParse.Failed>(parseScanFilter("d >= 2024-03-06", sparkLiterals = true))
        assertTrue("quote it: '2024-03-06'" in date.message, date.message)
        assertEquals(5, date.at)
        assertIs<ScanFilterParse.Failed>(parseScanFilter("p = x", sparkLiterals = true))
        assertEquals(
            ScanFilter.Term(ScanPredicate("n", PredicateOp.GT, "-5")),
            (parseScanFilter("n > -5", sparkLiterals = true) as ScanFilterParse.Parsed).filter,
        )
        assertEquals(
            ScanFilter.Term(ScanPredicate("d", PredicateOp.EQ, "2024-03-06")),
            (parseScanFilter("d = DATE '2024-03-06'", sparkLiterals = true) as ScanFilterParse.Parsed).filter,
        )
        // The pruning panel's clause keeps its bare values.
        assertIs<ScanFilterParse.Parsed>(parseScanFilter("name = alpha"))
    }

    @Test
    fun `LIKE matches the way Spark's default escape reads a pattern`() {
        assertTrue(sqlLikeMatches("xy", "%y"))
        assertFalse(sqlLikeMatches("yx", "%y"))
        assertTrue(sqlLikeMatches("ab", "a_"))
        assertFalse(sqlLikeMatches("abc", "a_"))
        assertTrue(sqlLikeMatches("50%", "50\\%"))
        assertFalse(sqlLikeMatches("500", "50\\%"))
        assertTrue(sqlLikeMatches("a.b", "a.b"), "a regex character is literal")
        assertFalse(sqlLikeMatches("axb", "a.b"))
        assertTrue(sqlLikeMatches("line\nbreak", "line%"))
    }
}
