package com.example.railwaypagerdemod.dsp

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * FIR lowpass filter using a Blackman-windowed sinc kernel.
 *
 * Exploits filter symmetry so that a 301-tap filter only requires 151 stored
 * tap values and 151 multiply-accumulate operations per sample.
 *
 * Ported from C++ firfilter.cpp / firfilter.h (FirFilter / Lowpass templates).
 *
 * @param nTaps      Number of filter taps (must be odd; enforced internally).
 * @param sampleRate Sample rate in Hz.
 * @param cutoff     –3 dB cutoff frequency in Hz.
 */
class LowpassFirFilter(nTaps: Int, sampleRate: Double, cutoff: Double) {

    // Half-symmetric taps (length = nTaps/2 + 1 = 151 for nTaps=301)
    private val taps: FloatArray
    // Circular sample buffer (length = nTaps = 301)
    private val samples: DoubleArray
    private var ptr: Int = 0

    init {
        taps = generateLowPassTaps(nTaps, sampleRate, cutoff)
        samples = DoubleArray(nTaps) { 0.0 }
    }

    /**
     * Filter one sample and return the output.
     * Uses the symmetric convolution from the C++ FirFilter::filter() method.
     */
    fun filter(sample: Double): Double {
        val nSamples = samples.size        // 301
        val nTaps    = taps.size - 1       // 150  (centre tap at index 150)

        var a = ptr
        var b = if (a == nSamples - 1) 0 else a + 1

        samples[ptr] = sample

        var acc = 0.0
        for (i in 0 until nTaps) {
            acc += (samples[a] + samples[b]) * taps[i]
            a = if (a == 0) nSamples - 1 else a - 1
            b = if (b == nSamples - 1) 0 else b + 1
        }
        acc += samples[a] * taps[nTaps]  // centre tap (applied once, not doubled)

        ptr = if (ptr == nSamples - 1) 0 else ptr + 1
        return acc
    }

    companion object {
        /**
         * Generate half-symmetric Blackman-windowed sinc lowpass taps.
         * Output length is nTaps/2 + 1 (the unique half including the centre).
         * Ported from C++ FirFilterGenerators::generateLowPassFilter.
         */
        fun generateLowPassTaps(nTaps: Int, sampleRate: Double, cutoff: Double): FloatArray {
            val n = if (nTaps % 2 == 0) nTaps + 1 else nTaps
            val wc = 2.0 * PI * cutoff / sampleRate
            val halfTaps = n / 2 + 1
            val taps = FloatArray(halfTaps)

            for (i in 0 until halfTaps) {
                taps[i] = if (i == halfTaps - 1) {
                    (wc / PI).toFloat()
                } else {
                    val ni = i.toDouble() - (n - 1) / 2.0
                    (sin(ni * wc) / (ni * PI)).toFloat()
                }
            }

            // Apply Blackman window
            for (i in 0 until halfTaps) {
                val ni = i.toDouble() - (n - 1) / 2.0
                val window = 0.42 + 0.5 * cos(2.0 * PI * ni / n) + 0.08 * cos(4.0 * PI * ni / n)
                taps[i] = (taps[i] * window).toFloat()
            }

            // Normalise so DC gain = 1
            var sum = 0.0
            for (i in 0 until taps.size - 1) sum += taps[i] * 2.0
            sum += taps[taps.size - 1]
            for (i in taps.indices) taps[i] = (taps[i] / sum).toFloat()

            return taps
        }
    }
}
