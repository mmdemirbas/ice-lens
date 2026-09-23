package service

import model.DeltaAction
import model.DeltaLastCheckpoint
import model.parseDeltaAction
import model.parseDeltaLastCheckpoint
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.name

/**
 * How a checkpoint is named (`PROTOCOL.md`, "Checkpoint Naming Scheme"): a classic single file, a
 * multi-part set, or a UUID-named V2 checkpoint in JSON or Parquet that may point at sidecars.
 */
enum class DeltaCheckpointNaming { CLASSIC, MULTI_PART, UUID }

/** One checkpoint of a version — every part it is made of, and whether all of them are there. */
data class DeltaCheckpointFile(
    val version: Long,
    val naming: DeltaCheckpointNaming,
    val parts: List<Path>,
    /** The part count the names state; a multi-part checkpoint with a part missing is not usable. */
    val expectedParts: Int,
) {
    val complete: Boolean get() = parts.size == expectedParts
    val isJson: Boolean get() = parts.singleOrNull()?.name?.endsWith(".json") == true
}

/** `<x>.<y>.compacted.json` — the reconciled actions of commits x through y. */
data class DeltaLogCompaction(val startVersion: Long, val endVersion: Long, val path: Path)

/** What `_delta_log/` holds, sorted by version, before any of it is read. */
data class DeltaLogListing(
    val logDir: Path,
    val commits: Map<Long, Path>,
    /** Every checkpoint found per version; a version can carry more than one (two writers raced). */
    val checkpoints: Map<Long, List<DeltaCheckpointFile>>,
    val compactions: List<DeltaLogCompaction>,
    val lastCheckpoint: Path?,
    /** Files in `_delta_log/` this listing gave no meaning to — named rather than dropped. */
    val unrecognised: List<Path>,
)

/**
 * Reads a Delta table's log. Commit files are JSON lines; checkpoints and sidecars are Parquet and
 * are read through DuckDB, each row turned into the JSON object of its action columns so that
 * [parseDeltaAction] reads them too — one decoder for every place an action is written.
 */
object DeltaReader {
    const val LOG_DIR = "_delta_log"

    private val COMMIT = Regex("""^(\d{20})\.json$""")
    private val CLASSIC = Regex("""^(\d{20})\.checkpoint\.parquet$""")
    private val MULTI_PART = Regex("""^(\d{20})\.checkpoint\.(\d{10})\.(\d{10})\.parquet$""")
    private val UUID_NAMED = Regex("""^(\d{20})\.checkpoint\.[0-9a-fA-F-]{36}\.(json|parquet)$""")
    private val COMPACTION = Regex("""^(\d{20})\.(\d{20})\.compacted\.json$""")

    /** Whether [name] is a commit or a checkpoint part — a file that names a table version. */
    fun isVersionFileName(name: String): Boolean =
        COMMIT.matches(name) || CLASSIC.matches(name) || MULTI_PART.matches(name) || UUID_NAMED.matches(name)

    fun list(tablePath: Path): DeltaLogListing {
        val logDir = tablePath.resolve(LOG_DIR)
        val names = Files.list(logDir).use { s -> s.filter { Files.isRegularFile(it) }.toList() }
            .filterNot { it.name.startsWith(".") } // Hadoop's .crc sidecars
            .sortedBy { it.name }
        val commits = sortedMapOf<Long, Path>()
        val multiParts = mutableMapOf<Pair<Long, Int>, MutableList<Path>>()
        val checkpoints = mutableMapOf<Long, MutableList<DeltaCheckpointFile>>()
        val compactions = mutableListOf<DeltaLogCompaction>()
        val unrecognised = mutableListOf<Path>()
        var last: Path? = null
        for (p in names) {
            val n = p.name
            COMMIT.matchEntire(n)?.let { commits[it.groupValues[1].toLong()] = p; continue }
            CLASSIC.matchEntire(n)?.let {
                val v = it.groupValues[1].toLong()
                checkpoints.getOrPut(v) { mutableListOf() } += DeltaCheckpointFile(v, DeltaCheckpointNaming.CLASSIC, listOf(p), 1)
                continue
            }
            MULTI_PART.matchEntire(n)?.let {
                // add, not +=: a Path is an Iterable<Path> of its segments, and += would append those
                multiParts.getOrPut(it.groupValues[1].toLong() to it.groupValues[3].toInt()) { mutableListOf() }.add(p)
                continue
            }
            UUID_NAMED.matchEntire(n)?.let {
                val v = it.groupValues[1].toLong()
                checkpoints.getOrPut(v) { mutableListOf() } += DeltaCheckpointFile(v, DeltaCheckpointNaming.UUID, listOf(p), 1)
                continue
            }
            COMPACTION.matchEntire(n)?.let {
                compactions += DeltaLogCompaction(it.groupValues[1].toLong(), it.groupValues[2].toLong(), p)
                continue
            }
            if (n == "_last_checkpoint") { last = p; continue }
            unrecognised.add(p)
        }
        for ((key, parts) in multiParts) {
            val (v, of) = key
            checkpoints.getOrPut(v) { mutableListOf() } +=
                DeltaCheckpointFile(v, DeltaCheckpointNaming.MULTI_PART, parts.sortedBy { it.name }, of)
        }
        return DeltaLogListing(logDir, commits, checkpoints.toSortedMap(), compactions, last, unrecognised)
    }

    /** One commit file, action by action, in file order. */
    fun readCommit(path: Path): List<DeltaAction> =
        Files.newBufferedReader(path).useLines { lines -> lines.mapNotNull { parseDeltaAction(it) }.toList() }

    fun readLastCheckpoint(path: Path): DeltaLastCheckpoint =
        parseDeltaLastCheckpoint(Files.readString(path))

    /**
     * A checkpoint's actions — every part, and on a V2 checkpoint the add and remove actions of the
     * sidecars it names, which live in `_delta_log/_sidecars/`.
     */
    fun readCheckpoint(checkpoint: DeltaCheckpointFile, logDir: Path): DeltaCheckpointRead {
        val own = checkpoint.parts.flatMap { part -> if (checkpoint.isJson) readCommit(part) else readParquetActions(part) }
        val sidecars = own.mapNotNull { it.sidecar }.map { sc ->
            val sidecarPath = logDir.resolve("_sidecars").resolve(sc.path.substringAfterLast('/'))
            sidecarPath to readParquetActions(sidecarPath)
        }
        return DeltaCheckpointRead(own, sidecars)
    }

    /** A Parquet file of actions — a checkpoint part or a sidecar — each row back to its JSON. */
    fun readParquetActions(path: Path): List<DeltaAction> {
        val (safePath, ext) = SampleRowReader.resolveForQuery(path.toString())
        require(ext == "parquet") { "A checkpoint or sidecar is Parquet, got .$ext" }
        return DuckDb.withConnection { conn ->
            conn.prepareStatement("SELECT to_json(t)::VARCHAR FROM read_parquet(?, hive_partitioning = false) t").use { st ->
                st.setString(1, safePath)
                st.executeQuery().use { rs ->
                    buildList { while (rs.next()) parseDeltaAction(rs.getString(1))?.let { add(it) } }
                }
            }
        }
    }
}

/** A checkpoint as read: the actions in its own files, and each sidecar's with its path. */
data class DeltaCheckpointRead(
    val actions: List<DeltaAction>,
    val sidecars: List<Pair<Path, List<DeltaAction>>>,
) {
    val allActions: List<DeltaAction> get() = actions + sidecars.flatMap { it.second }
}
