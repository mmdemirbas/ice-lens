package service

import java.util.Collections
import org.slf4j.LoggerFactory

private val logger = LoggerFactory.getLogger("service.ObjectStorage")

/**
 * Reading object storage through DuckDB.
 *
 * ### Why DuckDB rather than a filesystem library
 *
 * The alternative measured was AWS's `aws-java-nio-spi-for-s3`, which installs a real
 * `FileSystemProvider` for `s3://`. Three things decided against it, all observed by running both
 * against a MinIO container:
 *
 * - DuckDB is **already here**. The SPI adds 48 jars and 34 MB to `core`, for one scheme.
 * - DuckDB reads the **sample rows** too. The SPI does not — `read_parquet` would still need its
 *   own credentials, so that route means configuring the same key in two places, and a reader who
 *   typed it once and still got "access denied" from half the app would be right to call it a bug.
 * - The SPI's errors do not survive the boundary. `Files.exists()` is specified to answer `false`
 *   rather than throw, so a 403 on a bucket came back **identical to a missing table** — the
 *   likeliest failure, reported as the one thing it is not. Through DuckDB a 403 and a 404 arrive
 *   as distinct messages, which is what [ObjectStorageException] carries out.
 *
 * ### What is cached, and why there has to be a cache
 *
 * A graph is built for every artifact the metadata names, and `Files.isRegularFile` runs once per
 * data file — a thousand-file table would be a thousand round trips before anything is drawn. Two
 * caches stand between that and the network: a **directory listing** per prefix, and the **bytes**
 * of objects small enough to hold. Both are cleared when a table is opened, which is the point at
 * which this app re-reads anything.
 */
object ObjectStorage {

    /**
     * The largest object read whole into memory.
     *
     * Everything that reaches [readBytes] is a metadata artifact — a `metadata.json`, an Avro
     * manifest, a Puffin footer — and those are kilobytes to a few megabytes. Data files never
     * come through here: DuckDB reads those itself with `read_parquet`, which streams. The cap is
     * what keeps a mis-pointed read from becoming an `OutOfMemoryError` with no explanation.
     */
    const val MAX_OBJECT_BYTES = 256L * 1024 * 1024

    /** How many objects' bytes are retained. Metadata files, so a small count is a lot of table. */
    private const val CONTENT_CACHE_ENTRIES = 64

    /** What a listing found directly under a prefix. */
    data class Entry(val name: String, val isDirectory: Boolean)

    /** A read that reached the store and was refused or came back wrong. */
    class ObjectStorageException(val location: String, message: String, cause: Throwable? = null) :
        java.io.IOException(message, cause)

    private val listings = Collections.synchronizedMap(mutableMapOf<String, List<Entry>>())
    private val contents = Collections.synchronizedMap(
        object : LinkedHashMap<String, ByteArray>(16, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ByteArray>) =
                size > CONTENT_CACHE_ENTRIES
        }
    )

    /** Forgets every listing and every retained object. Called when the credentials change. */
    fun clearCache() {
        listings.clear()
        contents.clear()
    }

    /**
     * Forgets everything cached under [prefix], listings and bytes alike.
     *
     * This is what makes a *re-read* possible. The caches exist so that building a graph does not
     * become a round trip per artifact, but the same caches turn "has this table changed" into a
     * question about memory rather than about the store — a fingerprint served from a listing is
     * frozen at whatever the first read produced, and would report a table unchanged forever.
     *
     * Both maps are swept, because a stale *listing* and stale *bytes* fail differently: the first
     * hides a new snapshot, the second re-decodes the metadata the table used to have.
     */
    fun invalidate(prefix: String) {
        val root = prefix.trimEnd('/')
        synchronized(listings) { listings.keys.removeIf { it == root || it.startsWith("$root/") } }
        synchronized(contents) { contents.keys.removeIf { it == root || it.startsWith("$root/") } }
    }

    /**
     * What sits directly under [prefix].
     *
     * Globs the whole subtree with a recursive wildcard and derives the one level from it, rather
     * than globbing a single level, because object storage has no directory entries: a one-level
     * glob returns the objects at that level and **silently omits every subdirectory**. Deriving
     * from the subtree is the only way the answer is complete.
     *
     * The cost of that is real and bounded by where it is used: this app lists `metadata/`,
     * `snapshot/` and `schema/`, which hold tens of files. Do not point it at a warehouse root —
     * use a targeted glob for that, which is what [globTables] is.
     */
    fun list(prefix: String): List<Entry> = listings.getOrPut(prefix.trimEnd('/')) {
        val root = prefix.trimEnd('/')
        val found = glob("$root/**")
        val entries = LinkedHashMap<String, Entry>()
        found.forEach { url ->
            val relative = url.removePrefix("$root/")
            if (relative.isEmpty() || relative == url) return@forEach
            val cut = relative.indexOf('/')
            if (cut < 0) entries[relative] = Entry(relative, isDirectory = false)
            else relative.substring(0, cut).let { entries.putIfAbsent(it, Entry(it, isDirectory = true)) }
        }
        entries.values.toList()
    }

    /** Whether [location] names an object. Answered from its parent's listing, so it is cached. */
    fun isRegularFile(location: String): Boolean = entryOf(location)?.isDirectory == false

    /**
     * Whether [location] has anything under it.
     *
     * Asked directly rather than through the parent's listing: the parent of a table root is the
     * database, and listing *its* subtree would enumerate every table in it to answer a question
     * about one. A one-level glob is enough here — a prefix with any direct child is a directory,
     * and every directory this app asks about (`metadata/`, `snapshot/`, `schema/`) holds files.
     */
    fun isDirectory(location: String): Boolean {
        val root = location.trimEnd('/')
        listings[root]?.let { return it.isNotEmpty() }
        return runCatching { glob("$root/*").isNotEmpty() }.getOrDefault(false)
    }

    fun exists(location: String): Boolean = isRegularFile(location) || isDirectory(location)

    /** The object's size, from its bytes — see [readBytes] for why that is not as costly as it reads. */
    fun size(location: String): Long = readBytes(location).size.toLong()

    /** [location]'s bytes, retained so the several readers that open one artifact pay one round trip. */
    fun readBytes(location: String): ByteArray {
        contents[location]?.let { return it }
        val bytes = runCatching {
            DuckDb.withConnection { conn ->
                conn.prepareStatement("SELECT content FROM read_blob(?)").use { statement ->
                    statement.setString(1, location)
                    statement.executeQuery().use { rows ->
                        if (!rows.next()) throw ObjectStorageException(location, "No object at $location")
                        rows.getBytes(1) ?: ByteArray(0)
                    }
                }
            }
        }.getOrElse { failure ->
            if (failure is ObjectStorageException) throw failure
            throw ObjectStorageException(location, describe(location, failure.message.orEmpty()), failure)
        }
        if (bytes.size > MAX_OBJECT_BYTES) {
            throw ObjectStorageException(
                location, "$location is ${bytes.size} bytes; this reader holds at most $MAX_OBJECT_BYTES",
            )
        }
        contents[location] = bytes
        return bytes
    }

    fun readText(location: String): String = readBytes(location).decodeToString()

    /**
     * Every Iceberg or Paimon table under [warehouse], as one glob per format.
     *
     * A warehouse scan on a local disk walks directories; over object storage that is a request per
     * level per table. Two globs answer the same question in two round trips, because the marker
     * each format is detected by is a path shape: a `.metadata.json` under the table's `metadata`
     * directory for Iceberg, a `snapshot-` file under its `snapshot` directory for Paimon.
     *
     * **A refusal is thrown, not returned as an empty warehouse.** Both globs used to be wrapped in
     * a `runCatching { }.getOrDefault(emptyList())`, which was reaching for the wrong thing: [glob]
     * already answers an empty list for a prefix with nothing under it, so the only failures that
     * wrapper could ever absorb were the real ones. A key that no longer opens the bucket came back
     * as "this warehouse holds no tables" — the same answer as an empty warehouse, and the reader
     * had nothing to open that might have said otherwise.
     */
    fun globTables(warehouse: String): List<String> {
        val root = warehouse.trimEnd('/')
        val iceberg = glob("$root/**/metadata/*.metadata.json")
            .mapNotNull { it.substringBeforeLast("/metadata/", "").takeIf(String::isNotEmpty) }
        val paimon = glob("$root/**/snapshot/snapshot-*")
            .mapNotNull { it.substringBeforeLast("/snapshot/", "").takeIf(String::isNotEmpty) }
        return (iceberg + paimon).distinct().sorted()
    }

    /** The raw glob. A pattern that matches nothing is an empty list, not an error. */
    fun glob(pattern: String): List<String> = DuckDb.withConnection { conn ->
        runCatching {
            conn.prepareStatement("SELECT file FROM glob(?)").use { statement ->
                statement.setString(1, pattern)
                statement.executeQuery().use { rows ->
                    buildList { while (rows.next()) add(rows.getString(1)) }
                }
            }
        }.getOrElse { failure ->
            // A glob over a prefix with nothing under it is not an error, but a refusal is. The
            // distinction is the whole reason this goes through DuckDB rather than Files.exists.
            val message = failure.message.orEmpty()
            if (message.contains("404") || message.contains("No files found")) emptyList()
            else throw ObjectStorageException(pattern, describe(pattern, message), failure)
        }
    }

    /**
     * A store's own error, said in terms a reader can act on.
     *
     * The three that actually happen are told apart, because "could not read the table" for all of
     * them is the one-generic-message failure: a 403 means fix the key, a 404 means fix the path,
     * and a connection refused means the endpoint is wrong or the service is down. Only the
     * unrecognised case falls back to what the engine said.
     */
    internal fun describe(location: String, message: String): String = when {
        message.contains("403") || message.contains("Authentication Failure", ignoreCase = true) ->
            "Access denied reading $location. The credentials reached the store and it refused them — " +
                "check the key, and that it is allowed to list and read this bucket."
        message.contains("404") || message.contains("Not Found", ignoreCase = true) ->
            "Nothing at $location. The store answered, so the credentials are fine — check the path."
        message.contains("Connection", ignoreCase = true) || message.contains("Could not establish", ignoreCase = true) ->
            "Could not reach the store for $location. Check the endpoint and that the service is up."
        message.contains("No secret found", ignoreCase = true) || message.contains("credential", ignoreCase = true) ->
            "No credentials are configured for $location. Add them for this location and try again."
        else -> "Could not read $location: $message"
    }

    /** [location]'s entry in its parent's listing, or null when the parent holds no such name. */
    private fun entryOf(location: String): Entry? {
        val trimmed = location.trimEnd('/')
        val cut = trimmed.lastIndexOf('/')
        if (cut <= 0) return null
        val parent = trimmed.substring(0, cut)
        val name = trimmed.substring(cut + 1)
        if (name.isEmpty() || parent.endsWith(":/")) return null
        return runCatching { list(parent).firstOrNull { it.name == name } }
            .onFailure { logger.debug("Listing {} failed while looking for {}: {}", parent, name, it.message) }
            .getOrNull()
    }
}
