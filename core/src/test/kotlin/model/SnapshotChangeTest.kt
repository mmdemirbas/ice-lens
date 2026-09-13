package model

import java.io.File
import java.nio.file.Paths
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * What each commit did, checked against what the engine that wrote it said it did.
 *
 * This is the strongest oracle available without running Iceberg: every checked-in table's
 * snapshots carry a `summary` map written by Spark or Flink at commit time — `added-data-files`,
 * `deleted-records`, `added-files-size` — and nothing in this codebase produced any of it. If the
 * manifests are walked wrongly, or a status is misread, or a manifest is attributed to the wrong
 * commit, the two sides stop agreeing on a table nobody here wrote.
 *
 * It is also the assertion that would catch the mistake the attribution rule exists to prevent.
 * Counting statuses across a snapshot's whole manifest closure, rather than only the manifests it
 * added, credits every commit with all of its ancestors' work — which looks entirely reasonable
 * until it is put next to the writer's own figure.
 */
class SnapshotChangeTest {

    private val repoRoot: File = generateSequence(File(".").absoluteFile) { it.parentFile }
        .first { File(it, "settings.gradle.kts").isFile }

    private fun tableOf(fixture: String, format: String = "iceberg/default"): UnifiedTableModel {
        val dir = File(repoRoot, "example/$format/$fixture")
        assertTrue(dir.isDirectory, "fixture missing at $dir")
        return UnifiedTableModel(Paths.get(dir.absolutePath))
    }

    /**
     * Every snapshot of a table, deduplicated — each is re-listed by every later metadata file —
     * minus the expired ones, whose manifests are gone: their summary is all that is left, and
     * there is nothing to count against it.
     */
    private fun snapshotsOf(model: UnifiedTableModel): List<UnifiedSnapshot> =
        model.metadatas.flatMap { it.snapshots }
            .distinctBy { it.metadata.snapshotId }
            .filterNot { it.expired }
            .sortedBy { it.metadata.timestampMs }

    private val fixtures = listOf("test", "parted", "mor", "eqdel", "v3", "evolved", "respec", "branched", "expired", "maint", "v1", "extdata", "sorted", "promoted", "lineage", "pstats", "wap", "rolled")

    /**
     * The whole point, across every checked-in table: what the manifests say a commit did has to
     * match what the commit said it did.
     */
    @Test
    fun `every commit agrees with the summary its engine wrote`() {
        val checked = mutableListOf<String>()
        fixtures.forEach { fixture ->
            snapshotsOf(tableOf(fixture)).forEach { snapshot ->
                val change = snapshotChangeOf(snapshot)
                assertEquals(
                    0, change.unattributedManifests,
                    "$fixture snapshot ${change.snapshotId} lists a manifest with no added_snapshot_id, " +
                        "so this commit cannot be described",
                )
                change.tallies.filter { it.agrees != null }.forEach { tally ->
                    checked += "$fixture/${change.snapshotId}/${tally.label}"
                    assertEquals(
                        tally.recorded, tally.counted,
                        "$fixture snapshot ${change.snapshotId} (${change.operation}): the summary " +
                            "says ${tally.label.lowercase()} = ${tally.recorded}, its manifests count " +
                            "${tally.counted}",
                    )
                }
            }
        }
        // Without these the test passes on a version that finds no figures to compare at all.
        // The corpus yields 120 comparisons; the bound is well under that so adding a fixture does
        // not churn it, and the per-fixture check is the part that would catch a table dropping
        // out of the sweep entirely.
        assertTrue(
            checked.size >= 60,
            "only ${checked.size} figures had anything recorded to check against",
        )
        val silent = fixtures.filter { fixture -> checked.none { it.startsWith("$fixture/") } }
        assertTrue(silent.isEmpty(), "no figure was checked on these tables at all: $silent")
    }

    /**
     * Attribution by `added_snapshot_id`, stated as its own assertion.
     *
     * `branched` has ten metadata versions and five snapshots, so its later commits list manifests
     * written by commits well before them. A later commit's own additions must not include a file
     * an ancestor added — which is exactly what walking the closure would produce.
     */
    @Test
    fun `a commit is not credited with the files its ancestors added`() {
        val snapshots = snapshotsOf(tableOf("branched"))
        assertTrue(snapshots.size >= 4, "the branched fixture should carry several commits")

        val changes = snapshots.map { snapshotChangeOf(it) }
        val later = changes.last()
        assertTrue(
            later.added.isNotEmpty(),
            "the last commit should have added something, or this proves nothing",
        )

        // Every manifest the last commit lists, against the ones it is credited with.
        val listed = snapshots.last().manifests.size
        val creditedManifests = later.files.map { it.manifestPath }.distinct().size
        assertTrue(
            creditedManifests < listed,
            "the last commit lists $listed manifests and is credited with all $creditedManifests " +
                "of them, which means attribution is not filtering anything",
        )

        val addedByOthers = changes.dropLast(1).flatMap { it.added }.map { it.path }.toSet()
        val overlap = later.added.map { it.path }.filter { it in addedByOthers }
        assertTrue(overlap.isEmpty(), "the last commit claims files an earlier one added: $overlap")
    }

    /**
     * A merge-on-read delete writes a delete file and removes nothing, and a compaction removes
     * data files. Both are in `mor`, and they are the two shapes an append cannot show.
     */
    @Test
    fun `a delete adds a delete file and a compaction removes data files`() {
        val changes = snapshotsOf(tableOf("mor")).map { snapshotChangeOf(it) }

        val withDeleteFile = changes.filter { change ->
            change.added.any { it.content != DataFileContent.DATA }
        }
        assertTrue(withDeleteFile.isNotEmpty(), "the merge-on-read fixture should write a delete file")

        val withRemoval = changes.filter { it.removed.isNotEmpty() }
        assertTrue(
            withRemoval.isNotEmpty(),
            "its compaction should take data files out; operations seen: ${changes.map { it.operation }}",
        )
        withRemoval.forEach { change ->
            assertEquals(
                "replace", change.operation,
                "only a rewrite should remove files here, not a ${change.operation}",
            )
        }
    }

    /**
     * A v3 deletion vector is a delete file, and `added-dvs` is a breakdown of the delete-file
     * total rather than a separate key standing in for it.
     *
     * Worth pinning as its own test: the summary states `added-delete-files = 1`, `added-dvs = 1`
     * and `added-position-deletes = 1` about one vector, so a reader who treats `added-dvs` as
     * the v3 spelling of the total and adds them reports two delete files where one was written.
     */
    @Test
    fun `a deletion vector is counted as a delete file added`() {
        val changes = snapshotsOf(tableOf("v3")).map { snapshotChangeOf(it) }
        val withVector = changes.filter { change ->
            change.added.any { it.path.endsWith(".puffin") }
        }
        assertTrue(withVector.isNotEmpty(), "the v3 fixture should add deletion vectors")

        withVector.forEach { change ->
            assertEquals(
                change.summary["added-delete-files"], change.summary["added-dvs"],
                "on a v3 table every delete file added is a vector, so the two keys state the " +
                    "same number about the same files — they are a total and a breakdown of it",
            )
            val tally = change.tallies.first { it.label == "Delete files added" }
            assertEquals(tally.recorded, tally.counted, "on snapshot ${change.snapshotId}")
        }
    }

    @Test
    fun `the first commit has no parent and every later one names it`() {
        val changes = snapshotsOf(tableOf("parted")).map { snapshotChangeOf(it) }
        assertTrue(changes.first().isRoot, "the first commit of a table has no parent")
        assertTrue(changes.drop(1).none { it.isRoot }, "every later commit names a parent")
    }

    /**
     * A figure the writer left out is not a disagreement.
     *
     * Iceberg omits a summary key when its value is zero, so most commits state four or five of
     * the eight figures here. Reading a missing key as zero and comparing it would report a
     * disagreement on every plain append.
     */
    @Test
    fun `a figure the summary does not state is left unjudged`() {
        val change = snapshotChangeOf(snapshotsOf(tableOf("test")).first())
        val silent = change.tallies.filter { it.recorded == null }
        assertTrue(silent.isNotEmpty(), "a plain append states nothing about removals")
        assertTrue(silent.all { it.agrees == null }, "and an unstated figure agrees with nothing")
        assertTrue(change.disagreements.isEmpty())
    }
}
