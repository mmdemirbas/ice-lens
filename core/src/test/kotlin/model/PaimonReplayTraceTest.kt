package model

import java.io.File
import java.nio.file.Paths
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The per-entry replay trace, and the oracle that keeps it honest.
 *
 * Iceberg's manifest panel lists what each *entry* contributed and which rule dropped it, and
 * Paimon had no equivalent. It cannot have the same one: `manifestLedger` decides an entry on its
 * own, so it can be re-run scoped to any manifest, while a Paimon entry's effect is decided by what
 * the entries before it left in the map. So the trace records the *state the entry met* and comes
 * out of the same walk that produces the figures.
 *
 * That last part is what makes this testable without a second oracle. The trace and the manifest's
 * own contribution are two readings of one walk, so **the trace must sum to the contribution** — on
 * every manifest, for files, records and bytes at once. A trace computed separately would pass its
 * own unit tests and drift from the number it explains, which is the failure this rules out.
 *
 * It rules out only that. Two readings of one walk move together, so a walk that reads `DELETE`
 * wrong keeps them in perfect agreement — checked by making a `DELETE` behave as an `ADD`: every
 * sum-oracle here and in `PaimonSnapshotDiffTest` still passed. What caught it was the `dv`
 * fixture, whose figures are pinned against the snapshot's own `deltaRecordCount` and
 * `totalRecordCount` — numbers the writer recorded and nothing here computed.
 */
class PaimonReplayTraceTest {

    private val repoRoot: File = generateSequence(File(".").absoluteFile) { it.parentFile }
        .first { File(it, "settings.gradle.kts").isFile }

    private fun model(name: String): PaimonUnifiedTableModel {
        val dir = File(repoRoot, "example/paimon/db.db/$name")
        assertTrue(dir.isDirectory, "the Paimon fixture should be checked in at $dir")
        return PaimonUnifiedTableModel(Paths.get(dir.absolutePath))
    }

    /**
     * Every checked-in Paimon table. `test` is one Flink commit holding one ADD; `dv` is six Spark
     * commits, three of them compactions, which is where a delta first removes something a base
     * still lists — the case the replay exists for, and until `dv` one no real table reached; `cl`
     * adds an overwrite, which removes everything the base lists in one delta.
     */
    private fun snapshots(): List<PaimonUnifiedSnapshot> =
        listOf("test", "dv", "cl", "tg", "pt", "ao", "br", "cs", "fi", "ep", "rt", "sm", "se", "de", "lk", "ad").flatMap { name ->
            model(name).let { m -> m.snapshots + m.branches.flatMap { it.snapshots } }
        }

    /** Every manifest of every snapshot of every fixture, as (snapshot, manifest) pairs. */
    private fun everyManifest(): List<Pair<PaimonUnifiedSnapshot, PaimonUnifiedManifest>> =
        snapshots().flatMap { snapshot ->
            (snapshot.baseManifests + snapshot.deltaManifests).map { snapshot to it }
        }

    private fun everyTraceRow(): List<PaimonEntryTrace> =
        everyManifest().flatMap { (snapshot, manifest) ->
            replayPaimonSnapshot(snapshot, traceFor = paimonManifestKey(manifest)).trace
        }

    /**
     * The sweep below is only as strong as the effects the fixtures reach, so that is asserted
     * rather than assumed: a regenerated `dv` whose compactions merged instead of lifting would
     * leave every row `ADDED`, and every test here would pass having checked one branch.
     */
    @Test
    fun `the fixtures reach this code, and reach a real removal`() {
        val manifests = everyManifest()
        assertTrue(manifests.isNotEmpty(), "no Paimon manifests in the fixtures, so nothing below checks anything")
        assertTrue(
            manifests.any { (_, m) -> m.entries.isNotEmpty() },
            "no Paimon manifest entries in the fixtures",
        )
        val effects = everyTraceRow().map { it.effect }.toSet()
        assertTrue(PaimonEntryEffect.ADDED in effects)
        assertTrue(PaimonEntryEffect.REMOVED in effects, "no fixture removes a file the base lists: $effects")
    }

    /**
     * The trace sums to the contribution it explains, on every manifest.
     *
     * Three totals at once, because a per-entry delta that is right about files and wrong about
     * bytes is the shape a hand-computed trace takes: the file count is easy to get right by
     * counting, and the byte delta is where "replaced" differs from "added".
     */
    @Test
    fun `each manifest's trace adds up to its own contribution`() {
        var checked = 0
        everyManifest().forEach { (snapshot, manifest) ->
            val key = paimonManifestKey(manifest)
            val replay = replayPaimonSnapshot(snapshot, traceFor = key)
            val contribution = replay.contributions.firstOrNull { it.manifestPath == manifest.path.toString() }
                ?: return@forEach
            // A manifest carried forward from another list contributes nothing and is not replayed
            // again, so it has no trace either. That is the duplicate case, checked separately.
            if (contribution.firstCountedIn != null) return@forEach

            checked++
            assertEquals(
                contribution.delta.manifestEntryCount, replay.trace.size,
                "the trace should hold one row per entry of ${manifest.path}",
            )
            assertEquals(
                contribution.delta.dataFileCount, replay.trace.sumOf { it.liveFileDelta },
                "the live-file deltas should sum to what ${manifest.path} contributed",
            )
            assertEquals(
                contribution.delta.recordCount, replay.trace.sumOf { it.recordDelta },
                "the record deltas should sum to what ${manifest.path} contributed",
            )
            assertEquals(
                contribution.delta.dataSizeBytes, replay.trace.sumOf { it.byteDelta },
                "the byte deltas should sum to what ${manifest.path} contributed",
            )
        }
        assertTrue(checked > 0, "no manifest was actually traced, so this asserted nothing")
    }

    /** The other two figures the contribution carries, against the same rows. */
    @Test
    fun `the trace's kinds and replacements match the manifest's counts`() {
        var checked = 0
        everyManifest().forEach { (snapshot, manifest) ->
            val key = paimonManifestKey(manifest)
            val replay = replayPaimonSnapshot(snapshot, traceFor = key)
            val contribution = replay.contributions.firstOrNull { it.manifestPath == manifest.path.toString() }
                ?: return@forEach
            if (contribution.firstCountedIn != null) return@forEach

            checked++
            assertEquals(
                contribution.delta.deletedEntryCount,
                replay.trace.count { it.kind == PaimonEntryKind.DELETE },
                "every DELETE entry should be one deletedEntryCount",
            )
            assertEquals(
                contribution.entriesSuppressedAsDuplicate,
                replay.trace.count { it.effect == PaimonEntryEffect.REPLACED },
                "an ADD over a file already live is what `entriesSuppressedAsDuplicate` counts",
            )
        }
        assertTrue(checked > 0)
    }

    /**
     * Asking for a trace must not change the answer.
     *
     * The trace is recorded inside the walk that produces the figures, so a bookkeeping slip there
     * would move the numbers themselves — the worst outcome available, because the panel that
     * explains a figure would be what changed it.
     */
    @Test
    fun `recording a trace does not perturb the replay`() {
        everyManifest().forEach { (snapshot, manifest) ->
            val plain = replayPaimonSnapshot(snapshot)
            val traced = replayPaimonSnapshot(snapshot, traceFor = paimonManifestKey(manifest))
            assertEquals(plain.contributions, traced.contributions, "the figures must not move")
            assertEquals(plain.liveFiles.keys, traced.liveFiles.keys, "the file set must not move")
            assertTrue(plain.trace.isEmpty(), "an untraced replay carries no trace")
        }
    }

    @Test
    fun `a manifest nobody asked about is not traced`() {
        val (snapshot, manifest) = everyManifest().first()
        val other = replayPaimonSnapshot(snapshot, traceFor = "no-such-manifest-key")
        assertTrue(other.trace.isEmpty(), "only the manifest named gets a trace")
        assertTrue(
            replayPaimonSnapshot(snapshot, traceFor = paimonManifestKey(manifest)).trace.isNotEmpty() ||
                manifest.entries.isEmpty(),
            "and the one named does, unless it holds no entries",
        )
    }

    /**
     * Every row says which of the four things happened, and the four are not interchangeable.
     *
     * `wasLive` and the kind together decide the effect, so this checks the mapping rather than
     * trusting it — an `ADD` over a live file is a *replacement*, and reporting it as an addition
     * is how a live-file count grows by one for a file that was already there.
     */
    @Test
    fun `the effect follows from the entry kind and the state it met`() {
        var rows = 0
        everyManifest().forEach { (snapshot, manifest) ->
            replayPaimonSnapshot(snapshot, traceFor = paimonManifestKey(manifest)).trace.forEach { row ->
                rows++
                val expected = when {
                    row.kind == PaimonEntryKind.DELETE && row.wasLive -> PaimonEntryEffect.REMOVED
                    row.kind == PaimonEntryKind.DELETE -> PaimonEntryEffect.REMOVED_ABSENT
                    row.wasLive -> PaimonEntryEffect.REPLACED
                    else -> PaimonEntryEffect.ADDED
                }
                assertEquals(expected, row.effect, "for ${row.fileKey}")

                // And the deltas agree with the effect rather than being independent of it.
                when (row.effect) {
                    PaimonEntryEffect.ADDED -> assertEquals(1, row.liveFileDelta)
                    PaimonEntryEffect.REPLACED -> assertEquals(0, row.liveFileDelta)
                    PaimonEntryEffect.REMOVED -> assertEquals(-1, row.liveFileDelta)
                    PaimonEntryEffect.REMOVED_ABSENT -> {
                        assertEquals(0, row.liveFileDelta)
                        assertEquals(0L, row.recordDelta)
                        assertEquals(0L, row.byteDelta)
                    }
                }
                // What the entry met is reported only when it met something.
                if (!row.wasLive) {
                    assertEquals(null, row.previousRecordCount)
                    assertEquals(null, row.previousSizeBytes)
                }
            }
        }
        assertTrue(rows > 0, "no trace rows were produced, so this asserted nothing")
    }

    // ── What the Spark-written table reaches, on the bytes it wrote ──────────────────────────

    private val dv: PaimonUnifiedTableModel by lazy { model("dv") }

    private fun deltaTrace(snapshotId: Long): Pair<PaimonReplay, PaimonUnifiedManifest> {
        val snapshot = dv.snapshots.single { it.metadata.id == snapshotId }
        val manifest = snapshot.deltaManifests.single()
        return replayPaimonSnapshot(snapshot, traceFor = paimonManifestKey(manifest)) to manifest
    }

    /**
     * A compaction that lifts a file to a higher level removes it and adds it back, and moves the
     * table by nothing.
     *
     * This is what Paimon's LSM does to a level-0 file it decides not to rewrite: the delta
     * manifest carries a `DELETE` of the file at level 0 and an `ADD` of the *same* file — same
     * name, same rows, same bytes — at its new level. Read as a filter that pair is a file that
     * left; read as the replay it is, the file is gone for exactly one entry and the manifest's
     * contribution is zero on all three figures. The trace is the only place the reader can see
     * that the commit did something at all, which is why the pair is pinned row by row rather
     * than only through the sum.
     */
    @Test
    fun `an upgrade compaction removes the file and adds it back at its new level, contributing nothing`() {
        val (replay, manifest) = deltaTrace(snapshotId = 2)
        assertEquals("COMPACT", dv.snapshots.single { it.metadata.id == 2L }.metadata.commitKind)

        assertEquals(listOf(PaimonEntryEffect.REMOVED, PaimonEntryEffect.ADDED), replay.trace.map { it.effect })
        val (removed, added) = replay.trace
        assertEquals(removed.fileKey, added.fileKey, "one file, leaving and returning")
        assertEquals(-1000L, removed.recordDelta)
        assertEquals(1000L, added.recordDelta)
        assertEquals(true, removed.wasLive, "the base listed it, so the removal found it")
        assertEquals(false, added.wasLive, "and by the time the ADD applies the removal has run")

        val levels = manifest.entries.map { it.metadata.kind to it.metadata.file?.level }
        assertEquals(listOf(PaimonEntryKind.DELETE to 0, PaimonEntryKind.ADD to 5), levels, "level 0 out, level 5 in")

        val contribution = replay.contributions.single { it.manifestPath == manifest.path.toString() }
        assertEquals(0, contribution.delta.dataFileCount)
        assertEquals(0L, contribution.delta.recordCount)
        assertEquals(0L, contribution.delta.dataSizeBytes)
        assertEquals(1, contribution.delta.deletedEntryCount, "the DELETE entry is still counted as one")
        assertEquals(0, contribution.entriesSuppressedAsDuplicate, "the ADD met nothing live, so it is not a replacement")
    }

    /**
     * The commit that wrote the deletion vector is a pure removal, and its contribution is
     * negative — on bytes an engine wrote, which `a delta that only removes contributes a negative
     * delta` below could only state as model objects.
     *
     * The three `-D` rows were compacted into a vector, so the level-0 file that carried them is
     * removed and nothing is added in its place; the two files the vector marks are untouched and
     * stay in the base. The snapshot's own `deltaRecordCount` says `-3`, which is the same figure
     * from the writer's side.
     */
    @Test
    fun `the vector's commit removes the delete-row file and contributes a negative delta`() {
        val (replay, manifest) = deltaTrace(snapshotId = 6)
        val snapshot = dv.snapshots.single { it.metadata.id == 6L }

        assertEquals(listOf(PaimonEntryEffect.REMOVED), replay.trace.map { it.effect })
        val removed = replay.trace.single()
        assertEquals(3L, removed.previousRecordCount, "what the live set held for it")
        assertEquals(-3L, removed.recordDelta)
        assertEquals(-1, removed.liveFileDelta)

        val contribution = replay.contributions.single { it.manifestPath == manifest.path.toString() }
        assertEquals(-1, contribution.delta.dataFileCount)
        assertEquals(-3L, contribution.delta.recordCount)
        assertEquals(-removed.previousSizeBytes!!, contribution.delta.dataSizeBytes)
        assertEquals(snapshot.metadata.deltaRecordCount, contribution.delta.recordCount, "the writer's own figure for this commit")

        assertEquals(2, replay.liveFiles.size, "the two files the vector marks are still live")
        assertEquals(1500L, replay.liveFiles.values.sumOf { it?.rowCount ?: 0L })
        assertEquals(snapshot.metadata.totalRecordCount, replay.liveFiles.values.sumOf { it?.rowCount ?: 0L })
    }

    // ── The two effects no checked-in fixture reaches ────────────────────────────────────────
    //
    // `test` is one ADD; `dv` adds `REMOVED`, three times, but every one of its ADDs meets an
    // empty slot — an upgrade compaction removes the file *before* re-adding it. So `REPLACED` (an
    // ADD over a file still live) and `REMOVED_ABSENT` (a DELETE of a file the base never listed)
    // have still not met real bytes; the first needs an overwrite, the second a re-applied or
    // rolled-back commit.
    //
    // Built as model objects rather than as bytes on purpose, and the objection the project raises
    // about runtime-written Avro fixtures does not apply: those are not oracles because writer and
    // reader schema are the same object, which is a claim about *decoding*. Nothing here decodes.
    // What is under test is the replay rule — kind against the state the entry met — which is this
    // repository's own, so stating the state directly is the honest level to test it at. The
    // fixture keeps its job of pinning the arithmetic against a table an engine actually wrote.

    private fun meta(name: String, rows: Long, bytes: Long) =
        PaimonDataFileMeta(fileName = name, rowCount = rows, fileSize = bytes)

    private fun entry(kind: Int, name: String, rows: Long = 0, bytes: Long = 0) =
        PaimonUnifiedDataFile(
            path = Paths.get("/wh/db.db/t/bucket-0/$name"),
            metadata = PaimonManifestEntry(kind = kind, file = meta(name, rows, bytes)),
        )

    private fun manifest(name: String, entries: List<PaimonUnifiedDataFile>) =
        PaimonUnifiedManifest(
            path = Paths.get("/wh/db.db/t/manifest/$name"),
            metadata = PaimonManifestFileMeta(fileName = name),
            entries = entries,
        )

    private fun snapshot(base: List<PaimonUnifiedManifest>, delta: List<PaimonUnifiedManifest>) =
        PaimonUnifiedSnapshot(
            path = Paths.get("/wh/db.db/t/snapshot/snapshot-2"),
            metadata = PaimonSnapshot(id = 2L),
            schema = null,
            baseManifests = base,
            deltaManifests = delta,
            changelogManifests = emptyList(),
        )

    /**
     * A delta over a base, holding one of each effect.
     *
     * The compaction shape: the base lists two files, and the commit removes one, rewrites another
     * in place, adds a third, and carries a removal for a file this snapshot's base never listed —
     * which is what a re-applied or rolled-back commit leaves behind.
     */
    @Test
    fun `a delta over a base produces all four effects, and they sum to the contribution`() {
        val base = manifest(
            "base-m0.avro",
            listOf(
                entry(PaimonEntryKind.ADD, "f1.parquet", rows = 100, bytes = 1_000),
                entry(PaimonEntryKind.ADD, "f2.parquet", rows = 200, bytes = 2_000),
            ),
        )
        val delta = manifest(
            "delta-m0.avro",
            listOf(
                entry(PaimonEntryKind.DELETE, "f1.parquet", rows = 100, bytes = 1_000),
                entry(PaimonEntryKind.ADD, "f2.parquet", rows = 250, bytes = 2_500),
                entry(PaimonEntryKind.ADD, "f3.parquet", rows = 300, bytes = 3_000),
                entry(PaimonEntryKind.DELETE, "f9.parquet", rows = 900, bytes = 9_000),
            ),
        )
        val snap = snapshot(listOf(base), listOf(delta))

        val replay = replayPaimonSnapshot(snap, traceFor = paimonManifestKey(delta))
        assertEquals(
            listOf(
                PaimonEntryEffect.REMOVED,
                PaimonEntryEffect.REPLACED,
                PaimonEntryEffect.ADDED,
                PaimonEntryEffect.REMOVED_ABSENT,
            ),
            replay.trace.map { it.effect },
        )

        // What each row says it met. The replacement is the interesting one: it reports the *old*
        // figures, which is the only place the 200 rows f2 used to hold are still visible.
        val replaced = replay.trace[1]
        assertEquals(200L, replaced.previousRecordCount)
        assertEquals(2_000L, replaced.previousSizeBytes)
        assertEquals(50L, replaced.recordDelta, "a rewrite moves the total by the difference")
        assertEquals(500L, replaced.byteDelta)
        assertEquals(0, replaced.liveFileDelta)

        val absent = replay.trace[3]
        assertEquals(false, absent.wasLive)
        assertEquals(0L, absent.recordDelta, "removing what was never there moves nothing")

        // And the whole trace still adds up to what the manifest contributed — the same oracle the
        // real fixture is held to, on a manifest that actually removes and replaces things.
        val contribution = replay.contributions.single { it.manifestPath == delta.path.toString() }
        assertEquals(contribution.delta.dataFileCount, replay.trace.sumOf { it.liveFileDelta })
        assertEquals(contribution.delta.recordCount, replay.trace.sumOf { it.recordDelta })
        assertEquals(contribution.delta.dataSizeBytes, replay.trace.sumOf { it.byteDelta })
        assertEquals(2, contribution.delta.deletedEntryCount)
        assertEquals(1, contribution.entriesSuppressedAsDuplicate)

        // The table is left holding f2 at its new size and f3.
        assertEquals(setOf("f2.parquet", "f3.parquet"), replay.liveFiles.keys)
    }

    /** A contribution may be negative, which is the property Iceberg's ledger can never produce. */
    @Test
    fun `a delta that only removes contributes a negative delta`() {
        val base = manifest("base-m0.avro", listOf(entry(PaimonEntryKind.ADD, "f1.parquet", 100, 1_000)))
        val delta = manifest("delta-m0.avro", listOf(entry(PaimonEntryKind.DELETE, "f1.parquet", 100, 1_000)))
        val replay = replayPaimonSnapshot(snapshot(listOf(base), listOf(delta)), traceFor = paimonManifestKey(delta))

        val contribution = replay.contributions.single { it.manifestPath == delta.path.toString() }
        assertEquals(-1, contribution.delta.dataFileCount)
        assertEquals(-100L, contribution.delta.recordCount)
        assertEquals(-1_000L, contribution.delta.dataSizeBytes)
        assertEquals(listOf(PaimonEntryEffect.REMOVED), replay.trace.map { it.effect })
        assertEquals(-100L, replay.trace.single().recordDelta)
        assertTrue(replay.liveFiles.isEmpty())
    }
}
