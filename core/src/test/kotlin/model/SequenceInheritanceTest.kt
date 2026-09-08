package model

import java.io.File
import java.nio.file.Paths
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The sequence number an entry does not record, which Iceberg defines exactly.
 *
 * An entry written by the commit that wrote its manifest stores null and inherits
 * `manifest_file.sequence_number`; only an entry carried forward from an earlier commit records one
 * of its own. Reading a null as "unknown" is what had three of `mor`'s four files printing `N/A`
 * for a number the format pins down — and it is the number that decides which delete files a scan
 * applies to which data files, so it is not only a display question.
 *
 * The expected values are the fixture's own: `mor`'s current snapshot lists a data manifest at
 * sequence 5 holding one entry that records 4, and three delete manifests at 6, 4 and 3 whose
 * entries record nothing at all.
 */
class SequenceInheritanceTest {

    private val repoRoot: File = generateSequence(File(".").absoluteFile) { it.parentFile }
        .first { File(it, "settings.gradle.kts").isFile }

    private fun table(name: String) =
        UnifiedTableModel(Paths.get(File(repoRoot, "example/iceberg/default/$name").absolutePath))

    @Test
    fun `an entry with no sequence number of its own takes the manifest's`() {
        val snapshot = table("mor").metadatas.last().snapshots.last()
        val byManifest = snapshot.manifests.associate { manifest ->
            manifest.metadata.sequenceNumber to manifest.dataFiles.map { file ->
                file.metadata.sequenceNumber to
                    effectiveSequenceNumber(file.metadata, manifest.metadata.sequenceNumber)
            }
        }
        val expected: Map<Long?, List<Pair<Long?, Long?>>> = mapOf(
            // The compacted data file, carried forward: it records 4 and keeps it under a manifest
            // that has moved on to 5.
            5L to listOf(4L to 4L),
            // Three delete files, each added by the commit that wrote its manifest.
            6L to listOf(null to 6L),
            4L to listOf(null to 4L),
            3L to listOf(null to 3L),
        )
        assertEquals(expected, byManifest)
    }

    /**
     * The property across every checked-in table: nothing live is left without one.
     *
     * A file with no sequence number is a file no delete-application rule can be evaluated against,
     * so "how many are there" is the question worth pinning — and the answer has to be zero.
     */
    @Test
    fun `no live entry in any fixture is left without a sequence number`() {
        val fixtures = listOf("test", "parted", "mor", "eqdel", "v3", "evolved", "respec", "branched")
        val missing = fixtures.flatMap { name ->
            table(name).metadatas.last().snapshots.flatMap { snapshot ->
                snapshot.manifests.flatMap { manifest ->
                    manifest.dataFiles.mapNotNull { file ->
                        val effective = effectiveSequenceNumber(file.metadata, manifest.metadata.sequenceNumber)
                        if (effective == null) "$name/${manifest.path.fileName}" else null
                    }
                }
            }
        }
        assertTrue(missing.isEmpty(), "entries with no sequence number: $missing")
    }

    /** The graph node answers it the same way, and says when the number was not its own. */
    @Test
    fun `a file node reports the inherited number and that it was inherited`() {
        val graph = service.GraphLayoutService.layoutGraph(table("mor"), showRows = false)
        val deletes = graph.nodes.filterIsInstance<GraphNode.FileNode>()
            .filter { it.data.content == DataFileContent.POSITION_DELETES }
        assertTrue(deletes.isNotEmpty(), "mor has positional delete files")
        assertTrue(
            deletes.all { it.sequenceNumber != null && it.sequenceInherited },
            "every delete entry here inherits: ${deletes.map { it.entry.sequenceNumber to it.sequenceNumber }}",
        )
    }
}
