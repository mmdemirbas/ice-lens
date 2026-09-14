package model

import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `docs/fixtures/paimon-ppx.sql`: one append table partitioned by `(region, dt)`, both strings,
 * with `partition.expiration-time = 1 d` and `partition.timestamp-pattern = $dt`, kept twice —
 * `ppx` as written, `ppxa` after a bare `expire_partitions` call. The call printed
 * `region=eu, dt=2020-01-01` and `region=eu, dt=2021-06-15`, warned that `n-a` cannot be read as
 * a time, and wrote snapshot 3 as an `OVERWRITE` with two `DELETE` entries; a second call under
 * `update-time` printed `No expired partitions.` and wrote nothing.
 */
class PaimonPartitionExpiryFixtureTest {

    private val ppx by lazy { FixtureCatalog.paimonModel("ppx").expiryInput() }
    private val ppxa by lazy { FixtureCatalog.paimonModel("ppxa") }

    /** A second past the last commit, the clock the panel pins a capture to. */
    private fun nowFor(input: PaimonExpiryInput) = input.snapshotTimes.values.filterNotNull().max() + 1_000

    @Test
    fun `the plan from ppx drops exactly the partitions ppxa no longer lists`() {
        val plan = ppx.planPartitionExpiry(nowFor(ppx))
        assertEquals(24L * 60 * 60 * 1000, plan.expirationMs)
        assertEquals(PaimonPartitionExpireStrategy.VALUES_TIME, plan.strategy)
        assertEquals("\$dt", plan.pattern)
        assertEquals(4, plan.partitions.size, "four partitions, a file each")
        assertEquals(
            listOf("region=eu, dt=2020-01-01", "region=eu, dt=2021-06-15"),
            plan.dropped.map { it.entry.partition.display },
            "the two the procedure printed, in the order it printed them",
        )
        assertEquals(0, plan.heldBack)
        val kept = plan.partitions.filter { !it.expired }.associate { it.entry.partition.display to it.reason }
        assertTrue(kept.getValue("region=us, dt=2099-12-31").contains("at or after the cutoff"), kept.toString())
        assertTrue(kept.getValue("region=us, dt=n-a").contains("does not parse"), kept.toString())

        // The oracle: the partitions the plan drops are the ones whose files ppxa's latest
        // snapshot no longer lists, and the ones it keeps are still there.
        val after = replayPaimonSnapshot(ppxa.snapshots.maxByOrNull { it.metadata.id ?: -1 }, null).liveEntries.values
            .mapNotNull { it.partition?.display }.toSet()
        assertEquals(setOf("region=us, dt=2099-12-31", "region=us, dt=n-a"), after)
        assertEquals(after, plan.partitions.filter { !it.expired }.map { it.entry.partition.display }.toSet())
        // And the files stay on disk, still listed by snapshots 1 and 2: an expiry drops from the manifests.
        assertEquals(4, FixtureCatalog.paimonDir("ppxa").walk().count { it.name.endsWith(".parquet") })
    }

    @Test
    fun `after the expiry the plan has nothing left to drop, and the dropped partitions are not listed`() {
        val input = ppxa.expiryInput()
        assertEquals(setOf("region=us, dt=2099-12-31", "region=us, dt=n-a"), input.partitions.map { it.partition.display }.toSet(), "a partition at file count zero is not a partition")
        val plan = input.planPartitionExpiry(nowFor(input))
        assertEquals(emptyList(), plan.dropped)
    }

    /** `update-time` at a second past the last commit expires nothing, as the second call found; two days on it expires everything. */
    @Test
    fun `update-time expires by the newest file's creation time`() {
        val options = ppx.tableOptions + (PAIMON_PARTITION_EXPIRATION_STRATEGY_OPTION to "update-time")
        val now = nowFor(ppx)
        val fresh = planPaimonPartitionExpiry(ppx.partitions, options, now)
        assertEquals(PaimonPartitionExpireStrategy.UPDATE_TIME, fresh.strategy)
        assertEquals(emptyList(), fresh.dropped, fresh.partitions.map { it.reason }.toString())
        assertTrue(fresh.partitions.all { it.timeMs != null && it.timeMs!! <= now }, "every partition's newest file was written before now")
        val later = planPaimonPartitionExpiry(ppx.partitions, options, now + 2 * 24L * 60 * 60 * 1000)
        assertEquals(4, later.dropped.size)
    }

    /**
     * A `DATE` partition column never value-expires: the row's getter spells it as its epoch
     * day (`dt:19788`), which no date formatter reads. Run on a copy of `pt` with
     * `expiration_time => '1 d'`, with and without `timestamp_pattern => '$dt'`: fourteen
     * warnings, `No expired partitions.` twice, no snapshot written.
     */
    @Test
    fun `a DATE partition is spelled as its epoch day and is never expired by value`() {
        val pt = FixtureCatalog.paimonModel("pt").expiryInput()
        val options = pt.tableOptions + (PAIMON_PARTITION_EXPIRATION_TIME_OPTION to "1 d")
        val plan = planPaimonPartitionExpiry(pt.partitions, options, nowFor(pt))
        assertTrue(plan.partitions.isNotEmpty())
        assertEquals(emptyList(), plan.dropped)
        assertTrue(plan.partitions.all { it.reason.contains("does not parse") }, plan.partitions.map { it.reason }.toString())
        assertTrue(plan.partitions.all { Regex("'\\d{5}'").containsMatchIn(it.reason) }, "the epoch day is what was tried: ${plan.partitions.map { it.reason }}")
        val withPattern = planPaimonPartitionExpiry(pt.partitions, options + (PAIMON_PARTITION_TIMESTAMP_PATTERN_OPTION to "\$dt"), nowFor(pt))
        assertEquals(emptyList(), withPattern.dropped)
    }

    /** Without `partition.expiration-time` nothing expires on any table, and the reason says so. */
    @Test
    fun `every fixture without an expiration time expires nothing`() {
        for (name in FixtureCatalog.paimon) {
            val input = FixtureCatalog.paimonModel(name).expiryInput()
            if (PAIMON_PARTITION_EXPIRATION_TIME_OPTION in input.tableOptions) continue
            val plan = input.planPartitionExpiry(nowFor(input))
            assertNull(plan.cutoffMs, name)
            assertEquals(emptyList(), plan.dropped, name)
            assertTrue(plan.partitions.all { it.reason.contains(PAIMON_PARTITION_EXPIRATION_TIME_OPTION) }, name)
        }
    }

    /** The extractor's default formatters, the pattern substitution, and the zone the cutoff is taken in. */
    @Test
    fun `the time is read the way PartitionTimeExtractor reads it`() {
        assertEquals("2024-03-05T00:00", paimonPartitionTime("2024-03-05", null).toString())
        assertEquals("2024-03-05T10:20:30", paimonPartitionTime("2024-03-05 10:20:30", null).toString())
        assertEquals("2024-03-05T10:20:30.500", paimonPartitionTime("2024-3-5 10:20:30.5", null).toString(), "lenient widths")
        assertNull(paimonPartitionTime("20240305", null))
        assertEquals("2024-03-05T00:00", paimonPartitionTime("20240305", "yyyyMMdd").toString())
        assertEquals("2024-03-05T10:00", paimonPartitionTime("2024-03-05 10", "yyyy-MM-dd HH").toString())

        fun partition(vararg pairs: Pair<String, Any?>) = PaimonPartitionEntry(
            DecodedPaimonPartition(pairs.map { (name, value) -> PaimonPartitionValue(name, "STRING", value, value?.toString() ?: "__DEFAULT_PARTITION__") }),
            fileCount = 1, recordCount = 1, fileSizeBytes = 1, lastFileCreationTimeMs = null,
        )
        val utc = ZoneId.of("UTC")
        val dayMs = 24L * 60 * 60 * 1000
        val march6 = java.time.LocalDate.of(2024, 3, 6).atStartOfDay(utc).toInstant().toEpochMilli()
        val options = mapOf(PAIMON_PARTITION_EXPIRATION_TIME_OPTION to "1 d")
        // Cutoff March 5 00:00: a partition at March 5 is not before it, one at March 4 is.
        val byFirst = planPaimonPartitionExpiry(listOf(partition("dt" to "2024-03-05"), partition("dt" to "2024-03-04")), options, march6, utc)
        assertEquals(listOf("dt=2024-03-04"), byFirst.dropped.map { it.entry.partition.display })
        // Year, month and day in three fields, joined by the pattern.
        val split = planPaimonPartitionExpiry(
            listOf(partition("y" to "2024", "m" to "3", "d" to "4"), partition("y" to "2024", "m" to "3", "d" to "5")),
            options + (PAIMON_PARTITION_TIMESTAMP_PATTERN_OPTION to "\$y-\$m-\$d"), march6, utc,
        )
        assertEquals(listOf("y=2024, m=3, d=4"), split.dropped.map { it.entry.partition.display })
        // A null value is kept, the reason naming the field.
        val nulls = planPaimonPartitionExpiry(listOf(partition("dt" to null)), options, march6, utc)
        assertTrue(nulls.partitions.single().reason.contains("dt is null"), nulls.partitions.single().reason)
        // The cap: the smallest values go first, the rest are held back for the next run.
        val capped = planPaimonPartitionExpiry(
            listOf(partition("dt" to "2020-01-03"), partition("dt" to "2020-01-01"), partition("dt" to "2020-01-02")),
            options + (PAIMON_PARTITION_EXPIRATION_MAX_NUM_OPTION to "2"), march6, utc,
        )
        assertEquals(listOf("dt=2020-01-01", "dt=2020-01-02"), capped.dropped.map { it.entry.partition.display })
        assertEquals(1, capped.heldBack)
    }
}
