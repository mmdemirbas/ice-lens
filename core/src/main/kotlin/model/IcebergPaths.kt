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
 * Extracts the numeric version from a metadata file name like `v1.metadata.json` → `1`.
 * Returns null for non-matching file names.
 */
fun metadataVersionFromFileName(fileName: String): Int? =
    fileName.removePrefix("v").removeSuffix(".metadata.json").toIntOrNull()
