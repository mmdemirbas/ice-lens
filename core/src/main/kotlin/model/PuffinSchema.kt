package model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * What a Puffin file's footer says about one blob it holds.
 *
 * Puffin is the container Iceberg keeps things that do not fit in a manifest: today a v3 deletion
 * vector, and before that a Theta sketch of distinct values. The footer is JSON at the end of the
 * file, so this is a `@Serializable` schema class like the rest of the format's shapes, and
 * `service.PuffinReader` is what reads it.
 *
 * Read from the Puffin spec, <https://iceberg.apache.org/puffin-spec/>, 2026-08-22.
 */
@Serializable
data class PuffinBlobMetadata(
    val type: String,
    val fields: List<Int> = emptyList(),
    /** Puffin v1 cannot know these when it writes, so a deletion vector carries -1 for both. */
    @SerialName("snapshot-id") val snapshotId: Long? = null,
    @SerialName("sequence-number") val sequenceNumber: Long? = null,
    /** Where the blob's bytes start in the file. */
    val offset: Long,
    /** How many bytes they run to, after compression if any. */
    val length: Long,
    @SerialName("compression-codec") val compressionCodec: String? = null,
    val properties: Map<String, String> = emptyMap(),
) {
    /** The data file this vector applies to. The spec requires it on a `deletion-vector-v1`. */
    val referencedDataFile: String? get() = properties["referenced-data-file"]

    /** How many positions the writer says are set. Worth having beside what was decoded. */
    val recordedCardinality: Long? get() = properties["cardinality"]?.toLongOrNull()
}

/** A Puffin file's whole footer. */
@Serializable
data class PuffinFileMetadata(
    val blobs: List<PuffinBlobMetadata> = emptyList(),
    val properties: Map<String, String> = emptyMap(),
) {
    /** Which writer produced the file, when it says. */
    val createdBy: String? get() = properties["created-by"]
}

/**
 * The row positions a deletion vector marks, and the two figures worth checking it against.
 *
 * [positions] is capped; [cardinality] is always the full count decoded, so a reader is told
 * "showing 1,000 of 2,300,118" rather than being handed a truncated list as the whole vector.
 * [checksumMatches] and [recordedCardinality] are the same idea as `manifestTallies`: a figure the
 * writer recorded, sitting beside the same figure counted from the bytes. Nothing on a read path
 * checks either, so this does.
 */
data class DeletionVector(
    val positions: List<Long>,
    val cardinality: Long,
    val recordedCardinality: Long?,
    val checksumMatches: Boolean,
    val referencedDataFile: String?,
) {
    val truncated: Boolean get() = cardinality > positions.size
    val cardinalityAgrees: Boolean get() = recordedCardinality == null || recordedCardinality == cardinality
}
