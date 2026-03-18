package com.example.railwaypagerdemod.dsp

/**
 * Fixed-size rolling moving average.
 *
 * Ported from C++ util/movingaverage.h (MovingAverageUtil<double, double, N>).
 *
 * @param n  Window size (must be > 0).
 */
class MovingAverage(private val n: Int) {

    private val samples = DoubleArray(n)
    private var numSamples = 0
    private var index = 0
    private var total = 0.0

    fun reset() {
        numSamples = 0
        index = 0
        total = 0.0
        samples.fill(0.0)
    }

    /** Add one sample to the window. */
    fun update(sample: Double) {
        if (numSamples < n) {
            samples[numSamples++] = sample
            total += sample
        } else {
            total += sample - samples[index]
            samples[index] = sample
            index = (index + 1) % n
        }
    }

    /** Return the current window average as a Double. */
    fun asDouble(): Double = if (numSamples > 0) total / numSamples else 0.0
}
