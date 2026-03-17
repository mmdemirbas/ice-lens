package model

import java.net.URI

fun normalizeFilePath(path: String): String {
    val trimmed = path.trim()
    if (trimmed.isEmpty()) return trimmed
    val normalized = if (trimmed.startsWith("file:")) {
        runCatching { URI(trimmed).path }.getOrDefault(trimmed.removePrefix("file:"))
    } else trimmed
    return normalized.replace("\\", "/")
}

fun metadataVersionFromFileName(fileName: String): Int? =
    fileName.removePrefix("v").removeSuffix(".metadata.json").toIntOrNull()
