package service

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.longOrNull
import org.slf4j.LoggerFactory
import java.io.IOException
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap

/**
 * HDFS read over WebHDFS — the namenode's HTTP API — with no Hadoop client on the classpath.
 *
 * ### Why WebHDFS and not the HDFS client
 *
 * The HDFS client speaks the namenode's RPC protocol and pulls in `hadoop-client` and its
 * transitive tail, tens of megabytes for one scheme, where WebHDFS is plain HTTP and JSON the
 * JDK already reads. It is enabled on a Hadoop 3 namenode by default, on port 9870 (50070 on
 * Hadoop 2), and it is what Hadoop's own `webhdfs://` scheme means, so a location is written the
 * way a Hadoop user already writes it: `webhdfs://namenode:9870/warehouse/db/orders`, and
 * `swebhdfs://` for the same over TLS.
 *
 * ### What is read, and who it is read as
 *
 * `LISTSTATUS` for a directory — one request per directory, with the length and modification
 * time of every child, which object storage through DuckDB never gives — and `OPEN` for a file's
 * bytes. Nothing else: this backend issues `GET` and never a `PUT`, `POST` or `DELETE`, so the
 * read-only rule is a property of the code rather than of the caller. Requests carry simple
 * authentication's `user.name` — the name [setUsers] gave for the namenode, else what Hadoop's
 * own client sends when nothing is configured ([defaultUser]). A Kerberos-secured cluster asks
 * for SPNEGO, which this does not speak; it answers 401 and the message says so.
 *
 * ### The redirect is followed here, not by the HTTP client
 *
 * The namenode answers an `OPEN` with a 307 to a datanode, named by the hostname that datanode
 * registered with. When that name does not resolve from this machine — a cluster's internal DNS,
 * a container's hostname — an automatic redirect fails with an error that names neither host.
 * Followed by hand, the failure says which datanode the namenode sent the read to, which is the
 * one fact the reader needs.
 *
 * ### DuckDB reads a local copy
 *
 * DuckDB has no HDFS reader, so every SQL read of a data file on HDFS goes through a copy on this
 * machine ([localCopyOf]), streamed once per session into a bounded cache ([LocalCopyCache]).
 * Metadata files come through [read] like object storage's do, whole and cached by
 * [ObjectStorage].
 */
object WebHdfs : ObjectStorage.RemoteBackend {
    private val logger = LoggerFactory.getLogger(WebHdfs::class.java)

    /** Hadoop's own schemes for WebHDFS, over HTTP and over HTTPS. */
    val SCHEMES = setOf("webhdfs", "swebhdfs")

    /** What the session's copies of HDFS files for DuckDB may hold at once — the Avro copies' bound. */
    const val MAX_COPY_BYTES = 2L shl 30

    /**
     * How many directories a warehouse scan lists before it stops — the local scan's bound. A
     * warehouse is walked, since WebHDFS has no recursive listing, and the walk stops at every
     * table it finds, so this is directories *between* tables, not files.
     */
    const val MAX_WALK_DIRECTORIES = 10_000

    private val users = ConcurrentHashMap<String, String>()
    private val json = Json { ignoreUnknownKeys = true }
    private val client: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .followRedirects(HttpClient.Redirect.NEVER)
        .build()
    private val copies by lazy { LocalCopyCache(MAX_COPY_BYTES, "ice-lens-hdfs") }

    fun serves(location: String): Boolean = StorageLocation.schemeOf(location) in SCHEMES

    /** `webhdfs://namenode:9870` — what a user name is configured for. */
    fun scopeOf(location: String): String {
        val (scheme, authority, _) = split(location)
        return "$scheme://$authority"
    }

    /**
     * What Hadoop's client sends as `user.name` when nothing is configured: `HADOOP_USER_NAME`,
     * else the login name — `UserGroupInformation`'s order under simple authentication.
     */
    val defaultUser: String
        get() = System.getenv("HADOOP_USER_NAME")?.takeIf { it.isNotBlank() } ?: System.getProperty("user.name")

    /**
     * The user each namenode is asked as, keyed by any location on it. Replaces what was there,
     * and forgets every cached listing, since a listing made as one user says nothing about what
     * another may read.
     */
    fun setUsers(byLocation: Map<String, String>) {
        users.clear()
        byLocation.forEach { (location, user) -> users[scopeOf(location)] = user }
        ObjectStorage.clearCache()
    }

    fun userFor(location: String): String = users[scopeOf(location)] ?: defaultUser

    override fun list(root: String): List<ObjectStorage.Entry> {
        val body = try {
            get(root, "LISTSTATUS")
        } catch (e: NotFound) {
            return emptyList()
        }
        val statuses = json.parseToJsonElement(body).jsonObject["FileStatuses"]?.jsonObject
            ?.get("FileStatus")?.jsonArray.orEmpty()
        return statuses.mapNotNull { element ->
            val status = element.jsonObject
            val name = status.text("pathSuffix")
            // A LISTSTATUS on a file answers the file itself, with an empty suffix: nothing is under it.
            if (name.isNullOrEmpty()) return@mapNotNull null
            ObjectStorage.Entry(
                name = name,
                isDirectory = status.text("type") == "DIRECTORY",
                size = status["length"]?.jsonPrimitive?.longOrNull,
                modifiedMs = status["modificationTime"]?.jsonPrimitive?.longOrNull,
            )
        }
    }

    /**
     * A directory is a directory here, empty or not — unlike object storage, HDFS has them.
     * Answered from the parent's cached listing, so a table's detection costs its root's one
     * `LISTSTATUS` and not a request per marker; the namenode's root, which has no parent, is
     * asked directly.
     */
    override fun isDirectory(location: String): Boolean {
        val (scheme, authority, path) = split(location)
        val trimmed = path.trimEnd('/')
        if (trimmed.isEmpty()) {
            return runCatching { get(location, "GETFILESTATUS").contains("\"DIRECTORY\"") }.getOrDefault(false)
        }
        val parent = "$scheme://$authority${trimmed.substringBeforeLast('/')}"
        val name = trimmed.substringAfterLast('/')
        return runCatching { ObjectStorage.list(parent).firstOrNull { it.name == name }?.isDirectory == true }
            .getOrDefault(false)
    }

    override fun read(location: String): ByteArray {
        ObjectStorage.entryOf(location)?.size?.let { size ->
            if (size > ObjectStorage.MAX_OBJECT_BYTES) {
                throw ObjectStorage.ObjectStorageException(
                    location, "$location is $size bytes; this reader holds at most ${ObjectStorage.MAX_OBJECT_BYTES}",
                )
            }
        }
        return open(location, HttpResponse.BodyHandlers.ofByteArray())
    }

    /**
     * A copy of [location] on this machine, for DuckDB — made once per session and kept in a
     * bounded cache, keyed by the file's length and modification time so a file rewritten in
     * place is copied again. The copy keeps the file's name, which the Paimon readers tell a
     * `UNION ALL`'s files apart by.
     */
    fun localCopyOf(location: String): String {
        val entry = ObjectStorage.entryOf(location)
        val name = location.substringAfterLast('/')
        return copies.copyOf(
            key = "$location|${entry?.size}|${entry?.modifiedMs}",
            name = name,
            sourceBytes = entry?.size,
            tooLarge = { bytes, _ ->
                "$name is $bytes bytes, larger than the $MAX_COPY_BYTES bytes of copies of HDFS files this session keeps for DuckDB"
            },
        ) { target -> open(location, HttpResponse.BodyHandlers.ofFile(target)) }.toString()
    }

    /** Copies made this session, for the tests. */
    internal val copiesMade: Int get() = copies.size

    /**
     * The files [pattern] matches, walked a directory at a time through [ObjectStorage.list] and
     * so through its cache: `*` and `?` within one segment, `**` for any number of segments, a
     * trailing `**` for every file below. Directories are never returned, as DuckDB's glob
     * returns none. A directory that is not there matches nothing; a refusal is thrown.
     */
    override fun glob(pattern: String): List<String> {
        val (scheme, authority, path) = split(pattern)
        val found = LinkedHashSet<String>()
        walk("$scheme://$authority", path.split('/').filter { it.isNotEmpty() }, found)
        return found.toList()
    }

    private fun walk(dir: String, segments: List<String>, found: MutableSet<String>) {
        if (segments.isEmpty()) return
        val head = segments.first()
        val rest = segments.drop(1)
        if (head == "**") {
            if (rest.isEmpty()) return walk(dir, listOf("**", "*"), found)
            walk(dir, rest, found)
            ObjectStorage.list(dir).filter { it.isDirectory }.forEach { walk("$dir/${it.name}", segments, found) }
            return
        }
        val matcher = segmentPattern(head)
        ObjectStorage.list(dir).filter { matcher.matches(it.name) }.forEach { entry ->
            val child = "$dir/${entry.name}"
            if (rest.isEmpty()) {
                if (!entry.isDirectory) found += child
            } else if (entry.isDirectory) {
                walk(child, rest, found)
            }
        }
    }

    /**
     * Every table under [root], by walking it: each directory is put to [TableFormatDetector] —
     * the one definition of the markers, answered from the cached listings — and a table stops
     * the walk, so a table's own `data/` and `metadata/` are never listed to find tables in them.
     * Hidden directories are not entered. Stops after [MAX_WALK_DIRECTORIES] and says so.
     *
     * A refusal on [root] is thrown, as on object storage. A refusal *below* it is logged and the
     * directory passed over: permissions on HDFS are per directory, and a warehouse holding one
     * team's private database would otherwise list as nothing for everyone else.
     */
    override fun globTables(root: String): Map<String, TableFormat> {
        val found = sortedMapOf<String, TableFormat>()
        val queue = ArrayDeque(listOf(root))
        var listed = 0
        // Lists the root first, so a refusal is thrown rather than read as a warehouse holding nothing.
        ObjectStorage.list(root)
        while (queue.isNotEmpty() && listed < MAX_WALK_DIRECTORIES) {
            val dir = queue.removeFirst()
            listed++
            val format = TableFormatDetector.detect(StorageLocation.pathOf(dir))
            if (format != TableFormat.UNKNOWN) {
                found[dir] = format
                continue
            }
            val children = try {
                ObjectStorage.list(dir)
            } catch (e: PermissionDenied) {
                logger.warn("Not scanning {}: {}", dir, e.message)
                continue
            }
            children.filter { it.isDirectory && !it.name.startsWith(".") }.forEach { queue += "$dir/${it.name}" }
        }
        if (queue.isNotEmpty()) {
            logger.warn("Stopped scanning {} after {} directories; {} not listed", root, listed, queue.size)
        }
        return found
    }

    /** A namenode request's body, the refusals mapped to sentences — see [failure]. */
    private fun get(location: String, op: String): String = send(location, request(location, op), HttpResponse.BodyHandlers.ofString())

    /** An `OPEN`: the namenode's 307 followed to the datanode by hand, for the reason the class note gives. */
    private fun <T> open(location: String, body: HttpResponse.BodyHandler<T>): T {
        val redirect = exchange(location, request(location, "OPEN"), HttpResponse.BodyHandlers.ofString())
        if (redirect.statusCode() !in 300..399) {
            if (redirect.statusCode() == 200) {
                // A namenode configured to serve the bytes itself — read them again, as the handler asked.
                return send(location, request(location, "OPEN"), body)
            }
            throw failure(location, redirect.statusCode(), redirect.body())
        }
        val target = redirect.headers().firstValue("Location").orElse(null)
            ?: throw ObjectStorage.ObjectStorageException(location, "The namenode redirected a read of $location to nowhere")
        val datanode = runCatching { URI(target).authority }.getOrNull() ?: target
        val response = try {
            client.send(HttpRequest.newBuilder(URI(target)).timeout(READ_TIMEOUT).GET().build(), body)
        } catch (e: IOException) {
            throw ObjectStorage.ObjectStorageException(
                location,
                "The namenode sent the read of $location to datanode $datanode, which could not be reached " +
                    "(${e.javaClass.simpleName}${e.message?.let { ": $it" }.orEmpty()}). A datanode's address " +
                    "must resolve and be reachable from this machine.",
                e,
            )
        }
        if (response.statusCode() != 200) {
            throw failure(location, response.statusCode(), (response.body() as? String).orEmpty())
        }
        return response.body()
    }

    private fun <T> send(location: String, request: HttpRequest, body: HttpResponse.BodyHandler<T>): T {
        val response = exchange(location, request, body)
        if (response.statusCode() == 404) throw NotFound(location)
        if (response.statusCode() != 200) throw failure(location, response.statusCode(), (response.body() as? String).orEmpty())
        return response.body()
    }

    private fun <T> exchange(location: String, request: HttpRequest, body: HttpResponse.BodyHandler<T>): HttpResponse<T> =
        try {
            client.send(request, body)
        } catch (e: IOException) {
            val (_, authority, _) = split(location)
            throw ObjectStorage.ObjectStorageException(
                location,
                "Could not reach the namenode at $authority for $location (${e.javaClass.simpleName}" +
                    "${e.message?.let { ": $it" }.orEmpty()}). Check the host and the WebHDFS port — 9870 on " +
                    "Hadoop 3, 50070 on Hadoop 2 — and that the service is up.",
                e,
            )
        }

    private fun request(location: String, op: String): HttpRequest {
        val (scheme, authority, path) = split(location)
        val http = if (scheme == "swebhdfs") "https" else "http"
        // The multi-argument constructor quotes what a path needs quoting, `%` included.
        val rawPath = URI(null, null, "/webhdfs/v1" + path.ifEmpty { "/" }, null).rawPath
        val user = URLEncoder.encode(userFor(location), Charsets.UTF_8)
        return HttpRequest.newBuilder(URI.create("$http://$authority$rawPath?op=$op&user.name=$user"))
            .timeout(READ_TIMEOUT)
            .header("Accept", "application/json")
            .GET()
            .build()
    }

    /**
     * A namenode's refusal, said in terms a reader can act on. The body is the `RemoteException`
     * WebHDFS answers with, whose class names the case: a permission, a standby namenode, a
     * missing path, and — a 401 — a cluster that wants Kerberos.
     */
    internal fun failure(location: String, status: Int, body: String): ObjectStorage.ObjectStorageException {
        val remote = runCatching { json.parseToJsonElement(body).jsonObject["RemoteException"]?.jsonObject }.getOrNull()
        val exception = remote?.text("exception").orEmpty()
        val message = remote?.text("message")?.lineSequence()?.firstOrNull().orEmpty()
        val (_, authority, _) = split(location)
        val text = when {
            status == 401 || exception == "AuthenticationException" ->
                "The namenode at $authority asked for authentication. This reader sends simple authentication's " +
                    "user name only; a Kerberos-secured cluster (SPNEGO) is not read yet."
            exception == "AccessControlException" -> return PermissionDenied(
                location,
                "Permission denied reading $location as '${userFor(location)}': $message. Read it as a user HDFS " +
                    "lets list and read this path.",
            )
            exception == "StandbyException" ->
                "The namenode at $authority is a standby and serves no reads. Open the active namenode's address."
            status == 404 || exception == "FileNotFoundException" ->
                "Nothing at $location. The namenode answered, so the address is right — check the path."
            else -> "Could not read $location: HTTP $status${exception.takeIf { it.isNotEmpty() }?.let { " $it" }.orEmpty()}" +
                message.takeIf { it.isNotEmpty() }?.let { ": $it" }.orEmpty()
        }
        return ObjectStorage.ObjectStorageException(location, text)
    }

    /** HDFS refused the user a path — told apart because a warehouse walk passes over one ([globTables]). */
    class PermissionDenied(location: String, message: String) : ObjectStorage.ObjectStorageException(location, message)

    /** A 404 on a listing, which is an empty answer rather than an error. */
    private class NotFound(location: String) : IOException("Nothing at $location")

    /** `webhdfs://host:port/a/b` → (`webhdfs`, `host:port`, `/a/b`), without URI decoding the path. */
    private fun split(location: String): Triple<String, String, String> {
        val scheme = location.substringBefore("://").lowercase()
        val rest = location.substringAfter("://")
        val authority = rest.substringBefore('/')
        val path = rest.substring(authority.length)
        require(authority.isNotEmpty()) { "$location names no namenode" }
        return Triple(scheme, authority, path)
    }

    /** One glob segment as a pattern: `*` and `?` stay within the segment, everything else is literal. */
    private fun segmentPattern(segment: String): Regex = Regex(
        segment.split('*').joinToString(".*") { part -> part.split('?').joinToString(".") { Regex.escape(it) } },
    )

    private fun JsonObject.text(key: String): String? = get(key)?.jsonPrimitive?.content

    private val READ_TIMEOUT: Duration = Duration.ofSeconds(60)
}
