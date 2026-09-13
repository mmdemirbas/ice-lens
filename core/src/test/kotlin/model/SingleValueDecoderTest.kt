package model

import java.math.BigDecimal
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Iceberg single-value serialization (spec Appendix D).
 *
 * These bytes are the ones a manifest actually stores in `lower_bounds`, `upper_bounds` and
 * `partition`. They carry no self-description, so every assertion here is really about whether
 * the *type* was applied correctly — a decoder that reads the right bytes with the wrong type
 * returns a plausible number, not an error, which is why the traps get their own tests.
 */
class SingleValueDecoderTest {

    private fun le(build: ByteBuffer.() -> Unit, size: Int): ByteArray =
        ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN).apply(build).array()

    // ── Primitives ─────────────────────────────────────────────────────────

    @Test
    fun `boolean is one byte`() {
        assertEquals("false", decodeSingleValue(byteArrayOf(0), IcebergType.BooleanType).display)
        assertEquals("true", decodeSingleValue(byteArrayOf(1), IcebergType.BooleanType).display)
    }

    @Test
    fun `int is four bytes little-endian`() {
        val decoded = decodeSingleValue(le({ putInt(1) }, 4), IcebergType.IntType)
        assertEquals("1", decoded.display)
        assertEquals(1, decoded.value)
    }

    @Test
    fun `long is eight bytes little-endian and survives values beyond Int range`() {
        val big = 9_876_543_210_123L
        assertEquals(big.toString(), decodeSingleValue(le({ putLong(big) }, 8), IcebergType.LongType).display)
    }

    /**
     * Spec promotions: a bound written as `int` under a field since widened to `long` keeps its
     * four bytes when a manifest is rewritten under the new schema, and Iceberg's own
     * `Conversions.fromByteBuffer` reads it at four and widens. Same for `float` under `double`.
     * The decoded value says which width it came from; every other width is still a failure.
     */
    @Test
    fun `a four-byte bound under a promoted long or double is read at the width it was written`() {
        val asLong = decodeSingleValue(le({ putInt(2) }, 4), IcebergType.LongType)
        assertEquals("2", asLong.display)
        assertEquals(2L, asLong.value)
        assertEquals(IcebergType.IntType, asLong.writtenAs)
        assertNull(asLong.error)
        val asDouble = decodeSingleValue(le({ putFloat(2.5f) }, 4), IcebergType.DoubleType)
        assertEquals("2.5", asDouble.display)
        assertEquals(2.5, asDouble.value)
        assertEquals(IcebergType.FloatType, asDouble.writtenAs)
        assertNull(decodeSingleValue(le({ putLong(2) }, 8), IcebergType.LongType).writtenAs, "eight bytes is the type's own width")
        assertTrue(decodeSingleValue(le({ putShort(2) }, 2), IcebergType.LongType).isError, "two bytes is neither width")
        assertTrue(decodeSingleValue(le({ putInt(2) }, 4), IcebergType.TimestampType(false)).isError, "int → timestamp is not a promotion")
    }

    @Test
    fun `negative int round-trips`() {
        assertEquals("-42", decodeSingleValue(le({ putInt(-42) }, 4), IcebergType.IntType).display)
    }

    @Test
    fun `date is days since epoch, not millis`() {
        // 2026-08-12 is 20677 days after 1970-01-01.
        val decoded = decodeSingleValue(le({ putInt(20677) }, 4), IcebergType.DateType)
        assertEquals("2026-08-12", decoded.display)
    }

    @Test
    fun `time is microseconds from midnight, not milliseconds`() {
        // 13:45:30.123456 = 49530123456 microseconds.
        val decoded = decodeSingleValue(le({ putLong(49_530_123_456L) }, 8), IcebergType.TimeType())
        assertEquals("13:45:30.123456", decoded.display)
    }

    @Test
    fun `timestamp is microseconds since epoch and renders without a zone`() {
        // 2026-08-12T09:15:00Z = 1786526100 seconds.
        val micros = 1_786_526_100L * 1_000_000L
        val decoded = decodeSingleValue(le({ putLong(micros) }, 8), IcebergType.TimestampType(withZone = false))
        assertEquals("2026-08-12T09:15", decoded.display)
    }

    @Test
    fun `timestamptz renders as an instant`() {
        val micros = 1_786_526_100L * 1_000_000L
        val decoded = decodeSingleValue(le({ putLong(micros) }, 8), IcebergType.TimestampType(withZone = true))
        assertEquals("2026-08-12T09:15:00Z", decoded.display)
    }

    @Test
    fun `timestamp_ns reads nanoseconds, so the same bytes mean a different instant`() {
        val bytes = le({ putLong(1_786_526_100_000_000_000L) }, 8)
        val nanos = decodeSingleValue(bytes, IcebergType.TimestampType(true, IcebergType.TimeUnit.NANOS))
        val micros = decodeSingleValue(bytes, IcebergType.TimestampType(true, IcebergType.TimeUnit.MICROS))
        assertEquals("2026-08-12T09:15:00Z", nanos.display)
        assertTrue(micros.display != nanos.display, "micros and nanos must not agree on the same bytes")
    }

    @Test
    fun `string is raw UTF-8 with no length prefix`() {
        val decoded = decodeSingleValue("hello".toByteArray(Charsets.UTF_8), IcebergType.StringType)
        assertEquals("hello", decoded.display)
    }

    @Test
    fun `string handles multi-byte characters`() {
        val decoded = decodeSingleValue("İstanbul — 北京".toByteArray(Charsets.UTF_8), IcebergType.StringType)
        assertEquals("İstanbul — 北京", decoded.display)
    }

    @Test
    fun `uuid is big-endian, unlike every numeric type`() {
        val uuid = java.util.UUID.fromString("6f4d8b1a-2c3e-4f5a-9b8c-7d6e5f4a3b2c")
        val bytes = ByteBuffer.allocate(16).order(ByteOrder.BIG_ENDIAN)
            .putLong(uuid.mostSignificantBits).putLong(uuid.leastSignificantBits).array()
        assertEquals(uuid.toString(), decodeSingleValue(bytes, IcebergType.UuidType).display)
    }

    // ── Decimal: the trap that produces wrong numbers rather than errors ────

    @Test
    fun `decimal is unscaled two's-complement big-endian with scale from the schema`() {
        // 123.45 at scale 2 → unscaled 12345 → 0x3039, two bytes.
        val decoded = decodeSingleValue(byteArrayOf(0x30, 0x39), IcebergType.DecimalType(9, 2))
        assertEquals("123.45", decoded.display)
        assertEquals(BigDecimal("123.45"), decoded.value)
    }

    @Test
    fun `decimal byte length varies with the value, not the precision`() {
        // Same declared type, three different byte lengths — which is why length cannot be used
        // to infer a type, and why a fixed-width reader would be wrong.
        val small = decodeSingleValue(byteArrayOf(0x01), IcebergType.DecimalType(18, 2))
        val medium = decodeSingleValue(byteArrayOf(0x30, 0x39), IcebergType.DecimalType(18, 2))
        val large = decodeSingleValue(
            byteArrayOf(0x01, 0x63, 0x45, 0x78, 0x5D.toByte(), 0x8A.toByte()), IcebergType.DecimalType(18, 2)
        )
        assertEquals("0.01", small.display)
        assertEquals("123.45", medium.display)
        // 0x016345785D8A = 1525878906250 unscaled, scale 2.
        assertEquals("15258789062.50", large.display)
    }

    @Test
    fun `negative decimal uses two's-complement`() {
        // -123.45 at scale 2 → unscaled -12345 → 0xCFC7.
        val decoded = decodeSingleValue(byteArrayOf(0xCF.toByte(), 0xC7.toByte()), IcebergType.DecimalType(9, 2))
        assertEquals("-123.45", decoded.display)
    }

    // ── Floats: sign of zero is load-bearing for bounds ordering ────────────

    @Test
    fun `negative zero keeps its sign, because it sorts before positive zero`() {
        val negZero = decodeSingleValue(le({ putDouble(-0.0) }, 8), IcebergType.DoubleType)
        val posZero = decodeSingleValue(le({ putDouble(0.0) }, 8), IcebergType.DoubleType)
        assertEquals("-0.0", negZero.display)
        assertEquals("0", posZero.display)
    }

    @Test
    fun `infinities are named rather than printed as huge numbers`() {
        assertEquals("Infinity", decodeSingleValue(le({ putDouble(Double.POSITIVE_INFINITY) }, 8), IcebergType.DoubleType).display)
        assertEquals("-Infinity", decodeSingleValue(le({ putDouble(Double.NEGATIVE_INFINITY) }, 8), IcebergType.DoubleType).display)
    }

    @Test
    fun `double avoids scientific notation for ordinary magnitudes`() {
        assertEquals("0.1", decodeSingleValue(le({ putDouble(0.1) }, 8), IcebergType.DoubleType).display)
    }

    // ── Geospatial bounds are points, not geometries ────────────────────────

    @Test
    fun `geometry bound decodes as concatenated little-endian doubles`() {
        val bytes = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN)
            .putDouble(29.0).putDouble(41.0).array()
        val decoded = decodeSingleValue(bytes, IcebergType.GeometryType())
        assertEquals("(x=29, y=41)", decoded.display)
    }

    @Test
    fun `geometry bound omits NaN coordinates`() {
        val bytes = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN)
            .putDouble(29.0).putDouble(41.0).putDouble(Double.NaN).array()
        assertEquals("(x=29, y=41)", decodeSingleValue(bytes, IcebergType.GeometryType()).display)
    }

    // ── Totality: a bad value must not close the table ──────────────────────

    @Test
    fun `wrong byte count reports an error and shows hex rather than throwing`() {
        val decoded = decodeSingleValue(byteArrayOf(1, 2), IcebergType.IntType)
        assertTrue(decoded.isError, "should be flagged as undecodable")
        assertEquals("0102", decoded.display)
        assertNull(decoded.value)
        assertTrue(decoded.error!!.contains("4 bytes"), "error should name the expectation: ${decoded.error}")
    }

    @Test
    fun `an unrecognised type is reported by name rather than dropped`() {
        val decoded = decodeSingleValue(byteArrayOf(1), IcebergType.UnparsedType("hyperbolic"))
        assertTrue(decoded.isError)
        assertTrue(decoded.error!!.contains("hyperbolic"))
    }

    @Test
    fun `raw bytes are always retained so a wrong decode can be checked`() {
        val bytes = le({ putInt(7) }, 4)
        assertTrue(decodeSingleValue(bytes, IcebergType.IntType).raw.contentEquals(bytes))
    }

    // ── Type parsing ───────────────────────────────────────────────────────

    @Test
    fun `type strings parse, including the parameterised ones`() {
        fun t(s: String) = parseIcebergType(kotlinx.serialization.json.JsonPrimitive(s))
        assertEquals(IcebergType.LongType, t("long"))
        assertEquals(IcebergType.DecimalType(9, 2), t("decimal(9, 2)"))
        assertEquals(IcebergType.FixedType(16), t("fixed[16]"))
        assertEquals(IcebergType.TimestampType(withZone = true), t("timestamptz"))
        assertEquals(IcebergType.TimestampType(withZone = false, unit = IcebergType.TimeUnit.NANOS), t("timestamp_ns"))
        assertTrue(t("something-new-in-v5") is IcebergType.UnparsedType)
    }

    @Test
    fun `a manifest's embedded schema yields a field-id to type map`() {
        // Verbatim from the schema key of the example table's manifest Avro file metadata.
        val schema = parseIcebergSchema(
            """{"type":"struct","schema-id":0,"identifier-field-ids":[1],"fields":[
               {"id":1,"name":"k","required":true,"type":"int"},
               {"id":2,"name":"v","required":false,"type":"string"}]}"""
        )
        requireNotNull(schema)
        assertEquals(0, schema.schemaId)
        assertEquals(IcebergType.IntType, schema.typeOf(1))
        assertEquals(IcebergType.StringType, schema.typeOf(2))
        assertEquals("k", schema.nameOf(1))
        assertEquals(setOf(1), schema.identifierFieldIds)
    }

    @Test
    fun `nested struct fields are reachable by id`() {
        val schema = parseIcebergSchema(
            """{"type":"struct","schema-id":3,"fields":[
               {"id":1,"name":"id","required":true,"type":"long"},
               {"id":2,"name":"payload","required":false,"type":{"type":"struct","fields":[
                 {"id":7,"name":"amount","required":false,"type":"decimal(12, 4)"}]}}]}"""
        )
        requireNotNull(schema)
        assertEquals(IcebergType.DecimalType(12, 4), schema.typeOf(7))
        assertEquals("amount", schema.nameOf(7))
    }

    /**
     * The end-to-end shape: schema from the manifest's own file metadata, bytes from that
     * manifest's bounds. These are the literal bytes the example table stores — `01 00 00 00`
     * for `k`'s lower bound and the ASCII of "hello" for `v`'s.
     */
    @Test
    fun `bounds from the example table decode to their real values`() {
        val schema = requireNotNull(
            parseIcebergSchema(
                """{"type":"struct","schema-id":0,"identifier-field-ids":[1],"fields":[
                   {"id":1,"name":"k","required":true,"type":"int"},
                   {"id":2,"name":"v","required":false,"type":"string"}]}"""
            )
        )

        val kLower = decodeSingleValue(byteArrayOf(1, 0, 0, 0), requireNotNull(schema.typeOf(1)))
        val kUpper = decodeSingleValue(byteArrayOf(2, 0, 0, 0), requireNotNull(schema.typeOf(1)))
        val vLower = decodeSingleValue("hello".toByteArray(), requireNotNull(schema.typeOf(2)))
        val vUpper = decodeSingleValue("world".toByteArray(), requireNotNull(schema.typeOf(2)))

        assertEquals("1", kLower.display)
        assertEquals("2", kUpper.display)
        assertEquals("hello", vLower.display)
        assertEquals("world", vUpper.display)
    }
}
