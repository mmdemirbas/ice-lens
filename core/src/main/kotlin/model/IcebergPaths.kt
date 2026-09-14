package model

import java.net.URI

/** Known cloud/remote URI scheme prefixes. Paths with these schemes are returned as-is. */
private val REMOTE_SCHEME_REGEX = Regex("^[a-zA-Z][a-zA-Z0-9+.-]*://")

/**
 * Normalizes a file path for consistent comparison and deduplication.
 *
 * - `file:` URIs are converted to local paths via [URI.getPath].
 * - `file://host/share/...` URIs (non-empty, non-localhost authority) are
 *   reconstructed as UNC `//host/share/...` so the host isn't silently dropped.
 * - Cloud/remote URIs (`s3://`, `hdfs://`, `gs://`, `abfs://`, etc.) are returned as-is.
 * - Windows backslashes are converted to forward slashes.
 * - UNC paths (`\\server\share\...`) are preserved with `//server/share/...` form.
 */
fun normalizeFilePath(path: String): String {
    val trimmed = path.trim()
    if (trimmed.isEmpty()) return trimmed

    // file: URIs → convert to local path
    if (trimmed.startsWith("file:")) {
        val parsed = runCatching { URI(trimmed) }.getOrNull()
        if (parsed != null) {
            val host = parsed.host
            val rawPath = parsed.path.orEmpty()
            val local = if (!host.isNullOrEmpty() && !host.equals("localhost", ignoreCase = true)) {
                // file://server/share/path -> //server/share/path (UNC-style)
                "//$host$rawPath"
            } else {
                rawPath
            }
            return local.replace("\\", "/")
        }
        return trimmed.removePrefix("file:").replace("\\", "/")
    }

    // Cloud/remote URIs (s3://, hdfs://, gs://, abfs://, etc.) → return as-is
    if (REMOTE_SCHEME_REGEX.containsMatchIn(trimmed)) {
        return trimmed
    }

    // Local paths — convert backslashes to forward slashes (preserves UNC prefix as //server/share)
    return trimmed.replace("\\", "/")
}

/**
 * Extracts the numeric version from a metadata file name — a Hadoop table's `v1.metadata.json`
 * → `1`, a catalog table's `00001-<uuid>.metadata.json` → `1` (`BaseMetastoreTableOperations`
 * names a version `%05d-<uuid>` at 1.8.1, from `00000` for a new table), either with a gzip
 * codec in the name (see [isGzipMetadataFileName]); null for any other name.
 * Returns null for non-matching file names.
 */
fun metadataVersionFromFileName(fileName: String): Int? {
    val stem = fileName.removeSuffix(".gz").removeSuffix(".metadata.json").removeSuffix(".gz")
    if (stem.startsWith("v")) return stem.drop(1).toIntOrNull()
    // `%05d-<uuid>`: the version, a dash, the 36-character UUID that makes the name unique.
    val dash = stem.indexOf('-')
    return if (dash > 0 && stem.length - dash - 1 == 36) stem.substring(0, dash).toIntOrNull() else null
}

/**
 * Whether [fileName] is a metadata file by Iceberg's naming: `.metadata.json`, which under
 * `write.metadata.compression-codec = gzip` is `v3.gz.metadata.json` — the codec's extension
 * *before* the suffix (`TableMetadataParser.getFileExtension`, 1.8.1) — or the older
 * `.metadata.json.gz` the reader stays compatible with.
 */
fun isMetadataFileName(fileName: String): Boolean = fileName.endsWith(".metadata.json") || fileName.endsWith(".metadata.json.gz")

/** Whether the metadata file is gzip-compressed, read off its name the way `TableMetadataParser.Codec.fromFileName` reads it. */
fun isGzipMetadataFileName(fileName: String): Boolean =
    fileName.endsWith(".metadata.json.gz") || fileName.removeSuffix(".metadata.json").endsWith(".gz")
