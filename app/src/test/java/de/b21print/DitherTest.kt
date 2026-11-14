package de.b21print

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DitherTest {
    // The blue-noise map must rank every position once, so each tone has exactly its share of dots
    @Test
    fun blueNoiseIsAPermutation() {
        val m = Dither.blue
        assertEquals(Dither.BLUE_N * Dither.BLUE_N, m.size)
        assertEquals((0 until m.size).toList(), m.sorted())
    }

    @Test
    fun ditherCoverage() {
        for (tone in listOf(230f, 191f, 128f, 64f)) {
            val want = 1 - tone / 255
            for (bits in listOf(ImageProcessor.ditherFlat(tone, 128, 128, ImageProcessor.Raster.BLUE),
                ImageProcessor.ditherFlat(tone, 128, 128, ImageProcessor.Raster.RIEMERSMA))) {
                val got = bits.count { it.toInt() == 1 } / (128.0 * 128)
                assertTrue("tone $tone: $got vs $want", kotlin.math.abs(got - want) < 0.02)
            }
        }
    }

    // Skipping blocks outside the image must not change the dot order of the plain Hilbert walk
    @Test
    fun riemersmaMatchesFullWalk() {
        for ((w, h) in listOf(1 to 1, 3 to 7, 50 to 200, 117 to 33, 65 to 64)) {
            val gray = FloatArray(w * h) { ((it * 37) % 256).toFloat() }
            val got = ByteArray(w * h)
            Dither.riemersma(gray, w, h, got)
            // Reference: every step of the s × s curve, the error filter as in Dither
            val queue = 32; val r = 1.0 / 8
            val wt = DoubleArray(queue) { k -> Math.pow(r, (queue - 1 - k).toDouble() / (queue - 1)) }
            val sum = wt.sum(); for (k in wt.indices) wt[k] /= sum
            val hist = DoubleArray(queue); var head = 0
            var s = 1; while (s < maxOf(w, h)) s *= 2
            val want = ByteArray(w * h)
            for (d in 0 until s * s) {
                var x = 0; var y = 0; var t = d; var q = 1
                while (q < s) {
                    val rx = 1 and (t / 2); val ry = 1 and (t xor rx)
                    if (ry == 0) { if (rx == 1) { x = q - 1 - x; y = q - 1 - y }; val tmp = x; x = y; y = tmp }
                    x += q * rx; y += q * ry; t /= 4; q *= 2
                }
                if (x >= w || y >= h) continue
                var err = 0.0
                for (k in 0 until queue) err += hist[(head + k) % queue] * wt[k]
                val v = gray[y * w + x] + err
                want[y * w + x] = if (v < 128) 1 else 0
                hist[head] = v - if (v < 128) 0.0 else 255.0
                head = (head + 1) % queue
            }
            assertTrue("$w x $h", got.contentEquals(want))
        }
    }
}
