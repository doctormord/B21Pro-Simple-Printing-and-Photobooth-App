package de.b21print

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import kotlin.math.abs
import kotlin.math.pow

// Reads a photo of a calibration chart (TestChart.calChart), in the app instead of tools/calib.py:
// finds the four corner squares, maps the chart into the photo, and measures how dark each patch
// printed (0 = paper, 1 = the last patch, linear light), relative to the white gutter around it (for
// bands: the white left and right of it), so uneven light across the label cancels out.
object ChartReader {
    class Reading(val dark: FloatArray, val rectified: Bitmap)

    // photo: the whole label in view; w × h, lay: the chart as printed
    fun read(photo: Bitmap, w: Int, h: Int, lay: TestChart.CalLayout): Reading? {
        val pw = photo.width; val ph = photo.height
        val px = IntArray(pw * ph)
        photo.getPixels(px, 0, pw, 0, 0, pw, ph)
        val lin = FloatArray(px.size) { i ->
            val c = px[i]
            0.2126f * LIN[(c shr 16) and 255] + 0.7152f * LIN[(c shr 8) and 255] + 0.0722f * LIN[c and 255]
        }
        val img = Img(lin, pw, ph)
        // Chart corners in cyclic order TL, TR, BR, BL
        val chart = listOf(0, 1, 3, 2).map { val b = lay.corners[it]; (b.x + b.w / 2f) to (b.y + b.h / 2f) }

        var best: Pair<Float, FloatArray>? = null
        val cand = candidates(img)
        for (a in cand.indices) for (b in a + 1 until cand.size) for (c in b + 1 until cand.size) for (d in c + 1 until cand.size) {
            val four = listOf(cand[a], cand[b], cand[c], cand[d])
            if (four.maxOf { it.area } > 1.8f * four.minOf { it.area }) continue
            val quad = cyclic(four) ?: continue
            // The photo may show the label turned any way round
            for (k in 0 until 4) {
                val hm = homography(chart, List(4) { quad[(it + k) % 4] }) ?: continue
                val sc = score(measure(img, hm, lay, 5))
                if (best == null || sc > best.first) best = sc to hm
            }
        }
        val (sc, hm) = best ?: return null
        if (sc < 1.2f) return null
        val res = measure(img, hm, lay, 20)
        // Reflectance relative to the local white, scaled so the last patch is 1
        val r = FloatArray(res.size) { res[it].first / res[it].second }
        val black = r.last()
        if (black >= 0.9f) return null
        // A few percent is the paper's own unevenness: a level that leaves no mark counts as paper
        var run = 0f
        val dark = FloatArray(r.size) { i ->
            run = maxOf(run, ((1f - r[i]) / (1f - black)).coerceIn(0f, 1f).let { if (it < 0.04f) 0f else it })
            if (i == 0) 0f else if (i == r.size - 1) 1f else run
        }
        return Reading(dark, rectify(img, hm, w, h, lay))
    }

    private class Img(val v: FloatArray, val w: Int, val h: Int) {
        fun at(x: Float, y: Float): Float {
            val xi = x.toInt().coerceIn(0, w - 2); val yi = y.toInt().coerceIn(0, h - 2)
            val fx = (x - xi).coerceIn(0f, 1f); val fy = (y - yi).coerceIn(0f, 1f)
            val i = yi * w + xi
            return (v[i] * (1 - fx) + v[i + 1] * fx) * (1 - fy) + (v[i + w] * (1 - fx) + v[i + w + 1] * fx) * fy
        }
    }

    private class Blob(val area: Float, val x: Float, val y: Float)

    // Solid dark, roughly square blobs, found on a copy of about 800 px
    private fun candidates(img: Img): List<Blob> {
        val s = maxOf(1, maxOf(img.w, img.h) / 800)
        val w = img.w / s; val h = img.h / s
        val g = IntArray(w * h) { i ->
            val x0 = (i % w) * s; val y0 = (i / w) * s
            var sum = 0f
            for (y in y0 until y0 + s) for (x in x0 until x0 + s) sum += img.v[y * img.w + x]
            (255 * srgb(sum / (s * s))).toInt().coerceIn(0, 255)
        }
        val dark = BooleanArray(g.size)
        val t = otsu(g)
        for (i in g.indices) dark[i] = g[i] < t
        val seen = BooleanArray(g.size)
        val stack = IntArray(g.size)
        val out = ArrayList<Blob>()
        for (start in g.indices) {
            if (!dark[start] || seen[start]) continue
            var sp = 0; stack[sp++] = start; seen[start] = true
            var n = 0; var sx = 0L; var sy = 0L
            var x0 = w; var x1 = 0; var y0 = h; var y1 = 0
            while (sp > 0) {
                val i = stack[--sp]
                val x = i % w; val y = i / w
                n++; sx += x; sy += y
                if (x < x0) x0 = x; if (x > x1) x1 = x; if (y < y0) y0 = y; if (y > y1) y1 = y
                for (j in intArrayOf(i - 1, i + 1, i - w, i + w)) {
                    if (j < 0 || j >= g.size || seen[j] || !dark[j]) continue
                    if ((j == i - 1 && x == 0) || (j == i + 1 && x == w - 1)) continue
                    seen[j] = true; stack[sp++] = j
                }
            }
            val bw = x1 - x0 + 1; val bh = y1 - y0 + 1
            if (n < 12 || n > g.size * 0.02f || bw.toFloat() / bh !in 0.6f..1.6f) continue
            if (n < 0.8f * bw * bh) continue
            out += Blob(n.toFloat() * s * s, (sx.toFloat() / n + 0.5f) * s, (sy.toFloat() / n + 0.5f) * s)
        }
        // Dark patches and text can qualify too; the score sorts them out, but cap the search
        return out.sortedByDescending { it.area }.take(24)
    }

    private fun otsu(g: IntArray): Int {
        val hist = IntArray(256)
        for (v in g) hist[v]++
        val total = g.size.toDouble()
        var sum = 0.0
        for (i in 0..255) sum += i * hist[i].toDouble()
        var sumB = 0.0; var wB = 0.0; var bestT = 128; var bestV = -1.0
        for (t in 0..255) {
            wB += hist[t]
            if (wB == 0.0) continue
            val wF = total - wB
            if (wF == 0.0) break
            sumB += t * hist[t].toDouble()
            val mB = sumB / wB; val mF = (sum - sumB) / wF
            val v = wB * wF * (mB - mF) * (mB - mF)
            if (v > bestV) { bestV = v; bestT = t }
        }
        return bestT + 1
    }

    // Four points in cyclic order TL, TR, BR, BL, or null if they do not form a convex quadrilateral
    private fun cyclic(b: List<Blob>): List<Pair<Float, Float>>? {
        val cx = b.sumOf { it.x.toDouble() } / 4; val cy = b.sumOf { it.y.toDouble() } / 4
        val sorted = b.sortedBy { kotlin.math.atan2(it.y - cy, it.x - cx) }
        val tl = sorted.indices.minBy { sorted[it].x + sorted[it].y }
        val q = List(4) { sorted[(tl + it) % 4].let { p -> p.x to p.y } }
        var sign = 0
        for (i in 0 until 4) {
            val (ax, ay) = q[i]; val (bx, by) = q[(i + 1) % 4]; val (cx2, cy2) = q[(i + 2) % 4]
            val cr = (bx - ax) * (cy2 - by) - (by - ay) * (cx2 - bx)
            val s = if (cr > 0) 1 else -1
            if (sign != 0 && s != sign) return null
            sign = s
        }
        val side = { i: Int -> val (ax, ay) = q[i]; val (bx, by) = q[(i + 1) % 4]; kotlin.math.hypot(bx - ax, by - ay) }
        if ((0 until 4).any { side(it) < 40f }) return null
        return q
    }

    // Projective map from chart dots to photo pixels through four point pairs (h33 = 1)
    private fun homography(src: List<Pair<Float, Float>>, dst: List<Pair<Float, Float>>): FloatArray? {
        val a = Array(8) { DoubleArray(9) }
        for (i in 0 until 4) {
            val (x, y) = src[i]; val (u, v) = dst[i]
            a[2 * i] = doubleArrayOf(x.toDouble(), y.toDouble(), 1.0, 0.0, 0.0, 0.0, -u.toDouble() * x, -u.toDouble() * y, u.toDouble())
            a[2 * i + 1] = doubleArrayOf(0.0, 0.0, 0.0, x.toDouble(), y.toDouble(), 1.0, -v.toDouble() * x, -v.toDouble() * y, v.toDouble())
        }
        for (c in 0 until 8) {
            val p = (c until 8).maxBy { abs(a[it][c]) }
            if (abs(a[p][c]) < 1e-9) return null
            val t = a[p]; a[p] = a[c]; a[c] = t
            for (r in 0 until 8) if (r != c) {
                val f = a[r][c] / a[c][c]
                for (k in c..8) a[r][k] -= f * a[c][k]
            }
        }
        return FloatArray(9) { if (it == 8) 1f else (a[it][8] / a[it][it]).toFloat() }
    }

    private fun Img.chart(hm: FloatArray, x: Float, y: Float): Float {
        val z = hm[6] * x + hm[7] * y + 1f
        return at((hm[0] * x + hm[1] * y + hm[2]) / z, (hm[3] * x + hm[4] * y + hm[5]) / z)
    }

    // The part of a patch that is measured, as fractions of its width and height: away from the edges,
    // where the heat spreads into the white
    private fun inner(lay: TestChart.CalLayout) = if (lay.bands) floatArrayOf(0.05f, 0.95f, 0.3f, 0.7f) else floatArrayOf(0.2f, 0.8f, 0.2f, 0.8f)

    // Per patch: mean of its inner part and median of the gutter centre lines around it
    private fun measure(img: Img, hm: FloatArray, lay: TestChart.CalLayout, k: Int): List<Pair<Float, Float>> =
        if (lay.bands) measureBands(img, hm, lay, k) else lay.patches.map { b ->
            var sum = 0f
            for (j in 0 until k) for (i in 0 until k) {
                sum += img.chart(hm, b.x + b.w * (0.2f + 0.6f * (i + 0.5f) / k), b.y + b.h * (0.2f + 0.6f * (j + 0.5f) / k))
            }
            val g = lay.gut / 2f
            val ring = ArrayList<Float>()
            for (i in 0 until k) {
                val fx = b.x + b.w * (i + 0.5f) / k; val fy = b.y + b.h * (i + 0.5f) / k
                ring += img.chart(hm, fx, b.y - g); ring += img.chart(hm, fx, b.y + b.h + g)
                ring += img.chart(hm, b.x - g, fy); ring += img.chart(hm, b.x + b.w + g, fy)
            }
            ring.sort()
            sum / (k * k) to ring[ring.size / 2]
        }

    // Per band: mean of its inner part; white: the mean of the medians left and right of it, so a light
    // falling off across the label is taken at the band's middle
    private fun measureBands(img: Img, hm: FloatArray, lay: TestChart.CalLayout, k: Int): List<Pair<Float, Float>> {
        val (x0, x1, y0, y1) = inner(lay).toList()
        val rows = maxOf(2, k / 4)
        return lay.patches.map { b ->
            var sum = 0f
            for (j in 0 until rows) for (i in 0 until 4 * k) {
                sum += img.chart(hm, b.x + b.w * (x0 + (x1 - x0) * (i + 0.5f) / (4 * k)), b.y + b.h * (y0 + (y1 - y0) * (j + 0.5f) / rows))
            }
            val strip = (b.x - lay.gut - lay.margin).toFloat()
            fun white(xa: Float): Float {
                val v = ArrayList<Float>()
                for (j in 0 until rows) for (i in 0 until k) v += img.chart(hm, xa + strip * (0.2f + 0.6f * (i + 0.5f) / k), b.y + b.h * (y0 + (y1 - y0) * (j + 0.5f) / rows))
                v.sort()
                return v[v.size / 2]
            }
            sum / (rows * 4 * k) to (white(lay.margin.toFloat()) + white((b.x + b.w + lay.gut).toFloat())) / 2
        }
    }

    // Patches must get darker along the index and the gutters must be lighter than the patches
    private fun score(res: List<Pair<Float, Float>>): Float {
        val p = res.map { it.first }
        val n = p.size
        val rank = IntArray(n).also { r -> p.indices.sortedByDescending { p[it] }.forEachIndexed { k, i -> r[i] = k } }
        var d2 = 0.0
        for (i in 0 until n) d2 += (rank[i] - i).toDouble().pow(2)
        val spearman = 1 - 6 * d2 / (n * (n * n - 1.0))
        val white = res.sumOf { it.second.toDouble() } / n
        return (spearman + (white - p.last()) / maxOf(white, 1e-6)).toFloat()
    }

    // The chart as found in the photo, with the sampled areas outlined, so a wrong match is visible
    private fun rectify(img: Img, hm: FloatArray, w: Int, h: Int, lay: TestChart.CalLayout): Bitmap {
        val s = 2
        val bw = w / s; val bh = h / s
        val out = IntArray(bw * bh) { i ->
            val g = (255 * srgb(img.chart(hm, (i % bw + 0.5f) * s, (i / bw + 0.5f) * s))).toInt().coerceIn(0, 255)
            Color.rgb(g, g, g)
        }
        val bmp = Bitmap.createBitmap(out, bw, bh, Bitmap.Config.ARGB_8888).copy(Bitmap.Config.ARGB_8888, true)
        val paint = Paint().apply { style = Paint.Style.STROKE; color = Color.rgb(230, 40, 40); strokeWidth = 1.5f }
        val c = Canvas(bmp)
        val (x0, x1, y0, y1) = inner(lay).toList()
        for (b in lay.patches) {
            c.drawRect((b.x + x0 * b.w) / s, (b.y + y0 * b.h) / s, (b.x + x1 * b.w) / s, (b.y + y1 * b.h) / s, paint)
        }
        return bmp
    }

    private val LIN = FloatArray(256) { v ->
        val c = v / 255f
        if (c <= 0.04045f) c / 12.92f else ((c + 0.055f) / 1.055f).toDouble().pow(2.4).toFloat()
    }

    private fun srgb(r: Float) = if (r <= 0.0031308f) r * 12.92f else 1.055f * r.toDouble().pow(1 / 2.4).toFloat() - 0.055f

    // Measured darkness per step against the even line
    fun plot(dark: FloatArray, width: Int, height: Int): Bitmap {
        val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(Color.WHITE)
        val pad = height * 0.08f
        fun x(i: Int) = pad + (width - 2 * pad) * i / (dark.size - 1)
        fun y(d: Float) = height - pad - (height - 2 * pad) * d
        val grid = Paint().apply { color = Color.rgb(200, 200, 200); strokeWidth = 2f }
        c.drawLine(x(0), y(0f), x(dark.size - 1), y(1f), grid)
        c.drawLine(x(0), y(0f), x(dark.size - 1), y(0f), grid)
        c.drawLine(x(0), y(0f), x(0), y(1f), grid)
        val line = Paint().apply { color = Color.BLACK; strokeWidth = 4f; isAntiAlias = true }
        val dot = Paint().apply { color = Color.BLACK; isAntiAlias = true }
        for (i in dark.indices) {
            if (i > 0) c.drawLine(x(i - 1), y(dark[i - 1]), x(i), y(dark[i]), line)
            c.drawCircle(x(i), y(dark[i]), 6f, dot)
        }
        return bmp
    }
}
