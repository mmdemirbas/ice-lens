package service

import java.net.URI
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.ReadOnlyFileSystemException
import java.nio.file.StandardOpenOption
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The path algebra of the object-storage filesystem, with no store behind it.
 *
 * This is the half that has to be right before any credential is typed, and it is the half a
 * network test would not isolate: the model layer resolves manifests against their list's
 * directory, takes a snapshot's parent, and reads a file's name out of a path, and every one of
 * those is a `Path` operation this class now has to answer for a scheme the JDK does not ship.
 */
class ObjectFileSystemTest {

    private fun path(url: String): Path = StorageLocation.pathOf(url)

    /**
     * The provider is discovered, which nothing else here would prove.
     *
     * It is registered through `META-INF/services`, so it is reached by the JDK's `ServiceLoader`
     * and never by name. A missing or misspelled service file compiles perfectly and fails only
     * when a reader pastes an `s3://` URL, which is the moment it is least useful to find out.
     */
    @Test
    fun `the s3 scheme is installed and reachable through Paths`() {
        val schemes = FileSystems.getDefault().provider().let {
            java.nio.file.spi.FileSystemProvider.installedProviders().map { p -> p.scheme }
        }
        listOf("s3", "gs", "gcs", "r2").forEach {
            assertTrue(it in schemes, "the '$it' scheme should be installed, and the installed set is $schemes")
        }
        assertTrue(Paths.get(URI("s3://warehouse/db/t")) is ObjectPath)
    }

    /**
     * `toString` is the full URL, deliberately — see [ObjectPath].
     *
     * Every downstream use of a path's string is "show it" or "open it again": DuckDB is handed
     * one to read rows, the inspector prints one, the copy button copies one. A root-relative
     * `/db/t/data/x.parquet` is the single form that cannot be used to find the file again.
     */
    @Test
    fun `a path prints as the url it came from`() {
        assertEquals("s3://warehouse/db/t/metadata/v1.metadata.json",
            path("s3://warehouse/db/t/metadata/v1.metadata.json").toString())
        assertEquals("s3://warehouse", path("s3://warehouse").toString())
        assertEquals("gs://bucket/a", path("gs://bucket/a").toString())
    }

    @Test
    fun `resolve, parent and fileName are what the model layer asks for`() {
        val table = path("s3://warehouse/db/t")
        assertEquals("s3://warehouse/db/t/metadata", table.resolve("metadata").toString())
        assertEquals("s3://warehouse/db/t/metadata/v1.json", table.resolve("metadata/v1.json").toString())
        assertEquals("s3://warehouse/db/t", table.resolve("metadata").parent.toString())
        assertEquals("v1.json", table.resolve("metadata/v1.json").fileName.toString())
        assertEquals("t", table.fileName.toString())
        // A bucket root has no parent, and saying so is what stops a walk running off the top.
        assertNull(path("s3://warehouse").parent)
    }

    /**
     * Resolving an absolute URL replaces rather than appends.
     *
     * This is the shape `resolveRecordedOrRelative` produces: a manifest records an absolute
     * location, and it is resolved against the metadata directory. Appending would build
     * `s3://b/db/t/metadata/s3://b/db/t/data/x` — a path that exists nowhere and reads as missing.
     */
    @Test
    fun `resolving an absolute url replaces the base`() {
        val dir = path("s3://warehouse/db/t/metadata")
        assertEquals("s3://other/x/y", dir.resolve("s3://other/x/y").toString())
        assertEquals("s3://warehouse/a/b", dir.resolve("/a/b").toString())
    }

    @Test
    fun `normalize removes the dots a recorded path can carry`() {
        assertEquals("s3://b/a/c", path("s3://b/a/x/../c").normalize().toString())
        assertEquals("s3://b/a/c", path("s3://b/a/./c").normalize().toString())
    }

    @Test
    fun `relativize and startsWith agree about containment`() {
        val table = path("s3://warehouse/db/t")
        val file = path("s3://warehouse/db/t/data/x.parquet")
        assertTrue(file.startsWith(table))
        assertFalse(table.startsWith(file))
        assertEquals("data/x.parquet", table.relativize(file).toString())
    }

    @Test
    fun `two paths for the same object are equal and hash alike`() {
        assertEquals(path("s3://b/a/x"), path("s3://b/a/x"))
        assertEquals(path("s3://b/a/x").hashCode(), path("s3://b/a/x").hashCode())
        // A different scheme over the same names is a different object.
        assertFalse(path("s3://b/a/x") == path("gs://b/a/x"))
    }

    @Test
    fun `name elements are addressable, which is what a path walk needs`() {
        val file = path("s3://warehouse/db/t/data/x.parquet")
        assertEquals(4, file.nameCount)
        assertEquals("db", file.getName(0).toString())
        assertEquals("x.parquet", file.getName(3).toString())
        assertEquals("t/data", file.subpath(1, 3).toString())
        assertEquals(listOf("db", "t", "data", "x.parquet"), file.map { it.toString() })
    }

    /**
     * The whole filesystem refuses to write, and that is a type rather than a promise.
     *
     * "All data access is read-only" is a rule this repository states in prose. Here it is the
     * only behaviour available, so a future write cannot be introduced by accident against
     * somebody's production warehouse.
     */
    @Test
    fun `every mutating operation is refused`() {
        val file = path("s3://warehouse/db/t/data/x.parquet")
        assertTrue(file.fileSystem.isReadOnly)
        assertFailsWith<ReadOnlyFileSystemException> { Files.delete(file) }
        assertFailsWith<ReadOnlyFileSystemException> { Files.createDirectory(path("s3://warehouse/new")) }
        assertFailsWith<ReadOnlyFileSystemException> {
            Files.newByteChannel(file, mutableSetOf(StandardOpenOption.WRITE))
        }
    }

    /**
     * `toFile` fails rather than returning something plausible.
     *
     * A `java.io.File` names a file on this machine's disk and there is no such file. Returning
     * one is exactly how a remote path silently becomes a read of a local path that is not there —
     * the failure the whole filesystem exists to remove — so it says so instead.
     */
    @Test
    fun `toFile refuses instead of inventing a local path`() {
        val thrown = assertFailsWith<UnsupportedOperationException> {
            path("s3://warehouse/db/t/data/x.parquet").toFile()
        }
        assertTrue("s3://warehouse/db/t/data/x.parquet" in thrown.message.orEmpty())
    }

    /**
     * A scheme nothing serves is named, not silently treated as a relative path.
     *
     * `Path.of("hdfs://nn/x")` does not fail — it yields a relative path whose first segment is
     * `hdfs:`, which then reports "not found" against the working directory. That is a wrong
     * answer wearing a plausible message, and it is the one this type exists to prevent.
     */
    @Test
    fun `an unserved scheme is reported as itself`() {
        val thrown = assertFailsWith<StorageLocation.UnsupportedLocationException> {
            StorageLocation.pathOf("hdfs://namenode:8020/warehouse/db/t")
        }
        assertEquals("hdfs", thrown.scheme)
        // And the failure it replaces: the JDK would have produced a usable-looking relative path.
        assertFalse(Path.of("hdfs://namenode:8020/warehouse/db/t").isAbsolute)
    }

    @Test
    fun `a plain path is still a plain path`() {
        assertFalse(StorageLocation.isRemote("/wh/db/t"))
        assertFalse(StorageLocation.isRemote("file:///wh/db/t"))
        assertTrue(StorageLocation.isRemote("s3://b/k"))
        assertEquals("/wh/db/t", StorageLocation.pathOf("/wh/db/t").toString())
        assertNull(StorageLocation.schemeOf("/wh/db/t"))
        // A Windows drive letter is not a scheme, and reading it as one would break every
        // Windows path in the workspace.
        assertNull(StorageLocation.schemeOf("C:\\warehouse\\db"))
    }

    /**
     * The rule as a check: a location string becomes a [Path] through [StorageLocation.pathOf]
     * and nowhere else in core. A node carries its file's location as a string — what DuckDB is
     * handed — and `Paths.get(string)` on an `s3://` location is the relative-path trap above,
     * which five readers of a delete vector's `localPath` had walked into before this existed.
     */
    @Test
    fun `no reader in core turns a location string into a path on its own`() {
        val root = generateSequence(java.io.File(".").absoluteFile) { it.parentFile }.first { java.io.File(it, "settings.gradle.kts").isFile }
        val offenders = java.io.File(root, "core/src/main/kotlin").walkTopDown()
            .filter { it.isFile && it.extension == "kt" && it.name != "StorageLocation.kt" }
            // Code, not comments: the provider's own doc names the JDK call it serves.
            .filter { file ->
                val code = file.readLines().filterNot { it.trimStart().startsWith("*") || it.trimStart().startsWith("//") }.joinToString("\n")
                code.contains("Paths.get(") || Regex("""\bPath\.of\(""").containsMatchIn(code)
            }
            .map { it.name }
            .toList()
        assertEquals(emptyList(), offenders)
    }
}
