package model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The tasks a read takes, held to Iceberg 1.8.1's own `planTasks()` over every checked-in table
 * (`src/test/resources/iceberg-scan-plans/tasks.txt`, printed by `iceberg-scan-plans.scala`):
 * under the table's `read.split.*`, under an 8 MiB target — two 4 MiB-weighted splits a bin — and
 * under a 32 KiB target with no open-file cost, packed by bytes alone; then Spark's own partition
 * count at a parallelism of 200, which is the adaptive size shrinking the target to 16 MiB.
 *
 * The count is held everywhere. Which file lands in which task is held only where the plan's
 * order is fixed — one data manifest at the current snapshot — because `ManifestGroup.planFiles`
 * runs the manifests on the worker pool and the packing follows whatever order they finish in;
 * the oracle prints the default plan twice, and on the multi-manifest tables the two runs put
 * the same files in one task in a different order.
 */
class ScanTaskPlanTest {

    private class Section(val table: String, val label: String, val tasks: List<Set<String>>)

    private val sections: List<Section>
    private val sparkPartitions: Map<String, Int>
    /** A table whose DataFrame read the script's Spark refused, with the refusal — no partition count to hold. */
    private val sparkRefused: Map<String, String>

    init {
        val text = javaClass.getResource("/iceberg-scan-plans/tasks.txt")!!.readText()
        val out = mutableListOf<Section>()
        val spark = mutableMapOf<String, Int>()
        val refused = mutableMapOf<String, String>()
        var current: Triple<String, String, MutableList<Set<String>>>? = null
        var lastTable: String? = null
        for (line in text.lines()) {
            val head = Regex("""^-- (\S+): tasks (.+?)(?: = (\d+) tasks)?$""").find(line)
            val partitions = Regex("""^-- (\S+): spark partitions = (\d+) \(parallelism 200\)$""").find(line)
            when {
                partitions != null -> spark[partitions.groupValues[1]] = partitions.groupValues[2].toInt()
                head != null && head.groupValues[3].isNotEmpty() -> {
                    val c = current!!
                    assertEquals(head.groupValues[3].toInt(), c.third.size, line)
                    out += Section(c.first, c.second, c.third)
                    current = null
                }
                head != null -> { current = Triple(head.groupValues[1], head.groupValues[2], mutableListOf()); lastTable = head.groupValues[1] }
                line.startsWith("!") -> refused[lastTable!!] = line.removePrefix("! ")
                line.startsWith("#") || line.isBlank() || line.startsWith("-- ") -> {}
                else -> current!!.third += line.split(",").toSet()
            }
        }
        sections = out
        sparkPartitions = spark
        sparkRefused = refused
    }

    private fun currentSnapshot(m: UnifiedTableModel): UnifiedSnapshot {
        val newest = m.metadatas.last()
        return newest.snapshots.single { it.metadata.snapshotId == newest.metadata.currentSnapshotId }
    }

    private fun ScanTask.render(): Set<String> = entries.map { "${it.path.substringAfterLast('/')}:${it.start}+${it.length}" }.toSet()

    @Test
    fun `every table's task count agrees with planTasks under three option sets, and the packing where the order is fixed`() {
        val tables = sections.map { it.table }.distinct()
        assertTrue(tables.size >= 40, "the oracle covers the corpus: $tables")
        var packingsHeld = 0
        for (table in tables) {
            val m = FixtureCatalog.icebergModel(table)
            val snapshot = currentSnapshot(m)
            val options = ScanTaskOptions.from(m.metadatas.last().metadata.properties)
            val files = scanTaskFiles(liveFilesOf(snapshot), deleteReach(snapshot))
            val deterministic = snapshot.manifests.count { it.metadata.content == ManifestContent.DATA } == 1
            for (section in sections.filter { it.table == table }) {
                val plan = when (section.label) {
                    "default", "default again" -> planScanTasks(files, options)
                    "split=8MiB" -> planScanTasks(files, options.copy(splitSize = 8L * 1024 * 1024))
                    "split=32KiB cost=0" -> planScanTasks(files, options.copy(splitSize = 32L * 1024, openFileCost = 0))
                    else -> error(section.label)
                }
                assertEquals(section.tasks.size, plan.tasks.size, "$table ${section.label}")
                assertEquals(section.tasks.flatten().toSet(), plan.tasks.flatMap { it.render() }.toSet(), "$table ${section.label}: the entries, whatever the packing")
                if (deterministic) {
                    assertEquals(section.tasks.map { it.sorted() }.sortedBy { it.first() }, plan.tasks.map { it.render().sorted() }.sortedBy { it.first() }, "$table ${section.label}: one data manifest, so the packing is fixed")
                    packingsHeld++
                }
            }
            val plan = planScanTasks(files, options)
            val adjusted = adjustedSplitSize(plan.scanBytes, 200, options.splitSize)
            assertEquals(ScanTaskOptions.MIN_SPLIT_SIZE, adjusted, "$table: every checked-in table is small enough for the 16 MiB floor")
            val spark = sparkPartitions[table]
            if (spark != null) assertEquals(spark, planScanTasks(files, options, adjusted).tasks.size, "$table: Spark's partitions at a parallelism of 200")
            else assertEquals("UnsupportedOperationException: Unsupported type: variant", sparkRefused[table], "$table: no partition count, so the read was refused")
        }
        // `test` is refused whole (its list is recorded where it was written); the 1.10.0 Spark 3.5 module plans
        // `variant` and refuses to read it. Nothing else is without a count.
        assertEquals(setOf("test", "variant"), sparkRefused.keys)
        assertTrue(packingsHeld >= 20, "the fixed-order packings held: $packingsHeld")
    }

    /**
     * `rgs`: a 5,000-row file of thirteen row groups and a one-row file. A row group is a split
     * before it is a task, so the two files are fourteen splits of 4 MiB each — 56 MiB of weight
     * over 22 KB of bytes — one task at 128 MiB, seven at 8 MiB, four at Spark's 16 MiB, and one
     * again with the open-file cost off, where the splits weigh their bytes.
     */
    @Test
    fun `a row group is a split and pays the open-file cost, so a small file of many row groups takes several tasks`() {
        val m = FixtureCatalog.icebergModel("rgs")
        val snapshot = currentSnapshot(m)
        val files = scanTaskFiles(liveFilesOf(snapshot), deleteReach(snapshot))
        val big = files.single { it.splitOffsets!!.size == 13 }
        assertEquals(13, big.split(ScanTaskOptions.SPLIT_SIZE_DEFAULT).size)
        assertTrue(big.split(ScanTaskOptions.SPLIT_SIZE_DEFAULT).all { it.rule == SplitRule.BY_OFFSETS })
        assertEquals(4L, big.split(ScanTaskOptions.SPLIT_SIZE_DEFAULT).first().start, "the first split starts at the first row group, past the magic")
        assertEquals(big.sizeBytes, big.split(ScanTaskOptions.SPLIT_SIZE_DEFAULT).sumOf { it.length } + 4)
        val plan = planScanTasks(files, ScanTaskOptions())
        assertEquals(14, plan.splits.size)
        assertEquals(1, plan.tasks.size)
        assertEquals(14 * 4L * 1024 * 1024, plan.tasks.single().weight)
        assertEquals(2, plan.tasks.single().entries.size, "adjacent row groups joined back into one range per file")
        assertEquals(7, planScanTasks(files, ScanTaskOptions(splitSize = 8L * 1024 * 1024)).tasks.size)
        assertEquals(4, planScanTasks(files, ScanTaskOptions(), splitSize = adjustedSplitSize(plan.scanBytes, 200, plan.splitSize)).tasks.size)
        val byBytes = planScanTasks(files, ScanTaskOptions(splitSize = 32L * 1024, openFileCost = 0))
        assertEquals(1, byBytes.tasks.size)
        assertEquals(byBytes.tasks.single().bytes, byBytes.tasks.single().weight)
    }

    /** A delete file's bytes and its open cost travel with every split of the data file it is paired with; a vector counts at its blob's size. */
    @Test
    fun `a paired delete file adds its content bytes and an open cost to each split of its data file`() {
        for ((table, expected) in listOf("mor" to 1, "eqdel" to 2, "v3" to 1)) {
            val m = FixtureCatalog.icebergModel(table)
            val snapshot = currentSnapshot(m)
            val live = liveFilesOf(snapshot)
            val files = scanTaskFiles(live, deleteReach(snapshot))
            val withDeletes = files.filter { it.deleteFiles > 0 }
            assertTrue(withDeletes.isNotEmpty(), table)
            val split = withDeletes.first().split(ScanTaskOptions.SPLIT_SIZE_DEFAULT).single()
            assertEquals((1 + split.deleteFiles) * ScanTaskOptions.SPLIT_OPEN_FILE_COST_DEFAULT, split.weight(ScanTaskOptions.SPLIT_OPEN_FILE_COST_DEFAULT), "$table: the open cost per paired file outweighs the bytes")
            assertEquals(split.length + split.deleteBytes, split.weight(0), "$table: with no open cost, the bytes and the deletes'")
            if (table == "v3") {
                val vectors = live.filter { it.content == DataFileContent.POSITION_DELETES && it.path.endsWith(".puffin") }
                assertTrue(vectors.isNotEmpty() && vectors.all { it.chargedSizeBytes < it.sizeBytes }, "a vector is charged at its blob, below its container")
                assertTrue(withDeletes.all { f -> vectors.any { it.chargedSizeBytes == f.deleteBytes } }, "the vector's blob, not its container: $withDeletes")
            }
            assertEquals(expected, withDeletes.maxOf { it.deleteFiles }, table)
        }
    }

    @Test
    fun `the packer keeps lookback bins open and closes the heaviest when they overflow`() {
        // Weights 5, 5, 5, 5 at a target of 10 with one open bin: pairs in order.
        assertEquals(listOf(listOf(5, 5), listOf(5, 5)), binPack(listOf(5, 5, 5, 5), 10, 1, largestBinFirst = false) { it.toLong() })
        // 7, 4, 3, 6 at a target of 10, lookback 1: 7 alone (4 does not fit), then 4 + 3, then 6.
        assertEquals(listOf(listOf(7), listOf(4, 3), listOf(6)), binPack(listOf(7, 4, 3, 6), 10, 1, largestBinFirst = false) { it.toLong() })
        // The same with two bins open: 3 goes into the bin holding 7, 6 into the one holding 4.
        assertEquals(listOf(listOf(7, 3), listOf(4, 6)), binPack(listOf(7, 4, 3, 6), 10, 2, largestBinFirst = true) { it.toLong() })
        // Overflowing the lookback: 3, 9, 8 at lookback 2 — 8 fits neither open bin and opens a third,
        // so one closes: the heaviest (the 9) under the scan's rule, the oldest (the 3) under the rewriter's.
        assertEquals(listOf(listOf(9), listOf(3), listOf(8)), binPack(listOf(3, 9, 8), 10, 2, largestBinFirst = true) { it.toLong() })
        assertEquals(listOf(listOf(3), listOf(9), listOf(8)), binPack(listOf(3, 9, 8), 10, 2, largestBinFirst = false) { it.toLong() })
        assertEquals(listOf(listOf(9), listOf(3, 1), listOf(8)), binPack(listOf(3, 9, 8, 1), 10, 2, largestBinFirst = true) { it.toLong() }, "after the 9 closed, the 1 joins the 3")
        assertEquals(listOf(listOf(3), listOf(9, 1), listOf(8)), binPack(listOf(3, 9, 8, 1), 10, 2, largestBinFirst = false) { it.toLong() }, "after the 3 closed, the 1 fills the 9 to the target")
        // A lookback of two and a bin that fills exactly: 9, 2, 8 — the 8 joins the 2.
        assertEquals(listOf(listOf(9), listOf(2, 8)), binPack(listOf(9, 2, 8), 10, 2, largestBinFirst = true) { it.toLong() })
        // An item past the target still opens a bin of its own.
        assertEquals(listOf(listOf(30), listOf(1)), binPack(listOf(30, 1), 10, 1, largestBinFirst = true) { it.toLong() })
    }

    /** No checked-in table opens more than ten bins, so the scan's largest-first rule is pinned on three files past a lookback of two. */
    @Test
    fun `the scan closes the heaviest open bin past the lookback, which the rewriter's packer does not`() {
        fun file(name: String, bytes: Long) = ScanTaskFile(name, bytes, "parquet", null, 0, 0)
        val plan = planScanTasks(listOf(file("a", 3), file("b", 9), file("c", 8)), ScanTaskOptions(splitSize = 10, lookback = 2, openFileCost = 0))
        assertEquals(listOf(listOf("b"), listOf("a"), listOf("c")), plan.tasks.map { t -> t.entries.map { it.path } })
        assertEquals(listOf(9L, 3L, 8L), plan.tasks.map { it.weight })
        assertTrue(plan.splits.all { it.rule == SplitRule.BY_SIZE })
        // A file past the target is sliced, and a format that cannot be split is not.
        assertEquals(listOf(0L to 10L, 10L to 10L, 20L to 5L), file("d", 25).split(10).map { it.start to it.length })
        assertEquals(listOf(SplitRule.UNSPLIT), ScanTaskFile("e", 25, "puffin", null, 0, 0).split(10).map { it.rule })
        assertEquals(emptyList(), file("f", 0).split(10), "a file of no bytes yields no split")
        assertEquals(listOf(SplitRule.BY_SIZE), ScanTaskFile("g", 25, "parquet", listOf(4L, 30L), 0, 0).split(10).map { it.rule }.distinct(), "an offset past the file's end is a list Iceberg drops")
    }

    @Test
    fun `the adaptive split size shrinks to the scan over the parallelism, floored at 16 MiB and capped at the target`() {
        val mib = 1024L * 1024
        assertEquals(16 * mib, adjustedSplitSize(1000, 200, 128 * mib), "a tiny scan: the floor")
        assertEquals(128 * mib, adjustedSplitSize(200 * 128 * mib, 200, 128 * mib), "as many splits as the parallelism: unchanged")
        assertEquals(50 * mib, adjustedSplitSize(500 * mib, 10, 128 * mib), "four splits under a parallelism of ten: a tenth each")
        assertEquals(8 * mib, adjustedSplitSize(1000, 200, 8 * mib), "a target under 16 MiB is its own floor")
        assertEquals(128 * mib, adjustedSplitSize(1000, 0, 128 * mib), "no parallelism known: unchanged")
    }
}
