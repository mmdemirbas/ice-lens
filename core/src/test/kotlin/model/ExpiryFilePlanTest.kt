package model

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The cleanup rules on lists made up for the purpose — the readings the two fixtures cannot
 * separate: a `DELETED` entry frees nothing under the reachable cleanup even when no retained
 * manifest holds the file live; a `DELETED` entry whose deleting snapshot is retained frees
 * nothing under the incremental one; and the ref count that picks the strategy is the count
 * *after* the expiry, so a tag on an expiring snapshot does not make the cleanup reachable.
 */
class ExpiryFilePlanTest {

    private fun snapshot(id: Long, parent: Long?, list: String) =
        Snapshot(snapshotId = id, parentSnapshotId = parent, sequenceNumber = id, manifestList = list, summary = mapOf("operation" to "append"))

    private fun listed(path: String, by: Long, added: Int, existing: Int, deleted: Int) =
        ManifestListEntry(manifestPath = path, manifestLength = 7000, partitionSpecId = 0, content = 0, addedSnapshotId = by, addedFilesCount = added, existingFilesCount = existing, deletedFilesCount = deleted)

    private fun entry(status: Int, by: Long, file: String) =
        ManifestEntry(status = status, snapshotId = by, dataFile = DataFile(filePath = file, fileSizeInBytes = 100))

    private fun metadata(refs: Map<String, SnapshotRef>, vararg snapshots: Snapshot) =
        TableMetadata(formatVersion = 2, tableUuid = "t", location = "/t", refs = refs, snapshots = snapshots.toList(), currentSnapshotId = snapshots.last().snapshotId)

    @Test
    fun `reachable cleanup reads only live entries, so a manifest of removals frees no file`() {
        // Snapshot 3 rewrote m1 as m1' (f1 DELETED); an earlier expiry already took m1 away.
        val meta = metadata(
            mapOf("main" to SnapshotRef(snapshotId = 4, type = "branch"), "dev" to SnapshotRef(snapshotId = 4, type = "branch")),
            snapshot(3, null, "list-3"), snapshot(4, 3, "list-4"),
        )
        val input = ExpiryFileInput(
            meta,
            mapOf(3L to listOf(listed("m1p", 3, 0, 0, 1), listed("m2", 2, 1, 0, 0)), 4L to listOf(listed("m2", 2, 1, 0, 0))),
        ) { p -> if (p == "m1p") listOf(entry(ManifestEntryStatus.DELETED, 3, "f1")) else listOf(entry(ManifestEntryStatus.ADDED, 2, "f2")) }
        val plan = input.planExpiryFiles(setOf(3))
        assertEquals(ExpiryCleanup.REACHABLE, plan.cleanup)
        assertEquals(listOf("m1p"), plan.ofKind(ExpiryFileKind.MANIFEST).map { it.path })
        assertEquals(emptyList(), plan.ofKind(ExpiryFileKind.DATA_FILE))
        assertEquals(listOf("list-3"), plan.ofKind(ExpiryFileKind.MANIFEST_LIST).map { it.path })
    }

    @Test
    fun `incremental cleanup frees a removed file only when the snapshot that removed it is gone too`() {
        // m1' was written by 3 and records f1 as removed by 3; with 3 retained, nothing goes but the list of 2.
        val meta = metadata(
            mapOf("main" to SnapshotRef(snapshotId = 4, type = "branch")),
            snapshot(2, null, "list-2"), snapshot(3, 2, "list-3"), snapshot(4, 3, "list-4"),
        )
        val lists = mapOf(
            2L to listOf(listed("m1", 1, 1, 0, 0), listed("m2", 2, 1, 0, 0)),
            3L to listOf(listed("m1p", 3, 0, 0, 1), listed("m2", 2, 1, 0, 0)),
            4L to listOf(listed("m4", 4, 1, 0, 0), listed("m1p", 3, 0, 0, 1), listed("m2", 2, 1, 0, 0)),
        )
        val entries = mapOf(
            "m1" to listOf(entry(ManifestEntryStatus.ADDED, 1, "f1")),
            // f9's removal is credited to 4, which stays: read in IncrementalFileCleanup.findFilesToDelete,
            // a DELETED entry frees its file only when the entry's own snapshot is gone.
            "m1p" to listOf(entry(ManifestEntryStatus.DELETED, 3, "f1"), entry(ManifestEntryStatus.DELETED, 4, "f9")),
            "m2" to listOf(entry(ManifestEntryStatus.ADDED, 2, "f2")),
            "m4" to listOf(entry(ManifestEntryStatus.ADDED, 4, "f4")),
        )
        val input = ExpiryFileInput(meta, lists) { entries[it] }
        val onlyTwo = input.planExpiryFiles(setOf(2), input.coreApiCleanup(setOf(2)))
        assertEquals(ExpiryCleanup.INCREMENTAL, onlyTwo.cleanup, "the core API at one ref")
        assertEquals(listOf("m1"), onlyTwo.ofKind(ExpiryFileKind.MANIFEST).map { it.path }, "m1 is listed by nothing retained")
        assertEquals(emptyList(), onlyTwo.ofKind(ExpiryFileKind.DATA_FILE), "f1's removal belongs to 3, which stays")
        // Expire 3 as well and the removal's snapshot is gone: f1 goes, read from m1' in 4's list.
        val both = input.planExpiryFiles(setOf(2, 3), ExpiryCleanup.INCREMENTAL)
        assertEquals(listOf("f1"), both.ofKind(ExpiryFileKind.DATA_FILE).map { it.path }, "f9 stays with snapshot 4")
        assertEquals(ExpiryFileReason.DELETED_BY_EXPIRED, both.ofKind(ExpiryFileKind.DATA_FILE).single().reason)
        assertEquals(setOf("m1"), both.ofKind(ExpiryFileKind.MANIFEST).map { it.path }.toSet(), "m1' stays: 4 still lists it")
    }

    @Test
    fun `a tag on an expiring snapshot goes with it, and the ref count that picks the core API's strategy is the one after`() {
        val meta = metadata(
            mapOf("main" to SnapshotRef(snapshotId = 2, type = "branch"), "old" to SnapshotRef(snapshotId = 1, type = "tag")),
            snapshot(1, null, "list-1"), snapshot(2, 1, "list-2"),
        )
        val input = ExpiryFileInput(meta, mapOf(1L to listOf(listed("m1", 1, 1, 0, 0)), 2L to listOf(listed("m2", 2, 1, 0, 0), listed("m1", 1, 1, 0, 0)))) { emptyList() }
        assertEquals(ExpiryCleanup.INCREMENTAL, input.coreApiCleanup(setOf(1)), "the tag goes with its snapshot, leaving main alone")
        assertEquals(ExpiryCleanup.REACHABLE, input.planExpiryFiles(setOf(1)).cleanup, "the procedure's diff, whatever the count")
        assertEquals("1 manifest list", input.planExpiryFiles(setOf(1)).describe)
    }
}
