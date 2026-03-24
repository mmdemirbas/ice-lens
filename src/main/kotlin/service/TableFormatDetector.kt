package service

import java.io.File

/** Detected table format for a directory. */
enum class TableFormat {
    ICEBERG,
    // PAIMON — not yet implemented
    UNKNOWN,
}

/**
 * Detects the table format of a directory by examining its structure.
 *
 * - **Iceberg**: has a `metadata/` subdirectory containing at least one `*.metadata.json` file
 * - **Paimon** (future): has `snapshot/` + `schema/` subdirectories
 * - **Unknown**: none of the above markers found
 */
object TableFormatDetector {

    /** Returns the detected [TableFormat] for the given directory. */
    fun detect(dir: File): TableFormat {
        if (!dir.isDirectory) return TableFormat.UNKNOWN
        return when {
            isIcebergTable(dir) -> TableFormat.ICEBERG
            // Future: isPaimonTable(dir) -> TableFormat.PAIMON
            else -> TableFormat.UNKNOWN
        }
    }

    /** Checks whether the given directory is an Iceberg table. */
    fun isIcebergTable(dir: File): Boolean {
        val metaDir = File(dir, "metadata")
        return metaDir.exists() && metaDir.isDirectory && metaDir.listFiles { f ->
            f.name.endsWith(".metadata.json")
        }?.isNotEmpty() == true
    }
}
