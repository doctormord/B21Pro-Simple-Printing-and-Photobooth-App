package de.b21print

import kotlin.math.pow

// Measured tone response of the B21 Pro with the photo dither, from the calibration chart
// (TestChart.calibration, read with tools/calib.py): printed darkness (0 = paper, 1 = solid, linear
// light) of flat tones 255 · (1 − i / 24), per density 1..5, made monotone. Original rolls, 1-bit
// print at the fine speed (the only one the app uses; the fast speed printed dots too large).
//
// There is a sharp knee: single dots print true up to ~25 % coverage, then they merge and the
// print saturates around tone 100.
object PrintCurve {
    private val FINE = arrayOf(
        floatArrayOf(0.000f, 0.031f, 0.063f, 0.076f, 0.084f, 0.135f, 0.160f, 0.295f, 0.415f, 0.533f, 0.684f, 0.759f, 0.759f, 0.884f, 0.952f, 0.979f, 0.989f, 0.992f, 0.995f, 0.995f, 0.995f, 0.999f, 1.000f, 1.000f, 1.000f),
        floatArrayOf(0.000f, 0.030f, 0.065f, 0.085f, 0.110f, 0.137f, 0.147f, 0.288f, 0.411f, 0.510f, 0.641f, 0.694f, 0.694f, 0.847f, 0.936f, 1.000f, 1.000f, 1.000f, 1.000f, 1.000f, 1.000f, 1.000f, 1.000f, 1.000f, 1.000f),
        floatArrayOf(0.000f, 0.028f, 0.066f, 0.082f, 0.100f, 0.182f, 0.210f, 0.351f, 0.473f, 0.563f, 0.701f, 0.765f, 0.765f, 0.898f, 0.960f, 1.000f, 1.000f, 1.000f, 1.000f, 1.000f, 1.000f, 1.000f, 1.000f, 1.000f, 1.000f),
        floatArrayOf(0.000f, 0.047f, 0.085f, 0.106f, 0.132f, 0.175f, 0.198f, 0.351f, 0.498f, 0.605f, 0.762f, 0.816f, 0.816f, 0.951f, 0.982f, 1.000f, 1.000f, 1.000f, 1.000f, 1.000f, 1.000f, 1.000f, 1.000f, 1.000f, 1.000f),
        floatArrayOf(0.000f, 0.051f, 0.094f, 0.121f, 0.165f, 0.218f, 0.250f, 0.418f, 0.587f, 0.696f, 0.814f, 0.862f, 0.862f, 0.975f, 0.976f, 1.000f, 1.000f, 1.000f, 1.000f, 1.000f, 1.000f, 1.000f, 1.000f, 1.000f, 1.000f),
    )

    // Gray mode without a paper profile: how dark each of the 16 levels prints (0 = paper, 1 = level 15).
    // Measured with the gray calibration chart on an original roll at density 3. Levels 1–9 leave no
    // mark at any density (checked at 3, 4 and 5 on several rolls), so they count as paper and the
    // diffusion never uses them; light tones become sparse dots of level 10 instead of printing white.
    val GRAY = floatArrayOf(0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0.14f, 0.48f, 0.71f, 0.71f, 0.95f, 1f)

    // How dark a photo tone should print, 1 − (v/255)^gamma: 1.0 is linear, ~2.2 the screen's sRGB.
    // Chosen from a tone-sweep print (1.0 / 1.3 / 1.6 / 2.0) at density 3: higher values look grey
    // and flat on thermal paper.
    const val DEFAULT_GAMMA = 1.0

    // Above this darkness the phone photos used for measuring crush the shadows: patches that are
    // still distinguishable on paper read as solid. The table is trusted up to here and continues
    // in a straight line to solid at input 0, so the darkest photo tones still reach solid black.
    private const val TRUSTED = 0.9f

    private val luts = HashMap<String, FloatArray>()

    // measured: a paper profile's own table (same steps), instead of the built-in one
    private fun table(density: Int, measured: FloatArray?): FloatArray {
        val raw = measured ?: FINE[density.coerceIn(1, 5) - 1]
        val k = raw.indexOfFirst { it >= TRUSTED }.takeIf { it in 1 until raw.size - 1 } ?: return bridge(raw)
        val last = raw.size - 1
        return bridge(FloatArray(raw.size) { i ->
            if (i <= k) raw[i] else raw[k] + (1f - raw[k]) * (i - k) / (last - k)
        })
    }

    // Two steps measured equally dark (all built-in tables have one at steps 10/11) made the
    // correction jump a whole step at a single photo tone: tones 59 and 60 got dither inputs 11
    // apart, so faint banding in a photo (e.g. one taken of a screen) printed as cross stripes in
    // the shadows. Equal runs are therefore bridged by a straight line to the next darker step.
    private fun bridge(d: FloatArray): FloatArray {
        val out = d.copyOf()
        var i = 0
        while (i < out.size - 1) {
            var j = i + 1
            while (j < out.size && out[j] <= out[i]) j++
            if (j == out.size) break
            for (m in i + 1 until j) out[m] = out[i] + (out[j] - out[i]) * (m - i) / (j - i)
            i = j
        }
        return out
    }

    // Printed darkness of a dither input tone (0..255)
    fun darkness(density: Int, tone: Float, measured: FloatArray? = null): Float {
        val dark = table(density, measured)
        val x = ((255f - tone.coerceIn(0f, 255f)) / 255f) * (dark.size - 1)
        val i = x.toInt().coerceAtMost(dark.size - 2)
        val f = x - i
        return dark[i] * (1 - f) + dark[i + 1] * f
    }

    // Dither input tone for each photo tone 0..255, so the print has the target darkness
    fun lut(density: Int, gamma: Double = DEFAULT_GAMMA, measured: FloatArray? = null): FloatArray = synchronized(luts) {
        luts.getOrPut("${density.coerceIn(1, 5)} $gamma ${measured?.contentHashCode()}") {
            val dark = table(density, measured)
            val n = dark.size
            FloatArray(256) { v ->
                val target = (1 - (v / 255.0).pow(gamma)).toFloat()
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
