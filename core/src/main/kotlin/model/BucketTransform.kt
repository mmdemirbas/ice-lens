package model

import com.google.common.hash.Hashing
import java.math.BigDecimal
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets

/**
 * Iceberg's `bucket[N]` partition transform.
 *
 * **This is not a hash re-implemented from the spec, and the distinction is the whole reason it
 * exists.** The standing objection to computing a bucket here was that a hash written from a spec
 * agrees with itself long before it agrees with the writer, and a wrong bucket number prunes a
 * manifest that holds the rows — a silent wrong answer, which is worse than declining to answer.
 * What is used instead is `Hashing.murmur3_32_fixed()`, the same Guava function Iceberg's own
 * `Bucket` transform hashes with, so the only thing left to get wrong is how a value becomes bytes.
 *
 * That part is checked against the writer rather than against the spec text:
 * `BucketTransformTest` takes the bucket number Spark recorded for each data file of `parted` and
 * `respec` and requires this to reproduce it from the file's own bounds — including `respec`,
 * where the same ids are bucketed at both 4 and 8, so a modulus applied at the wrong point cannot
 * pass both.
 *
 * The spec's serialisation rules, one per type:
 *
 * | Type | Bytes hashed |
 * |---|---|
 * | `int`, `long`, `date`, `time`, `timestamp`, `timestamptz` | the value as a **long**, 8 bytes little-endian |
 * | `decimal` | the unscaled value, minimal big-endian two's complement |
 * | `string` | UTF-8, no length prefix |
 * | `uuid` | 16 bytes, most significant half first |
 * | `fixed`, `binary` | the bytes themselves |
 *
 * `boolean`, `float` and `double` are **not bucketable** — the spec excludes them, because
 * `-0.0` and `0.0` are equal and would have to hash alike. Passing one returns null rather than a
 * plausible number.
 *
 * A `date` is days since the epoch and a `timestamp` is microseconds, both widened to a long
 * *without conversion*: bucketing a date hashes 19,000, not a millisecond count. Getting that
 * wrong yields a valid bucket number for the wrong value, which is exactly the failure mode the
 * fixture check is here to catch.
 */
object BucketTransform {

    @Suppress("UnstableApiUsage")
    private val murmur3 = Hashing.murmur3_32_fixed()

    /**
     * The bucket [value] falls in, or null when the type cannot be bucketed or the value is null.
     *
     * Null is Iceberg's own answer for a null value: a null is not in any bucket, and the
     * partition value recorded for it is null rather than a number.
     */
    fun bucketOf(value: Any?, numBuckets: Int): Int? {
        if (numBuckets <= 0) return null
        val bytes = hashableBytes(value) ?: return null
        // The spec's `(hash & Integer.MAX_VALUE) % N`. Masking off the sign bit rather than taking
        // an absolute value: `abs(Int.MIN_VALUE)` is `Int.MIN_VALUE`, so an abs-based version is
        // correct for every input except one and negative there.
        return (murmur3.hashBytes(bytes).asInt() and Int.MAX_VALUE) % numBuckets
    }

    /** The bytes the spec hashes for this value, or null when its type has no bucket transform. */
    private fun hashableBytes(value: Any?): ByteArray? = when (value) {
        null -> null
        is Int -> longBytes(value.toLong())
        is Long -> longBytes(value)
        is Short -> longBytes(value.toLong())
        is Byte -> longBytes(value.toLong())
        is String -> value.toByteArray(StandardCharsets.UTF_8)
        is CharSequence -> value.toString().toByteArray(StandardCharsets.UTF_8)
        is BigDecimal -> value.unscaledValue().toByteArray()
        is ByteArray -> value
        is ByteBuffer -> ByteArray(value.remaining()).also { value.duplicate().get(it) }
        is java.util.UUID -> ByteBuffer.allocate(16)
            .order(ByteOrder.BIG_ENDIAN)
            .putLong(value.mostSignificantBits)
            .putLong(value.leastSignificantBits)
            .array()
        // A date, time or timestamp arrives already decoded as its epoch ordinal, so the branches
        // above cover it. Everything else — booleans and floating point — has no bucket transform.
        else -> null
    }

    private fun longBytes(value: Long): ByteArray =
        ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(value).array()
}
