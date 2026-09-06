package service

import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import kotlin.streams.asSequence

private val logger = LoggerFactory.getLogger(TableFormatDetector::class.java)

/** Detected table format for a directory. */
enum class TableFormat {
    ICEBERG,
    PAIMON,
    UNKNOWN,
}

/**
 * Detects the table format of a directory by examining its structure.
 *
 * - **Iceberg**: has a `metadata/` subdirectory containing at least one `*.metadata.json` file
 * - **Paimon**: has both `snapshot/` and `schema/` subdirectories
 * - **Unknown**: none of the above markers found
 */
object TableFormatDetector {

    /** Returns the detected [TableFormat] for the given directory. */
    fun detect(dir: Path): TableFormat {
        if (!Files.isDirectory(dir)) return TableFormat.UNKNOWN
        val format = when {
            isIcebergTable(dir) -> TableFormat.ICEBERG
            isPaimonTable(dir) -> TableFormat.PAIMON
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
                entries.asSequence().any { it.fileName.toString().endsWith(".metadata.json") }
            }
        }.getOrDefault(false)
    }

    /** Checks whether the given directory is a Paimon table (has `snapshot/` + `schema/` dirs). */
    fun isPaimonTable(dir: Path): Boolean =
        Files.isDirectory(dir.resolve("snapshot")) && Files.isDirectory(dir.resolve("schema"))
}
