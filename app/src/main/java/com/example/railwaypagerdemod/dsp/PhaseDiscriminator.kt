package com.example.railwaypagerdemod.dsp

import kotlin.math.PI
import kotlin.math.abs

/**
 * FM phase discriminator using delta-phase method with fast atan2 approximation.
 *
 * Ported from C++ phasediscri.h (PhaseDiscriminators::phaseDiscriminatorDelta).
 *
 * @param fmScaling  Maps ±deviation to ±1.0. Set to sampleRate / (2 × deviation).
 */
class PhaseDiscriminator(private val fmScaling: Float) {

    private var prevArg: Float = 0.0f

    fun reset() {
        prevArg = 0.0f
    }

    /**
     * Process one I/Q sample.
     *
     * @param i  Normalised in-phase component (–1..+1)
     * @param q  Normalised quadrature component (–1..+1)
     * @return   Pair of (FM demodulated output, magnitude squared)
     */
    fun process(i: Float, q: Float): Pair<Double, Double> {
        val magsq = (i * i + q * q).toDouble()
        val curArg = atan2Approx(q, i)
        var fmDev = (curArg - prevArg) / PI.toFloat()
        prevArg = curArg

        if (fmDev < -1.0f) fmDev += 2.0f
        else if (fmDev > 1.0f) fmDev -= 2.0f

        return Pair((fmDev * fmScaling).toDouble(), magsq)
    }

    /**
     * Fast atan2 approximation, |error| < 0.005.
     * Ported from C++ atan2_approximation2.
     */
    private fun atan2Approx(y: Float, x: Float): Float {
        val piF = PI.toFloat()
        val piBy2 = (PI / 2.0).toFloat()

        if (x == 0.0f) {
            return when {
                y > 0.0f  ->  piBy2
                y == 0.0f ->  0.0f
                else      -> -piBy2
            }
        }
        val z = y / x
        return if (abs(z) < 1.0f) {
            val atan = z / (1.0f + 0.28f * z * z)
            if (x < 0.0f) {
                if (y < 0.0f) atan - piF else atan + piF
            } else {
                atan
            }
        } else {
            val atan = piBy2 - z / (z * z + 0.28f)
            if (y < 0.0f) atan - piF else atan
        }
    }
}
