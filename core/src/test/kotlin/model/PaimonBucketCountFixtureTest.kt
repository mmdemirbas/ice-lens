package model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `docs/fixtures/paimon-pbk.sql`: `bucket` raised from 1 to 2 after three writes. `pbk` is the
 * table with the option changed and nothing rescaled — an INSERT was refused with `Try to write
 * table with a new bucket num 2, but the previous bucket num is 1` — and `pbka` the same table
 * after `INSERT OVERWRITE … SELECT *` rescaled it and an INSERT went through.
 */
class PaimonBucketCountFixtureTest {

    private fun live(name: String): Pair<Collection<PaimonUnifiedDataFile>, Int> {
        val model = FixtureCatalog.paimonModel(name)
        val latest = model.snapshots.maxByOrNull { it.metadata.id ?: -1 }
        val bucket = model.schemas.maxByOrNull { it.id ?: -1 }?.options?.get(PAIMON_BUCKET_OPTION)?.toIntOrNull() ?: PAIMON_DEFAULT_BUCKET
        return replayPaimonSnapshot(latest, null).liveEntries.values to bucket
    }

    @Test
    fun `the files written under the old count disagree with the option until the rescale`() {
        val (before, bucketBefore) = live("pbk")
        assertEquals(2, bucketBefore, "schema-1 says 2")
        val checks = paimonBucketCountChecks(before, bucketBefore)
        assertEquals(3, checks.size)
        assertTrue(checks.all { it.agrees == false && it.recorded == 1 && it.configured == 2 }, checks.toString())
        assertTrue(checks.all { it.partition == null })

        val (after, bucketAfter) = live("pbka")
        assertEquals(2, bucketAfter)
        val rescaled = paimonBucketCountChecks(after, bucketAfter)
        assertEquals(3, rescaled.size, "two files from the overwrite and one from the append after it")
        assertTrue(rescaled.all { it.agrees == true && it.recorded == 2 }, rescaled.toString())
        assertEquals(setOf(0, 1), after.map { it.metadata.bucket }.toSet(), "the rescale spread the rows over both buckets")
    }

    /** A count at or below zero — dynamic or unaware bucketing — is not compared, as the commit-time check skips it. */
    @Test
    fun `an unaware or dynamic bucket count is not compared`() {
        val (ao, bucket) = live("ao")
        assertEquals(-1, bucket)
        val checks = paimonBucketCountChecks(ao, bucket)
        assertTrue(checks.isNotEmpty())
        checks.forEach { assertNull(it.agrees, it.toString()) }
    }

    /** Every other fixture's live files were written under the count in force. */
    @Test
    fun `every fixture but pbk agrees on the bucket count`() {
        for (name in FixtureCatalog.paimon) {
            if (name == "pbk") continue
            val (files, bucket) = live(name)
            val disagree = paimonBucketCountChecks(files, bucket).filter { it.agrees == false }
            assertEquals(emptyList(), disagree, name)
        }
    }
}
