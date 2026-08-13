package model

import java.io.File

sealed class WorkspaceItem {
    abstract val path: String
    abstract val name: String

    data class Warehouse(
        override val path: String,
        override val name: String,
        val tables: List<String> = emptyList()
    ) : WorkspaceItem()

    data class SingleTable(
        override val path: String,
        override val name: String
    ) : WorkspaceItem()

    fun serialize(): String = when (this) {
        is Warehouse   -> "W|${encodePath(path)}"
        is SingleTable -> "T|${encodePath(path)}"
    }

    companion object {
        // Path separators in the persisted format are `;` (between items) and `|` (between
        // type and path). Encode those characters in the path to keep round-trip safety on
        // filesystems that allow them (Linux/macOS).
        private fun encodePath(path: String): String =
            path.replace("%", "%25").replace(";", "%3B").replace("|", "%7C")

        private fun decodePath(s: String): String =
            s.replace("%7C", "|").replace("%3B", ";").replace("%25", "%")

        fun deserialize(s: String): WorkspaceItem? {
            val parts = s.split("|", limit = 2)
            if (parts.size != 2) return null
            val type = parts[0]
            val path = decodePath(parts[1])
            val name = File(path).name
            return when (type) {
                "W" -> Warehouse(path, name)
                "T" -> SingleTable(path, name)
                else -> null
            }
        }
    }
}

enum class WorkspaceTableStatus {
    EXISTING,
    NEW,
    DELETED
}
