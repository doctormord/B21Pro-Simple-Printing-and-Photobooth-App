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
        'M' to "#...#|##.##|#.#.#|#.#.#|#...#|#...#|#...#",
        'G' to ".###.|#...#|#....|#.###|#...#|#...#|.###.",
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
        'O' to ".###.|#...#|#...#|#...#|#...#|#...#|.###.",
        'S' to ".####|#....|#....|.###.|....#|....#|####.",
        '.' to ".....|.....|.....|.....|.....|.##..|.##..",
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

    class Box(val x: Int, val y: Int, val w: Int, val h: Int)

    // Layout of the calibration charts: four solid corner squares for locating the chart in a photo,
    // n × n patches (row by row) with white gutters around each as a local paper-white reference.
    // bands: the patches are full-width bands, and the paper white is read left and right of each.
    class CalLayout(val corners: List<Box>, val patches: List<Box>, val gut: Int, val margin: Int, val bands: Boolean = false)

    fun calLayout(w: Int, h: Int, n: Int): CalLayout {
        val mm = 591 / 50f
        val m = (2.5f * mm).toInt()          // edge margin
        val f = (3f * mm).toInt()            // corner square
        val corners = listOf(m to m, w - m - f to m, m to h - m - f, w - m - f to h - m - f).map { (x, y) -> Box(x, y, f, f) }
        val gut = (1f * mm).toInt()
        val left = m + f + gut; val right = w - m - f - gut
        val top = m + f + gut; val bottom = h - m - f - gut
        val cw = (right - left - (n - 1) * gut) / n
        val ch = (bottom - top - (n - 1) * gut) / n
        val patches = List(n * n) { i -> Box(left + (i % n) * (cw + gut), top + (i / n) * (ch + gut), cw, ch) }
        return CalLayout(corners, patches, gut, m)
    }

    // Gray chart: one band per level across the whole width. A patch grid misreads the levels: the
    // right side of the head prints darker, so the last patch of each row came out a step too dark.
    // A band averages over the head, and every level is measured with the same load (all planes up to
    // its level fully used).
    fun grayLayout(w: Int, h: Int): CalLayout {
        val grid = calLayout(w, h, 4)
        val top = grid.margin + grid.corners[0].h + grid.gut
        val left = grid.patches[0].x
        val gap = 3
        val bh = (h - 2 * top - 15 * gap) / 16
        val bands = List(16) { i -> Box(left, top + i * (bh + gap), w - 2 * left, bh) }
        return CalLayout(grid.corners, bands, grid.gut, grid.margin, bands = true)
    }

    fun calChart(w: Int, h: Int, gray: Boolean) = if (gray) grayLayout(w, h) else calLayout(w, h, 5)

    // Calibration chart at print resolution (1 px = 1 dot), already dithered, so it reaches the
    // printer unchanged when printed with the hard-threshold raster: 5 × 5 patches of the tones
    // 255 · (1 − i / 24) and the print settings as text. look: whose dither fills the patches (the
    // photo look, Amiga fine, blue noise, Riemersma), so the curve fits that look.
    fun calibration(w: Int, h: Int, caption: String, look: ImageProcessor.Raster = ImageProcessor.Raster.PHOTO): Bitmap {
        val px = IntArray(w * h) { -1 }
        val lay = calLayout(w, h, 5)
        for (b in lay.corners) for (y in b.y until b.y + b.h) for (x in b.x until b.x + b.w) px[y * w + x] = BLACK
        for ((i, b) in lay.patches.withIndex()) {
            val bits = ImageProcessor.ditherFlat(255f * (1f - i / (CAL_STEPS - 1f)), b.w, b.h, look, b.x, b.y)
            for (y in 0 until b.h) for (x in 0 until b.w) if (bits[y * b.w + x].toInt() != 0) px[(b.y + y) * w + b.x + x] = BLACK
        }
        val bits = ByteArray(w * h)
        calCaption(bits, w, h, lay, caption, 1)
        for (i in bits.indices) if (bits[i].toInt() != 0) px[i] = BLACK
        return Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888)
    }

    // Gray-mode calibration chart, levels 0 = white .. 15 = black: a band per level (grayLayout)
    fun grayCalibration(w: Int, h: Int, caption: String): ByteArray {
        val px = ByteArray(w * h)
        val lay = grayLayout(w, h)
        for (b in lay.corners) for (y in b.y until b.y + b.h) for (x in b.x until b.x + b.w) px[y * w + x] = 15
        for ((i, b) in lay.patches.withIndex()) for (y in b.y until b.y + b.h) px.fill(i.toByte(), y * w + b.x, y * w + b.x + b.w)
        calCaption(px, w, h, lay, caption, 15)
        return px
    }

    // Caption between the bottom corner squares
    private fun calCaption(px: ByteArray, w: Int, h: Int, lay: CalLayout, caption: String, ink: Int) {
        val f = lay.corners[0].w
        val s = maxOf(1, (f / 9))
        var x = lay.margin + f + lay.gut
        val y = h - lay.margin - f + (f - 7 * s) / 2
        for (c in caption.uppercase()) {
            FONT[c]?.let { g ->
                for ((ry, line) in g.withIndex()) for ((rx, ch) in line.withIndex()) if (ch == '#') {
                    for (yy in y + ry * s until y + ry * s + s) for (xx in x + rx * s until x + rx * s + s) {
                        if (xx in 0 until w && yy in 0 until h) px[yy * w + xx] = ink.toByte()
                    }
                }
            }
            x += 6 * s
        }
    }

    private const val BLACK = 0xff000000.toInt()

    // Test for the 16-level gray mode, full head width, levels 0 = white .. 15 = black. Three bands:
    // 16 flat steps (white left), a smooth ramp error-diffused to 16 levels, and the same ramp
    // diffused to black and white only, which is what the 1-bit mode can do.
    fun grayWedge(h: Int, caption: String): ByteArray {
        val w = Niimbot.COLOR_HEAD
        val px = ByteArray(w * h)
        val m = 24
        stamp(px, w, h, m, m, caption, w, 15)
        val top = m + 40; val gap = 16
        val bh = (h - top - m - 2 * gap) / 3
        val x0 = m; val x1 = w - m
        for (y in top until top + bh) for (x in x0 until x1) px[y * w + x] = (16 * (x - x0) / (x1 - x0)).toByte()
        for ((band, levels) in listOf(1 to 16, 2 to 2)) {
            val y0 = top + band * (bh + gap)
            val ramp = FloatArray((x1 - x0) * bh) { 15f * (it % (x1 - x0)) / (x1 - x0 - 1) }
            val q = diffuse(ramp, x1 - x0, bh, levels)
            for (y in 0 until bh) System.arraycopy(q, y * (x1 - x0), px, (y0 + y) * w + x0, x1 - x0)
        }
        return px
    }

    // Load test for the gray mode: many dots heating at once pull the supply down, and the printer
    // makes up for it with a longer pulse per level plane; when that is too little, a tone prints
    // lighter where much of the row shares its levels. For a light, a mid and a dark level three rows
    // each: a patch on the left alone, with the same level running on over half the width, and over
    // the whole width. With a right compensation the three patches of a level match.
    val LOAD_LEVELS = intArrayOf(4, 7, 10)

    fun grayLoad(h: Int, caption: String): ByteArray {
        val w = Niimbot.COLOR_HEAD
        val px = ByteArray(w * h)
        val m = 24
        // The head is wider than a 40 mm label, which sits in its middle: start the patches on the label
        val lm = 64
        stamp(px, w, h, lm, m, caption, w, 15)
        val top = m + 40; val gap = 8; val group = 24
        val rows = 3 * LOAD_LEVELS.size
        val rh = (h - top - m - (rows - LOAD_LEVELS.size) * gap - (LOAD_LEVELS.size - 1) * group) / rows
        val patch = (w - 2 * m) / 4
        var y = top
        for ((g, level) in LOAD_LEVELS.withIndex()) {
            if (g > 0) y += group - gap
            for (run in 0..2) {
                val end = when (run) { 0 -> lm + patch; 1 -> w / 2 + patch / 2; else -> w - m }
                for (yy in y until y + rh) {
                    for (x in lm until lm + patch) px[yy * w + x] = level.toByte()
                    for (x in lm + patch + 12 until end) px[yy * w + x] = level.toByte()
                }
                y += rh + gap
            }
        }
        return px
    }

    // Test for red/black rolls, full head width, returns (black, red) planes:
    // black ramp, red ramp, the two side by side and in 8-dot stripes in the same rows,
    // and a 1-dot red/black checkerboard to show how well the two colours line up.
    fun twoColor(h: Int, caption: String): Pair<ByteArray, ByteArray> {
        val w = Niimbot.COLOR_HEAD
        val black = ByteArray(w * h); val red = ByteArray(w * h)
        val m = 24
        stamp(black, w, h, m, m, caption, w)
        val top = m + 40; val gap = 16
        val bh = (h - top - m - 4 * gap) / 5
        val x0 = m; val x1 = w - m; val rw = x1 - x0
        fun ramp(plane: ByteArray, y0: Int, xa: Int, xb: Int) {
            val t = FloatArray((xb - xa) * bh) { 15f * (it % (xb - xa)) / (xb - xa - 1) }
            val q = diffuse(t, xb - xa, bh, 2)
            for (y in 0 until bh) for (x in 0 until xb - xa) if (q[y * (xb - xa) + x].toInt() != 0) plane[(y0 + y) * w + xa + x] = 1
        }
        fun fill(plane: ByteArray, y0: Int, xa: Int, xb: Int, on: (Int, Int) -> Boolean) {
            for (y in y0 until y0 + bh) for (x in xa until xb) if (on(x, y)) plane[y * w + x] = 1
        }
        var y = top
        ramp(black, y, x0, x1); y += bh + gap
        ramp(red, y, x0, x1); y += bh + gap
        ramp(black, y, x0, x0 + rw / 2); ramp(red, y, x0 + rw / 2, x1); y += bh + gap
        fill(black, y, x0, x1) { x, _ -> (x / 8) % 2 == 0 }; fill(red, y, x0, x1) { x, _ -> (x / 8) % 2 == 1 }; y += bh + gap
        fill(black, y, x0, x1) { x, yy -> (x + yy) % 2 == 0 }; fill(red, y, x0, x1) { x, yy -> (x + yy) % 2 == 1 }
        return black to red
    }

    // Floyd-Steinberg from 0..15 to the given number of evenly spaced levels, result in 0..15
    private fun diffuse(v: FloatArray, w: Int, h: Int, levels: Int): ByteArray {
        val out = ByteArray(w * h)
        val step = 15f / (levels - 1)
        for (y in 0 until h) for (x in 0 until w) {
            val i = y * w + x
            val q = (Math.round(v[i] / step) * step).coerceIn(0f, 15f)
            out[i] = Math.round(q).toByte()
            val e = v[i] - q
            if (x + 1 < w) v[i + 1] += e * 7 / 16
            if (y + 1 < h) {
                if (x > 0) v[i + w - 1] += e * 3 / 16
                v[i + w] += e * 5 / 16
                if (x + 1 < w) v[i + w + 1] += e / 16
            }
        }
        return out
    }

    // Small label in print bits: black text on a white box, scale chosen from the label width
    fun stamp(bits: ByteArray, w: Int, h: Int, x0: Int, y0: Int, text: String, labelW: Int, ink: Int = 1) {
        val s = maxOf(2, labelW / 160)
        val tw = text.length * 6 * s + 2 * s; val th = 9 * s
        for (y in y0 until minOf(h, y0 + th)) for (x in x0 until minOf(w, x0 + tw)) if (y >= 0 && x >= 0) bits[y * w + x] = 0
        var x = x0 + s
        for (c in text) {
            FONT[c]?.let { g ->
                for ((ry, line) in g.withIndex()) for ((rx, ch) in line.withIndex()) if (ch == '#') {
                    for (yy in 0 until s) for (xx in 0 until s) {
                        val px = x + rx * s + xx; val py = y0 + s + ry * s + yy
                        if (px in 0 until w && py in 0 until h) bits[py * w + px] = ink.toByte()
                    }
                }
            }
            x += 6 * s
        }
    }

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
