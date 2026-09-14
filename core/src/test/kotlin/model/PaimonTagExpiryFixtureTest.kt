package model

import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `docs/fixtures/paimon-ptt.sql`: three tags — `short` with `time_retained => '1 s'`, `long` with
 * `'1 d'`, `plain` with none — kept as `ptt`, and as `ptta` after a bare `expire_tags` removed
 * `short`. The scratch runs the header records: `older_than => '2099-01-01 00:00:00'` removed
 * `long` and `plain` both, and on a copy of `tg` removed `first` and the data file only it held.
 */
class PaimonTagExpiryFixtureTest {

    private val ptt by lazy { FixtureCatalog.paimonModel("ptt") }
    private val ptta by lazy { FixtureCatalog.paimonModel("ptta") }

    /** The bare call's `LocalDateTime.now()`, which the fixture's own timing puts a few seconds past the tags' creation. */
    private fun nowFor(input: PaimonExpiryInput) = input.snapshotTimes.values.filterNotNull().max() + 10_000

    @Test
    fun `a tag records a create time and a retention only when created with one`() {
        val tags = ptt.expiryInput().tags.associateBy { it.name }
        assertEquals(setOf("short", "long", "plain"), tags.keys)
        val short = tags.getValue("short")
        assertNotNull(short.createTime, "the Jackson array form is read")
        assertEquals(1_000L, short.timeRetainedMs, "PT1S, written as 1.0 seconds")
        assertEquals(86_400_000L, tags.getValue("long").timeRetainedMs)
        val plain = tags.getValue("plain")
        assertNull(plain.createTime)
        assertNull(plain.timeRetainedMs)
        assertNotNull(plain.fileModifiedMs)
        assertTrue(tags.values.all { it.snapshotRetained }, "snapshots 1..3 are all retained")
        // The raw fields: the array and the seconds, as Jackson writes a LocalDateTime and a Duration.
        val raw = ptt.tags.first { it.name == "short" }.snapshot.metadata
        assertTrue(raw.tagCreateTime is kotlinx.serialization.json.JsonArray, raw.tagCreateTime.toString())
        assertEquals(1.0, (raw.tagTimeRetained as kotlinx.serialization.json.JsonPrimitive).content.toDouble(), "seconds with nine decimals: 1.000000000")
    }

    @Test
    fun `a bare call removes the tag whose retention ran out, which is what ptta lost`() {
        val input = ptt.expiryInput()
        val plan = input.planTagExpiry(nowFor(input))
        assertEquals(listOf("short"), plan.removed.map { it.tag.name }, plan.tags.map { it.tag.name to it.reason }.toString())
        assertEquals(setOf("long", "plain"), ptta.tags.map { it.name }.toSet())
        val reasons = plan.tags.associate { it.tag.name to it.reason }
        assertTrue(reasons.getValue("short").contains("ran out"), reasons.toString())
        assertTrue(reasons.getValue("long").contains("retention left"), reasons.toString())
        assertTrue(reasons.getValue("plain").contains("records no retention"), reasons.toString())
        // After: nothing left for a bare call.
        val after = ptta.expiryInput()
        assertEquals(emptyList(), after.planTagExpiry(nowFor(after)).removed)
    }

    /** `older_than` reaches every tag: the retained ones by their create time, `plain` by its file's modification time. */
    @Test
    fun `older_than removes a tag by its create time, or by its file's time when it records none`() {
        val input = ptt.expiryInput()
        val far = java.time.LocalDate.of(2099, 1, 1).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val plan = input.planTagExpiry(nowFor(input), olderThanMs = far)
        assertEquals(setOf("short", "long", "plain"), plan.removed.map { it.tag.name }.toSet(), plan.tags.map { it.tag.name to it.reason }.toString())
        assertTrue(plan.tags.first { it.tag.name == "plain" }.reason.contains("recording no create time"))
        // And an older_than before every tag removes only the one whose retention ran out.
        val early = input.planTagExpiry(nowFor(input), olderThanMs = 0L)
        assertEquals(listOf("short"), early.removed.map { it.tag.name })
    }

    /** `tg`: tag `first` names expired snapshot 1, whose data file only the tag lists; removing it frees that file, as the scratch run did. */
    @Test
    fun `removing a tag on an expired snapshot frees the files it alone held`() {
        val tg = FixtureCatalog.paimonModel("tg")
        val input = tg.expiryFileInput()
        val plan = assertNotNull(input.planTagDeletion("first"))
        val data = plan.dataFiles.map { it.name }
        assertEquals(1, data.size, plan.files.toString())
        assertTrue(data.single().startsWith("data-"), data.toString())
        // The one file no retained snapshot lists: tg's snapshot 4 lists two data files, and the tag's snapshot 1 one.
        val retained = tg.snapshots.flatMap { s -> replayPaimonSnapshot(s, null).liveEntries.values.mapNotNull { it.metadata.file?.fileName } }.toSet()
        assertTrue(data.single() !in retained)
        assertTrue(plan.files.any { it.kind == PaimonExpiryFileKind.TAG })
        assertTrue(plan.files.any { it.kind == PaimonExpiryFileKind.MANIFEST_LIST }, plan.describe)
        assertTrue(plan.note.contains("has expired"), plan.note)
        // The scratch run: expire_tags(older_than => 2099) on a copy left bucket-0 with two of its three parquet files.
        assertEquals(3, FixtureCatalog.paimonDir("tg").walk().count { it.name.endsWith(".parquet") })
    }

    /** A tag on a retained snapshot frees nothing but its own file, whatever it holds. */
    @Test
    fun `removing a tag on a retained snapshot deletes the tag file alone`() {
        val input = ptt.expiryFileInput()
        for (name in listOf("short", "long", "plain")) {
            val plan = assertNotNull(input.planTagDeletion(name))
            assertEquals(listOf(PaimonExpiryFileKind.TAG), plan.files.map { it.kind }, name)
            assertTrue(plan.note.contains("still retained"), plan.note)
        }
        assertNull(input.planTagDeletion("nothing"))
    }

    @Test
    fun `every fixture's tags plan without error, and a bare call removes none of the plain ones`() {
        for (name in FixtureCatalog.paimon) {
            val input = FixtureCatalog.paimonModel(name).expiryInput()
            val plan = input.planTagExpiry(nowFor(input))
            plan.tags.filter { it.tag.timeRetainedMs == null }.forEach { assertTrue(!it.expired, "$name ${it.tag.name}") }
        }
        assertEquals("1 d", formatPaimonDurationMs(86_400_000))
        assertEquals("1 s", formatPaimonDurationMs(1_000))
        assertEquals("23 h 59 min", formatPaimonDurationMs(86_400_000 - 60_000))
    }
}
