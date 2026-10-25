package de.b21print

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface

object ImageProcessor {
    enum class Fit { CONTAIN, COVER, STRETCH }

    // PHOTO: Atkinson dithering, DOTS: halftone screen, TEXT: hard threshold
    // Order = order in the raster picker (R.array.raster)
    enum class Raster { PHOTO, DOTS, LINES, SCANLINES, SCANLINES_WIDE, MAC, AMIGA, FAX, TEXT }

    class Options(
        val fit: Fit,
        val rotation: Int,
        val raster: Raster,
        val brightness: Int,   // -60..60
        val contrast: Int,     // -50..50
        val invert: Boolean,
        val instant: Boolean,
        val caption: String,
        val offsetXMm: Double = 0.0,
        val offsetYMm: Double = 0.0,
        // The photo fades to white towards the edge of its area (circle on round labels)
        val softEdge: Boolean = false,
        // Round labels: print further past the rim, for rolls that sit a bit off. Not for windows:
        // the pre-printed frame is thermal paper too, overscan would grey it.
        val overscan: Boolean = false,
    )

    // bits/width/height: what is printed (at most the print head width)
    // preview: the whole label as it looks after printing
    class Result(val bits: ByteArray, val width: Int, val height: Int, val preview: Bitmap)

    // Bleed into the pre-printed frame / past the circle so no white gap remains
    private const val BLEED_MM = 0.6
    // Overscan: a roll can sit up to about ±2 mm off
    private const val OVERSCAN_MM = 2.0

    fun mmToPx(mm: Double) = Math.round(mm / 25.4 * Niimbot.DPI).toInt()

    // Instant look: equal border top/sides, wider bottom border for the caption
    fun instantPhotoRect(w: Int, h: Int): Rect {
        val side = Math.round(w * 0.07f)
        val minBottom = Math.round(h * 0.2f)
        val photoW = w - 2 * side
        val photoH = minOf(photoW, h - side - minBottom)
        return Rect(side, side, side + photoW, side + photoH)
    }

    // Sign mode: text instead of a photo
    fun renderText(style: TextStyle, labelWmm: Double, labelHmm: Double, window: PhotoWindow?, frameColor: Int?, round: Boolean): Result {
        val labelW = mmToPx(labelWmm)
        val w = minOf(Niimbot.PRINTHEAD_PIXELS, labelW)
        val h = mmToPx(labelHmm)
        val headLeft = (labelW - w) / 2
        val area = if (window != null) {
            RectF(mmToPx(window.x).toFloat() - headLeft, mmToPx(window.y).toFloat(),
                mmToPx(window.x + window.w).toFloat() - headLeft, mmToPx(window.y + window.h).toFloat())
        } else {
            RectF(-headLeft.toFloat(), 0f, (labelW - headLeft).toFloat(), h.toFloat())
        }
        val bits = TextLabel.render(style, w, h, area, round)
        return Result(bits, w, h, composePreview(bits, w, h, labelW, headLeft, window, frameColor))
    }

    fun render(src: Bitmap?, labelWmm: Double, labelHmm: Double, window: PhotoWindow?, frameColor: Int?, o: Options, round: Boolean = false): Result {
        val labelW = mmToPx(labelWmm)
        val w = minOf(Niimbot.PRINTHEAD_PIXELS, labelW)
        val h = mmToPx(labelHmm)
        // Wider labels sit centered under the print head
        val headLeft = (labelW - w) / 2

        val canvasBmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(canvasBmp)
        canvas.drawColor(Color.WHITE)

        val bleed = if (o.overscan && round) OVERSCAN_MM else BLEED_MM
        fun windowRect(b: Double) = Rect(
            mmToPx(window!!.x - b + o.offsetXMm) - headLeft,
            mmToPx(window.y - b + o.offsetYMm),
            mmToPx(window.x + window.w + b + o.offsetXMm) - headLeft,
            mmToPx(window.y + window.h + b + o.offsetYMm),
        ).apply { intersect(0, 0, w, h) }
        val area = when {
            window != null -> windowRect(bleed)
            o.instant -> instantPhotoRect(w, h)
            else -> Rect(0, 0, w, h)
        }
        // The visible edge of the photo: the soft edge fades out here, not at the bleed
        val edge = if (window != null) windowRect(0.0) else area
        val radius = minOf(area.width(), area.height()) / 2f
        if (round) {
            // Round label: print past the rim (like bleed in normal print) so no white gap shows
            // when the label is slightly off; the rest stays white
            canvas.clipPath(Path().apply {
                addCircle(area.exactCenterX(), area.exactCenterY(), radius + mmToPx(bleed), Path.Direction.CW)
            })
        }
        if (src != null) {
            drawFitted(canvas, src, area, o.fit, o.rotation)
        } else if (o.instant || window != null) {
            // Placeholder so the layout is visible
            canvas.drawRect(area, Paint().apply { color = Color.rgb(210, 210, 210) })
        }

        val px = IntArray(w * h)
        canvasBmp.getPixels(px, 0, w, 0, 0, w, h)
        val fade = if (src != null && o.softEdge) softEdge(w, h, area, edge, round) else null
        val bits = toBits(px, w, h, area, o, fade)
        canvasBmp.recycle()

        if (o.instant && window == null) {
            // Thin line around the photo so bright images still stand out
            for (x in area.left until area.right) {
                bits[area.top * w + x] = 1; bits[(area.bottom - 1) * w + x] = 1
            }
            for (y in area.top until area.bottom) {
                bits[y * w + area.left] = 1; bits[y * w + area.right - 1] = 1
            }
            if (o.caption.isNotBlank()) drawCaption(bits, w, h, area, o.caption)
        }

        return Result(bits, w, h, composePreview(bits, w, h, labelW, headLeft, window, frameColor))
    }

    // Soft frame: how much of the photo remains (1) or fades to white (0) over the outer ~12 % of
    // its area. Rectangles fade per side (the product gives soft rounded corners), circles radially;
    // beyond the edge it is white. Applied after the tone curve, so it does not shift auto levels.
    private fun softEdge(w: Int, h: Int, area: Rect, edge: Rect, round: Boolean): FloatArray {
        val alpha = FloatArray(w * h) { 1f }
        val fade = 0.12f * minOf(edge.width(), edge.height())
        val cx = edge.exactCenterX(); val cy = edge.exactCenterY()
        val radius = minOf(edge.width(), edge.height()) / 2f
        fun ramp(d: Float): Float { val t = (d / fade).coerceIn(0f, 1f); return t * t * (3 - 2 * t) }
        for (y in maxOf(0, area.top) until minOf(h, area.bottom)) for (x in maxOf(0, area.left) until minOf(w, area.right)) {
            val fx = x + 0.5f; val fy = y + 0.5f
            val a = if (round) ramp(radius - Math.hypot((fx - cx).toDouble(), (fy - cy).toDouble()).toFloat())
                else ramp(minOf(fx - edge.left, edge.right - fx)) * ramp(minOf(fy - edge.top, edge.bottom - fy))
            alpha[y * w + x] = a
        }
        return alpha
    }

    private fun composePreview(
        bits: ByteArray, w: Int, h: Int, labelW: Int, headLeft: Int, window: PhotoWindow?, frameColor: Int?,
    ): Bitmap {
        val out = IntArray(labelW * h) { Color.WHITE }
        if (window != null && frameColor != null) {
            out.fill(frameColor)
            val l = mmToPx(window.x); val t = mmToPx(window.y)
            val r = mmToPx(window.x + window.w); val b = mmToPx(window.y + window.h)
            for (y in t until minOf(b, h)) for (x in l until minOf(r, labelW)) out[y * labelW + x] = Color.WHITE
        }
        // Simulate the printed dot: each black cell bleeds a little into its neighbours, so the
        // preview darkens by the same amount the tones were pre-lightened for. Preview ≈ print.
        for (y in 0 until h) for (x in 0 until w) {
            val i = y * w + x
            if (bits[i].toInt() != 0) {
                out[y * labelW + headLeft + x] = Color.BLACK
            } else if (x > 0 && y > 0 && x < w - 1 && y < h - 1) {
                var n = 0
                if (bits[i - 1].toInt() != 0) n++
                if (bits[i + 1].toInt() != 0) n++
                if (bits[i - w].toInt() != 0) n++
                if (bits[i + w].toInt() != 0) n++
                if (n > 0) {
                    val ink = (DOT_GAIN * n / 4.0).coerceIn(0.0, 1.0)
                    val g = (255 * (1 - ink)).toInt()
                    out[y * labelW + headLeft + x] = Color.rgb(g, g, g)
                }
            }
        }
        return Bitmap.createBitmap(out, labelW, h, Bitmap.Config.ARGB_8888).let {
            if (window != null && frameColor != null) decorateFilm(it, window) else it
        }
    }

    // Lettering like on the real frame, preview only
    private fun decorateFilm(bmp: Bitmap, window: PhotoWindow): Bitmap {
        val m = bmp.copy(Bitmap.Config.ARGB_8888, true)
        val c = Canvas(m)
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(205, 190, 200)
            textSize = mmToPx(2.6).toFloat()
            letterSpacing = 0.35f
            textAlign = Paint.Align.CENTER
            typeface = Typeface.create(Typeface.SERIF, Typeface.ITALIC)
        }
        c.drawText("Memory Film", m.width / 2f, mmToPx(window.y * 0.65).toFloat(), p)
        p.textAlign = Paint.Align.RIGHT
        p.letterSpacing = 0.1f
        p.color = Color.rgb(240, 170, 140)
        c.drawText("28", mmToPx(window.x + window.w).toFloat(), mmToPx(window.y + window.h + 5.0).toFloat(), p)
        bmp.recycle()
        return m
    }

    private fun drawFitted(canvas: Canvas, src: Bitmap, area: Rect, fit: Fit, rotation: Int) {
        val aw = area.width().toFloat()
        val ah = area.height().toFloat()
        val sw = if (rotation % 180 != 0) src.height else src.width
        val sh = if (rotation % 180 != 0) src.width else src.height
        var sx = aw / sw
        var sy = ah / sh
        if (fit != Fit.STRETCH) {
            val s = if (fit == Fit.CONTAIN) minOf(sx, sy) else maxOf(sx, sy)
            sx = s; sy = s
        }
        // No rotation (the usual case – crop bakes rotation in): anti-aliased downscale.
        // Bilinear undersamples and leaves moiré on slanted/fine structures.
        if (rotation % 180 == 0 && sx < 0.98f && sy < 0.98f) {
            val dw = (sw * sx).toInt().coerceAtLeast(1)
            val dh = (sh * sy).toInt().coerceAtLeast(1)
            val scaled = downscaleAA(src, dw, dh)
            canvas.save()
            canvas.clipRect(area)
            canvas.drawBitmap(scaled, area.exactCenterX() - dw / 2f, area.exactCenterY() - dh / 2f, null)
            canvas.restore()
            if (scaled !== src) scaled.recycle()
            return
        }
        // Halve large photos step by step; one big downscale step causes moiré
        var img = src
        while (minOf(sx, sy) * 2 < 1f && img.width > 64 && img.height > 64) {
            img = Bitmap.createScaledBitmap(img, img.width / 2, img.height / 2, true)
            sx *= 2; sy *= 2
        }
        val m = Matrix().apply {
            postTranslate(-img.width / 2f, -img.height / 2f)
            postRotate(rotation.toFloat())
            postScale(sx, sy)
            postTranslate(area.exactCenterX(), area.exactCenterY())
        }
        canvas.save()
        canvas.clipRect(area)
        canvas.drawBitmap(img, m, Paint(Paint.FILTER_BITMAP_FLAG))
        canvas.restore()
    }

    // Anti-aliased downscale: cheaply halve (area) down to within 2x of the target, then a small
    // Gaussian pre-blur removes the residual aliasing band a box filter leaves (its stopband is
    // imperfect), then a final area average. The blur only removes detail finer than the target can
    // show, so it costs no real sharpness but roughly quarters the leftover moiré on fine structures.
    private fun downscaleAA(src: Bitmap, dw: Int, dh: Int): Bitmap {
        var cur = src
        while (cur.width >= 2 * dw && cur.height >= 2 * dh) {
            val half = areaScale(cur, cur.width / 2, cur.height / 2)
            if (cur !== src) cur.recycle()
            cur = half
        }
        val sigma = (0.6f * cur.width / dw).coerceIn(0.5f, 1.2f)
        val blurred = gaussBlur(cur, sigma)
        if (cur !== src) cur.recycle()
        val out = areaScale(blurred, dw, dh)
        if (blurred !== src && blurred !== out) blurred.recycle()
        return out
    }

    // Separable Gaussian blur on ARGB. Runs on the small (<=2x target) intermediate, so it is cheap.
    private fun gaussBlur(src: Bitmap, sigma: Float): Bitmap {
        val w = src.width; val h = src.height
        val rad = Math.max(1, (sigma * 3).toInt())
        val k = FloatArray(2 * rad + 1); var ks = 0f
        for (i in -rad..rad) { val v = Math.exp(-(i * i) / (2.0 * sigma * sigma)).toFloat(); k[i + rad] = v; ks += v }
        for (i in k.indices) k[i] /= ks
        val sp = IntArray(w * h); src.getPixels(sp, 0, w, 0, 0, w, h)
        val tmp = IntArray(w * h)
        for (y in 0 until h) {
            val row = y * w
            for (x in 0 until w) {
                var a = 0f; var r = 0f; var g = 0f; var b = 0f
                for (j in -rad..rad) {
                    val c = sp[row + (x + j).coerceIn(0, w - 1)]; val wt = k[j + rad]
                    a += ((c ushr 24) and 0xff) * wt; r += ((c ushr 16) and 0xff) * wt
                    g += ((c ushr 8) and 0xff) * wt; b += (c and 0xff) * wt
                }
                tmp[row + x] = (a.toInt() shl 24) or (r.toInt() shl 16) or (g.toInt() shl 8) or b.toInt()
            }
        }
        val out = IntArray(w * h)
        for (x in 0 until w) {
            for (y in 0 until h) {
                var a = 0f; var r = 0f; var g = 0f; var b = 0f
                for (j in -rad..rad) {
                    val c = tmp[(y + j).coerceIn(0, h - 1) * w + x]; val wt = k[j + rad]
                    a += ((c ushr 24) and 0xff) * wt; r += ((c ushr 16) and 0xff) * wt
                    g += ((c ushr 8) and 0xff) * wt; b += (c and 0xff) * wt
                }
                out[y * w + x] = (a.toInt() shl 24) or (r.toInt() shl 16) or (g.toInt() shl 8) or b.toInt()
            }
        }
        return Bitmap.createBitmap(out, w, h, Bitmap.Config.ARGB_8888)
    }

    // Proper area-averaging downscale (separable): each output pixel is the coverage-weighted
    // mean of the source pixels it spans. Only for downscaling; returns src unchanged otherwise.
    private fun areaScale(src: Bitmap, dw: Int, dh: Int): Bitmap {
        val sw = src.width
        val sh = src.height
        if (dw >= sw || dh >= sh) return src
        val sp = IntArray(sw * sh)
        src.getPixels(sp, 0, sw, 0, 0, sw, sh)
        val tmp = IntArray(dw * sh)
        val fx = sw.toDouble() / dw
        for (y in 0 until sh) {
            val row = y * sw
            for (x in 0 until dw) {
                val xa = x * fx; val xb = (x + 1) * fx
                var a = 0.0; var r = 0.0; var g = 0.0; var b = 0.0; var ws = 0.0
                var xx = xa.toInt()
                val xe = Math.ceil(xb).toInt().coerceAtMost(sw)
                while (xx < xe) {
                    val wgt = minOf(xb, (xx + 1).toDouble()) - maxOf(xa, xx.toDouble())
                    if (wgt > 0) {
                        val c = sp[row + xx]
                        a += ((c ushr 24) and 0xff) * wgt; r += ((c ushr 16) and 0xff) * wgt
                        g += ((c ushr 8) and 0xff) * wgt; b += (c and 0xff) * wgt; ws += wgt
                    }
                    xx++
                }
                val inv = if (ws > 0) 1.0 / ws else 0.0
                tmp[y * dw + x] = ((a * inv).toInt() shl 24) or ((r * inv).toInt() shl 16) or
                    ((g * inv).toInt() shl 8) or (b * inv).toInt()
            }
        }
        val out = IntArray(dw * dh)
        val fy = sh.toDouble() / dh
        for (y in 0 until dh) {
            val ya = y * fy; val yb = (y + 1) * fy
            val j0 = ya.toInt(); val je = Math.ceil(yb).toInt().coerceAtMost(sh)
            for (x in 0 until dw) {
                var a = 0.0; var r = 0.0; var g = 0.0; var b = 0.0; var ws = 0.0
                var yy = j0
                while (yy < je) {
                    val wgt = minOf(yb, (yy + 1).toDouble()) - maxOf(ya, yy.toDouble())
                    if (wgt > 0) {
                        val c = tmp[yy * dw + x]
                        a += ((c ushr 24) and 0xff) * wgt; r += ((c ushr 16) and 0xff) * wgt
                        g += ((c ushr 8) and 0xff) * wgt; b += (c and 0xff) * wgt; ws += wgt
                    }
                    yy++
                }
                val inv = if (ws > 0) 1.0 / ws else 0.0
                out[y * dw + x] = ((a * inv).toInt() shl 24) or ((r * inv).toInt() shl 16) or
                    ((g * inv).toInt() shl 8) or (b * inv).toInt()
            }
        }
        return Bitmap.createBitmap(out, dw, dh, Bitmap.Config.ARGB_8888)
    }

    private fun luminance(px: IntArray) = FloatArray(px.size) { i ->
        val c = px[i]
        val a = Color.alpha(c) / 255f
        val g = 0.299f * Color.red(c) + 0.587f * Color.green(c) + 0.114f * Color.blue(c)
        g * a + 255f * (1 - a)
    }

    // At full heat each printed dot bleeds into its neighbours, so a mid-tone dither pattern
    // comes out much darker than on screen. We pre-lighten the tones by the inverse of that
    // spread; black and white stay put, mid-tones lose ink. 0 = off, ~0.55 = strong thermal.
    private const val DOT_GAIN = 0.55

    // Prepare tones for thermal printing: auto contrast in the photo area, brightness/contrast,
    // gentle sharpening, and dot-gain compensation so the print matches the preview.
    private fun prepare(gray: FloatArray, w: Int, h: Int, area: Rect, o: Options, fade: FloatArray?) {
        val r = Rect(area).apply { intersect(0, 0, w, h) }
        if (r.isEmpty) return
        val hist = IntArray(256)
        for (y in r.top until r.bottom) for (x in r.left until r.right) hist[gray[y * w + x].toInt().coerceIn(0, 255)]++
        val total = r.width() * r.height()
        fun pct(q: Double): Int {
            var acc = 0
            for (i in 0..255) { acc += hist[i]; if (acc >= total * q) return i }
            return 255
        }
        var lo = pct(0.01).toFloat()
        // Only the brightest 0.1 % may clip, so light grey stays distinguishable from paper white
        var hi = pct(0.999).toFloat()
        // Do not stretch flat areas (e.g. no photo yet) to black
        if (hi - lo < 32f) { lo = 0f; hi = 255f }
        val contrast = 1f + o.contrast / 50f * 0.8f
        val bright = o.brightness * 1.5f
        val photo = o.raster != Raster.TEXT
        // Dot gain only matters for patterns of single dots; coarse cells and thick lines print true
        val fine = o.raster == Raster.PHOTO || o.raster == Raster.DOTS ||
            o.raster == Raster.SCANLINES || o.raster == Raster.SCANLINES_WIDE

        // Unsharp mask (3×3); kept light so scaled high-contrast edges do not bloom at full heat
        val src = gray.copyOf()
        for (y in r.top until r.bottom) for (x in r.left until r.right) {
            val i = y * w + x
            var v = src[i]
            if (photo && x > r.left && y > r.top && x < r.right - 1 && y < r.bottom - 1) {
                val blur = (src[i - 1] + src[i + 1] + src[i - w] + src[i + w] + 4 * v) / 8f
                v += 0.4f * (v - blur)
            }
            v = (v - lo) / (hi - lo) * 255f
            v = (v - 128f) * contrast + 128f + bright
            if (fine) {
                // Dot-gain compensation in ink-coverage space (d = 1 - v, 1 = full black).
                // Taper it out in the highlights (small d) so light tones keep their dots.
                val d = (1f - v / 255f).coerceIn(0f, 1f)
                val wgt = ((d - 0.05f) / 0.17f).coerceIn(0f, 1f)
                val di = d - DOT_GAIN.toFloat() * d * (1f - d) * wgt
                v = (1f - di) * 255f
            }
            v = if (o.invert) 255f - v else v.coerceIn(0f, 255f)
            gray[i] = if (fade != null) v * fade[i] + 255f * (1f - fade[i]) else v
        }
    }

    private fun toBits(px: IntArray, w: Int, h: Int, area: Rect, o: Options, fade: FloatArray? = null): ByteArray {
        val gray = luminance(px)
        prepare(gray, w, h, area, o, fade)
        val bits = ByteArray(w * h)
        when (o.raster) {
            Raster.TEXT -> for (i in gray.indices) bits[i] = if (gray[i] < 128) 1 else 0
            Raster.DOTS -> {
                // Round dots at 45°, about 0.5 mm screen pitch at 300 dpi
                val period = 6.0
                val k = 2 * Math.PI / period / Math.sqrt(2.0)
                for (y in 0 until h) for (x in 0 until w) {
                    val i = y * w + x
                    val t = (Math.cos((x + y) * k) + Math.cos((x - y) * k) + 2) / 4
                    bits[i] = if (gray[i] / 255.0 < t) 1 else 0
                }
            }
            Raster.PHOTO -> atkinson(gray, w, h, bits)
            Raster.LINES -> {
                // Engraving look: horizontal lines whose thickness carries the tone (0.6 mm pitch)
                val pitch = 7
                for (y in 0 until h) {
                    val c = (y / pitch) * pitch + pitch / 2f
                    val dy = Math.abs(y + 0.5f - c)
                    val sy = c.toInt().coerceAtMost(h - 1)
                    for (x in 0 until w) {
                        val t = (1f - gray[sy * w + x] / 255f).coerceIn(0f, 1f) * pitch
                        bits[y * w + x] = if (dy < t / 2f) 1 else 0
                    }
                }
            }
            // Dot spread closes about one row of the gap, so 2 rows print as a fine line, 4 as a wide one
            Raster.SCANLINES -> scanlines(gray, w, h, bits, pitch = 6, lit = 4)
            Raster.SCANLINES_WIDE -> scanlines(gray, w, h, bits, pitch = 10, lit = 6)
            // MacPaint: Atkinson at 72 dpi, so each screen pixel is a 4×4 block
            Raster.MAC -> blocks(gray, w, h, bits, 4, 4) { g, bw, bh, b -> atkinson(g, bw, bh, b) }
            // Amiga / early PC: 4×4 Bayer ordered dither on 2×2 blocks, the typical cross-hatch
            Raster.AMIGA -> blocks(gray, w, h, bits, 2, 2) { g, bw, bh, b ->
                for (y in 0 until bh) for (x in 0 until bw) {
                    val t = (BAYER4[(y and 3) * 4 + (x and 3)] + 0.5f) / 16f * 255f
                    b[y * bw + x] = if (g[y * bw + x] < t) 1 else 0
                }
            }
            // Fax "standard" resolution (204 × 98 dpi): coarse rows, Floyd-Steinberg
            Raster.FAX -> blocks(gray, w, h, bits, 2, 3) { g, bw, bh, b -> floyd(g, bw, bh, b) }
        }
        return bits
    }

    private val BAYER4 = intArrayOf(0, 8, 2, 10, 12, 4, 14, 6, 3, 11, 1, 9, 15, 7, 13, 5)

    // Atkinson spreads only 6/8 of the error: cleaner mid-tones, less mud. But the lost error means
    // a flat tone lighter than ~223 never gets a dot, so highlights burn out. Light tones therefore
    // pass on the full error (6/6); pure white still stays blank.
    private fun atkinson(gray: FloatArray, w: Int, h: Int, bits: ByteArray) {
        val tone = gray.copyOf()
        for (y in 0 until h) for (x in 0 until w) {
            val i = y * w + x
            val v = gray[i]
            val black = v < 128
            bits[i] = if (black) 1 else 0
            val full = ((tone[i] - 200f) / 35f).coerceIn(0f, 1f)
            val err = (v - if (black) 0f else 255f) / (8f - 2f * full)
            fun add(xx: Int, yy: Int) {
                if (xx in 0 until w && yy < h) gray[yy * w + xx] += err
            }
            add(x + 1, y); add(x + 2, y)
            add(x - 1, y + 1); add(x, y + 1); add(x + 1, y + 1)
            add(x, y + 2)
        }
    }

    // CRT look: the picture sits in bands of `lit` rows with a white gap up to `pitch`. The bands are
    // darkened so the overall tone holds and dithered as if they were contiguous, so no error is lost
    // in the gaps.
    private fun scanlines(gray: FloatArray, w: Int, h: Int, bits: ByteArray, pitch: Int, lit: Int) {
        val rows = (0 until h).filter { it % pitch < lit }
        val band = FloatArray(rows.size * w)
        for ((j, y) in rows.withIndex()) for (x in 0 until w) {
            band[j * w + x] = (255f - (255f - gray[y * w + x]) * pitch / lit).coerceAtLeast(0f)
        }
        val bb = ByteArray(band.size)
        atkinson(band, w, rows.size, bb)
        for ((j, y) in rows.withIndex()) System.arraycopy(bb, j * w, bits, y * w, w)
    }

    private fun floyd(gray: FloatArray, w: Int, h: Int, bits: ByteArray) {
        for (y in 0 until h) for (x in 0 until w) {
            val i = y * w + x
            val v = gray[i]
            val black = v < 128
            bits[i] = if (black) 1 else 0
            val err = v - if (black) 0f else 255f
            if (x + 1 < w) gray[i + 1] += err * 7 / 16
            if (y + 1 < h) {
                if (x > 0) gray[i + w - 1] += err * 3 / 16
                gray[i + w] += err * 5 / 16
                if (x + 1 < w) gray[i + w + 1] += err * 1 / 16
            }
        }
    }

    // Dither at a coarser resolution (bx × by print pixels per cell) and scale the result back up
    private fun blocks(
        gray: FloatArray, w: Int, h: Int, bits: ByteArray, bx: Int, by: Int,
        dither: (FloatArray, Int, Int, ByteArray) -> Unit,
    ) {
        val cw = (w + bx - 1) / bx
        val ch = (h + by - 1) / by
        val cells = FloatArray(cw * ch)
        val count = IntArray(cw * ch)
        for (y in 0 until h) for (x in 0 until w) {
            val c = (y / by) * cw + x / bx
            cells[c] += gray[y * w + x]; count[c]++
        }
        for (c in cells.indices) cells[c] /= count[c]
        val cb = ByteArray(cw * ch)
        dither(cells, cw, ch, cb)
        for (y in 0 until h) for (x in 0 until w) bits[y * w + x] = cb[(y / by) * cw + x / bx]
    }

    // Caption, sharp (no dithering), in the bottom border
    private fun drawCaption(bits: ByteArray, w: Int, h: Int, photo: Rect, text: String) {
        val box = RectF(photo.left.toFloat(), photo.bottom.toFloat(), photo.right.toFloat(), h.toFloat())
        if (box.height() < 10) return
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            typeface = Typeface.create("casual", Typeface.NORMAL)
            textAlign = Paint.Align.CENTER
            textSize = box.height() * 0.42f
        }
        while (paint.measureText(text) > box.width() && paint.textSize > 12) paint.textSize *= 0.92f
        val tmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(tmp)
        c.drawColor(Color.WHITE)
        val fm = paint.fontMetrics
        c.drawText(text, box.centerX(), box.centerY() - (fm.ascent + fm.descent) / 2, paint)
        val px = IntArray(w * h)
        tmp.getPixels(px, 0, w, 0, 0, w, h)
        for (i in px.indices) if (Color.red(px[i]) < 128) bits[i] = 1
        tmp.recycle()
    }
}
