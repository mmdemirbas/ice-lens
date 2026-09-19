package model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The splits a batch read takes, held to Paimon 1.3.1's own `newScan().plan().splits()` over
 * every checked-in table's latest snapshot (`src/test/resources/paimon-scan-plans/splits.txt`,
 * printed by `docs/fixtures/paimon-scan-splits.scala`) — per bucket, the files each split holds
 * and whether it reads raw — and Spark's input partitions from the same read at a parallelism of
 * 1 and of 200. The oracle prints a partition as its `BinaryRow`'s identity, so a split is
 * matched by its bucket and its file names; every split is matched, and every table is planned.
 */
class PaimonSplitPlanTest {

    private class Split(val bucket: Int, val raw: Boolean, val files: Set<String>)
    private class Section(val table: String, val splits: List<Split>, val spark: Map<Int, Int>)

    private val sections: Map<String, Section>

    init {
        val text = javaClass.getResource("/paimon-scan-plans/splits.txt")!!.readText()
        val splits = linkedMapOf<String, MutableList<Split>>()
        val spark = mutableMapOf<String, MutableMap<Int, Int>>()
        var current: String? = null
        for (line in text.lines()) {
            val head = Regex("""^-- (\S+): splits$""").find(line)
            val count = Regex("""^-- (\S+): splits = (\d+)$""").find(line)
            val partitions = Regex("""^-- (\S+): spark partitions = (\d+) \(parallelism (\d+)\)$""").find(line)
            val split = Regex("""^s\d+ \S+ b(-?\d+) raw=(true|false) (.*)$""").find(line)
            when {
                head != null -> { current = head.groupValues[1]; splits[current] = mutableListOf() }
                count != null -> assertEquals(count.groupValues[2].toInt(), splits.getValue(count.groupValues[1]).size, line)
                partitions != null -> spark.getOrPut(partitions.groupValues[1]) { mutableMapOf() }[partitions.groupValues[3].toInt()] = partitions.groupValues[2].toInt()
                split != null -> splits.getValue(current!!) += Split(split.groupValues[1].toInt(), split.groupValues[2].toBoolean(), split.groupValues[3].split(",").toSet())
                line.startsWith("!") -> error("the oracle could not plan: $line")
            }
        }
        sections = splits.keys.associateWith { Section(it, splits.getValue(it), spark.getValue(it)) }
    }

    private fun PaimonSplit.key(): Triple<Int, Boolean, Set<String>> = Triple(bucket, rawConvertible, files.map { it.fileName }.toSet())
    private fun Split.key(): Triple<Int, Boolean, Set<String>> = Triple(bucket, raw, files)

    @Test
    fun `every table's splits agree with the plan, bucket by bucket and file by file, raw or merged`() {
        assertTrue(sections.size >= 60, "the oracle covers the corpus: ${sections.keys}")
        val planned = FixtureCatalog.paimon.toSet()
        assertEquals(planned, sections.keys, "every checked-in table is in the oracle")
        var rules = mutableSetOf<PaimonSplitRule>()
        for ((table, section) in sections) {
            val input = assertNotNull(FixtureCatalog.paimonModel(table).paimonRowLookupInput(), table)
            val plan = planPaimonSplits(input)
            assertEquals(section.splits.size, plan.splits.size, "$table: split count")
            assertEquals(section.splits.map { it.key() }.sortedBy { it.toString() }, plan.splits.map { it.key() }.sortedBy { it.toString() }, "$table: the splits, by bucket, rawness and files")
            rules += plan.rules
        }
        assertEquals(PaimonSplitRule.entries.toSet(), rules, "every generator branch is reached by some table")
    }

    @Test
    fun `every table's Spark partitions agree with the DataFrame's, at a parallelism of 1 and of 200`() {
        for ((table, section) in sections) {
            val input = assertNotNull(FixtureCatalog.paimonModel(table).paimonRowLookupInput(), table)
            val plan = planPaimonSplits(input)
            for ((parallelism, expected) in section.spark) {
                val spark = paimonSparkPartitions(plan, parallelism)
                assertEquals(expected, spark.partitions.size, "$table at parallelism $parallelism")
                assertEquals(plan.files, spark.partitions.sumOf { it.files.size }, "$table: every file is in one partition")
            }
        }
    }

    /**
     * A vector's bytes are charged to the partition and left out of the bound, which is what puts
     * `dv`'s two files in two partitions at a parallelism of one where a table without vectors
     * takes one — the plan test above holds it; this says which rule did it.
     */
    @Test
    fun `a vector's length is charged to its file's partition and not to the bound`() {
        val input = assertNotNull(FixtureCatalog.paimonModel("dv").paimonRowLookupInput())
        val plan = planPaimonSplits(input)
        assertTrue(plan.deletionVectors && plan.vectorLengths.isNotEmpty(), "dv has vectors")
        val spark = paimonSparkPartitions(plan, 1)
        assertEquals(2, spark.partitions.size)
        assertEquals(spark.rawBytes, spark.maxSplitBytes, "at a parallelism of one the bound is the raw bytes, vectors excluded")
        assertTrue(spark.partitions.sumOf { it.bytes } > spark.rawBytes, "the partitions carry the vectors' bytes on top")
        val without = paimonSparkPartitions(plan.copy(vectorLengths = emptyMap()), 1)
        assertEquals(1, without.partitions.size, "without the vectors' bytes, one partition")
    }

    /** `packForOrdered` never reorders, closes a bin only when it holds something, and lets an item past the target stand alone. */
    @Test
    fun `ordered packing keeps the order and lets an oversized item stand alone`() {
        val packed = packForOrdered(listOf(3L, 3L, 10L, 1L, 1L, 4L), 6L) { it }
        assertEquals(listOf(listOf(3L, 3L), listOf(10L), listOf(1L, 1L, 4L)), packed)
        assertEquals(emptyList(), packForOrdered(emptyList<Long>(), 6L) { it })
    }

    /** Files at the target's edge: the bound is exclusive above — `weight + w > target` closes, equal fits. */
    @Test
    fun `a bin fills exactly to the target`() {
        assertEquals(listOf(listOf(4L, 2L), listOf(1L)), packForOrdered(listOf(4L, 2L, 1L), 6L) { it })
    }

    private fun file(name: String, size: Long, level: Int, minKey: Int, maxKey: Int, deleteRows: Long = 0L, bucket: Int = 0, minSeq: Long = 0L) = PaimonLookupFile(
        fileName = name, localPath = "/wh/$name", partition = "", bucket = bucket, level = level, recordCount = 10L,
        fileSize = size, deleteRowCount = deleteRows, minSequenceNumber = minSeq, keyRange = PaimonKeyRange(listOf(minKey), listOf(maxKey)),
    )

    private fun input(files: List<PaimonLookupFile>, primaryKey: Boolean, options: Map<String, String> = emptyMap()): PaimonReadInput {
        val schema = PaimonSchema(id = 0, primaryKeys = if (primaryKey) listOf("k") else emptyList(), options = options)
        return PaimonReadInput(1L, schema, schema.primaryKeys, paimonMergeRuleOf(options, primaryKey), files, emptyList())
    }

    /**
     * No checked-in primary-key table holds two files at one level above 0 without vectors, so the
     * `oneLevel` clause is the source's word on the corpus: two level-5 files with disjoint keys
     * are packed whole and read raw, where the sections rule would make one merge split of them.
     */
    @Test
    fun `files all at one level above 0 are packed whole and read raw, whatever their keys`() {
        val files = listOf(file("a", 1L shl 20, 5, 1, 10), file("b", 1L shl 20, 5, 11, 20))
        val plan = planPaimonSplits(input(files, primaryKey = true))
        assertEquals(listOf(PaimonSplitRule.MERGE_PACKED_WHOLE), plan.splits.map { it.rule })
        assertEquals(listOf(true), plan.splits.map { it.rawConvertible })
        assertEquals(2, plan.splits.single().files.size)
        // A level-0 file beside them: the sections rule, and one merge split of both since they fit one target.
        val mixed = planPaimonSplits(input(files + file("c", 1L shl 20, 0, 5, 8), primaryKey = true))
        assertEquals(listOf(PaimonSplitRule.MERGE_SECTIONS), mixed.splits.map { it.rule })
        assertEquals(listOf(false), mixed.splits.map { it.rawConvertible })
        assertEquals(2, mixed.splits.single().items, "two sections: c lies inside a, b starts past both — packed into one split")
    }

    /**
     * The bound is derived from the raw splits' bytes alone — a merge split's bytes are not in it —
     * which no checked-in table can tell: two raw 1 MiB files beside a 100 MiB merge split, at a
     * parallelism of two, are two partitions and not one.
     */
    @Test
    fun `Spark's bound is derived from the raw splits' bytes, not from every split's`() {
        val raw = listOf(file("a", 1L shl 20, 5, 1, 10), file("b", 1L shl 20, 5, 11, 20))
        val merged = listOf(file("c", 100L shl 20, 0, 1, 20), file("d", 1L shl 20, 3, 1, 20))
        val plan = planPaimonSplits(input(raw.map { it.copy(bucket = 1) } + merged, primaryKey = true))
        assertEquals(2, plan.splits.size)
        assertEquals(1, plan.rawSplits)
        val spark = paimonSparkPartitions(plan, 2)
        assertEquals(2 * (5L shl 20), spark.rawBytes, "two raw files at 1 MiB plus the 4 MiB open cost each")
        assertEquals(5L shl 20, spark.maxSplitBytes, "the raw bytes over the parallelism of two")
        assertEquals(3, spark.partitions.size, "the merge split, and a partition per raw file")
        assertEquals(listOf(false, true, true), spark.partitions.map { it.reshuffled })
    }

    /** `pt`, partitioned: five splits over five buckets of seven files, two merged and three raw — the sections rule and the one-file rule side by side. */
    @Test
    fun `pt's partitions read as two merge splits and three raw ones`() {
        val input = assertNotNull(FixtureCatalog.paimonModel("pt").paimonRowLookupInput())
        val plan = planPaimonSplits(input)
        assertEquals(5, plan.splits.size)
        assertEquals(3, plan.rawSplits)
        assertEquals(setOf(PaimonSplitRule.MERGE_SECTIONS), plan.rules)
        assertEquals(listOf(1, 1, 1, 2, 2), plan.splits.map { it.files.size }.sorted())
        assertEquals(3, paimonSparkPartitions(plan, 1).partitions.size, "the three raw splits fold into one partition; the two merge splits stay")
        assertEquals(5, paimonSparkPartitions(plan, 200).partitions.size)
    }
}
