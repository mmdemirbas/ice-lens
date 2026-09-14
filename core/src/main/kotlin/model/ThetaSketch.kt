package model

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * A compact DataSketches theta sketch, decoded from an `apache-datasketches-theta-v1` blob —
 * what `compute_table_stats` writes per column and what the blob's `ndv` property was read
 * off (`NDVSketchUtil` at Iceberg 1.8.1: `(long) sketch.getEstimate()`).
 *
 * The record keeps the figure and not how it was made, and the two are different facts: a
 * sketch holds every hash while a column's distinct values fit its nominal entries (4,096 by
 * default), and its count is then **exact**; past that it keeps only the hashes below a
 * threshold θ and the count is `retained ÷ θ`, an **estimate** — `ndv` 20,158 on a column of
 * 20,000 values, with nothing in the record saying which kind it is. [exact] is that fact.
 *
 * The layout (`PreambleUtil` and `CompactOperations.computeCompactPreLongs` at
 * datasketches-java 6.1.1, serialization version 3): byte 0 the preamble length in longs, byte
 * 1 the version, byte 2 the family (3 is compact), byte 5 the flags — bit 0 big-endian, bit 2
 * empty, bit 3 compact, bit 4 ordered, bit 5 a single item — bytes 6–7 the seed's hash; an
 * empty sketch is that one long, a single-item sketch that long and the one hash, an exact
 * sketch two longs (the retained count at byte 8, `p` at 12) and the hashes, an estimating
 * sketch three (θ at byte 16) and the hashes. Multi-byte figures are in native byte order,
 * which the big-endian flag says of the writer. Version 4 — a delta-packed form 5.x and later
 * can write with `toByteArrayCompressed` — is refused by its version, since Iceberg writes 3.
 */
data class ThetaSketch(
    /** How many hashes the sketch keeps — every distinct value's while [exact]. */
    val retainedEntries: Int,
    /** The sampling threshold as a fraction of the hash space, 1.0 while every hash is kept. */
    val theta: Double,
    val empty: Boolean,
    val singleItem: Boolean,
    val ordered: Boolean,
) {
    /** Whether the count is the distinct values themselves rather than a projection from a sample of them. */
    val exact: Boolean get() = empty || theta >= 1.0

    /** `getEstimate()`: the retained hashes divided by the fraction of the hash space they were kept from. */
    val estimate: Double get() = if (empty) 0.0 else retainedEntries / theta

    /** The figure Iceberg records as `ndv`, truncated the way `(long) getEstimate()` truncates it. */
    val ndv: Long get() = estimate.toLong()

    /** `exact, 7 hashes` / `estimate, 4,096 hashes kept below θ 0.2032` / `single value` / `empty`. */
    fun describe(): String = when {
        empty -> "empty"
        singleItem -> "single value"
        exact -> "exact, ${"%,d".format(retainedEntries)} hashes"
        else -> "estimate, ${"%,d".format(retainedEntries)} hashes kept below θ ${"%.4f".format(theta)}"
    }

    companion object {
        /** The blob type `compute_table_stats` writes a column's sketch under (`StandardBlobTypes`). */
        const val BLOB_TYPE = "apache-datasketches-theta-v1"
        const val SER_VER = 3
        const val FAMILY_COMPACT = 3
        private const val LONG_MAX = Long.MAX_VALUE.toDouble()

        fun decode(bytes: ByteArray): ThetaSketch {
            if (bytes.size < 8) throw IllegalArgumentException("a ${bytes.size}-byte blob is too short to hold a sketch preamble")
            val preLongs = bytes[0].toInt() and 0x3F
            val serVer = bytes[1].toInt() and 0xFF
            val family = bytes[2].toInt() and 0xFF
            val flags = bytes[5].toInt() and 0xFF
            if (serVer != SER_VER) throw IllegalArgumentException("serialization version $serVer is not the $SER_VER this reads")
            if (family != FAMILY_COMPACT) throw IllegalArgumentException("family $family is not a compact sketch (3)")
            val bigEndian = flags and 1 != 0
            val empty = flags and 4 != 0
            val singleItem = flags and 32 != 0
            val ordered = flags and 16 != 0
            val buffer = ByteBuffer.wrap(bytes).order(if (bigEndian) ByteOrder.BIG_ENDIAN else ByteOrder.LITTLE_ENDIAN)
            if (bytes.size < preLongs * 8) throw IllegalArgumentException("a ${bytes.size}-byte blob cannot hold a $preLongs-long preamble")
            return when (preLongs) {
                1 -> when {
                    empty -> ThetaSketch(0, 1.0, empty = true, singleItem = false, ordered = ordered)
                    singleItem || bytes.size >= 16 -> ThetaSketch(1, 1.0, empty = false, singleItem = true, ordered = ordered)
                    else -> throw IllegalArgumentException("a one-long preamble with neither the empty nor the single-item flag")
                }
                2, 3 -> {
                    val retained = buffer.getInt(8)
                    val thetaLong = if (preLongs == 3) buffer.getLong(16) else Long.MAX_VALUE
                    if (retained < 0) throw IllegalArgumentException("a retained count of $retained")
                    if (thetaLong <= 0) throw IllegalArgumentException("a theta of $thetaLong")
                    val hashesEnd = preLongs * 8L + retained * 8L
                    if (bytes.size < hashesEnd) throw IllegalArgumentException("a ${bytes.size}-byte blob cannot hold $retained hashes after a $preLongs-long preamble")
                    ThetaSketch(retained, thetaLong / LONG_MAX, empty = empty, singleItem = false, ordered = ordered)
                }
                else -> throw IllegalArgumentException("a preamble of $preLongs longs is not a compact sketch's")
            }
        }
    }
}
