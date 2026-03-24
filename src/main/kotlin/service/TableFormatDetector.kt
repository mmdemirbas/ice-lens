package service

import org.slf4j.LoggerFactory
import java.io.File

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
    fun detect(dir: File): TableFormat {
        if (!dir.isDirectory) return TableFormat.UNKNOWN
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
    fun isIcebergTable(dir: File): Boolean {
        val metaDir = File(dir, "metadata")
        return metaDir.exists() && metaDir.isDirectory && metaDir.listFiles { f ->
            f.name.endsWith(".metadata.json")
        }?.isNotEmpty() == true
    }

    /** Checks whether the given directory is a Paimon table (has `snapshot/` + `schema/` dirs). */
    fun isPaimonTable(dir: File): Boolean {
        val snapshotDir = File(dir, "snapshot")
        val schemaDir = File(dir, "schema")
        return snapshotDir.exists() && snapshotDir.isDirectory &&
            schemaDir.exists() && schemaDir.isDirectory
    }
}
