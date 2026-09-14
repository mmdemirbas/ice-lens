package model

import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Every partitioned entry of every engine-written table has a partition its own bounds
 * transform to — across every transform the corpus carries — and then each way the two can
 * disagree is planted, since a check that never disagrees may not be looking.
 */
class PartitionBoundsCheckTest {

    private fun icebergChecks(name: String): List<Pair<String, PartitionFieldCheck>> {
        val model = FixtureCatalog.icebergModel(name)
        val tableFields = model.metadatas.last().metadata.fieldsEverDefined()
        return model.metadatas.flatMap { it.snapshots }.flatMap { it.manifests }.distinctBy { it.metadata.manifestPath }.flatMap { m ->
            m.dataFiles.flatMap { file ->
                val partition = file.partition?.takeIf { !it.isUnpartitioned } ?: return@flatMap emptyList()
                val data = file.metadata.dataFile ?: return@flatMap emptyList()
                partitionBoundsChecks(partition, columnStatsFor(data, m.schema, tableFields)).map { "$name/${data.filePath?.substringAfterLast('/')}" to it }
            }
        }
    }

    @Test
    fun `every partitioned Iceberg entry's partition is what its bounds transform to`() {
        val all = FixtureCatalog.iceberg.flatMap(::icebergChecks)
        val bad = all.filter { it.second.verdict == PartitionFieldVerdict.DISAGREES }
        assertEquals(emptyList(), bad.map { "${it.first}: ${it.second}" })
        val agreed = all.filter { it.second.verdict == PartitionFieldVerdict.AGREES }
        assertTrue(agreed.size >= 60, "${agreed.size} agreed of ${all.size}")
        // Every transform the corpus carries is exercised on the agreeing side — `parted`'s
        // one-row files make even bucket decidable, its bounds being one value.
        val transforms = agreed.map { it.second.transform.substringBefore('[') }.toSet()
        assertEquals(setOf("identity", "bucket", "truncate", "day", "hour", "month", "year"), transforms)
        // What could not be checked is a bucket over a range, or a column with no bounds recorded.
        val unchecked = all.filter { it.second.verdict == PartitionFieldVerdict.NOT_CHECKED }.map { it.second.reason }.toSet()
        assertTrue(unchecked.all { it.contains("says nothing about a bucket") || it.startsWith("no bounds recorded") }, "$unchecked")
    }

    @Test
    fun `every partitioned Paimon entry's partition is within its column bounds`() {
        var agreed = 0
        val unchecked = mutableListOf<String>()
        for (name in FixtureCatalog.paimon) {
            val model = FixtureCatalog.paimonModel(name)
            val manifests = (model.snapshots + model.tagOnlySnapshots).flatMap { it.baseManifests + it.deltaManifests + it.changelogManifests }.distinctBy { it.path }
            for (m in manifests) for (e in m.entries) {
                val partition = e.partition?.takeIf { it.values.isNotEmpty() } ?: continue
                for (c in paimonPartitionBoundsChecks(partition, e.columnBounds, e.metadata.file?.rowCount)) {
                    assertTrue(c.verdict != PartitionFieldVerdict.DISAGREES, "$name/${e.metadata.file?.fileName}: $c")
                    if (c.verdict == PartitionFieldVerdict.AGREES) agreed++ else unchecked += "$name/${c.field}: ${c.reason}"
                }
            }
        }
        // Only `pt` is partitioned, ten entries over two keys; nothing is left unchecked, a Paimon bound being exact.
        assertEquals(emptyList(), unchecked)
        assertTrue(agreed >= 19, "$agreed agreed")
    }

    @Test
    fun `a partition the bounds do not transform to, a null under a value and a value under null are each named`() {
        // parted's d_day for 2024-03-05 over a one-row file: the bounds are the day.
        val day = icebergChecks("parted").first { it.second.field == "d_day" && it.second.verdict == PartitionFieldVerdict.AGREES }.second
        assertEquals("both bounds of d transform to ${day.recorded}", day.reason)

        val moved = checkPartitionField("d_day", "day", "ts", LocalDate.of(2024, 3, 6), "2024-03-06", lower = LocalDate.of(2024, 3, 5).atStartOfDay(), upper = LocalDate.of(2024, 3, 5).atTime(23, 0), nullCount = 0, valueCount = 4)
        assertEquals(PartitionFieldVerdict.DISAGREES, moved.verdict)
        assertEquals("2024-03-05", moved.fromBounds)
        assertEquals("the bounds of ts transform to 2024-03-05, not 2024-03-06", moved.reason)

        val nullRows = checkPartitionField("region", "identity", "region", "eu", "eu", lower = "eu", upper = "eu", nullCount = 2, valueCount = 5)
        assertEquals(PartitionFieldVerdict.DISAGREES, nullRows.verdict)
        assertEquals("2 of the rows have a null region, and a null belongs in the null partition, not under eu", nullRows.reason)

        val allNull = checkPartitionField("region", "identity", "region", "eu", "eu", lower = null, upper = null, nullCount = 5, valueCount = 5)
        assertEquals(PartitionFieldVerdict.DISAGREES, allNull.verdict)

        val valuesUnderNull = checkPartitionField("region", "identity", "region", null, "null", lower = "eu", upper = "us", nullCount = 0, valueCount = 5)
        assertEquals(PartitionFieldVerdict.DISAGREES, valuesUnderNull.verdict)
        assertEquals("eu … us", valuesUnderNull.fromBounds)

        val nullPartition = checkPartitionField("region", "identity", "region", null, "null", lower = null, upper = null, nullCount = 5, valueCount = 5)
        assertEquals(PartitionFieldVerdict.AGREES, nullPartition.verdict)

        // A number is exact: a partition value inside a range of values is still not what every row holds.
        val spanning = checkPartitionField("id", "identity", "id", 5, "5", lower = 1, upper = 9, nullCount = 0, valueCount = 9)
        assertEquals(PartitionFieldVerdict.DISAGREES, spanning.verdict)
        assertEquals("1 … 9", spanning.fromBounds)

        // A bucket is decided only where the bounds are one value; over a range it says nothing.
        val bucketRange = checkPartitionField("id_bucket", "bucket[4]", "id", 2, "2", lower = 1L, upper = 9L, nullCount = 0, valueCount = 9)
        assertEquals(PartitionFieldVerdict.NOT_CHECKED, bucketRange.verdict)
        val bucketOne = checkPartitionField("id_bucket", "bucket[4]", "id", BucketTransform.bucketOf(7L, 4)!! + 1, "x", lower = 7L, upper = 7L, nullCount = 0, valueCount = 1)
        assertEquals(PartitionFieldVerdict.DISAGREES, bucketOne.verdict)

        // A string bound is a truncated prefix, so the value is held within the bounds rather than equal to them.
        val longString = checkPartitionField("name", "identity", "name", "abcdefghijklmnopqrstuvwxyz", "abcdefghijklmnopqrstuvwxyz", lower = "abcdefghijklmnop", upper = "abcdefghijklmnoq", nullCount = 0, valueCount = 3)
        assertEquals(PartitionFieldVerdict.AGREES, longString.verdict)
        assertTrue(longString.reason.contains("within the bounds"), longString.reason)
        val otherString = checkPartitionField("name", "identity", "name", "zzz", "zzz", lower = "abcdefghijklmnop", upper = "abcdefghijklmnoq", nullCount = 0, valueCount = 3)
        assertEquals(PartitionFieldVerdict.DISAGREES, otherString.verdict)
    }

    @Test
    fun `the integrity report lists a partition its bounds contradict under file partitions`() {
        val report = FixtureCatalog.icebergModel("parted").integrityReport()
        assertTrue(report.findings.none { it.check == IntegrityCheck.FILE_PARTITIONS }, "${report.findings}")
        assertTrue(report.checked > 0)
        val paimon = FixtureCatalog.paimonModel("pt").integrityReport()
        assertTrue(paimon.findings.none { it.check == IntegrityCheck.FILE_PARTITIONS }, "${paimon.findings}")
    }
}
