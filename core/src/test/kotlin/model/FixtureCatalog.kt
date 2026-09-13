package model

import java.io.File
import java.nio.file.Paths

/**
 * Every checked-in table, listed from the directories rather than from a list a test keeps by
 * hand. Twenty tests carried their own copy of the fixture names, and each had stopped at the
 * fixture that existed when it was written — the sweeps that are this suite's strongest oracles
 * (a snapshot's figures against its manifests, the replay trace against the contribution it
 * explains, no false orphan on an engine-written table) were not running on the six newest
 * tables. A test that means a *subset* keeps its own list and says why.
 */
object FixtureCatalog {
    val repoRoot: File = generateSequence(File(".").absoluteFile) { it.parentFile }
        .first { File(it, "settings.gradle.kts").isFile }

    /** The Iceberg tables under `example/iceberg/default`, by directory name, sorted. */
    val iceberg: List<String> = tables(File(repoRoot, "example/iceberg/default"), "metadata")

    /** The Paimon tables under `example/paimon/db.db`, by directory name, sorted. */
    val paimon: List<String> = tables(File(repoRoot, "example/paimon/db.db"), "snapshot")

    fun icebergDir(name: String): File = File(repoRoot, "example/iceberg/default/$name")
    fun paimonDir(name: String): File = File(repoRoot, "example/paimon/db.db/$name")

    fun icebergModel(name: String) = UnifiedTableModel(Paths.get(icebergDir(name).absolutePath))
    fun paimonModel(name: String) = PaimonUnifiedTableModel(Paths.get(paimonDir(name).absolutePath))

    private fun tables(root: File, marker: String): List<String> =
        root.listFiles().orEmpty().filter { it.isDirectory && File(it, marker).isDirectory }.map { it.name }.sorted()
}
