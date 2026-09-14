package service

import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TableFormatDetectorTest {

    @Test
    fun `detect returns ICEBERG for directory with metadata json`() {
        val dir = createTempDirectory("iceberg").toFile()
        val metaDir = File(dir, "metadata")
        metaDir.mkdirs()
        File(metaDir, "v1.metadata.json").writeText("{}")
        try {
            assertEquals(TableFormat.ICEBERG, TableFormatDetector.detect(dir.toPath()))
            assertTrue(TableFormatDetector.isIcebergTable(dir.toPath()))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `detect returns UNKNOWN for empty directory`() {
        val dir = createTempDirectory("empty").toFile()
        try {
            assertEquals(TableFormat.UNKNOWN, TableFormatDetector.detect(dir.toPath()))
            assertFalse(TableFormatDetector.isIcebergTable(dir.toPath()))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `detect returns UNKNOWN for directory with metadata but no json files`() {
        val dir = createTempDirectory("nojson").toFile()
        val metaDir = File(dir, "metadata")
        metaDir.mkdirs()
        File(metaDir, "manifest.avro").writeText("")
        try {
            assertEquals(TableFormat.UNKNOWN, TableFormatDetector.detect(dir.toPath()))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `detect returns UNKNOWN for non-directory`() {
        val file = File.createTempFile("test", ".txt")
        file.deleteOnExit()
        assertEquals(TableFormat.UNKNOWN, TableFormatDetector.detect(file.toPath()))
    }

    @Test
    fun `detect returns PAIMON for directory with snapshot and schema subdirs`() {
        val dir = createTempDirectory("paimon").toFile()
        File(dir, "snapshot").mkdirs()
        File(dir, "schema").mkdirs()
        try {
            assertEquals(TableFormat.PAIMON, TableFormatDetector.detect(dir.toPath()))
            assertTrue(TableFormatDetector.isPaimonTable(dir.toPath()))
            assertFalse(TableFormatDetector.isIcebergTable(dir.toPath()))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `isPaimonTable returns false for directory with only snapshot`() {
        val dir = createTempDirectory("half-paimon").toFile()
        File(dir, "snapshot").mkdirs()
        try {
            assertFalse(TableFormatDetector.isPaimonTable(dir.toPath()))
            assertEquals(TableFormat.UNKNOWN, TableFormatDetector.detect(dir.toPath()))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `isPaimonTable returns false for directory with only schema`() {
        val dir = createTempDirectory("half-paimon2").toFile()
        File(dir, "schema").mkdirs()
        try {
            assertFalse(TableFormatDetector.isPaimonTable(dir.toPath()))
        } finally {
            dir.deleteRecursively()
        }
    }

    /**
     * Both markers is a real shape, not a corner case: under
     * `metadata.iceberg.storage = table-location` every Paimon commit also writes Iceberg metadata
     * under `<table>/metadata/` for an Iceberg reader to open the data files with — the `pic`
     * fixture. The table is the Paimon one and the Iceberg metadata is its export, so Paimon is
     * asked first. Read the other way round, the table's own `snapshot/`, `schema/` and
     * `manifest/` are orphans and its snapshots, levels and merge engine are invisible.
     */
    @Test
    fun `Paimon takes precedence over Iceberg when both markers exist`() {
        val dir = createTempDirectory("both").toFile()
        val metaDir = File(dir, "metadata")
        metaDir.mkdirs()
        File(metaDir, "v1.metadata.json").writeText("{}")
        File(dir, "snapshot").mkdirs()
        File(dir, "schema").mkdirs()
        try {
            assertEquals(TableFormat.PAIMON, TableFormatDetector.detect(dir.toPath()))
        } finally {
            dir.deleteRecursively()
        }
    }
}
