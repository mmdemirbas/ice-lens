package model

import java.nio.file.Paths
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import service.GraphLayoutService

/**
 * Every column of every engine-written file records the shape `write.metadata.metrics.*` says
 * for it, under the configuration in force when the file was written; `metrics` and `metricsw`
 * exercise each rule `MetricsConfig.from` applies; then each way a file can differ from its
 * configuration is planted, since a check that never disagrees may not be looking.
 */
class MetricsConfigTest {

    private fun checks(name: String): List<Pair<String, MetricsModeCheck>> {
        val model = FixtureCatalog.icebergModel(name)
        val tableFields = model.metadatas.last().metadata.fieldsEverDefined()
        val history = MetricsConfigHistory(model.metadatas.map { it.metadata })
        return model.metadatas.flatMap { it.snapshots }.flatMap { it.manifests }.distinctBy { it.metadata.manifestPath }.flatMap { m ->
            m.dataFiles.flatMap { file ->
                val data = file.metadata.dataFile ?: return@flatMap emptyList()
                // A DELETED entry names the snapshot that removed the file, not the one whose configuration wrote it.
                if (file.metadata.status == ManifestEntryStatus.DELETED) return@flatMap emptyList()
                val at = history.at(file.metadata.snapshotId ?: m.metadata.addedSnapshotId) ?: return@flatMap emptyList()
                metricsModeChecks(columnStatsFor(data, m.schema, tableFields), at.schema ?: m.schema, at.config, data).map { "$name/${data.filePath?.substringAfterLast('/')}" to it }
            }
        }
    }

    @Test
    fun `every engine-written file records the shape its metrics configuration says`() {
        val all = FixtureCatalog.iceberg.flatMap(::checks)
        val bad = all.filter { it.second.agrees == false }
        assertEquals(emptyList(), bad.map { "${it.first} ${it.second.column}: ${it.second.reason} (${it.second.configured.setBy})" })
        val agreed = all.filter { it.second.agrees == true }
        assertTrue(agreed.size >= 400, "${agreed.size} agreed of ${all.size}")
        // Every mode the corpus configures is met on the agreeing side.
        val modes = agreed.map { it.second.configured.mode.spelled }.toSet()
        assertEquals(setOf("none", "counts", "truncate(4)", "truncate(16)", "full"), modes)
        // What could not be judged is a column every value of which is null.
        assertTrue(all.filter { it.second.agrees == null }.all { it.second.reason.startsWith("every value is null") })
    }

    @Test
    fun `metrics gives each rule its column, and a file keeps the configuration it was written under`() {
        val byFile = checks("metrics").groupBy { it.first.substringAfterLast('/') }.mapValues { (_, v) -> v.map { it.second }.associateBy { c -> c.column } }
        val (first, second) = byFile.keys.sorted().let { it[0] to it[1] }
        fun mode(file: String, column: String) = byFile.getValue(file).getValue(column)

        assertEquals("counts", mode(first, "id").configured.mode.spelled)
        assertEquals("write.metadata.metrics.default = counts", mode(first, "id").configured.setBy)
        assertEquals("counts", mode(first, "id").recorded)
        assertEquals("truncate(4)", mode(first, "name").configured.mode.spelled)
        assertEquals("write.metadata.metrics.column.name = truncate(4)", mode(first, "name").configured.setBy)
        assertEquals("counts and bounds", mode(first, "name").recorded)
        assertEquals("full", mode(first, "note").configured.mode.spelled)
        assertEquals("none", mode(first, "tag").configured.mode.spelled)
        assertEquals("nothing", mode(first, "tag").recorded)
        assertEquals("truncate(16)", mode(first, "score").configured.mode.spelled)
        assertEquals("the sort column, promoted from counts", mode(first, "score").configured.setBy)
        assertEquals("counts", mode(first, "addr.city").configured.mode.spelled)
        assertEquals("full", mode(first, "addr.zip").configured.mode.spelled)
        assertEquals("write.metadata.metrics.column.addr.zip = full", mode(first, "addr.zip").configured.setBy)
        assertTrue(byFile.getValue(first).values.all { it.agrees == true }, byFile.getValue(first).values.toString())

        // `tag` went from none to counts between the two inserts: file 2 records a count, file 1 nothing, each as configured when written.
        assertEquals("counts", mode(second, "tag").configured.mode.spelled)
        assertEquals("counts", mode(second, "tag").recorded)
        assertTrue(byFile.getValue(second).values.all { it.agrees == true }, byFile.getValue(second).values.toString())
        val history = MetricsConfigHistory(FixtureCatalog.icebergModel("metrics").metadatas.map { it.metadata })
        val snapshots = FixtureCatalog.icebergModel("metrics").metadatas.last().metadata.snapshots.sortedBy { it.sequenceNumber }
        assertEquals("none", history.at(snapshots[0].snapshotId)!!.config.modeOf("tag").mode.spelled)
        assertEquals("counts", history.at(snapshots[1].snapshotId)!!.config.modeOf("tag").mode.spelled)
        assertEquals("the metadata that committed snapshot ${snapshots[0].snapshotId}", history.at(snapshots[0].snapshotId)!!.source)
        assertTrue(history.at(-1)!!.source.startsWith("the newest metadata, no retained version"))
    }

    @Test
    fun `metricsw records the first three columns and nothing past them`() {
        val all = checks("metricsw").map { it.second }.associateBy { it.column }
        listOf("c1", "c2", "c3.a", "c3.b").forEach { column ->
            assertEquals("truncate(16)", all.getValue(column).configured.mode.spelled, column)
            assertEquals("within the first 3 columns (write.metadata.metrics.max-inferred-column-defaults = 3)", all.getValue(column).configured.setBy, column)
            assertEquals("counts and bounds", all.getValue(column).recorded, column)
        }
        listOf("c4", "c5", "c6").forEach { column ->
            assertEquals("none", all.getValue(column).configured.mode.spelled, column)
            assertEquals("past the first 3 columns (write.metadata.metrics.max-inferred-column-defaults = 3)", all.getValue(column).configured.setBy, column)
            assertEquals("nothing", all.getValue(column).recorded, column)
        }
        assertTrue(all.values.all { it.agrees == true })
        // Without the property a six-column table is under the limit of a hundred, and every column keeps the default.
        val schema = FixtureCatalog.icebergModel("metricsw").metadatas.last().metadata.currentSchemaModel()
        val unlimited = metricsConfigOf(emptyMap(), schema, null)
        assertEquals("the default, nothing configured", unlimited.modeOf("c6").setBy)
        assertEquals("truncate(16)", unlimited.modeOf("c6").mode.spelled)
    }

    @Test
    fun `a mode is parsed the way MetricsModes reads it, and an invalid one falls back to the default`() {
        assertEquals(MetricsMode.Truncate(8), MetricsMode.parse(" TRUNCATE(8) "))
        assertEquals(MetricsMode.Counts, MetricsMode.parse("Counts"))
        assertNull(MetricsMode.parse("truncate(x)"))
        assertNull(MetricsMode.parse("bogus"))
        val config = metricsConfigOf(mapOf(METRICS_DEFAULT_PROPERTY to "bogus", "${METRICS_COLUMN_PROPERTY_PREFIX}id" to "nope"), null, null)
        assertEquals("truncate(16)", config.default.mode.spelled)
        assertTrue(config.default.setBy.contains("no mode the writer accepts"))
        assertEquals("truncate(16)", config.modeOf("id").mode.spelled)
    }

    @Test
    fun `bounds under counts, a bound past a truncation, and nothing under counts are each named, and an all-null column is not judged`() {
        val schema = FixtureCatalog.icebergModel("metrics").metadatas.last().metadata.currentSchemaModel()!!
        fun stats(id: Int, name: String, lower: String?, upper: String?, values: Long?, nulls: Long?) = ColumnStats(
            fieldId = id, columnName = name, type = IcebergType.StringType,
            lowerBound = lower?.let { DecodedValue(it, it, IcebergType.StringType, it.toByteArray()) },
            upperBound = upper?.let { DecodedValue(it, it, IcebergType.StringType, it.toByteArray()) },
            valueCount = values, nullValueCount = nulls, nanValueCount = null, columnSizeBytes = null,
        )
        val config = metricsConfigOf(
            mapOf(METRICS_DEFAULT_PROPERTY to "counts", "${METRICS_COLUMN_PROPERTY_PREFIX}name" to "truncate(4)", "${METRICS_COLUMN_PROPERTY_PREFIX}note" to "full"),
            schema, null,
        )
        val parquet = DataFile(filePath = "f.parquet", fileFormat = "PARQUET", content = DataFileContent.DATA)
        val checks = metricsModeChecks(
            listOf(
                stats(3, "note", "alpha", "bravo", 3, 0),       // full: as configured
                stats(2, "name", "alpha", "bravo", 3, 0),       // truncate(4) with five-character bounds
                stats(4, "tag", "x", "y", 3, 0),                // counts, with bounds recorded
                stats(1, "id", null, null, null, null),         // counts, nothing recorded
                stats(6, "addr", null, null, null, null),       // a struct: not a leaf, not listed
            ),
            schema, config, parquet,
        ).associateBy { it.column }
        assertEquals(true, checks.getValue("note").agrees)
        assertEquals("a bound of 5 characters under truncate(4)", checks.getValue("name").reason)
        assertEquals("bounds recorded under counts", checks.getValue("tag").reason)
        assertEquals("no counts recorded under counts", checks.getValue("id").reason)
        assertTrue("addr" !in checks && "addr.city" in checks)

        val allNull = metricsModeChecks(listOf(stats(3, "note", null, null, 3, 3)), schema, config, parquet).single { it.column == "note" }
        assertNull(allNull.agrees)
        assertEquals("every value is null, so no bound is recorded under any mode", allNull.reason)

        // The Avro writer records no metrics under any mode, so an Avro file is not judged.
        assertEquals(emptyList(), metricsModeChecks(listOf(stats(1, "id", null, null, null, null)), schema, config, parquet.copy(fileFormat = "AVRO")))
    }

    /** The pruning table's "not evaluated" names the mode where the mode is why: a filter on `tag` or `id` has nothing to rule a file out against, by configuration. */
    @Test
    fun `a term the file cannot evaluate says which metrics mode is why`() {
        val graph = GraphLayoutService.layoutGraph(UnifiedTableModel(Paths.get(FixtureCatalog.icebergDir("metrics").absolutePath)), showRows = false)
        fun reasons(filter: String) = evaluateScan(graph, (parseScanFilter(filter) as ScanFilterParse.Parsed).filter).files.values.flatMap { r -> r.outcomes.map { it.reason } }
        val tag = reasons("tag = 'x'")
        assertTrue(tag.isNotEmpty() && tag.all { it.contains("its metrics mode is none (write.metadata.metrics.column.tag = none), so nothing is recorded for it") || it.contains("its metrics mode is counts (write.metadata.metrics.column.tag = counts), so no bound is recorded for it") }, tag.toString())
        val id = reasons("id = 1")
        assertTrue(id.isNotEmpty() && id.all { it.contains("its metrics mode is counts (write.metadata.metrics.default = counts), so no bound is recorded for it") }, id.toString())
        // A column with bounds says nothing about its mode: the bounds decide.
        val name = reasons("name = 'zulu'")
        assertTrue(name.isNotEmpty() && name.none { it.contains("metrics mode") }, name.toString())
    }

    @Test
    fun `the integrity report lists a column recorded against its configuration under metrics modes`() {
        val report = FixtureCatalog.icebergModel("metrics").integrityReport()
        assertTrue(report.findings.none { it.check == IntegrityCheck.METRICS_MODES }, report.findings.toString())
        assertTrue(report.checked >= 14, "${report.checked} figures checked")
    }
}
