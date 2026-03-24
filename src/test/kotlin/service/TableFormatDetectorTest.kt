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
            assertEquals(TableFormat.ICEBERG, TableFormatDetector.detect(dir))
            assertTrue(TableFormatDetector.isIcebergTable(dir))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `detect returns UNKNOWN for empty directory`() {
        val dir = createTempDirectory("empty").toFile()
        try {
            assertEquals(TableFormat.UNKNOWN, TableFormatDetector.detect(dir))
            assertFalse(TableFormatDetector.isIcebergTable(dir))
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
            assertEquals(TableFormat.UNKNOWN, TableFormatDetector.detect(dir))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `detect returns UNKNOWN for non-directory`() {
        val file = File.createTempFile("test", ".txt")
        file.deleteOnExit()
        assertEquals(TableFormat.UNKNOWN, TableFormatDetector.detect(file))
    }

    @Test
    fun `detect returns PAIMON for directory with snapshot and schema subdirs`() {
        val dir = createTempDirectory("paimon").toFile()
        File(dir, "snapshot").mkdirs()
        File(dir, "schema").mkdirs()
        try {
            assertEquals(TableFormat.PAIMON, TableFormatDetector.detect(dir))
            assertTrue(TableFormatDetector.isPaimonTable(dir))
            assertFalse(TableFormatDetector.isIcebergTable(dir))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `isPaimonTable returns false for directory with only snapshot`() {
        val dir = createTempDirectory("half-paimon").toFile()
        File(dir, "snapshot").mkdirs()
        try {
            assertFalse(TableFormatDetector.isPaimonTable(dir))
            assertEquals(TableFormat.UNKNOWN, TableFormatDetector.detect(dir))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `isPaimonTable returns false for directory with only schema`() {
        val dir = createTempDirectory("half-paimon2").toFile()
        File(dir, "schema").mkdirs()
        try {
            assertFalse(TableFormatDetector.isPaimonTable(dir))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `Iceberg takes precedence over Paimon when both markers exist`() {
        val dir = createTempDirectory("both").toFile()
        val metaDir = File(dir, "metadata")
        metaDir.mkdirs()
        File(metaDir, "v1.metadata.json").writeText("{}")
        File(dir, "snapshot").mkdirs()
        File(dir, "schema").mkdirs()
        try {
            assertEquals(TableFormat.ICEBERG, TableFormatDetector.detect(dir))
        } finally {
            dir.deleteRecursively()
        }
    }
}
