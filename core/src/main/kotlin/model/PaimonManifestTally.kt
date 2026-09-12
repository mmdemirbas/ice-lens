package model

/**
 * One figure a Paimon manifest list records about a manifest, beside the same figure folded from
 * the manifest's own entries.
 *
 * The same idea as [ManifestTally] and deliberately a separate type: an Iceberg manifest's six
 * figures are counts, and a Paimon manifest list also records *ranges* — the lowest and highest
 * bucket and level, and a per-column minimum and maximum over the entries' partitions — so both
 * sides are text here, formatted the way the panel prints them, and a range of dates compares
 * as a pair of dates rather than as a number.
 */
data class PaimonManifestTally(
    val label: String,
    /** From the manifest list entry. Null when the writer recorded nothing — older manifest lists omit the ranges. */
    val recorded: String?,
    /** Folded from the manifest's entries. Null when there are no entries to fold. */
    val counted: String?,
) {
    /** Null when either side has nothing to say. */
    val agrees: Boolean? = if (recorded == null || counted == null) null else recorded == counted
}

/**
 * Every figure the manifest list records about a manifest, each against its entries.
 *
 * The partition minimum and maximum are **per column**, not the lowest and highest entry: Paimon's
 * `SimpleStats` keeps one minimum and one maximum per field, so a manifest holding
 * `(2024-03-07, eu)` and `(2024-03-05, north-america)` records `(2024-03-05, eu)` as its minimum —
 * a partition no entry has. Folding the entries the same way is what makes the comparison hold;
 * folding them as rows would disagree with a correct manifest list on exactly the manifests where
 * the distinction exists. The counts and ranges cover every entry, `_KIND` 0 and 1 alike, which the
 * fixtures with removals settle rather than the reading of any document.
 */
fun paimonManifestTallies(
    recorded: PaimonManifestFileMeta,
    entries: List<PaimonManifestEntryView>,
    recordedMin: DecodedPaimonPartition?,
    recordedMax: DecodedPaimonPartition?,
): List<PaimonManifestTally> {
    fun count(kind: Int): String = entries.count { it.entry.kind == kind }.toString()
    fun <T : Comparable<T>> range(values: List<T>): Pair<String?, String?> =
        values.minOrNull()?.toString() to values.maxOrNull()?.toString()

    val (lowestBucket, highestBucket) = range(entries.mapNotNull { it.entry.bucket })
    val (lowestLevel, highestLevel) = range(entries.mapNotNull { it.entry.file?.level })

    val figures = mutableListOf(
        PaimonManifestTally("Added entries", recorded.numAddedFiles?.toString(), count(PaimonEntryKind.ADD)),
        PaimonManifestTally("Deleted entries", recorded.numDeletedFiles?.toString(), count(PaimonEntryKind.DELETE)),
        PaimonManifestTally("Lowest bucket", recorded.minBucket?.toString(), lowestBucket),
        PaimonManifestTally("Highest bucket", recorded.maxBucket?.toString(), highestBucket),
        PaimonManifestTally("Lowest level", recorded.minLevel?.toString(), lowestLevel),
        PaimonManifestTally("Highest level", recorded.maxLevel?.toString(), highestLevel),
    )

    // One column at a time: the key's name comes from whichever side decoded, and the counted
    // side folds the entries' decoded values under natural order, nulls left out and counted.
    val keys = (recordedMin ?: recordedMax)?.values?.map { it.name }
        ?: entries.firstNotNullOfOrNull { it.partition }?.values?.map { it.name }
        .orEmpty()
    keys.forEachIndexed { index, key ->
        val decoded = entries.mapNotNull { it.partition?.values?.getOrNull(index) }
        val present = decoded.mapNotNull { it.value }
        val countedMin = present.minWithOrNull(naturalOrder)?.toString()
        val countedMax = present.maxWithOrNull(naturalOrder)?.toString()
        val countedNulls = if (entries.isEmpty()) null else decoded.count { it.value == null }.toString()
        figures += PaimonManifestTally("$key minimum", recordedMin?.values?.getOrNull(index)?.display, countedMin)
        figures += PaimonManifestTally("$key maximum", recordedMax?.values?.getOrNull(index)?.display, countedMax)
        figures += PaimonManifestTally("$key nulls", recorded.partitionStats?.nullCounts?.getOrNull(index)?.toString(), countedNulls)
    }
    return figures
}

/**
 * Natural order over the values [decodePaimonPartition] produces, which are all `Comparable`
 * within one column. Two values of different classes in one column cannot happen for a decoded
 * partition; they fall back to their text so that the fold still ends.
 */
private val naturalOrder: Comparator<Any> = Comparator { a, b ->
    @Suppress("UNCHECKED_CAST")
    if (a::class == b::class && a is Comparable<*>) (a as Comparable<Any>).compareTo(b)
    else a.toString().compareTo(b.toString())
}
