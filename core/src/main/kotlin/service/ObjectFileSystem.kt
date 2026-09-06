package service

import java.io.IOException
import java.net.URI
import java.nio.ByteBuffer
import java.nio.channels.SeekableByteChannel
import java.nio.file.AccessDeniedException
import java.nio.file.AccessMode
import java.nio.file.CopyOption
import java.nio.file.DirectoryStream
import java.nio.file.FileStore
import java.nio.file.FileSystem
import java.nio.file.LinkOption
import java.nio.file.NoSuchFileException
import java.nio.file.NotDirectoryException
import java.nio.file.OpenOption
import java.nio.file.Path
import java.nio.file.PathMatcher
import java.nio.file.ReadOnlyFileSystemException
import java.nio.file.StandardOpenOption
import java.nio.file.WatchEvent
import java.nio.file.WatchKey
import java.nio.file.WatchService
import java.nio.file.attribute.BasicFileAttributeView
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.FileAttribute
import java.nio.file.attribute.FileAttributeView
import java.nio.file.attribute.FileTime
import java.nio.file.attribute.UserPrincipalLookupService
import java.nio.file.spi.FileSystemProvider

/**
 * A read-only `java.nio` filesystem over object storage, served by [ObjectStorage].
 *
 * ### Why a filesystem and not a storage interface threaded through the readers
 *
 * Because `java.nio.Path` already *is* this app's storage abstraction. The model layer resolves
 * manifests against their list's directory, walks `metadata/`, asks whether a data file is there —
 * all in `Path` and `Files`, all of it correct for any filesystem. Threading a storage object
 * through instead would mean carrying it into `UnifiedTableModel`, every `UnifiedSnapshot`,
 * `UnifiedManifest` and `UnifiedDataFile`, and into the `DeferredRead` lambdas that read lazily
 * from inside graph nodes — a parameter on everything, to say something only the location knows.
 * Supplying the missing provider is the smaller change and the one that leaves the model alone.
 *
 * ### Read-only is enforced here, not just intended
 *
 * "All data access is read-only" is a rule this repository states; on this filesystem it is also a
 * type. Every mutating operation throws [ReadOnlyFileSystemException], so a future write cannot be
 * introduced by accident against somebody's production warehouse.
 *
 * ### What it does not do
 *
 * There is no modification time. `glob` returns names and nothing else, so
 * [BasicFileAttributes.lastModifiedTime] is the epoch. Both places this app reads one treat it as
 * a last-resort tiebreaker behind the version number in the file's own name, and both already
 * tolerate not getting one. There is no [WatchService] and no `toFile()` — neither has a meaning
 * here, and both say so rather than returning something that looks like an answer.
 */
class ObjectFileSystem internal constructor(
    private val fsProvider: ObjectFileSystemProvider,
) : FileSystem() {

    val scheme: String get() = fsProvider.getScheme()

    override fun provider(): FileSystemProvider = fsProvider
    override fun close() = Unit
    override fun isOpen(): Boolean = true

    /** True, and the whole point — see the class note. */
    override fun isReadOnly(): Boolean = true
    override fun getSeparator(): String = "/"
    override fun getRootDirectories(): Iterable<Path> = emptyList()
    override fun getFileStores(): Iterable<FileStore> = emptyList()
    override fun supportedFileAttributeViews(): Set<String> = setOf("basic")

    override fun getPath(first: String, vararg more: String): Path {
        val joined = (listOf(first) + more).filter { it.isNotEmpty() }.joinToString("/")
        return ObjectPath.parse(this, joined)
    }

    override fun getPathMatcher(syntaxAndPattern: String): PathMatcher =
        throw UnsupportedOperationException("This filesystem has no path matcher")

    override fun getUserPrincipalLookupService(): UserPrincipalLookupService =
        throw UnsupportedOperationException("Object storage has no user principals")

    override fun newWatchService(): WatchService =
        throw UnsupportedOperationException("Object storage cannot be watched")
}

/**
 * A location in object storage: a bucket and the segments under it.
 *
 * **[toString] is the full URL**, not a root-relative path. That is a deliberate departure from
 * what a local `Path` prints, and it is what makes the rest of the app work unchanged: a path's
 * string is handed to DuckDB to read rows, put in front of the reader in the inspector, and copied
 * to the clipboard, and in every one of those a bare `/db/t/data/x.parquet` would be the one form
 * that cannot be used to find the file again.
 */
class ObjectPath internal constructor(
    private val fs: ObjectFileSystem,
    val bucket: String,
    val segments: List<String>,
    private val absolute: Boolean = true,
) : Path {

    companion object {
        /** Parses `scheme://bucket/a/b`, or a bucket-relative `/a/b`, against [fs]. */
        internal fun parse(fs: ObjectFileSystem, text: String): ObjectPath {
            val withoutScheme = text.substringAfter("://", missingDelimiterValue = "")
            if (withoutScheme.isNotEmpty()) {
                val bucket = withoutScheme.substringBefore('/')
                val rest = withoutScheme.substringAfter('/', missingDelimiterValue = "")
                return ObjectPath(fs, bucket, split(rest))
            }
            // No scheme: relative to nothing this filesystem can name on its own.
            return ObjectPath(fs, "", split(text), absolute = text.startsWith("/"))
        }

        private fun split(text: String): List<String> =
            text.split('/').filter { it.isNotEmpty() }
    }

    private fun with(segments: List<String>) = ObjectPath(fs, bucket, segments, absolute)

    val location: String get() = buildString {
        if (absolute && bucket.isNotEmpty()) append(fs.scheme).append("://").append(bucket)
        else if (absolute) append("")
        if (segments.isNotEmpty()) {
            if (isNotEmpty()) append('/')
            append(segments.joinToString("/"))
        }
    }

    override fun getFileSystem(): FileSystem = fs
    override fun isAbsolute(): Boolean = absolute && bucket.isNotEmpty()
    override fun getRoot(): Path? = if (isAbsolute) ObjectPath(fs, bucket, emptyList()) else null

    override fun getFileName(): Path? =
        segments.lastOrNull()?.let { ObjectPath(fs, "", listOf(it), absolute = false) }

    override fun getParent(): Path? =
        if (segments.isEmpty()) null else with(segments.dropLast(1))

    override fun getNameCount(): Int = segments.size

    override fun getName(index: Int): Path {
        require(index in segments.indices) { "No element $index in $location" }
        return ObjectPath(fs, "", listOf(segments[index]), absolute = false)
    }

    override fun subpath(beginIndex: Int, endIndex: Int): Path {
        require(beginIndex in segments.indices && endIndex in (beginIndex + 1)..segments.size) {
            "subpath($beginIndex, $endIndex) is outside $location"
        }
        return ObjectPath(fs, "", segments.subList(beginIndex, endIndex), absolute = false)
    }

    override fun startsWith(other: Path): Boolean {
        val that = other as? ObjectPath ?: return false
        return that.bucket == bucket && segments.size >= that.segments.size &&
            segments.subList(0, that.segments.size) == that.segments
    }

    override fun endsWith(other: Path): Boolean {
        val that = other as? ObjectPath ?: return false
        return segments.size >= that.segments.size &&
            segments.subList(segments.size - that.segments.size, segments.size) == that.segments
    }

    override fun normalize(): Path {
        val out = ArrayDeque<String>()
        segments.forEach { segment ->
            when (segment) {
                "." -> Unit
                ".." -> if (out.isNotEmpty() && out.last() != "..") out.removeLast() else out.addLast(segment)
                else -> out.addLast(segment)
            }
        }
        return with(out.toList())
    }

    override fun resolve(other: Path): Path {
        val that = other as? ObjectPath ?: return resolve(other.toString())
        return if (that.isAbsolute) that else with(segments + that.segments)
    }

    override fun resolve(other: String): Path {
        if (other.contains("://")) return parse(fs, other)
        if (other.startsWith("/")) return ObjectPath(fs, bucket, split(other))
        return with(segments + split(other))
    }

    override fun relativize(other: Path): Path {
        val that = other as? ObjectPath
            ?: throw IllegalArgumentException("Cannot relativize a ${other.javaClass.simpleName} against $location")
        require(that.bucket == bucket) { "$other is in another bucket than $location" }
        require(that.segments.size >= segments.size && that.segments.subList(0, segments.size) == segments) {
            "$other is not under $location"
        }
        return ObjectPath(fs, "", that.segments.drop(segments.size), absolute = false)
    }

    override fun toUri(): URI = URI(fs.scheme, bucket, "/" + segments.joinToString("/"), null)
    override fun toAbsolutePath(): Path = this
    override fun toRealPath(vararg options: LinkOption): Path = this

    /**
     * Always fails, and that is the contract.
     *
     * `java.io.File` names a file on this machine's disk; there is no such file. Returning
     * something plausible is how a remote path silently becomes a read of a local path that is not
     * there — the exact failure this whole filesystem exists to remove.
     */
    override fun toFile(): java.io.File =
        throw UnsupportedOperationException("$location is not on this machine's filesystem")

    override fun register(watcher: WatchService, events: Array<out WatchEvent.Kind<*>>, vararg modifiers: WatchEvent.Modifier): WatchKey =
        throw UnsupportedOperationException("Object storage cannot be watched")

    override fun compareTo(other: Path): Int = location.compareTo((other as ObjectPath).location)
    override fun iterator(): MutableIterator<Path> =
        segments.indices.map { getName(it) }.toMutableList().iterator()

    override fun equals(other: Any?): Boolean =
        other is ObjectPath && other.fs.scheme == fs.scheme && other.bucket == bucket &&
            other.segments == segments && other.absolute == absolute

    override fun hashCode(): Int = location.hashCode() * 31 + fs.scheme.hashCode()
    override fun toString(): String = location
}

/**
 * The provider. One subclass per scheme, because [getScheme] answers exactly one.
 *
 * Registered through `META-INF/services/java.nio.file.spi.FileSystemProvider`, which is what makes
 * `Paths.get(URI("s3://…"))` resolve here without anything calling this class by name.
 */
abstract class ObjectFileSystemProvider(private val schemeName: String) : FileSystemProvider() {

    private val fileSystem: ObjectFileSystem by lazy { ObjectFileSystem(this) }

    override fun getScheme(): String = schemeName
    override fun newFileSystem(uri: URI, env: MutableMap<String, *>?): FileSystem = fileSystem
    override fun getFileSystem(uri: URI): FileSystem = fileSystem

    override fun getPath(uri: URI): Path {
        require(uri.scheme.equals(schemeName, ignoreCase = true)) { "$uri is not a $schemeName URI" }
        val bucket = uri.authority ?: uri.host
            ?: throw IllegalArgumentException("$uri names no bucket")
        return ObjectPath(fileSystem, bucket, uri.path.orEmpty().split('/').filter { it.isNotEmpty() })
    }

    private fun objectPath(path: Path): ObjectPath =
        path as? ObjectPath ?: throw java.nio.file.ProviderMismatchException()

    override fun newByteChannel(
        path: Path,
        options: MutableSet<out OpenOption>,
        vararg attrs: FileAttribute<*>,
    ): SeekableByteChannel {
        val writing = options.any {
            it == StandardOpenOption.WRITE || it == StandardOpenOption.APPEND ||
                it == StandardOpenOption.CREATE || it == StandardOpenOption.CREATE_NEW
        }
        if (writing) throw ReadOnlyFileSystemException()
        val target = objectPath(path)
        val bytes = try {
            ObjectStorage.readBytes(target.location)
        } catch (e: ObjectStorage.ObjectStorageException) {
            throw e
        } catch (e: Exception) {
            throw IOException(ObjectStorage.describe(target.location, e.message.orEmpty()), e)
        }
        return ByteArrayChannel(bytes)
    }

    override fun newDirectoryStream(dir: Path, filter: DirectoryStream.Filter<in Path>): DirectoryStream<Path> {
        val target = objectPath(dir)
        val entries = ObjectStorage.list(target.location)
        if (entries.isEmpty() && !ObjectStorage.isDirectory(target.location)) {
            throw NotDirectoryException(target.location)
        }
        val paths = entries.map { target.resolve(it.name) }.filter { filter.accept(it) }
        return object : DirectoryStream<Path> {
            override fun iterator(): MutableIterator<Path> = paths.toMutableList().iterator()
            override fun close() = Unit
        }
    }

    @Suppress("UNCHECKED_CAST")
    override fun <A : BasicFileAttributes> readAttributes(path: Path, type: Class<A>, vararg options: LinkOption): A {
        require(type.isAssignableFrom(BasicFileAttributes::class.java)) {
            "This filesystem only has basic attributes, not ${type.simpleName}"
        }
        val target = objectPath(path)
        val directory = ObjectStorage.isDirectory(target.location)
        if (!directory && !ObjectStorage.isRegularFile(target.location)) {
            throw NoSuchFileException(target.location)
        }
        return ObjectAttributes(target, directory) as A
    }

    override fun readAttributes(path: Path, attributes: String, vararg options: LinkOption): MutableMap<String, Any> {
        val basic = readAttributes(path, BasicFileAttributes::class.java)
        return mutableMapOf(
            "size" to basic.size(),
            "isDirectory" to basic.isDirectory,
            "isRegularFile" to basic.isRegularFile,
            "isOther" to false,
            "isSymbolicLink" to false,
            "lastModifiedTime" to basic.lastModifiedTime(),
        )
    }

    override fun <V : FileAttributeView> getFileAttributeView(path: Path, type: Class<V>, vararg options: LinkOption): V? {
        if (!type.isAssignableFrom(BasicFileAttributeView::class.java)) return null
        @Suppress("UNCHECKED_CAST")
        return object : BasicFileAttributeView {
            override fun name() = "basic"
            override fun readAttributes(): BasicFileAttributes =
                this@ObjectFileSystemProvider.readAttributes(path, BasicFileAttributes::class.java)
            override fun setTimes(lastModified: FileTime?, lastAccess: FileTime?, create: FileTime?) =
                throw ReadOnlyFileSystemException()
        } as V
    }

    override fun checkAccess(path: Path, vararg modes: AccessMode) {
        if (modes.any { it == AccessMode.WRITE || it == AccessMode.EXECUTE }) throw AccessDeniedException(path.toString())
        val target = objectPath(path)
        if (!ObjectStorage.exists(target.location)) throw NoSuchFileException(target.location)
    }

    override fun isSameFile(path: Path, path2: Path): Boolean = path == path2
    override fun isHidden(path: Path): Boolean = false
    override fun getFileStore(path: Path): FileStore =
        throw UnsupportedOperationException("Object storage reports no file store")

    override fun createDirectory(dir: Path, vararg attrs: FileAttribute<*>) = throw ReadOnlyFileSystemException()
    override fun delete(path: Path) = throw ReadOnlyFileSystemException()
    override fun copy(source: Path, target: Path, vararg options: CopyOption) = throw ReadOnlyFileSystemException()
    override fun move(source: Path, target: Path, vararg options: CopyOption) = throw ReadOnlyFileSystemException()
    override fun setAttribute(path: Path, attribute: String, value: Any?, vararg options: LinkOption) =
        throw ReadOnlyFileSystemException()
}

/** See [ObjectFileSystem]'s note on the missing modification time. */
private class ObjectAttributes(
    private val path: ObjectPath,
    private val directory: Boolean,
) : BasicFileAttributes {
    override fun lastModifiedTime(): FileTime = FileTime.fromMillis(0)
    override fun lastAccessTime(): FileTime = lastModifiedTime()
    override fun creationTime(): FileTime = lastModifiedTime()
    override fun isRegularFile(): Boolean = !directory
    override fun isDirectory(): Boolean = directory
    override fun isSymbolicLink(): Boolean = false
    override fun isOther(): Boolean = false
    override fun size(): Long = if (directory) 0 else ObjectStorage.size(path.location)
    override fun fileKey(): Any = path.location
}

/** A read-only channel over bytes already in hand — see [ObjectStorage.readBytes]. */
private class ByteArrayChannel(private val bytes: ByteArray) : SeekableByteChannel {
    private var position = 0L
    private var open = true

    override fun read(destination: ByteBuffer): Int {
        if (!open) throw java.nio.channels.ClosedChannelException()
        if (position >= bytes.size) return -1
        val count = minOf(destination.remaining().toLong(), bytes.size - position).toInt()
        destination.put(bytes, position.toInt(), count)
        position += count
        return count
    }

    override fun write(source: ByteBuffer): Int = throw java.nio.channels.NonWritableChannelException()
    override fun position(): Long = position
    override fun position(newPosition: Long): SeekableByteChannel = apply {
        require(newPosition >= 0) { "A position cannot be negative: $newPosition" }
        position = newPosition
    }
    override fun size(): Long = bytes.size.toLong()
    override fun truncate(size: Long): SeekableByteChannel = throw java.nio.channels.NonWritableChannelException()
    override fun isOpen(): Boolean = open
    override fun close() { open = false }
}

/** Amazon S3, and everything that speaks its API — MinIO, Ceph, OBS, and the rest. */
class S3FileSystemProvider : ObjectFileSystemProvider("s3")

/** Google Cloud Storage, through DuckDB's S3-compatible interoperability endpoint. */
class GcsFileSystemProvider : ObjectFileSystemProvider("gs")

/** The other spelling of the same store, which is what Iceberg writes on some engines. */
class GcsAltFileSystemProvider : ObjectFileSystemProvider("gcs")

/** Cloudflare R2. */
class R2FileSystemProvider : ObjectFileSystemProvider("r2")
