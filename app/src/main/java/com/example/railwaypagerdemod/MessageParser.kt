package com.example.railwaypagerdemod

import java.nio.charset.Charset

/**
 * Parsed representation of one POCSAG message.
 *
 * Fields are set according to the address type:
 * - **1234002** messages carry GPS + route info  → vehicleId, route, latitude, longitude
 * - **1234000** messages carry train operational data → trainNo, speed, mileage
 */
data class ParsedMessage(
    val vehicleId: String = "",
    val route:     String = "",
    val latitude:  String = "",
    val longitude: String = "",
    val trainNo:   String = "",
    val speed:     String = "",
    val mileage:   String = ""
)

/**
 * Parses raw POCSAG numeric messages into structured data.
 *
 * Ported from C++ decodeMessage.cpp.
 *
 * The accumulated state (so that fields from different message types are
 * preserved between calls) is held externally in [MainViewModel].
 */
object MessageParser {

    private val NUMERIC_CHARS = "0123456789.U -)('"

    // ─────────────────────────────────────────────────────────────────────────
    // Public API
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Parse a single raw message string of the form
     * `"<10-digit-address> <numeric-payload>"`.
     *
     * Returns a [ParsedMessage] whose non-empty fields should be merged into
     * the running UI state by the caller.
     */
    fun parseMessage(msg: String): ParsedMessage {
        return when {
            msg.contains("1234002") -> parse1234002(msg)
            msg.contains("1234000") -> parse1234000(msg)
            else                    -> ParsedMessage()
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Message-type parsers
    // ─────────────────────────────────────────────────────────────────────────

    /** Decode a GPS + route message (address prefix 1234002). */
    private fun parse1234002(msg: String): ParsedMessage {
        if (msg.length < 58) return ParsedMessage()

        val vehicleId = msg.substring(15, 23)
        val routeRaw  = msg.substring(25, 39)
        val lonRaw    = msg.substring(41, 50)
        val latRaw    = msg.substring(50, 58)

        val latitude  = "${latRaw.substring(0, 2)}.${latRaw.substring(2)}"
            .toDoubleOrNull()?.toString() ?: latRaw
        val longitude = "${lonRaw.substring(0, 3)}.${lonRaw.substring(3)}"
            .toDoubleOrNull()?.toString() ?: lonRaw

        val routeBytes = decodeGb2312(routeRaw)
        val route = if (routeBytes.isNotEmpty())
            String(routeBytes, Charset.forName("GB2312"))
        else ""

        return ParsedMessage(
            vehicleId = vehicleId,
            route     = route,
            latitude  = latitude,
            longitude = longitude
        )
    }

    /** Decode a train-operation message (address prefix 1234000). */
    private fun parse1234000(msg: String): ParsedMessage {
        val trimmed = msg.trim()
        val parts = trimmed.split("\\s+".toRegex())
        // parts: [address, trainNo, speed, mileage, ...]
        val trainNo = parts.getOrElse(1) { "" }
        val speed   = parts.getOrElse(2) { "" }
        val mileage = parts.getOrElse(3) { "" }

        return ParsedMessage(
            trainNo = trainNo,
            speed   = speed,
            mileage = mileage
        )
    }

    // ─────────────────────────────────────────────────────────────────────────
    // GB2312 decoder  (ported from C++ decode_gb2312)
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Convert a numeric-character field to raw GB2312 bytes.
     *
     * Tries four nibble-combination strategies and returns the bytes of the
     * second valid GB2312 sequence found (matching original C++ `results.at(1)`
     * behaviour), falling back to the first if only one exists.
     */
    private fun decodeGb2312(numericField: String): ByteArray {
        val nibbles = strToNibbles(numericField)
        val results = mutableListOf<ByteArray>()

        for (reverseBits in listOf(false, true)) {
            for (highFirst in listOf(false, true)) {
                val bytes = mutableListOf<Byte>()
                var idx = 0
                while (idx + 1 < nibbles.size) {
                    var n1 = nibbles[idx]
                    var n2 = nibbles[idx + 1]
                    if (reverseBits) { n1 = reverse4(n1); n2 = reverse4(n2) }
                    val byte = if (highFirst) ((n1 shl 4) or n2).toByte()
                               else          ((n2 shl 4) or n1).toByte()
                    bytes.add(byte)
                    idx += 2
                }
                if (bytes.any { it.toInt() and 0xFF >= 0xA1 }) {
                    results.add(bytes.toByteArray())
                }
            }
        }

        return when {
            results.size > 1 -> results[1]
            results.isNotEmpty() -> results[0]
            else -> ByteArray(0)
        }
    }

    /** Convert each character in [s] to its index in [NUMERIC_CHARS]. */
    private fun strToNibbles(s: String): List<Int> =
        s.mapNotNull { ch -> NUMERIC_CHARS.indexOf(ch).takeIf { it >= 0 } }

    /** Reverse the lower 4 bits of an integer. */
    private fun reverse4(x: Int): Int {
        var r = 0
        for (i in 0 until 4) r = r or (((x shr i) and 1) shl (3 - i))
        return r
    }
}
