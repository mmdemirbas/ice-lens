package model

import java.nio.ByteBuffer
import java.util.UUID

/**
 * Z85, ZeroMQ's Base85 (rfc.zeromq.org/spec/32), which Delta uses for a deletion vector's
 * `pathOrInlineDv`: 4 bytes to 5 characters, big-endian, from an alphabet chosen to be safe in
 * JSON. Only decoding is needed — this app writes nothing.
 */
object Z85 {
    private const val ALPHABET =
        "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ.-:+=^!/*?&<>()[]{}@%\$#"
    private val index = IntArray(128) { -1 }.also { t -> ALPHABET.forEachIndexed { i, c -> t[c.code] = i } }

    /** [text] decoded; its length must be a multiple of 5, as every Z85 string's is. */
    fun decode(text: String): ByteArray {
        require(text.length % 5 == 0) { "Z85 text of length ${text.length} is not a multiple of 5" }
        val out = ByteArray(text.length / 5 * 4)
        for (block in 0 until text.length / 5) {
            var value = 0L
            for (i in 0 until 5) {
                val c = text[block * 5 + i]
                val digit = if (c.code < 128) index[c.code] else -1
                require(digit >= 0) { "'$c' is not a Z85 character" }
                value = value * 85 + digit
            }
            for (i in 0 until 4) out[block * 4 + i] = (value ushr (24 - 8 * i)).toByte()
        }
        return out
    }

    /** The UUID a relative deletion vector's last 20 characters encode (`PROTOCOL.md`, "Derived Fields"). */
    fun decodeUuid(text: String): UUID {
        val bytes = ByteBuffer.wrap(decode(text))
        return UUID(bytes.long, bytes.long)
    }
}
