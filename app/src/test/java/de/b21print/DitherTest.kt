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
}
