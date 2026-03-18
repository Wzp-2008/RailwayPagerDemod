package com.example.railwaypagerdemod

import com.example.railwaypagerdemod.dsp.LowpassFirFilter
import com.example.railwaypagerdemod.dsp.MovingAverage
import com.example.railwaypagerdemod.dsp.PhaseDiscriminator

/**
 * POCSAG demodulator.
 *
 * Processes a stream of decimated I/Q byte samples and extracts POCSAG
 * messages.  Call [processOneSample] for each decimated sample.  When a
 * complete codeword batch is ready [isMessageReady] becomes `true` and the
 * message text is available in [numericMsg] / [address].
 *
 * Ported from C++ demod.cpp / demod.h plus the DSP helpers it uses.
 */
class Demodulator {

    companion object {
        const val SAMPLE_RATE = 48000.0
        const val BAUD_RATE   = 1200.0
        const val DEVIATION   = 4500.0
        private const val SAMPLES_PER_SYMBOL = SAMPLE_RATE / BAUD_RATE   // 40.0

        // POCSAG protocol constants
        private val POCSAG_SYNCCODE:     UInt = 0x7CD215D8u
        private val POCSAG_SYNCCODE_INV: UInt = POCSAG_SYNCCODE.inv()
        private val POCSAG_IDLECODE:     UInt = 0x7A89C197u
        private const val BATCH_WORDS           = 17
        private const val FRAMES_PER_BATCH      = 8
        private const val CODEWORDS_PER_FRAME   = 2

        private val NUMERIC_CHARS = charArrayOf(
            '0', '1', '2', '3', '4', '5', '6', '7', '8', '9', '.', 'U', ' ', '-', ')', '('
        )
    }

    // ── DSP chain ─────────────────────────────────────────────────────────────
    private val phaseDiscri = PhaseDiscriminator(
        fmScaling = (SAMPLE_RATE / (2.0 * DEVIATION)).toFloat()
    )
    private val lowpassBaud = LowpassFirFilter(
        nTaps      = 301,
        sampleRate = SAMPLE_RATE,
        cutoff     = BAUD_RATE * 5.0
    )
    private val preambleMovingAverage = MovingAverage(2048)

    // ── Public outputs ────────────────────────────────────────────────────────
    /** Set to `true` after each completed batch; caller must reset to `false`. */
    var isMessageReady: Boolean = false
    var numericMsg:     String  = ""
    var alphaMsg:       String  = ""
    var address:        UInt    = 0u
    /** Exponentially representative magnitude-squared of the input signal. */
    var magsqRaw:       Double  = 0.0

    // ── Demodulator state ─────────────────────────────────────────────────────
    private var gotSC        = false
    private var dcOffset     = 0.0
    private var prevData     = false
    private var bitInverted  = false
    private var syncCnt      = 0
    private var bitCnt       = 0
    private var wordCnt      = 0
    private var bits:        UInt = 0u

    private val codeWords         = UIntArray(BATCH_WORDS)
    private val codeWordsBchError = BooleanArray(BATCH_WORDS)

    private var functionBits           = 0
    private var alphaBitBuffer:        UInt = 0u
    private var alphaBitBufferBits     = 0

    // ─────────────────────────────────────────────────────────────────────────
    // Public API
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Feed one decimated I/Q sample (signed, –128..+127).
     * Equivalent to C++ processOneSample().
     */
    fun processOneSample(i: Byte, q: Byte) {
        val fi = i.toFloat() / 128.0f
        val fq = q.toFloat() / 128.0f

        val (fmDemod, magsq) = phaseDiscri.process(fi, fq)
        magsqRaw = magsq

        val filt = lowpassBaud.filter(fmDemod)

        if (!gotSC) {
            preambleMovingAverage.update(filt)
            dcOffset = preambleMovingAverage.asDouble()
        }

        val data = (filt - dcOffset) >= 0.0

        if (data != prevData) {
            // Detected a transition: re-align symbol clock
            syncCnt = (SAMPLES_PER_SYMBOL / 2).toInt()
        } else {
            syncCnt--
            if (syncCnt <= 0) {
                // Sample at symbol midpoint
                val dataBit = if (bitInverted) data else !data

                bits = (bits shl 1) or (if (dataBit) 1u else 0u)
                bitCnt++
                if (bitCnt > 32) bitCnt = 32

                if (bitCnt == 32 && !gotSC) {
                    tryAcquireSync()
                } else if (bitCnt == 32 && gotSC) {
                    processCodeWord()
                }

                syncCnt = SAMPLES_PER_SYMBOL.toInt()
            }
        }

        prevData = data
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Private helpers
    // ─────────────────────────────────────────────────────────────────────────

    /** Try to lock onto the POCSAG sync code. */
    private fun tryAcquireSync() {
        when {
            bits == POCSAG_SYNCCODE -> {
                gotSC = true; bitInverted = false
            }
            bits == POCSAG_SYNCCODE_INV -> {
                gotSC = true; bitInverted = true
            }
            popCnt(bits xor POCSAG_SYNCCODE) <= 3 -> {
                val corrected = bchDecode(bits)
                if (corrected == POCSAG_SYNCCODE) { gotSC = true; bitInverted = false }
            }
            popCnt(bits xor POCSAG_SYNCCODE_INV) <= 3 -> {
                val corrected = bchDecode(bits.inv())
                if (corrected == POCSAG_SYNCCODE) { gotSC = true; bitInverted = true }
            }
        }

        if (gotSC) {
            bits             = 0u
            bitCnt           = 0
            codeWords[0]     = POCSAG_SYNCCODE
            wordCnt          = 1
        }
    }

    /** Accumulate one codeword after sync has been acquired. */
    private fun processCodeWord() {
        val corrected             = bchDecode(bits)
        codeWordsBchError[wordCnt] = (corrected == null)
        codeWords[wordCnt]         = corrected ?: bits
        wordCnt++

        // After each batch the first codeword of the next batch must be the
        // sync code; if not, re-acquire sync (mirrors original C++ logic).
        if (wordCnt == 1 && codeWords[0] != POCSAG_SYNCCODE) {
            gotSC       = false
            bitInverted = false
        }

        if (wordCnt == BATCH_WORDS) {
            decodeBatch()
            wordCnt = 0
            isMessageReady = true
        }

        bits   = 0u
        bitCnt = 0
    }

    /**
     * Decode a full batch of [BATCH_WORDS] codewords into address + message.
     * Equivalent to C++ decodeBatch().
     */
    private fun decodeBatch() {
        var i = 1  // codeWords[0] is always the sync code
        for (frame in 0 until FRAMES_PER_BATCH) {
            for (word in 0 until CODEWORDS_PER_FRAME) {
                val cw = codeWords[i]
                val isAddressCodeWord = (cw shr 31) and 1u == 0u
                val parityOk = evenParity(cw, 1, 31, (cw and 1u).toInt())

                when {
                    cw == POCSAG_IDLECODE -> { /* idle – nothing to do */ }
                    isAddressCodeWord -> {
                        functionBits       = ((cw shr 11) and 0x3u).toInt()
                        val addressBits    = (cw shr 13) and 0x3FFFFu
                        address            = (addressBits shl 3) or frame.toUInt()
                        numericMsg         = ""
                        alphaMsg           = ""
                        alphaBitBufferBits = 0
                        alphaBitBuffer     = 0u
                    }
                    else -> {
                        // Message codeword – decode as numeric and 7-bit ASCII
                        val messageBits = (cw shr 11) and 0xFFFFFu

                        // ── Numeric ──────────────────────────────────────────
                        var j = 16
                        while (j >= 0) {
                            val nibble = (messageBits shr j) and 0xFu
                            val reversed = reverse32(nibble) shr 28
                            numericMsg += NUMERIC_CHARS[reversed.toInt()]
                            j -= 4
                        }

                        // ── 7-bit ASCII ───────────────────────────────────────
                        alphaBitBuffer      = (alphaBitBuffer shl 20) or messageBits
                        alphaBitBufferBits += 20
                        while (alphaBitBufferBits >= 7) {
                            var c = ((alphaBitBuffer shr (alphaBitBufferBits - 7)) and 0x7Fu).toInt()
                            c = (reverse32(c.toUInt()) shr 25).toInt()
                            if (c != 0 && c != 0x3 && c != 0x4) {
                                alphaMsg += c.toChar()
                            }
                            alphaBitBufferBits -= 7
                            alphaBitBuffer = if (alphaBitBufferBits == 0) 0u
                            else alphaBitBuffer and ((1u shl alphaBitBufferBits) - 1u)
                        }
                    }
                }
                i++
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // BCH codec  (ported from C++ bchEncode / bchDecode)
    // ─────────────────────────────────────────────────────────────────────────

    private fun bchEncode(cw: UInt): UInt {
        var localCW = cw and 0xFFFFF800u
        var cwE     = localCW
        for (bit in 1..21) {
            if (cwE and 0x80000000u != 0u) cwE = cwE xor 0xED200000u
            cwE = cwE shl 1
        }
        return localCW or (cwE shr 21)
    }

    /**
     * Attempt BCH error correction.
     * Returns the corrected codeword, or `null` if uncorrectable.
     */
    private fun bchDecode(cw: UInt): UInt? {
        var syndrome = ((bchEncode(cw) xor cw) shr 1) and 0x3FFu
        if (syndrome == 0u) return cw          // no errors

        var result:    UInt = 0u
        var damagedCW: UInt = cw
        var s = syndrome

        for (xbit in 0 until 31) {
            result = result shl 1
            if (s == 0x3B4u || s == 0x26Eu || s == 0x359u || s == 0x076u ||
                s == 0x255u || s == 0x0F0u || s == 0x216u || s == 0x365u ||
                s == 0x068u || s == 0x25Au || s == 0x343u || s == 0x07Bu ||
                s == 0x1E7u || s == 0x129u || s == 0x14Eu || s == 0x2C9u ||
                s == 0x0BEu || s == 0x231u || s == 0x0C2u || s == 0x20Fu ||
                s == 0x0DDu || s == 0x1B4u || s == 0x2B4u || s == 0x334u ||
                s == 0x3F4u || s == 0x394u || s == 0x3A4u || s == 0x3BCu ||
                s == 0x3B0u || s == 0x3B6u || s == 0x3B5u
            ) {
                s      = s xor 0x3B4u
                result = result or ((damagedCW.inv() and 0x80000000u) shr 30)
            } else {
                result = result or ((damagedCW and 0x80000000u) shr 30)
            }
            damagedCW = damagedCW shl 1
            s = if (s and 0x200u != 0u) ((s shl 1) xor 0x769u) and 0x3FFu
                else                     (s shl 1)              and 0x3FFu
        }

        return if (s != 0u) null else result
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Bit-manipulation utilities
    // ─────────────────────────────────────────────────────────────────────────

    /** Population count (number of set bits). */
    private fun popCnt(cw: UInt): Int {
        var v = cw; var count = 0
        while (v != 0u) { count += (v and 1u).toInt(); v = v shr 1 }
        return count
    }

    /** Reverse all 32 bits. */
    private fun reverse32(x: UInt): UInt {
        var v = x
        v = ((v and 0xAAAAAAAAu) shr 1) or ((v and 0x55555555u) shl 1)
        v = ((v and 0xCCCCCCCCu) shr 2) or ((v and 0x33333333u) shl 2)
        v = ((v and 0xF0F0F0F0u) shr 4) or ((v and 0x0F0F0F0Fu) shl 4)
        v = ((v and 0xFF00FF00u) shr 8) or ((v and 0x00FF00FFu) shl 8)
        return (v shr 16) or (v shl 16)
    }

    /** XOR reduction of bits [firstBit..lastBit] in word. */
    private fun xorBits(word: UInt, firstBit: Int, lastBit: Int): Int {
        var x = 0
        for (i in firstBit..lastBit) x = x xor ((word shr i) and 1u).toInt()
        return x
    }

    /** True if the parity bit at [parityBit] matches even parity of [firstBit..lastBit]. */
    private fun evenParity(word: UInt, firstBit: Int, lastBit: Int, parityBit: Int): Boolean =
        xorBits(word, firstBit, lastBit) == parityBit
}
