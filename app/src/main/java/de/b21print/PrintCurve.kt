package de.b21print

import kotlin.math.pow

// Measured tone response of the B21 Pro with the photo dither, from the calibration chart
// (TestChart.calibration): printed darkness (0 = paper, 1 = solid, in linear light) of flat tones
// 255 · (1 − i / 24). Made monotone; measured on original rolls, fine speed.
// The response has a sharp knee: single dots print true up to ~25 % coverage, then they merge and
// the print saturates around tone 110. A smooth dot-gain formula cannot follow that.
object PrintCurve {
    private val D1 = floatArrayOf(
        0.000f, 0.031f, 0.063f, 0.076f, 0.084f, 0.135f, 0.160f, 0.295f, 0.415f, 0.533f, 0.684f, 0.759f, 0.759f,
        0.884f, 0.952f, 0.979f, 0.989f, 0.992f, 0.995f, 0.995f, 0.995f, 0.999f, 1.000f, 1.000f, 1.000f,
    )
    private val D5 = floatArrayOf(
        0.000f, 0.051f, 0.094f, 0.121f, 0.165f, 0.218f, 0.250f, 0.418f, 0.587f, 0.696f, 0.814f, 0.862f, 0.862f,
        0.975f, 0.976f, 1.000f, 1.000f, 1.000f, 1.000f, 1.000f, 1.000f, 1.000f, 1.000f, 1.000f, 1.000f,
    )

    // How dark a photo tone should print: between linear (1.0) and the screen's sRGB (~2.2)
    private const val TARGET_GAMMA = 1.6

    private val luts = HashMap<Int, FloatArray>()

    // Measured darkness at step i for a density 1..5; 2–4 are interpolated until measured
    private fun measured(density: Int, i: Int): Float {
        val t = ((density.coerceIn(1, 5) - 1) / 4f)
        return D1[i] + (D5[i] - D1[i]) * t
    }

    // Printed darkness of a dither input tone (0..255)
    fun darkness(density: Int, tone: Float): Float {
        val x = ((255f - tone.coerceIn(0f, 255f)) / 255f) * (D1.size - 1)
        val i = x.toInt().coerceAtMost(D1.size - 2)
        val f = x - i
        return measured(density, i) * (1 - f) + measured(density, i + 1) * f
    }

    // Dither input tone for each photo tone 0..255, so the print has the target darkness
    fun lut(density: Int): FloatArray = synchronized(luts) {
        luts.getOrPut(density.coerceIn(1, 5)) {
            val n = D1.size
            val dark = FloatArray(n) { measured(density, it) }
            FloatArray(256) { v ->
                val target = (1 - (v / 255.0).pow(TARGET_GAMMA)).toFloat()
                // First step whose darkness reaches the target, then interpolate within the step
                var i = 1
                while (i < n - 1 && dark[i] < target) i++
                val d0 = dark[i - 1]; val d1 = dark[i]
                val f = if (d1 > d0) ((target - d0) / (d1 - d0)).coerceIn(0f, 1f) else 0f
                val step = (i - 1) + f
                255f * (1f - step / (n - 1))
            }
        }
    }
}
