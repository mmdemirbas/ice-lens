package service

import java.net.URI
import java.nio.file.Path
import java.nio.file.Paths

/**
 * The one place a location string becomes something this app can open.
 *
 * Every reader here took a `String` and called `java.io.File(it)`, which can only ever name a file
 * on the machine's own disk. `java.nio.Path` is the wider type: `Paths.get(URI)` dispatches to
 * whichever [java.nio.file.spi.FileSystemProvider] claims the scheme, so the same call opens a
 * local file today and an object-storage one as soon as a provider for that scheme is installed.
 * Nothing downstream has to know which it got — which is the whole point of routing every open
 * through here rather than letting each reader decide.
 *
 * Three shapes arrive, and they are not interchangeable:
 *
 * - a bare path (`/wh/db/t/metadata/v1.metadata.json`) — the common case
 * - a `file:` URI, which Iceberg writes into `manifest_list` on some engines
 * - another scheme (`s3:`, `hdfs:`, `gs:`, `abfs:`) — a table that was never copied down
 *
 * A scheme with no installed provider throws [UnsupportedLocationException] rather than being
 * quietly treated as a relative path. `Path.of("s3://bucket/key")` does not fail: it yields a
 * *relative* path whose first segment is `s3:`, which then reports "not found" against the working
 * directory. That is a wrong answer wearing a plausible message, and it is the failure this type
 * exists to make impossible.
 */
object StorageLocation {

    /** Matches anything with a URI scheme, so a bare Windows drive letter (`C:\`) is excluded. */
    private val URI_SCHEME = Regex("^[a-zA-Z][a-zA-Z0-9+.-]{1,}:.*")

    /** A location naming a scheme no [java.nio.file.spi.FileSystemProvider] on the classpath serves. */
    class UnsupportedLocationException(val location: String, val scheme: String, cause: Throwable? = null) :
        IllegalArgumentException(
            "Cannot open '$location': nothing on the classpath reads the '$scheme' scheme", cause,
        )

    /** The scheme of [location], or null when it names a plain filesystem path. */
    fun schemeOf(location: String): String? =
        if (location.matches(URI_SCHEME)) location.substringBefore(':').lowercase() else null

    /** Whether [location] names something other than the local filesystem. */
    fun isRemote(location: String): Boolean = schemeOf(location)?.let { it != "file" } == true

    /**
     * [location] as a [Path], on whatever filesystem its scheme names.
     *
     * @throws UnsupportedLocationException when no provider serves the scheme.
     */
    fun pathOf(location: String): Path {
        val scheme = schemeOf(location) ?: return Path.of(location)
        val uri = runCatching { URI(location) }.getOrElse {
            throw UnsupportedLocationException(location, scheme, it)
        }
        return runCatching { Paths.get(uri) }.getOrElse {
            throw UnsupportedLocationException(location, scheme, it)
        }
    }
}
