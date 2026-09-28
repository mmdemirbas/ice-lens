package service

import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicLong

/**
 * Copies of files on this machine for DuckDB, which cannot read the originals — an Avro file
 * under a codec it refuses ([AvroTranscodeCache]), a file on HDFS ([WebHdfs.localCopyOf]). Each
 * copy is written once per session under a key the caller chooses, and kept until the cache
 * would pass [maxBytes], when the least recently used copies go; a file larger than [maxBytes]
 * on its own is refused with the caller's sentence.
 *
 * **A copy keeps the file's name**, in a directory of its own under the session's temp root,
 * because the Paimon readers tell a `UNION ALL`'s files apart by the `filename` column's last
 * segment. The root is removed when the JVM exits; nothing beside the original is ever written.
 */
class LocalCopyCache(private val maxBytes: Long, private val prefix: String) {
    private val root: Path by lazy {
        Files.createTempDirectory(prefix).also { dir ->
            Runtime.getRuntime().addShutdownHook(Thread { runCatching { deleteTree(dir) } })
        }
    }
    private val copies = LinkedHashMap<String, Copy>(16, 0.75f, true)
    private var held = 0L
    private val next = AtomicLong()

    private class Copy(val path: Path, val bytes: Long)

    /** Copies held now. */
    val size: Int @Synchronized get() = copies.size

    /**
     * The copy under [key], written by [write] to a path named [name] if there is none yet.
     * [sourceBytes], where known, refuses a file that cannot fit before anything is written;
     * the written size is checked again, since a copy may be larger than its source.
     * [tooLarge] says why a file of so many bytes is refused, and whether it was written first.
     */
    @Synchronized
    fun copyOf(
        key: String,
        name: String,
        sourceBytes: Long?,
        tooLarge: (bytes: Long, written: Boolean) -> String,
        write: (Path) -> Unit,
    ): Path {
        copies[key]?.let { copy ->
            if (Files.isRegularFile(copy.path)) return copy.path
            copies.remove(key); held -= copy.bytes
        }
        if (sourceBytes != null && sourceBytes > maxBytes) throw IllegalArgumentException(tooLarge(sourceBytes, false))
        val dir = Files.createDirectories(root.resolve(next.incrementAndGet().toString()))
        val target = dir.resolve(name)
        try {
            write(target)
        } catch (e: Throwable) {
            runCatching { Files.deleteIfExists(target); Files.deleteIfExists(dir) }
            throw e
        }
        val bytes = Files.size(target)
        if (bytes > maxBytes) {
            runCatching { Files.deleteIfExists(target); Files.deleteIfExists(dir) }
            throw IllegalArgumentException(tooLarge(bytes, true))
        }
        evictUntilRoomFor(bytes)
        copies[key] = Copy(target, bytes)
        held += bytes
        return target
    }

    private fun evictUntilRoomFor(bytes: Long) {
        val it = copies.entries.iterator()
        while (held + bytes > maxBytes && it.hasNext()) {
            val (_, copy) = it.next()
            it.remove()
            held -= copy.bytes
            runCatching { Files.deleteIfExists(copy.path); Files.deleteIfExists(copy.path.parent) }
        }
    }

    private fun deleteTree(dir: Path) {
        Files.walk(dir).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach { runCatching { Files.deleteIfExists(it) } } }
    }
}
