package model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The OPTIMIZE plan held to the one compaction the fixtures ran — `dopt` is `dvac` copied before
 * it, and `dvac`'s version 7 is the `OPTIMIZE` — and to the packing rules on inputs built here,
 * since one engine run exercises one bin.
 */
class DeltaOptimizePlanFixtureTest {

    @Test
    fun `dopt's plan removes what dvac's OPTIMIZE removed, with the figures it recorded`() {
        val plan = FixtureCatalog.deltaModel("dopt").planOptimize().getOrThrow()
        val commit = FixtureCatalog.deltaModel("dvac").commitByVersion.getValue(7)
        assertEquals("OPTIMIZE", commit.commitInfo?.operation)
        assertEquals(commit.removes.map { it.path }.toSet(), plan.removed.map { it.add.path }.toSet())
        val metrics = commit.commitInfo!!.operationMetrics!!
        assertEquals(metrics["numRemovedFiles"]!!.toInt(), plan.removed.size)
        assertEquals(metrics["numRemovedBytes"]!!.toLong(), plan.removedBytes)
        assertEquals(metrics["numAddedFiles"]!!.toInt(), plan.filesAdded)
        assertTrue(plan.commits)
    }

    @Test
    fun `after the OPTIMIZE one file is left, and a lone candidate is not rewritten`() {
        val plan = FixtureCatalog.deltaModel("dvac").planOptimize().getOrThrow()
        assertEquals(1, plan.files.size)
        assertEquals(1, plan.leftAlone.size, "small, so a candidate — and alone in its bin")
        assertFalse(plan.commits)
    }

    private fun add(path: String, size: Long, part: String = "x", records: Long? = 10, marked: Long? = null) = DeltaAddFile(
        path = path,
        partitionValues = mapOf("p" to part),
        size = size,
        stats = records?.let { """{"numRecords":$it}""" },
        deletionVector = marked?.let { DeltaDeletionVector(storageType = "u", pathOrInlineDv = "ab^-aqEH.-t@S}K{vb[*k^", offset = 1, sizeInBytes = 34, cardinality = it) },
    )

    @Test
    fun `candidates pack smallest first per partition, a file that would overflow the bin opens the next`() {
        val options = DeltaOptimizeOptions(minFileSize = 100, maxFileSize = 100)
        val plan = planDeltaOptimize(1, listOf(add("a", 60), add("b", 30), add("c", 50), add("d", 20), add("e", 10, part = "y"), add("big", 500)), options)
        // x: 20, 30, 50 fit 100; 60 opens a bin of its own. y: one file. big is no candidate.
        assertEquals(listOf(listOf("d", "b", "c"), listOf("a"), listOf("e")), plan.bins.map { b -> b.files.map { it.add.path } })
        assertEquals(listOf("d", "b", "c"), plan.removed.map { it.add.path })
        assertEquals(setOf("a", "e"), plan.leftAlone.map { it.add.path }.toSet())
        assertEquals(null, plan.files.single { it.add.path == "big" }.candidateBecause)
    }

    @Test
    fun `a large file is a candidate when its vector marks more than the ratio, or when it has no numRecords`() {
        val options = DeltaOptimizeOptions(minFileSize = 100, maxFileSize = 1000)
        val plan = planDeltaOptimize(1, listOf(
            add("marked", 200, records = 100, marked = 6),
            add("just", 200, records = 100, marked = 5),
            add("blind", 200, records = null, marked = 1),
        ), options)
        val because = plan.files.associate { it.add.path to it.candidateBecause }
        assertTrue(because["marked"]!!.contains("6.0%"), because["marked"])
        assertEquals(null, because["just"], "5% is not over 5%")
        assertTrue(because["blind"]!!.contains("no numRecords"), because["blind"])
        assertEquals(setOf("marked", "blind"), plan.removed.map { it.add.path }.toSet())
    }
}
