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
 */
class PaimonReplayTraceTest {

    private val repoRoot: File = generateSequence(File(".").absoluteFile) { it.parentFile }
        .first { File(it, "settings.gradle.kts").isFile }

    private fun model(): PaimonUnifiedTableModel {
        val dir = File(repoRoot, "example/paimon/db.db/test")
        assertTrue(dir.isDirectory, "the Paimon fixture should be checked in at $dir")
        return PaimonUnifiedTableModel(Paths.get(dir.absolutePath))
    }

    private fun snapshots(): List<PaimonUnifiedSnapshot> = model().snapshots

    /** Every manifest of every snapshot, as (snapshot, manifest) pairs. */
    private fun everyManifest(): List<Pair<PaimonUnifiedSnapshot, PaimonUnifiedManifest>> =
        snapshots().flatMap { snapshot ->
            (snapshot.baseManifests + snapshot.deltaManifests).map { snapshot to it }
        }

    @Test
    fun `the fixture reaches this code at all`() {
        val manifests = everyManifest()
        assertTrue(manifests.isNotEmpty(), "no Paimon manifests in the fixture, so nothing below checks anything")
        assertTrue(
            manifests.any { (_, m) -> m.entries.isNotEmpty() },
            "no Paimon manifest entries in the fixture",
        )
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

    // ── The three effects the checked-in fixture cannot reach ────────────────────────────────
    //
    // `example/paimon/db.db/test` is one snapshot with one manifest holding one ADD entry, so it
    // exercises `ADDED` and nothing else. The other three need a delta applied over a base, which
    // means a table with a compaction or an overwrite behind it — blocked on the Flink container
    // the Paimon fixtures are generated from.
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
