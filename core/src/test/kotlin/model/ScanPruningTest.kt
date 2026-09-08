package model

import java.io.File
import java.nio.file.Paths
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Pruning verdicts against the two fixtures written to carry every transform shape.
 *
 * `parted` has eight partition fields over five source columns — identity on a string and on a
 * decimal, `bucket[4]`, `truncate[3]`, `day`, and `year` / `month` / `hour` over three separate
 * timestamptz columns — and two manifests whose ranges differ sharply: one spanning 1969 to 2025,
 * one holding a single row. `respec` has two specs, so the same predicate meets `day` in one
 * manifest and `month` in the other.
 *
 * The expected values here come from the fixtures' own recorded bounds, printed once and read off
 * (`d_day` on the single-row manifest is `2024-03-05 … 2024-03-05`; `ts_y_year` is the ordinal
 * `54 … 54`, which is 2024). They are not what this code produced.
 */
class ScanPruningTest {

    private val repoRoot: File = generateSequence(File(".").absoluteFile) { it.parentFile }
        .first { File(it, "settings.gradle.kts").isFile }

    private fun manifests(fixture: String): List<UnifiedManifest> =
        UnifiedTableModel(Paths.get(File(repoRoot, "example/iceberg/default/$fixture").absolutePath))
            .metadatas.flatMap { it.snapshots }.flatMap { it.manifests }
            .distinctBy { it.metadata.manifestPath }

    /** The manifest holding one row, whose every field is a single value. */
    private fun oneRow(): UnifiedManifest = manifests("parted").first { it.dataFiles.size == 1 }

    /** The manifest spanning 1969 to 2025. */
    private fun wide(): UnifiedManifest = manifests("parted").first { it.dataFiles.size == 3 }

    private fun evaluate(manifest: UnifiedManifest, vararg predicates: ScanPredicate) =
        evaluatePruning(manifest.partitionSummaries, predicates.toList())

    private fun effectOn(manifest: UnifiedManifest, field: String, predicate: ScanPredicate): TermEffect =
        evaluate(manifest, predicate).outcomes.single { it.fieldName == field }.effect

    @Test
    fun `a date outside a manifest's day range skips it and inside it does not`() {
        assertEquals(
            TermEffect.SKIPS,
            effectOn(oneRow(), "d_day", ScanPredicate("d", PredicateOp.EQ, "2020-01-01")),
        )
        assertEquals(
            TermEffect.KEEPS,
            effectOn(oneRow(), "d_day", ScanPredicate("d", PredicateOp.EQ, "2024-03-05")),
        )
        // The same predicate against the manifest that spans 1969 to 2025 cannot be ruled out.
        assertEquals(
            TermEffect.KEEPS,
            effectOn(wide(), "d_day", ScanPredicate("d", PredicateOp.EQ, "2020-01-01")),
        )
    }

    /**
     * The bridge the whole feature turns on: the predicate is a timestamp and the manifest records
     * an int ordinal, so the literal has to go through the transform before it can be compared.
     * `ts_y_year` on the single-row manifest is `54 … 54`, which is 2024.
     */
    @Test
    fun `a timestamp literal is compared as the ordinal the transform stores`() {
        val ts2026 = ScanPredicate("ts_y", PredicateOp.GT, "2026-01-01T00:00:00Z")
        val outcome = evaluate(oneRow(), ts2026).outcomes.single { it.fieldName == "ts_y_year" }

        assertEquals(TermEffect.SKIPS, outcome.effect)
        assertTrue(
            outcome.reason.contains("2024") && outcome.reason.contains("2026"),
            "the reason should name both the bound and the transformed literal, not the ordinals: " +
                outcome.reason,
        )
    }

    @Test
    fun `an hour partition prunes on a timestamp below its range`() {
        assertEquals(
            TermEffect.SKIPS,
            effectOn(oneRow(), "ts_h_hour", ScanPredicate("ts_h", PredicateOp.LT, "2020-01-01T00:00:00Z")),
        )
        assertEquals(
            TermEffect.KEEPS,
            effectOn(oneRow(), "ts_h_hour", ScanPredicate("ts_h", PredicateOp.LT, "2025-01-01T00:00:00Z")),
        )
    }

    /**
     * One predicate, two partition fields. `name` is partitioned twice — identity and
     * `truncate[3]` — so naming the source column gives a scan two chances to eliminate the
     * manifest, and this is the case where both take it.
     */
    @Test
    fun `a predicate on a twice-partitioned column is evaluated against both fields`() {
        val outcomes = evaluate(wide(), ScanPredicate("name", PredicateOp.EQ, "delta")).outcomes

        assertEquals(listOf("name", "name_trunc"), outcomes.map { it.fieldName })
        assertTrue(outcomes.all { it.effect == TermEffect.SKIPS }, "both should rule it out: $outcomes")
        // 'delta' truncates to 'del', which is past 'cha' — a different comparison from the
        // identity one, on a different value.
        assertTrue(
            outcomes.last().reason.contains("del"),
            "the truncate outcome should show the truncated literal: ${outcomes.last().reason}",
        )
    }

    @Test
    fun `a decimal range prunes on the sign the manifest recorded`() {
        // The wide manifest's amounts start at 0.01; the single-row one holds -5.50.
        assertEquals(
            TermEffect.SKIPS,
            effectOn(wide(), "amount", ScanPredicate("amount", PredicateOp.LT, "0")),
        )
        assertEquals(
            TermEffect.KEEPS,
            effectOn(oneRow(), "amount", ScanPredicate("amount", PredicateOp.LT, "0")),
        )
    }

    /**
     * A bucket field decides equality, and declines everything else.
     *
     * This test used to assert the opposite for equality too, and it was right to: computing the
     * bucket meant reproducing Iceberg's murmur3, and a verdict from a hash that agreed only with
     * itself would prune manifests holding the rows. What changed is not the argument but the
     * evidence — `BucketTransform` calls the same Guava function Iceberg's own transform calls,
     * and `BucketTransformTest` checks the result against the buckets Spark recorded at two
     * different bucket counts. The old assertion is superseded, not relaxed.
     *
     * The other operators still decline, and that half has not changed at all: bucketing is a
     * hash, so a range of bucket numbers says nothing about a range of values.
     */
    @Test
    fun `a bucket field decides equality and declines every other operator`() {
        val decided = evaluate(wide(), ScanPredicate("id", PredicateOp.EQ, "7"))
            .outcomes.single { it.fieldName == "id_bucket" }
        assertTrue(
            decided.effect != TermEffect.NOT_EVALUATED,
            "equality across a bucket is decidable: ${decided.reason}",
        )
        assertTrue(decided.reason.contains("bucket"), decided.reason)

        listOf(PredicateOp.LT, PredicateOp.LTE, PredicateOp.GT, PredicateOp.GTE, PredicateOp.NOT_EQ)
            .forEach { op ->
                val outcome = evaluate(wide(), ScanPredicate("id", op, "7"))
                    .outcomes.single { it.fieldName == "id_bucket" }
                assertEquals(
                    TermEffect.NOT_EVALUATED,
                    outcome.effect,
                    "$op across a hash proves nothing: ${outcome.reason}",
                )
            }
    }

    @Test
    fun `a column no partition field reads is reported, not silently dropped`() {
        val result = evaluate(wide(), ScanPredicate("no_such_column", PredicateOp.EQ, "1"))

        assertEquals(TermEffect.NOT_EVALUATED, result.outcomes.single().effect)
        assertTrue(result.isUnevaluated, "a verdict of 'would be read' here carries no information")
        assertTrue(!result.isSkipped)
    }

    @Test
    fun `is null prunes on contains_null, which is recorded even where bounds are not`() {
        assertEquals(
            TermEffect.SKIPS,
            effectOn(wide(), "d_day", ScanPredicate("d", PredicateOp.IS_NULL)),
        )
        assertEquals(
            TermEffect.NOT_EVALUATED,
            effectOn(wide(), "d_day", ScanPredicate("d", PredicateOp.IS_NOT_NULL)),
        )
    }

    /**
     * Only identity can prove a manifest holds nothing but one value. `day` maps a whole day of
     * timestamps to one date, so bounds that meet do not mean every row carries the excluded value.
     */
    @Test
    fun `not-equal prunes under identity and declines under a many-to-one transform`() {
        assertEquals(
            TermEffect.SKIPS,
            effectOn(oneRow(), "name", ScanPredicate("name", PredicateOp.NOT_EQ, "alpha")),
        )
        assertEquals(
            TermEffect.NOT_EVALUATED,
            effectOn(oneRow(), "d_day", ScanPredicate("d", PredicateOp.NOT_EQ, "2024-03-05")),
        )
    }

    /**
     * The same predicate against a table that was repartitioned. `respec`'s first manifest
     * partitions `d` by `day`, its second by `month`, and the evaluation has to follow each
     * manifest's own spec — using the current spec for both is the silent mis-decode this
     * fixture exists to catch.
     */
    @Test
    fun `each manifest is evaluated against the spec it was written under`() {
        val (byDay, byMonth) = manifests("respec").let { it.first { m -> m.metadata.partitionSpecId == 0 } to
            it.first { m -> m.metadata.partitionSpecId == 3 } }

        val old = ScanPredicate("d", PredicateOp.LT, "1969-01-01")
        assertEquals("d_day", byDay.partitionSummaries.first { it.field.transformName == "day" }.field.name)
        assertEquals(TermEffect.SKIPS, effectOn(byDay, "d_day", old))
        assertEquals(TermEffect.SKIPS, effectOn(byMonth, "d_month", old))

        val inRange = ScanPredicate("d", PredicateOp.EQ, "2024-03-05")
        assertEquals(TermEffect.KEEPS, effectOn(byDay, "d_day", inRange))
        assertEquals(TermEffect.KEEPS, effectOn(byMonth, "d_month", inRange))
    }

    @Test
    fun `a manifest is skipped when any one term rules it out`() {
        val result = evaluate(
            oneRow(),
            ScanPredicate("name", PredicateOp.EQ, "alpha"),
            ScanPredicate("d", PredicateOp.EQ, "2020-01-01"),
        )

        assertTrue(result.isSkipped)
        assertEquals("d_day", result.skippedBy?.fieldName)
    }

    /**
     * An `OR` on a real table, where each half alone would rule the manifest out.
     *
     * This is the case a list of terms cannot express and gets wrong by construction. `d` is
     * `2024-03-05` on the one-row manifest, so either literal below prunes it on its own — and
     * ORed they must not, because a manifest matching neither half of the disjunction still has to
     * match *some* half before it can be dropped. An evaluator that took the first proof would skip
     * a manifest a real query reads, which is the one class of pruning bug that loses rows.
     */
    @Test
    fun `an OR of two terms that each prune does not prune together until both do`() {
        val outside2020 = ScanFilter.Term(ScanPredicate("d", PredicateOp.EQ, "2020-01-01"))
        val outside2021 = ScanFilter.Term(ScanPredicate("d", PredicateOp.EQ, "2021-01-01"))
        val inside = ScanFilter.Term(ScanPredicate("d", PredicateOp.EQ, "2024-03-05"))
        val manifest = oneRow()

        assertTrue(
            evaluatePruning(manifest.partitionSummaries, ScanFilter.And(listOf(outside2020))).isSkipped,
            "each half has to prune on its own, or the OR case below proves nothing",
        )
        assertTrue(evaluatePruning(manifest.partitionSummaries, ScanFilter.And(listOf(outside2021))).isSkipped)

        assertTrue(
            evaluatePruning(
                manifest.partitionSummaries, ScanFilter.Or(listOf(outside2020, outside2021)),
            ).isSkipped,
            "both halves ruled out, so the disjunction is ruled out",
        )
        assertTrue(
            !evaluatePruning(
                manifest.partitionSummaries, ScanFilter.Or(listOf(outside2020, inside)),
            ).isSkipped,
            "one half might match, so the manifest must be read whatever the other half says",
        )
    }

    /**
     * `NOT` prunes through the leaf rewrite, on the fixture's own recorded range.
     *
     * The one-row manifest holds `d = 2024-03-05`, so `NOT (d <> 2020-01-01)` is `d = 2020-01-01`
     * and prunes, while `NOT (d = 2024-03-05)` becomes `d <> 2024-03-05` and — under an identity
     * transform on a single-valued range — also prunes. Both go through `pushNegation`; a `NOT`
     * left in the tree would have pruned neither.
     */
    @Test
    fun `NOT prunes by negating the operator it wraps`() {
        val manifest = oneRow()
        val pruned = ScanFilter.Not(ScanFilter.Term(ScanPredicate("d", PredicateOp.NOT_EQ, "2020-01-01")))
        assertTrue(evaluatePruning(manifest.partitionSummaries, pruned).isSkipped)

        val kept = ScanFilter.Not(ScanFilter.Term(ScanPredicate("d", PredicateOp.NOT_EQ, "2024-03-05")))
        assertTrue(
            !evaluatePruning(manifest.partitionSummaries, kept).isSkipped,
            "the manifest's only value is exactly this one, so nothing rules it out",
        )
    }

    /**
     * The property that catches an inverted comparison anywhere in the evaluator: a file's own
     * partition value must never rule out the manifest that lists the file.
     *
     * Run over every identity field of every manifest of every checked-in table, because identity
     * is where a file's stored value is also a valid source literal. It is worth more than the
     * cases above — those assert what one predicate does, this asserts that the evaluator cannot
     * exclude something it is holding.
     */
    @Test
    fun `no file's own partition value skips the manifest that lists it`() {
        var checked = 0
        listOf("parted", "respec").forEach { fixture ->
            manifests(fixture).forEach { manifest ->
                val identityFields = manifest.partitionSummaries.filter {
                    it.field.transformName == "identity" && it.sourceName != null
                }
                manifest.dataFiles.forEach { file ->
                    identityFields.forEach { summary ->
                        val value = file.partition?.values
                            ?.firstOrNull { it.field.name == summary.field.name } ?: return@forEach
                        val predicate = ScanPredicate(summary.sourceName!!, PredicateOp.EQ, value.human)
                        val effect = effectOn(manifest, summary.field.name!!, predicate)
                        checked++
                        assertTrue(
                            effect != TermEffect.SKIPS,
                            "$fixture: ${predicate} skipped a manifest that holds exactly that file",
                        )
                    }
                }
            }
        }
        println("Checked $checked file-own-value predicates against their own manifests")
        assertTrue(checked > 0, "the fixtures should have identity partition fields to check")
    }

    /**
     * `LIKE` against the two string fields `parted` records: `name` (identity) and `name_trunc`
     * (`truncate[3]`).
     *
     * The bounds are the fixture's own, printed once and read off: the three-file manifest records
     * `name` as `alpha … charlie` and `name_trunc` as `alp … cha`; the one-row manifest records
     * `alpha … alpha` and `alp … alp`. So `del%` is past the top of both fields on both manifests,
     * `b%` sits inside the wide one and above the narrow one, and `a%` is inside both.
     */
    @Test
    fun `a pattern prunes on the leading text, under identity and under truncate`() {
        val far = ScanPredicate("name", PredicateOp.LIKE, "del%")
        assertEquals(TermEffect.SKIPS, effectOn(wide(), "name", far))
        assertEquals(TermEffect.SKIPS, effectOn(oneRow(), "name", far))
        // truncate is a prefix, so it keeps exactly the text a pattern is compared against.
        assertEquals(TermEffect.SKIPS, effectOn(wide(), "name_trunc", far))
        assertEquals(TermEffect.SKIPS, effectOn(oneRow(), "name_trunc", far))

        val bravo = ScanPredicate("name", PredicateOp.LIKE, "b%")
        assertEquals(TermEffect.KEEPS, effectOn(wide(), "name", bravo), "bravo is in alpha … charlie")
        assertEquals(TermEffect.SKIPS, effectOn(oneRow(), "name", bravo), "alpha … alpha is below b")

        val alpha = ScanPredicate("name", PredicateOp.LIKE, "a%")
        assertEquals(TermEffect.KEEPS, effectOn(wide(), "name", alpha))
        assertEquals(TermEffect.KEEPS, effectOn(oneRow(), "name", alpha))
    }

    /**
     * A prefix longer than the bound is compared over what the bound has, and that is the whole
     * reason truncated bounds stay sound.
     *
     * `name_trunc` records three characters. `alph%` is four, and `alp … alp` cannot rule it out —
     * the manifest genuinely may hold `alpha`. Comparing the full prefix against a bound three
     * characters long would find `alp` < `alph` and skip a manifest holding a matching row, which
     * is the one pruning bug that loses data.
     */
    @Test
    fun `a prefix longer than the recorded bound does not prune`() {
        val predicate = ScanPredicate("name", PredicateOp.LIKE, "alph%")
        assertEquals(TermEffect.KEEPS, effectOn(oneRow(), "name_trunc", predicate))
        assertEquals(TermEffect.SKIPS, effectOn(oneRow(), "name_trunc", ScanPredicate("name", PredicateOp.LIKE, "b%")))
    }

    /**
     * `NOT LIKE` runs the proof the other way: it needs *every* value to match the pattern.
     *
     * So it is answered where the bounds are the values — an identity field — and declines where a
     * transform folded many values into one, since bounds that both start `alp` under `truncate[3]`
     * say nothing about what follows in the source.
     */
    @Test
    fun `NOT LIKE is proved only where every value must match`() {
        val predicate = ScanPredicate("name", PredicateOp.NOT_LIKE, "alpha%")
        assertEquals(TermEffect.SKIPS, effectOn(oneRow(), "name", predicate), "every row here is alpha")
        assertEquals(TermEffect.KEEPS, effectOn(wide(), "name", predicate), "alpha … charlie holds more")
        assertEquals(TermEffect.NOT_EVALUATED, effectOn(oneRow(), "name_trunc", predicate))
    }

    /**
     * The row form can also produce a `LIKE`, and there it may hold no wildcard at all.
     *
     * The clause parser reads a wildcard-free pattern as `=` before the evaluator sees it, but the
     * form has an operator menu and no parser, so `like alpha` arrives intact. Answering "pins no
     * text at the start" about `alpha` would be nonsense; it prunes on the whole pattern instead,
     * which is weaker than equality and never wrong.
     */
    @Test
    fun `a wildcard-free pattern from the form still prunes on its text`() {
        assertEquals(
            TermEffect.SKIPS,
            effectOn(oneRow(), "name", ScanPredicate("name", PredicateOp.LIKE, "charlie")),
        )
        assertEquals(
            TermEffect.KEEPS,
            effectOn(oneRow(), "name", ScanPredicate("name", PredicateOp.LIKE, "alpha")),
        )
    }

    /** A pattern that pins nothing at the front, and one on a field that is not text. */
    @Test
    fun `a pattern with no leading text, and one on a bucket, report that they did not evaluate`() {
        assertEquals(
            TermEffect.NOT_EVALUATED,
            effectOn(oneRow(), "name", ScanPredicate("name", PredicateOp.LIKE, "%pha")),
        )
        // The bucket path answers first, and its reason is the one that fits: a hash keeps no
        // ordering at all, let alone leading text.
        assertEquals(
            TermEffect.NOT_EVALUATED,
            evaluate(oneRow(), ScanPredicate("id", PredicateOp.LIKE, "1%"))
                .outcomes.single { it.fieldName == "id_bucket" }.effect,
        )
    }

    /**
     * The file stage answers the same question against a file's own column bounds.
     *
     * `parted`'s three-file manifest holds one file per name — `alpha`, `bravo`, `charlie` — so a
     * pattern separates them one from another, which a manifest-level range cannot.
     */
    @Test
    fun `a pattern prunes a file by its own column bounds`() {
        val files = wide().dataFiles.map { columnStatsFor(it.metadata.dataFile!!, wide().schema) }
        fun fates(predicate: ScanPredicate) =
            files.map { evaluateFilePruning(it, listOf(predicate)).fate }.groupingBy { it }.eachCount()

        assertEquals(
            mapOf(FileFate.WOULD_BE_READ to 1, FileFate.SKIPPED to 2),
            fates(ScanPredicate("name", PredicateOp.LIKE, "b%")),
            "only the file whose name bounds are bravo … bravo can hold one",
        )
        assertEquals(
            mapOf(FileFate.SKIPPED to 3),
            fates(ScanPredicate("name", PredicateOp.LIKE, "del%")),
        )
        assertEquals(
            mapOf(FileFate.SKIPPED to 1, FileFate.WOULD_BE_READ to 2),
            fates(ScanPredicate("name", PredicateOp.NOT_LIKE, "alpha%")),
            "the alpha … alpha file holds nothing that is not alpha",
        )
        // A pattern on a column that is not text is declined rather than answered.
        assertEquals(
            mapOf(FileFate.UNEVALUATED to 3),
            fates(ScanPredicate("amount", PredicateOp.LIKE, "1%")),
        )
    }
}
