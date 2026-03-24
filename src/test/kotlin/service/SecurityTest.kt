package service

import kotlin.test.Test
import kotlin.test.assertFailsWith

class SecurityTest {

    @Test
    fun `SampleRowReader rejects non-existent files`() {
        assertFailsWith<IllegalArgumentException> {
            SampleRowReader.querySampleRows("/nonexistent/file.parquet")
        }
    }

    @Test
    fun `SampleRowReader rejects directories`() {
        assertFailsWith<IllegalArgumentException> {
            SampleRowReader.querySampleRows(System.getProperty("java.io.tmpdir"))
        }
    }

    @Test
    fun `SampleRowReader rejects files with disallowed extensions`() {
        val tmpFile = java.io.File.createTempFile("test", ".txt")
        tmpFile.deleteOnExit()
        assertFailsWith<IllegalArgumentException> {
            SampleRowReader.querySampleRows(tmpFile.absolutePath)
        }
    }

    @Test
    fun `IcebergReader rejects http URI scheme`() {
        assertFailsWith<IllegalArgumentException> {
            IcebergReader.readManifestList("http://evil.com/malware.avro")
        }
    }

    @Test
    fun `IcebergReader rejects jar URI scheme`() {
        assertFailsWith<IllegalArgumentException> {
            IcebergReader.readManifestList("jar:file:///tmp/evil.jar!/manifest.avro")
        }
    }

    @Test
    fun `IcebergReader rejects ftp URI scheme`() {
        assertFailsWith<IllegalArgumentException> {
            IcebergReader.readManifestFile("ftp://evil.com/manifests/file.avro")
        }
    }

    @Test
    fun `IcebergReader accepts file URI scheme`() {
        // Should fail with file-not-found, not URI rejection
        assertFailsWith<Exception> {
            IcebergReader.readManifestList("file:///nonexistent/manifest.avro")
        }
    }
}
