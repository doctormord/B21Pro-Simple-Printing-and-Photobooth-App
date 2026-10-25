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
    enum class Raster { PHOTO, DOTS, TEXT }

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
    )

    // bits/width/height: what is printed (at most the print head width)
    // preview: the whole label as it looks after printing
    class Result(val bits: ByteArray, val width: Int, val height: Int, val preview: Bitmap)

    // Bleed into the pre-printed frame so no white gap remains
    private const val BLEED_MM = 0.6

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

        val area = when {
            window != null -> Rect(
                mmToPx(window.x - BLEED_MM + o.offsetXMm) - headLeft,
                mmToPx(window.y - BLEED_MM + o.offsetYMm),
                mmToPx(window.x + window.w + BLEED_MM + o.offsetXMm) - headLeft,
                mmToPx(window.y + window.h + BLEED_MM + o.offsetYMm),
            ).apply { intersect(0, 0, w, h) }
            o.instant -> instantPhotoRect(w, h)
            else -> Rect(0, 0, w, h)
        }
        if (round) {
            // Round label: everything outside the circle stays white
            canvas.clipPath(Path().apply {
                addCircle(area.exactCenterX(), area.exactCenterY(), minOf(area.width(), area.height()) / 2f, Path.Direction.CW)
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
        val bits = toBits(px, w, h, area, o)
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
        // No rotation (the usual case – crop bakes rotation in): area-average the downscale.
        // Bilinear undersamples and leaves moiré on slanted/fine structures; area averaging is clean.
        if (rotation % 180 == 0 && sx < 0.98f && sy < 0.98f) {
            val dw = (sw * sx).toInt().coerceAtLeast(1)
            val dh = (sh * sy).toInt().coerceAtLeast(1)
            val scaled = areaScale(src, dw, dh)
            canvas.save()
            canvas.clipRect(area)
            canvas.drawBitmap(scaled, area.exactCenterX() - dw / 2f, area.exactCenterY() - dh / 2f, null)
            canvas.restore()
            if (scaled != src) scaled.recycle()
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

    // Proper area-averaging downscale (separable): each output pixel is the coverage-weighted
    // mean of the source pixels it spans. Removes the aliasing that bilinear leaves on fine
    // diagonal/ring structures. Only for downscaling; returns src unchanged if it would upscale.
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

    // Highlight headroom: the brightest tone maps here instead of pure white, so light areas
    // keep a faint dot texture instead of clipping to blank paper. Lower = more highlight detail.
    private const val WHITE = 240f

    // Prepare tones for thermal printing: auto contrast in the photo area, brightness/contrast,
    // gentle sharpening, and dot-gain compensation so the print matches the preview.
    private fun prepare(gray: FloatArray, w: Int, h: Int, area: Rect, o: Options) {
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
        var hi = pct(0.99).toFloat()
        // Do not stretch flat areas (e.g. no photo yet) to black
        if (hi - lo < 32f) { lo = 0f; hi = 255f }
        val contrast = 1f + o.contrast / 50f * 0.8f
        val bright = o.brightness * 1.5f
        val photo = o.raster != Raster.TEXT

        // Unsharp mask (3×3); kept light so scaled high-contrast edges do not bloom at full heat
        val src = gray.copyOf()
        for (y in r.top until r.bottom) for (x in r.left until r.right) {
            val i = y * w + x
            var v = src[i]
            if (photo && x > r.left && y > r.top && x < r.right - 1 && y < r.bottom - 1) {
                val blur = (src[i - 1] + src[i + 1] + src[i - w] + src[i + w] + 4 * v) / 8f
                v += 0.4f * (v - blur)
            }
            val top = if (photo) WHITE else 255f
            v = (v - lo) / (hi - lo) * top
            v = (v - 128f) * contrast + 128f + bright
            if (photo) {
                // Dot-gain compensation in ink-coverage space (d = 1 - v, 1 = full black).
                // Taper it out in the highlights (small d) so light tones keep their dots.
                val d = (1f - v / 255f).coerceIn(0f, 1f)
                val wgt = ((d - 0.05f) / 0.17f).coerceIn(0f, 1f)
                val di = d - DOT_GAIN.toFloat() * d * (1f - d) * wgt
                v = (1f - di) * 255f
            }
            gray[i] = if (o.invert) 255f - v else v.coerceIn(0f, 255f)
        }
    }

    private fun toBits(px: IntArray, w: Int, h: Int, area: Rect, o: Options): ByteArray {
        val gray = luminance(px)
        prepare(gray, w, h, area, o)
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
            Raster.PHOTO -> {
                // Atkinson spreads only 6/8 of the error: cleaner highlights, less mud
                for (y in 0 until h) for (x in 0 until w) {
                    val i = y * w + x
                    val v = gray[i]
                    val black = v < 128
                    bits[i] = if (black) 1 else 0
                    val err = (v - if (black) 0f else 255f) / 8f
                    fun add(xx: Int, yy: Int) {
                        if (xx in 0 until w && yy < h) gray[yy * w + xx] += err
                    }
                    add(x + 1, y); add(x + 2, y)
                    add(x - 1, y + 1); add(x, y + 1); add(x + 1, y + 1)
                    add(x, y + 2)
                }
            }
        }
        return bits
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
