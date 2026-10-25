package de.b21print

import android.graphics.Bitmap
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

// Thermal test chart, a port of tools/mkchart.py: tone wedge, gradient, dither patches, line and
// checkerboard resolution, Siemens star, rings and microtext. Rendered at 2x the print resolution
// of the photo area so the normal downscale/dither path is exercised too.
object TestChart {
    const val SCALE = 2

    private val FONT = mapOf(
        '0' to ".###.|#...#|#..##|#.#.#|##..#|#...#|.###.",
        '1' to "..#..|.##..|..#..|..#..|..#..|..#..|.###.",
        '2' to ".###.|#...#|....#|...#.|..#..|.#...|#####",
        '3' to "#####|...#.|..#..|...#.|....#|#...#|.###.",
        '4' to "...#.|..##.|.#.#.|#..#.|#####|...#.|...#.",
        '5' to "#####|#....|####.|....#|....#|#...#|.###.",
        '6' to "..##.|.#...|#....|####.|#...#|#...#|.###.",
        '7' to "#####|....#|...#.|..#..|.#...|.#...|.#...",
        '8' to ".###.|#...#|#...#|.###.|#...#|#...#|.###.",
        '9' to ".###.|#...#|#...#|.####|....#|...#.|.##..",
        'B' to "####.|#...#|#...#|####.|#...#|#...#|####.",
        'P' to "####.|#...#|#...#|####.|#....|#....|#....",
        'R' to "####.|#...#|#...#|####.|#.#..|#..#.|#...#",
        'I' to ".###.|..#..|..#..|..#..|..#..|..#..|.###.",
        'N' to "#...#|##..#|#.#.#|#.#.#|#..##|#...#|#...#",
        'T' to "#####|..#..|..#..|..#..|..#..|..#..|..#..",
        'A' to ".###.|#...#|#...#|#####|#...#|#...#|#...#",
        'C' to ".###.|#...#|#....|#....|#....|#...#|.###.",
        'D' to "####.|#...#|#...#|#...#|#...#|#...#|####.",
        'E' to "#####|#....|#....|####.|#....|#....|#####",
        'F' to "#####|#....|#....|####.|#....|#....|#....",
        'L' to "#....|#....|#....|#....|#....|#....|#####",
        'S' to ".####|#....|#....|.###.|....#|....#|####.",
    ).mapValues { it.value.split('|') }

    // Chart for a photo area of w × h print pixels. Round labels get the chart inside the
    // inscribed square, so nothing important is clipped by the circle.
    fun create(w: Int, h: Int, round: Boolean): Bitmap {
        val bw = w * SCALE
        val bh = h * SCALE
        val gray: IntArray
        if (round) {
            gray = IntArray(bw * bh) { 255 }
            val d = min(bw, bh)
            val s = (d / sqrt(2.0) * 0.97).toInt()
            val inner = draw(s, s)
            val ox = (bw - s) / 2; val oy = (bh - s) / 2
            for (y in 0 until s) System.arraycopy(inner, y * s, gray, (oy + y) * bw + ox, s)
        } else {
            gray = draw(bw, bh)
        }
        val px = IntArray(gray.size) { val v = gray[it]; (0xff shl 24) or (v shl 16) or (v shl 8) or v }
        return Bitmap.createBitmap(px, bw, bh, Bitmap.Config.ARGB_8888)
    }

    const val CAL_STEPS = 25

    // Calibration chart at print resolution (1 px = 1 dot), already dithered, so it reaches the
    // printer unchanged when printed with the hard-threshold raster. 5 × 5 patches of the tones
    // 255 · (1 − i / 24), row by row, with white gutters as a local paper-white reference, solid
    // corner squares for locating the chart in a photo, and the print settings as text.
    fun calibration(w: Int, h: Int, caption: String): Bitmap {
        val px = IntArray(w * h) { -1 }
        fun fill(x0: Int, y0: Int, x1: Int, y1: Int) {
            for (y in maxOf(0, y0) until minOf(h, y1)) for (x in maxOf(0, x0) until minOf(w, x1)) px[y * w + x] = BLACK
        }
        val mm = 591 / 50f
        val m = (2.5f * mm).toInt()          // edge margin
        val f = (3f * mm).toInt()            // corner square
        for ((cx, cy) in listOf(m to m, w - m - f to m, m to h - m - f, w - m - f to h - m - f)) fill(cx, cy, cx + f, cy + f)
        val gut = (1f * mm).toInt()
        val left = m + f + gut; val right = w - m - f - gut
        val top = m + f + gut; val bottom = h - m - f - gut
        val cw = (right - left - 4 * gut) / 5
        val ch = (bottom - top - 4 * gut) / 5
        for (i in 0 until CAL_STEPS) {
            val x0 = left + (i % 5) * (cw + gut)
            val y0 = top + (i / 5) * (ch + gut)
            val bits = ImageProcessor.ditherFlat(255f * (1f - i / (CAL_STEPS - 1f)), cw, ch)
            for (y in 0 until ch) for (x in 0 until cw) if (bits[y * cw + x].toInt() != 0) px[(y0 + y) * w + x0 + x] = BLACK
        }
        // Caption between the bottom corner squares
        val s = maxOf(1, (f / 9))
        var x = m + f + gut
        val y = h - m - f + (f - 7 * s) / 2
        for (c in caption.uppercase()) {
            FONT[c]?.let { g ->
                for ((ry, line) in g.withIndex()) for ((rx, ch2) in line.withIndex()) {
                    if (ch2 == '#') fill(x + rx * s, y + ry * s, x + rx * s + s, y + ry * s + s)
                }
            }
            x += 6 * s
        }
        return Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888)
    }

    private const val BLACK = 0xff000000.toInt()

    private fun draw(W: Int, H: Int): IntArray {
        val buf = IntArray(W * H) { 255 }

        fun put(x: Int, y: Int, v: Int) {
            if (x in 0 until W && y in 0 until H) buf[y * W + x] = v
        }

        fun rect(x0: Double, y0: Double, x1: Double, y1: Double, v: Int) {
            val xa = max(0, x0.toInt()); val ya = max(0, y0.toInt())
            val xb = min(W, x1.toInt()); val yb = min(H, y1.toInt())
            for (y in ya until yb) buf.fill(v, y * W + xa, max(y * W + xa, y * W + xb))
        }

        fun rect(x0: Int, y0: Int, x1: Int, y1: Int, v: Int) =
            rect(x0.toDouble(), y0.toDouble(), x1.toDouble(), y1.toDouble(), v)

        fun glyph(ch: Char, x: Int, y: Int, s: Int) {
            val g = FONT[ch] ?: return
            for ((ry, line) in g.withIndex()) for ((rx, c) in line.withIndex()) {
                if (c == '#') rect(x + rx * s, y + ry * s, x + rx * s + s, y + ry * s + s, 0)
            }
        }

        fun text(st: String, x0: Int, y: Int, s: Int) {
            var x = x0
            for (ch in st) { glyph(ch, x, y, s); x += 6 * s }
        }

        val m = max(5, (min(W, H) * 0.02).roundToInt())
        // Frame
        rect(m, m, W - m, m + 2, 0); rect(m, H - m - 2, W - m, H - m, 0)
        rect(m, m, m + 2, H - m, 0); rect(W - m - 2, m, W - m, H - m, 0)
        // Corner marks
        val ln = (min(W, H) * 0.05).roundToInt()
        for ((cx, cy, sx, sy) in listOf(
            intArrayOf(m, m, 1, 1), intArrayOf(W - m, m, -1, 1),
            intArrayOf(m, H - m, 1, -1), intArrayOf(W - m, H - m, -1, -1),
        )) {
            for (i in 0 until ln) {
                put(cx + i * sx, cy + if (sy > 0) 0 else -1, 0)
                put(cx + if (sx > 0) 0 else -1, cy + i * sy, 0)
            }
        }

        val gx = (W * 0.025).roundToInt()
        val left = m + gx
        val right = W - m - gx
        val wc = right - left
        val top = m + (H * 0.015).roundToInt()
        val hc = (H - m - (H * 0.015).roundToInt()) - top
        val fs = max(2, (min(W, H) / 300.0).roundToInt())  // label font scale
        val labH = 7 * fs + 4

        fun band(f0: Double, f1: Double) = Pair(top + (hc * f0).roundToInt(), top + (hc * f1).roundToInt())
        fun label(n: Int, x: Int, y: Int) = glyph('0' + n, x, y, fs)

        // Zone 1: tonal step wedge 0..10
        run {
            val (y0, y1) = band(0.00, 0.145)
            label(1, left, y0)
            val by0 = y0 + labH
            val by1 = y1 - labH
            val steps = 11
            val bw = wc.toDouble() / steps
            for (i in 0 until steps) {
                val v = (255 * (1 - i / (steps - 1.0))).roundToInt()
                rect(left + i * bw, by0.toDouble(), left + (i + 1) * bw, by1.toDouble(), v)
                val t = i.toString()
                text(t, (left + i * bw + bw / 2 - 3 * fs * t.length).toInt(), by1 + 2, fs)
            }
        }

        // Zone 2: smooth gradient
        run {
            val (y0, y1) = band(0.17, 0.25)
            label(2, left, y0)
            val gy0 = y0 + labH
            for (x in 0 until wc) {
                val v = 255 - (255.0 * x / (wc - 1)).roundToInt()
                rect(left + x, gy0, left + x + 1, y1, v)
            }
        }

        // Zone 3: flat dither patches 10/25/50/75/90 % ink
        run {
            val (y0, y1) = band(0.27, 0.42)
            label(3, left, y0)
            val vals = intArrayOf(230, 191, 128, 64, 25)
            val pw = wc.toDouble() / vals.size
            for ((i, v) in vals.withIndex()) {
                rect(left + i * pw + 6, (y0 + labH).toDouble(), left + (i + 1) * pw - 6, y1.toDouble(), v)
            }
        }

        // Zone 4: line resolution horizontal / vertical / diagonal, pitches 2..10 px
        run {
            val (y0, y1) = band(0.44, 0.60)
            label(4, left, y0)
            val ly0 = y0 + labH
            val third = wc / 3
            val pitches = intArrayOf(2, 4, 6, 8, 10)
            val rows = max(1, (y1 - ly0) / pitches.size)
            for ((pi, p) in pitches.withIndex()) {
                val yy0 = ly0 + pi * rows
                val yy1 = ly0 + (pi + 1) * rows - 2
                for (yy in yy0 until yy1) {
                    if (((yy - yy0) / p) % 2 == 0) rect(left, yy, left + third - 8, yy + 1, 0)
                }
                for (xx in left + third until left + 2 * third - 8) {
                    if (((xx - (left + third)) / p) % 2 == 0) rect(xx, yy0, xx + 1, yy1, 0)
                }
                for (yy in yy0 until yy1) for (xx in left + 2 * third until right) {
                    if (((xx + yy) / p) % 2 == 0) put(xx, yy, 0)
                }
            }
        }

        // Zone 5: checkerboards, cells 1,2,3,4,6,8
        run {
            val (y0, y1) = band(0.62, 0.75)
            label(5, left, y0)
            val cells = intArrayOf(1, 2, 3, 4, 6, 8)
            val cw = wc.toDouble() / cells.size
            for ((ci, cs) in cells.withIndex()) {
                val cx0 = (left + ci * cw + 6).toInt()
                val cx1 = (left + (ci + 1) * cw - 6).toInt()
                for (yy in y0 + labH until y1) for (xx in cx0 until cx1) {
                    if (((xx - cx0) / cs + (yy - (y0 + labH)) / cs) % 2 == 0) put(xx, yy, 0)
                }
            }
        }

        // Zone 6: Siemens star + concentric rings
        run {
            val (y0, y1) = band(0.77, 0.93)
            label(6, left, y0)
            val sy0 = y0 + labH
            val sr = min((y1 - sy0) / 2, wc / 6)
            val cy = (sy0 + y1) / 2
            val scx = left + sr + 10
            val spokes = 36
            for (yy in cy - sr until cy + sr) for (xx in scx - sr until scx + sr) {
                val dx = xx - scx; val dy = yy - cy
                if (dx * dx + dy * dy <= sr * sr &&
                    Math.floorMod(Math.floor(atan2(dy.toDouble(), dx.toDouble()) / PI * spokes).toInt(), 2) == 0
                ) put(xx, yy, 0)
            }
            val rcx = right - sr - 10
            for (yy in cy - sr until cy + sr) for (xx in rcx - sr until rcx + sr) {
                val d = hypot((xx - rcx).toDouble(), (yy - cy).toDouble())
                if (d <= sr && (d / 4).toInt() % 2 == 0) put(xx, yy, 0)
            }
            for (ang in 0 until 360 step 15) {
                val a = Math.toRadians(ang.toDouble())
                for (r in 0 until sr) put((rcx + r * cos(a)).toInt(), (cy + r * sin(a)).toInt(), 0)
            }
        }

        // Microtext legibility, 3 sizes
        run {
            val (y0, y1) = band(0.94, 1.0)
            val s1 = max(1, fs - 1)
            val msg = "B21 PRINT 0123456789"
            var yy = y0
            for (s in s1..s1 + 2) {
                if (yy + 7 * s <= y1 && left + msg.length * 6 * s <= right) {
                    text(msg, left, yy, s)
                    yy += 7 * s + 3
                }
            }
        }
        return buf
    }
}
