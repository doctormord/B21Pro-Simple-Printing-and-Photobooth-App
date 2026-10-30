package de.b21print

import android.graphics.Bitmap
import android.graphics.Matrix
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

// A check of ChartReader without a printer or a real photo: the pre-dithered calibration chart is
// "photographed" by software (turned and tilted, 1.4 photo pixels per dot, blurred, with a light
// gradient, paper texture and noise) and read; the result is compared with the ink coverage the chart
// really has. Shows how far aliasing and noise move the measurement.
object CalSelfTest {
    private const val INK = 0.08f      // reflectance of a printed dot
    private const val SCALE_PX = 1000   // photo size

    fun run(looks: List<Pair<String, ImageProcessor.Raster>>): List<String> = looks.map { (name, look) ->
        runCatching { one(name, look) }.getOrElse { "$name: ${it.message}" }
    }

    private fun one(name: String, look: ImageProcessor.Raster): String {
        val w = 591; val h = 591
        val lay = TestChart.calChart(w, h, false)
        val chart = TestChart.calibration(w, h, "SELF", look)
        val cpx = IntArray(w * h).also { chart.getPixels(it, 0, w, 0, 0, w, h) }
        val cov = lay.patches.map { b ->
            var n = 0; var tot = 0
            for (y in b.y + (0.2f * b.h).toInt() until b.y + (0.8f * b.h).toInt())
                for (x in b.x + (0.2f * b.w).toInt() until b.x + (0.8f * b.w).toInt()) { tot++; if (cpx[y * w + x] and 0xFFFFFF == 0) n++ }
            n.toFloat() / tot
        }
        val truth = FloatArray(cov.size) { cov[it] / cov.last() }

        // Chart → photo: a slightly tilted, keystoned quadrilateral
        val m = Matrix().apply {
            setPolyToPoly(floatArrayOf(0f, 0f, w.toFloat(), 0f, w.toFloat(), h.toFloat(), 0f, h.toFloat()), 0,
                floatArrayOf(110f, 130f, 930f, 95f, 950f, 925f, 90f, 940f), 0, 4)
        }
        val inv = Matrix(); m.invert(inv)
        val iv = FloatArray(9).also { inv.getValues(it) }
        val n = SCALE_PX
        val lin = FloatArray(n * n)
        val rnd = java.util.Random(7)
        val ss = 2
        for (v in 0 until n) for (u in 0 until n) {
            var acc = 0f
            for (sy in 0 until ss) for (sx in 0 until ss) {
                val px = u + (sx + 0.5f) / ss; val py = v + (sy + 0.5f) / ss
                val z = iv[6] * px + iv[7] * py + iv[8]
                val cxf = (iv[0] * px + iv[1] * py + iv[2]) / z; val cyf = (iv[3] * px + iv[4] * py + iv[5]) / z
                val cx = Math.floor(cxf.toDouble()).toInt(); val cy = Math.floor(cyf.toDouble()).toInt()
                acc += if (cx in 0 until w && cy in 0 until h) (if (cpx[cy * w + cx] and 0xFFFFFF == 0) INK else 1f) else 1f
            }
            val paper = (0.80f + 0.2f * u / n) * (1f + 0.015f * sin(0.9f * (u + v)))
            lin[v * n + u] = acc / (ss * ss) * paper
        }
        repeat(2) { boxBlur(lin, n) }
        for (i in lin.indices) lin[i] = (lin[i] + 0.01f * rnd.nextGaussian().toFloat()).coerceIn(0f, 1f)
        val pix = IntArray(n * n) {
            val g = (255 * srgb(lin[it])).toInt().coerceIn(0, 255)
            0xFF000000.toInt() or (g shl 16) or (g shl 8) or g
        }
        val photo = Bitmap.createBitmap(pix, n, n, Bitmap.Config.ARGB_8888)

        val t0 = System.currentTimeMillis()
        val r = ChartReader.read(photo, w, h, lay) ?: return "$name: chart not found"
        val ms = System.currentTimeMillis() - t0
        var sq = 0.0; var worst = 0f
        for (i in 1 until truth.size - 1) { val d = r.dark[i] - truth[i]; sq += d * d; worst = maxOf(worst, kotlin.math.abs(d)) }
        return "$name: rms %.1f %%, worst %.1f %%, %d ms".format(100 * sqrt(sq / (truth.size - 2)), 100 * worst, ms)
    }

    private fun boxBlur(a: FloatArray, n: Int) {
        val t = FloatArray(a.size)
        for (y in 0 until n) for (x in 0 until n) {
            var s = 0f; var c = 0
            for (d in -1..1) { val xx = x + d; if (xx in 0 until n) { s += a[y * n + xx]; c++ } }
            t[y * n + x] = s / c
        }
        for (y in 0 until n) for (x in 0 until n) {
            var s = 0f; var c = 0
            for (d in -1..1) { val yy = y + d; if (yy in 0 until n) { s += t[yy * n + x]; c++ } }
            a[y * n + x] = s / c
        }
    }

    private fun srgb(r: Float) = if (r <= 0.0031308f) r * 12.92f else 1.055f * r.toDouble().pow(1 / 2.4).toFloat() - 0.055f
}
