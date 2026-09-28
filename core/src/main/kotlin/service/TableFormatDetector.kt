package service

import model.isMetadataFileName
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import kotlin.streams.asSequence

private val logger = LoggerFactory.getLogger(TableFormatDetector::class.java)

/** Detected table format for a directory. */
enum class TableFormat {
    ICEBERG,
    PAIMON,
    DELTA,
    UNKNOWN,
}

/**
 * Detects the table format of a directory by examining its structure.
 *
 * - **Paimon**: has both `snapshot/` and `schema/` subdirectories
 * - **Delta**: has a `_delta_log/` subdirectory holding a commit or a checkpoint
 * - **Iceberg**: has a `metadata/` subdirectory containing at least one `*.metadata.json` file
 * - **Unknown**: none of the above markers found
 *
 * Paimon is asked first because a Paimon table can carry both markers: under
 * `metadata.iceberg.storage = table-location` every commit also writes Iceberg metadata to
 * `<table>/metadata/` so an Iceberg reader can open the data files (`pic`). That directory is
 * the table's *export*, and the table is Paimon — opened as Iceberg, its snapshots, levels and
 * merge engine are invisible and its own `snapshot/`, `schema/` and `manifest/` read as orphans.
 * Delta is asked before Iceberg for the same reason: a UniForm table writes Iceberg metadata
 * beside its `_delta_log/`, and the log is the table.
 */
object TableFormatDetector {

    /**
     * Returns the detected [TableFormat] for the given directory.
     *
     * The markers decide, and the directory itself is not asked whether it is one: object storage
     * has no directories, `ObjectStorage.isDirectory` looks one level down for an object, and a
     * table root holds only prefixes (`metadata/`, `data/`) — so asking first answered UNKNOWN for
     * every table in a bucket, read as Iceberg by `readTableModel` and therefore wrong only for
     * Paimon and Delta. A path that is not a directory has no markers, which is the same answer.
     */
    fun detect(dir: Path): TableFormat {
        val format = when {
            isPaimonTable(dir) -> TableFormat.PAIMON
            isDeltaTable(dir) -> TableFormat.DELTA
            isIcebergTable(dir) -> TableFormat.ICEBERG
            else -> TableFormat.UNKNOWN
        }
        if (format != TableFormat.UNKNOWN) {
            logger.debug("Detected {} table at: {}", format, dir)
        }
        return format
    }

    /** Checks whether the given directory is an Iceberg table. */
    fun isIcebergTable(dir: Path): Boolean {
        val metaDir = dir.resolve("metadata")
        if (!Files.isDirectory(metaDir)) return false
        // A listing is a network round trip once the directory is in object storage, so this stops
        // at the first match rather than materialising every metadata version of the table.
        return runCatching {
            Files.list(metaDir).use { entries ->
                entries.asSequence().any { isMetadataFileName(it.fileName.toString()) }
            }
        }.getOrDefault(false)
    }

    /**
     * Checks whether the given directory is a Delta table: `_delta_log/` holding a commit file or a
     * checkpoint. The directory alone is not enough — a table whose first commit failed leaves an
     * empty one, and a log cleaned up to its checkpoints holds no commit at version 0.
     */
    fun isDeltaTable(dir: Path): Boolean {
        val logDir = dir.resolve(DeltaReader.LOG_DIR)
        if (!Files.isDirectory(logDir)) return false
        return runCatching {
            Files.list(logDir).use { entries ->
                entries.asSequence().any { DeltaReader.isVersionFileName(it.fileName.toString()) }
            }
        }.getOrDefault(false)
    }

    /** Checks whether the given directory is a Paimon table (has `snapshot/` + `schema/` dirs). */
    fun isPaimonTable(dir: Path): Boolean =
        Files.isDirectory(dir.resolve("snapshot")) && Files.isDirectory(dir.resolve("schema"))
}
