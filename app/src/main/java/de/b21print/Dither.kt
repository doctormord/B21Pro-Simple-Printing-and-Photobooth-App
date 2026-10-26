package de.b21print

import kotlin.math.exp
import kotlin.math.pow

// Two 1-bit dithers after https://surma.dev/things/ditherpunk/
object Dither {
    // Blue-noise threshold map, 64 × 64, tiling: void-and-cluster after Ulichney. Like Bayer every
    // tone gets one fixed pattern (so a calibration chart measures exactly what a photo prints), but
    // the dots are spread evenly without a visible grid. Built once on first use (well under a second).
    const val BLUE_N = 64

    val blue: IntArray by lazy { voidAndCluster(BLUE_N, 1.5, 7) }

    private fun voidAndCluster(n: Int, sigma: Double, seed: Long): IntArray {
        val size = n * n
        // Gaussian energy kernel on the torus, so the map tiles without seams
        val kernel = FloatArray(size) { i ->
            val dx = minOf(i % n, n - i % n); val dy = minOf(i / n, n - i / n)
            exp(-(dx * dx + dy * dy) / (2 * sigma * sigma)).toFloat()
        }
        val on = BooleanArray(size)
        val energy = FloatArray(size)
        fun toggle(p: Int, set: Boolean) {
            on[p] = set
            val px = p % n; val py = p / n
            val s = if (set) 1f else -1f
            for (y in 0 until n) {
                val ky = ((y - py + n) % n) * n
                for (x in 0 until n) energy[y * n + x] += s * kernel[ky + (x - px + n) % n]
            }
        }
        fun tightestCluster(): Int {
            var best = -1
            for (i in 0 until size) if (on[i] && (best < 0 || energy[i] > energy[best])) best = i
            return best
        }
        fun largestVoid(): Int {
            var best = -1
            for (i in 0 until size) if (!on[i] && (best < 0 || energy[i] < energy[best])) best = i
            return best
        }

        // Initial pattern: 10 % random dots, then move dots from clusters into voids until stable
        val rnd = java.util.Random(seed)
        val ones = size / 10
        while (on.count { it } < ones) { val p = rnd.nextInt(size); if (!on[p]) toggle(p, true) }
        while (true) {
            val c = tightestCluster(); toggle(c, false)
            val v = largestVoid()
            if (v == c) { toggle(c, true); break }
            toggle(v, true)
        }
        val start = on.copyOf(); val startEnergy = energy.copyOf()
        val rank = IntArray(size)
        // Ranks below the initial dots: remove them cluster by cluster
        for (r in ones - 1 downTo 0) { val c = tightestCluster(); toggle(c, false); rank[c] = r }
        // Ranks above: fill voids one by one
        start.copyInto(on); startEnergy.copyInto(energy)
        for (r in ones until size) { val v = largestVoid(); toggle(v, true); rank[v] = r }
        return rank
    }

    // Threshold (0..255) of the blue-noise map at a print position
    fun blueThreshold(x: Int, y: Int) =
        (blue[(y and (BLUE_N - 1)) * BLUE_N + (x and (BLUE_N - 1))] + 0.5f) / (BLUE_N * BLUE_N) * 255f

    // Riemersma: error diffusion along a Hilbert curve, the error of the last 32 dots weighted
    // exponentially (newest 1, oldest 1/8, normalised). No rows, so no row-wise textures.
    fun riemersma(gray: FloatArray, w: Int, h: Int, bits: ByteArray) {
        val queue = 32; val r = 1.0 / 8
        val weights = DoubleArray(queue) { k -> r.pow((queue - 1 - k).toDouble() / (queue - 1)) }
        val sum = weights.sum()
        for (k in weights.indices) weights[k] /= sum
        val hist = DoubleArray(queue); var head = 0
        var s = 1
        while (s < maxOf(w, h)) s *= 2
        for (d in 0 until s * s) {
            val (x, y) = hilbert(s, d)
            if (x >= w || y >= h) continue
            var err = 0.0
            for (k in 0 until queue) err += hist[(head + k) % queue] * weights[k]
            val i = y * w + x
            val v = gray[i] + err
            val black = v < 128
            bits[i] = if (black) 1 else 0
            hist[head] = v - if (black) 0.0 else 255.0
            head = (head + 1) % queue
        }
    }

    // Position of step d on a Hilbert curve through an s × s square (s a power of two)
    private fun hilbert(s: Int, d: Int): Pair<Int, Int> {
        var x = 0; var y = 0; var t = d; var q = 1
        while (q < s) {
            val rx = 1 and (t / 2); val ry = 1 and (t xor rx)
            if (ry == 0) {
                if (rx == 1) { x = q - 1 - x; y = q - 1 - y }
                val tmp = x; x = y; y = tmp
            }
            x += q * rx; y += q * ry
            t /= 4; q *= 2
        }
        return x to y
    }
}
