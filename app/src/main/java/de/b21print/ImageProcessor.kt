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
import java.util.Locale
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.roundToInt

object ImageProcessor {
    enum class Fit { CONTAIN, COVER, STRETCH }

    // PHOTO: Atkinson dithering, DOTS: halftone screen, TEXT: hard threshold
    // Order = order in the raster picker (R.array.raster)
    enum class Raster { PHOTO, DOTS, LINES, SCANLINES, SCANLINES_WIDE, MAC, AMIGA, BAYER, BLUE, RIEMERSMA, FAX, TEXT }

    data class Options(
        val fit: Fit,
        val rotation: Int,
        val raster: Raster,
        val brightness: Int,   // -60..60
        val contrast: Int,     // -50..50
        val invert: Boolean,
        val instant: Boolean,
        val caption: String,
        // Date or short text, bottom right (pixel font, upper case). On a full photo it is punched into
        // the picture in white or black; on an instant frame it sits in the frame, opposite to its colour.
        val stamp: String = "",
        val stampWhite: Boolean = false,
        val instantBlack: Boolean = false,
        val offsetXMm: Double = 0.0,
        val offsetYMm: Double = 0.0,
        // The photo fades to white towards the edge of its area (circle on round labels)
        val softEdge: Boolean = false,
        // Round labels: print further past the rim, for rolls that sit a bit off. Not for windows:
        // the pre-printed frame is thermal paper too, overscan would grey it.
        val overscan: Boolean = false,
        // Printer density 1..5; the photo raster corrects tones with the curve measured for it
        val density: Int = 3,
        // Photo raster: target tone curve of the print (see PrintCurve)
        val toneGamma: Double = PrintCurve.DEFAULT_GAMMA,
        val ink: Ink = Ink.MONO,
        // RED_BLACK: the band of image tones (0 = black, 1 = white) that prints red
        val redLo: Float = 0.5f,
        val redHi: Float = 1f,
        // From the paper profile, for this density (see PaperProfile.curves); null = built-in default
        val grayCurve: FloatArray? = null,
        val monoCurve: FloatArray? = null,
        // Portrait photos with a depth map: what is nearer than depthCut (0..1 of the photo's depth
        // range) is the subject, the rest the background, treated as `background` says
        val depthCut: Float = 0.5f,
        val background: Background = Background.SAME,
        // LIGHTER/DARKER: 0..1, how far the background moves towards white or black
        val bgStrength: Float = 0.5f,
        // LOOK: the background's raster and its measured curve
        val bgRaster: Raster = Raster.BLUE,
        val bgMonoCurve: FloatArray? = null,
    )

    // RED and SUBJECT_RED only on two-colour rolls: the depth decides the colour instead of the tone
    enum class Background { SAME, LIGHTER, DARKER, LOOK, RED, SUBJECT_RED }

    // MONO: 1-bit dots. GRAY: the printer's 16-level mode, photo raster only (the other looks are
    // 1-bit patterns by nature and stay MONO). RED_BLACK: 1-bit dots on a two-colour roll, each red or black.
    enum class Ink { MONO, GRAY, RED_BLACK }

    // bits/width/height: what is printed (at most the print head width), 1 = dot.
    // levels: GRAY only, 0 = white .. 15 = black. red: RED_BLACK only, 1 = the dot in bits is red.
    // preview: the whole label as it looks after printing
    class Result(
        val bits: ByteArray, val width: Int, val height: Int, val preview: Bitmap,
        val levels: ByteArray? = null, val red: ByteArray? = null,
        // Landscape labels are laid out wide on screen and turned for the print: 0 none, 1 clockwise, 2 counter-clockwise
        val rot: Int = 0,
    ) {
        fun withRot(r: Int) = Result(bits, width, height, preview, levels, red, r)

        // Columns x0 until x1 as a result of their own (panorama: one label of the strip)
        fun slice(x0: Int, x1: Int): Result {
            fun cut(a: ByteArray): ByteArray {
                val w = x1 - x0
                val out = ByteArray(w * height)
                for (y in 0 until height) System.arraycopy(a, y * width + x0, out, y * w, w)
                return out
            }
            val pw = preview.width
            val px0 = (x0.toLong() * pw / width).toInt(); val px1 = (x1.toLong() * pw / width).toInt()
            return Result(cut(bits), x1 - x0, height, Bitmap.createBitmap(preview, px0, 0, maxOf(1, px1 - px0), preview.height),
                levels?.let { cut(it) }, red?.let { cut(it) }, rot)
        }

        // The dots as they go to the printer (head width across), turned when rot is set
        fun forPrint(): Result {
            if (rot == 0) return this
            fun turn(a: ByteArray): ByteArray {
                val out = ByteArray(a.size)
                for (y in 0 until height) for (x in 0 until width) {
                    // new size: height wide, width tall
                    val nx = if (rot == 1) height - 1 - y else y
                    val ny = if (rot == 1) x else width - 1 - x
                    out[ny * height + nx] = a[y * width + x]
                }
                return out
            }
            return Result(turn(bits), height, width, preview, levels?.let { turn(it) }, red?.let { turn(it) }, 0)
        }
    }

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
    fun renderText(
        style: TextStyle, labelWmm: Double, labelHmm: Double, window: PhotoWindow?, frameColor: Int?, round: Boolean,
        cap: Int = Niimbot.PRINTHEAD_PIXELS, twoColour: Boolean = false,
    ): Result {
        val labelW = mmToPx(labelWmm)
        val w = minOf(cap, labelW)
        val h = mmToPx(labelHmm)
        val headLeft = (labelW - w) / 2
        val area = if (window != null) {
            RectF(mmToPx(window.x).toFloat() - headLeft, mmToPx(window.y).toFloat(),
                mmToPx(window.x + window.w).toFloat() - headLeft, mmToPx(window.y + window.h).toFloat())
        } else {
            RectF(-headLeft.toFloat(), 0f, (labelW - headLeft).toFloat(), h.toFloat())
        }
        if (twoColour) {
            val (bits, red) = TextLabel.renderTwoColour(style, w, h, area, round)
            return Result(bits, w, h, composePreview(bits, w, h, labelW, headLeft, window, frameColor, red = red), red = red)
        }
        val bits = TextLabel.render(style, w, h, area, round)
        return Result(bits, w, h, composePreview(bits, w, h, labelW, headLeft, window, frameColor))
    }

    // depth: nearness map of src (DepthMap), same aspect, any size
    fun render(
        src: Bitmap?, labelWmm: Double, labelHmm: Double, window: PhotoWindow?, frameColor: Int?, o: Options,
        round: Boolean = false, depth: Bitmap? = null, cap: Int = Niimbot.PRINTHEAD_PIXELS,
    ): Result {
        val labelW = mmToPx(labelWmm)
        val w = minOf(cap, labelW)
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
        val ink = inkFor(o)
        val bgSplit = when (o.background) {
            Background.SAME -> false
            Background.RED, Background.SUBJECT_RED -> ink == Ink.RED_BLACK
            else -> true
        }
        val subject = if (src != null && depth != null && bgSplit) subjectMask(src, depth, w, h, area, o) else null
        val gray = luminance(px)
        val bgGray = if (subject != null && o.background == Background.LOOK) gray.copyOf() else null
        val tone = if (ink == Ink.RED_BLACK) FloatArray(w * h) else null
        prepare(gray, w, h, area, o, fade, ink, tone, subject)
        var levels = if (ink == Ink.GRAY) toLevels(gray, w, h, o.grayCurve ?: PrintCurve.GRAY) else null
        val bits = levels?.let { l -> ByteArray(w * h) { if (l[it] > 0) 1 else 0 } } ?: toBits(gray, w, h, o)
        // Background in another look: the same photo prepared and rasterised again with that look's
        // tone correction, then the two are joined along the depth boundary (blue-noise mixed across
        // the soft edge of the mask, so the seam has no outline)
        val bgOpts = if (bgGray != null) o.copy(raster = o.bgRaster, monoCurve = o.bgMonoCurve) else null
        var bgPick: BooleanArray? = null
        if (bgGray != null && bgOpts != null && subject != null) {
            val bgInk = inkFor(bgOpts)
            prepare(bgGray, w, h, area, bgOpts, fade, bgInk, null, null)
            val bgLevels = if (bgInk == Ink.GRAY) toLevels(bgGray, w, h, o.grayCurve ?: PrintCurve.GRAY) else null
            val bgBits = bgLevels?.let { l -> ByteArray(w * h) { if (l[it] > 0) 1 else 0 } } ?: toBits(bgGray, w, h, bgOpts)
            val pick = BooleanArray(w * h) { i -> subject[i] * 255f <= Dither.blueThreshold(i % w, i / w) }
            // One of the two in gray: 1-bit dots of the other print at the darkest level
            if (levels != null || bgLevels != null) {
                val fg = levels
                levels = ByteArray(w * h) { i ->
                    if (pick[i]) bgLevels?.get(i) ?: (bgBits[i] * 15).toByte()
                    else fg?.get(i) ?: (bits[i] * 15).toByte()
                }
            }
            for (i in bits.indices) if (pick[i]) bits[i] = bgBits[i]
            bgPick = pick
        }
        val red = tone?.let { redMask(bits, it, w, h, o, subject) }
        canvasBmp.recycle()

        fun setDot(i: Int, v: Int) {
            bits[i] = v.toByte(); levels?.set(i, if (v == 1) 15 else 0); red?.set(i, 0)
        }
        val black = o.instant && window == null && o.instantBlack
        if (o.instant && window == null) {
            val mark = ByteArray(w * h)
            if (black) {
                // Black paper all around the photo
                for (y in 0 until h) for (x in 0 until w) if (!area.contains(x, y)) setDot(y * w + x, 1)
            } else {
                // Thin line around the photo so bright images still stand out; always solid black
                for (x in area.left until area.right) {
                    mark[area.top * w + x] = 1; mark[(area.bottom - 1) * w + x] = 1
                }
                for (y in area.top until area.bottom) {
                    mark[y * w + area.left] = 1; mark[y * w + area.right - 1] = 1
                }
            }
            if (o.caption.isNotBlank()) drawCaption(mark, w, h, area, o.caption)
            for (i in mark.indices) if (mark[i].toInt() != 0) setDot(i, if (black) 0 else 1)
        }
        if (o.stamp.isNotBlank() && !round) {
            val text = o.stamp.trim().take(32).uppercase()
            // Window labels: inside the visible window, not the bleed around it
            val box = if (window != null) edge else Rect(0, 0, w, h)
            var s = maxOf(1, w / 160)
            var pad = maxOf(6, 2 * s + 3)
            while (s > 1 && PhotoStrip.textWidth(text, s) > box.width() - 2 * pad) { s--; pad = maxOf(6, 2 * s + 3) }
            val tw = PhotoStrip.textWidth(text, s); val th = 7 * s
            val x0 = box.right - pad - tw
            // Instant: in the white field below the photo, if it is deep enough
            val inField = window == null && o.instant && h - area.bottom >= th + 2 * pad
            val y0 = (if (inField) h else if (window != null) box.bottom else area.bottom) - pad - th
            if (x0 >= 0 && y0 >= 0) {
                // Ink: instant frames take the opposite of the paper, a full photo the chosen colour
                val white = if (inField) black else o.stampWhite
                val glyph = ByteArray(w * h)
                for ((n, ch) in text.withIndex()) TestChart.FONT[ch]?.let { g ->
                    for ((ry, row) in g.withIndex()) for ((rx, v) in row.withIndex()) if (v == '#')
                        for (yy in 0 until s) for (xx in 0 until s) {
                            val px = x0 + n * 6 * s + rx * s + xx; val py = y0 + ry * s + yy
                            if (px in 0 until w && py in 0 until h) glyph[py * w + px] = 1
                        }
                }
                if (!inField) {
                    // A one-dot outline in the opposite colour keeps the text readable on any picture
                    val halo = maxOf(1, s / 2)
                    for (y in 0 until h) for (x in 0 until w) if (glyph[y * w + x].toInt() != 0)
                        for (dy in -halo..halo) for (dx in -halo..halo) {
                            val px = x + dx; val py = y + dy
                            if (px in 0 until w && py in 0 until h && glyph[py * w + px].toInt() == 0) setDot(py * w + px, if (white) 1 else 0)
                        }
                }
                for (i in glyph.indices) if (glyph[i].toInt() != 0) setDot(i, if (white) 0 else 1)
            }
        }

        val curve = if (measured(o)) o else null
        val bgCurve = bgOpts?.takeIf { measured(it) }
        return Result(bits, w, h,
            composePreview(bits, w, h, labelW, headLeft, window, frameColor, curve, levels, red, bgCurve, bgPick),
            levels, red)
    }

    // How much each print dot belongs to the subject (1) or the background (0): the depth map laid
    // over the label exactly like the photo, with a soft step at the boundary (the map is coarse and
    // its edges follow the subject only roughly). Outside the photo area: subject (left untouched).
    private fun subjectMask(src: Bitmap, depth: Bitmap, w: Int, h: Int, area: Rect, o: Options): FloatArray {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(Color.WHITE)
        val (sx, sy) = fitScale(src, area, o.fit, o.rotation)
        val m = Matrix().apply {
            postTranslate(-depth.width / 2f, -depth.height / 2f)
            postScale(src.width.toFloat() / depth.width, src.height.toFloat() / depth.height)
            postRotate(o.rotation.toFloat())
            postScale(sx, sy)
            postTranslate(area.exactCenterX(), area.exactCenterY())
        }
        c.save()
        c.clipRect(area)
        c.drawColor(Color.BLACK)
        c.drawBitmap(depth, m, Paint(Paint.FILTER_BITMAP_FLAG))
        c.restore()
        val px = IntArray(w * h)
        bmp.getPixels(px, 0, w, 0, 0, w, h)
        bmp.recycle()
        val inArea = Rect(area).apply { intersect(0, 0, w, h) }
        return FloatArray(w * h) { i ->
            if (!inArea.contains(i % w, i / w)) return@FloatArray 1f
            val t = ((Color.red(px[i]) / 255f - o.depthCut) / DEPTH_FEATHER + 0.5f).coerceIn(0f, 1f)
            t * t * (3 - 2 * t)
        }
    }

    // Width of the soft subject boundary, in parts of the photo's depth range
    private const val DEPTH_FEATHER = 0.08f

    fun inkFor(o: Options) = if (o.ink == Ink.GRAY && o.raster != Raster.PHOTO) Ink.MONO else o.ink

    // 16-level error diffusion (Floyd-Steinberg): 0 = white .. 15 = black. dark: how dark each level
    // prints (PaperProfile or PrintCurve.GRAY), null = evenly spaced. Each dot gets the level nearest to its
    // target darkness, so levels that print alike are simply not used.
    private fun toLevels(gray: FloatArray, w: Int, h: Int, dark: FloatArray?): ByteArray {
        val d = dark ?: FloatArray(16) { it / 15f }
        val out = ByteArray(w * h)
        for (y in 0 until h) for (x in 0 until w) {
            val i = y * w + x
            val t = 1f - gray[i].coerceIn(0f, 255f) / 255f
            var l = 0
            for (k in 1 until 16) if (abs(d[k] - t) < abs(d[l] - t)) l = k
            out[i] = l.toByte()
            val err = 255f * (d[l] - t)
            if (x + 1 < w) gray[i + 1] += err * 7 / 16
            if (y + 1 < h) {
                if (x > 0) gray[i + w - 1] += err * 3 / 16
                gray[i + w] += err * 5 / 16
                if (x + 1 < w) gray[i + w + 1] += err / 16
            }
        }
        return out
    }

    // Which dots print red: those whose image tone lies in the red band. The tone is blurred first,
    // so the colours form areas instead of changing dot by dot: next to a black dot a red one blackens
    // (the heat spreads), and fine red/black mixtures print as a muddy dark brown.
    // The band edges are soft, like split toning: across RED_FEATHER of the tone range the share of red
    // rises, mixed by an ordered pattern of 2 × 2-dot cells (coarse on purpose, see above).
    // With a depth split (Background.RED / SUBJECT_RED) the subject mask sets the share instead.
    private fun redMask(bits: ByteArray, tone: FloatArray, w: Int, h: Int, o: Options, subject: FloatArray?): ByteArray {
        val byDepth = subject != null && (o.background == Background.RED || o.background == Background.SUBJECT_RED)
        val t = boxBlur(tone, w, h, 3)
        val f = RED_FEATHER * 255f
        val lo = o.redLo * 255f; val hi = o.redHi * 255f
        // A band edge at the end of the range is not an edge: tones there are fully red
        fun ramp(d: Float) = (d / f + 0.5f).coerceIn(0f, 1f)
        return ByteArray(w * h) { i ->
            if (bits[i].toInt() == 0) return@ByteArray 0
            val share = if (byDepth) subject!![i].let { if (o.background == Background.RED) 1f - it else it }
                else (if (o.redLo <= 0f) 1f else ramp(t[i] - lo)) * (if (o.redHi >= 1f) 1f else ramp(hi - t[i]))
            val cell = ((i / w) shr 1 and 3) * 4 + ((i % w) shr 1 and 3)
            if (share > (BAYER4[cell] + 0.5f) / 16f) 1 else 0
        }
    }

    private const val RED_FEATHER = 0.12f

    private fun boxBlur(v: FloatArray, w: Int, h: Int, r: Int): FloatArray {
        val tmp = FloatArray(w * h)
        for (y in 0 until h) {
            var s = 0f
            for (x in -r..r) s += v[y * w + x.coerceIn(0, w - 1)]
            for (x in 0 until w) {
                tmp[y * w + x] = s / (2 * r + 1)
                s += v[y * w + (x + r + 1).coerceAtMost(w - 1)] - v[y * w + (x - r).coerceAtLeast(0)]
            }
        }
        val out = FloatArray(w * h)
        for (x in 0 until w) {
            var s = 0f
            for (y in -r..r) s += tmp[y.coerceIn(0, h - 1) * w + x]
            for (y in 0 until h) {
                out[y * w + x] = s / (2 * r + 1)
                s += tmp[(y + r + 1).coerceAtMost(h - 1) * w + x] - tmp[(y - r).coerceAtLeast(0) * w + x]
            }
        }
        return out
    }

    // The same photo with several tone targets side by side in vertical stripes, each labelled with
    // its gamma at the top, to pick the target from a single print
    // variants: label and options of each stripe, left to right
    fun renderSweep(
        src: Bitmap?, labelWmm: Double, labelHmm: Double, window: PhotoWindow?, frameColor: Int?, o: Options,
        round: Boolean, variants: List<Pair<String, Options>>,
    ): Result {
        val parts = variants.map { render(src, labelWmm, labelHmm, window, frameColor, it.second, round) }
        val w = parts[0].width; val h = parts[0].height
        val labelW = mmToPx(labelWmm)
        val top = (if (window != null) mmToPx(window.y) else 0) + mmToPx(1.5)
        val bits = ByteArray(w * h)
        val levels = parts[0].levels?.let { ByteArray(w * h) }
        val red = parts[0].red?.let { ByteArray(w * h) }
        for ((k, part) in parts.withIndex()) {
            val x0 = k * w / parts.size; val x1 = (k + 1) * w / parts.size
            for ((dst, src) in listOf(bits to part.bits, levels to part.levels, red to part.red)) {
                if (dst != null && src != null) for (y in 0 until h) System.arraycopy(src, y * w + x0, dst, y * w + x0, x1 - x0)
            }
            val label = variants[k].first
            TestChart.stamp(bits, w, h, x0 + mmToPx(1.0), top, label, labelW)
            levels?.let { TestChart.stamp(it, w, h, x0 + mmToPx(1.0), top, label, labelW, 15) }
            red?.let { TestChart.stamp(it, w, h, x0 + mmToPx(1.0), top, label, labelW, 0) }
        }
        val curve = if (measured(o)) o else null
        return Result(bits, w, h, composePreview(bits, w, h, labelW, (labelW - w) / 2, window, frameColor, curve, levels, red),
            levels, red)
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
        curve: Options? = null, levels: ByteArray? = null, red: ByteArray? = null,
        // Background in another look: its curve (null = not measured) and which dots are background
        bgCurve: Options? = null, bgPick: BooleanArray? = null,
    ): Bitmap {
        // Two-colour rolls have a cream base (colour from a photo of the roll)
        val paper = if (red != null) Color.rgb(250, 232, 212) else Color.WHITE
        val out = IntArray(labelW * h) { paper }
        if (window != null && frameColor != null) {
            out.fill(frameColor)
            val l = mmToPx(window.x); val t = mmToPx(window.y)
            val r = mmToPx(window.x + window.w); val b = mmToPx(window.y + window.h)
            for (y in t until minOf(b, h)) for (x in l until minOf(r, labelW)) out[y * labelW + x] = paper
        }
        fun done() = Bitmap.createBitmap(out, labelW, h, Bitmap.Config.ARGB_8888).let {
            if (window != null && frameColor != null && window.filmText) decorateFilm(it, window) else it
        }
        if (levels != null) {
            // Gray mode: each level as dark as measured for the paper, or on an original roll
            val d = curve?.grayCurve ?: PrintCurve.GRAY
            val shade = IntArray(16) { l -> val g = (255 * srgb(1f - (d?.get(l) ?: (l / 15f)))).toInt().coerceIn(0, 255); Color.rgb(g, g, g) }
            for (y in 0 until h) for (x in 0 until w) {
                val l = levels[y * w + x].toInt()
                if (l > 0) out[y * labelW + headLeft + x] = shade[l]
            }
            return done()
        }
        if (red != null) {
            // Red/black: local coverage of each ink (3 × 3), mixed into the paper colour (linear).
            // The roll's black is a dark red-brown, never neutral. With a measured look the total
            // darkness comes from the print curve as in the 1-bit preview (dots merge); plain coverage
            // showed the tone-corrected photo far too light, as if everything were grey.
            val inked = FloatArray(10) { n ->
                if (curve == null || n == 0) n / 9f
                else PrintCurve.darkness(curve.density, 255f * (1f - n / 9f), curve.monoCurve)
            }
            val pc = floatArrayOf(0.955f, 0.807f, 0.658f)
            val kc = floatArrayOf(0.030f, 0.012f, 0.011f)
            val rc = floatArrayOf(0.680f, 0.061f, 0.045f)
            for (y in 0 until h) for (x in 0 until w) {
                var nk = 0; var nr = 0
                for (dy in -1..1) for (dx in -1..1) {
                    val i = (y + dy).coerceIn(0, h - 1) * w + (x + dx).coerceIn(0, w - 1)
                    if (bits[i].toInt() != 0) { if (red[i].toInt() != 0) nr++ else nk++ }
                }
                if (nk + nr == 0) continue
                val f = inked[nk + nr] / (nk + nr)
                val k = nk * f; val r = nr * f
                fun ch(c: Int) = (255 * srgb((pc[c] * (1f - k - r) + kc[c] * k + rc[c] * r).coerceIn(0f, 1f))).toInt()
                out[y * labelW + headLeft + x] = Color.rgb(ch(0), ch(1), ch(2))
            }
            return done()
        }
        // Measured looks: show each spot as dark as the measured print curve says for its local dot
        // coverage (3 × 3), so the preview matches the paper. Other looks: dot spread (below).
        fun shades(c: Options?) = c?.let {
            IntArray(10) { n ->
                val r = 1f - PrintCurve.darkness(it.density, 255f * (1f - n / 9f), it.monoCurve)
                val g = (255 * srgb(r)).toInt().coerceIn(0, 255)
                Color.rgb(g, g, g)
            }
        }
        val fgShade = shades(curve)
        val bgShade = if (bgPick != null) shades(bgCurve) else fgShade
        for (y in 0 until h) for (x in 0 until w) {
            val i = y * w + x
            val shade = if (bgPick?.get(i) == true) bgShade else fgShade
            if (shade != null) {
                var n = 0
                for (dy in -1..1) for (dx in -1..1) {
                    val yy = (y + dy).coerceIn(0, h - 1); val xx = (x + dx).coerceIn(0, w - 1)
                    n += bits[yy * w + xx]
                }
                if (n > 0) out[y * labelW + headLeft + x] = shade[n]
                continue
            }
            // Simulate the printed dot: each black cell bleeds a little into its neighbours, so the
            // preview darkens by the same amount the tones were pre-lightened for. Preview ≈ print.
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
        return done()
    }

    // Linear reflectance to sRGB (0..1)
    private fun srgb(r: Float) = if (r <= 0.0031308f) r * 12.92f else 1.055f * r.toDouble().pow(1 / 2.4).toFloat() - 0.055f

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

    // Scale of the (turned) photo in the area
    private fun fitScale(src: Bitmap, area: Rect, fit: Fit, rotation: Int): Pair<Float, Float> {
        val sw = if (rotation % 180 != 0) src.height else src.width
        val sh = if (rotation % 180 != 0) src.width else src.height
        val sx = area.width().toFloat() / sw
        val sy = area.height().toFloat() / sh
        if (fit == Fit.STRETCH) return sx to sy
        val s = if (fit == Fit.CONTAIN) minOf(sx, sy) else maxOf(sx, sy)
        return s to s
    }

    private fun drawFitted(canvas: Canvas, src: Bitmap, area: Rect, fit: Fit, rotation: Int) {
        val sw = if (rotation % 180 != 0) src.height else src.width
        val sh = if (rotation % 180 != 0) src.width else src.height
        var (sx, sy) = fitScale(src, area, fit, rotation)
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
    // The preview at screen size. Left to the ImageView, which scales by a non-integer factor with
    // bilinear sampling, regular patterns such as Amiga fine showed bands that are not on the paper.
    // So: enlarge by a whole factor without filtering, then area-average down to the screen size.
    fun forScreen(preview: Bitmap, dw: Int, dh: Int): Bitmap {
        if (dw <= 0 || dh <= 0 || dw == preview.width) return preview
        val k = (dw + preview.width - 1) / preview.width
        val big = if (k > 1) Bitmap.createScaledBitmap(preview, preview.width * k, preview.height * k, false) else preview
        return areaScale(big, dw, dh).also { if (big !== preview && big !== it) big.recycle() }
    }

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

    // Brightness (−60..60) and contrast (−50..50) as a table over 0..1 → 0..255.
    // The curves alone never reach paper white (or solid black) before the end of the range, so an
    // off-white background kept a sprinkle of dots at any setting. Raising brightness or contrast
    // therefore also clips the lightest tones to white (up to the top 15 % at the slider end, contrast
    // both ends by 10 %), lowering brightness the darkest to black.
    private fun toneCurve(brightness: Int, contrast: Int): FloatArray {
        val b = 2f.pow(-brightness / 60f)
        val c = 2f.pow(contrast / 50f)
        val lift = 0.15f * maxOf(0, brightness) / 60f
        val drop = 0.15f * maxOf(0, -brightness) / 60f
        val clip = 0.1f * maxOf(0, contrast) / 50f
        return FloatArray(1024) { k ->
            var x = ((k / 1023f - drop) / (1f - lift - drop)).coerceIn(0f, 1f)
            x = ((x - clip) / (1f - 2 * clip)).coerceIn(0f, 1f).pow(b)
            255f * if (x < 0.5f) 0.5f * (2f * x).pow(c) else 1f - 0.5f * (2f * (1f - x)).pow(c)
        }
    }

    // Prepare tones for thermal printing: auto contrast in the photo area, brightness/contrast,
    // gentle sharpening, and dot-gain compensation so the print matches the preview.
    // tone (optional) receives the image tone before the print correction, for the red band.
    // subject (optional, see subjectMask): the background is lightened or darkened (Background).
    private fun prepare(
        gray: FloatArray, w: Int, h: Int, area: Rect, o: Options, fade: FloatArray?, ink: Ink, tone: FloatArray?,
        subject: FloatArray?,
    ) {
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
        // Brightness and contrast as curves that keep black and white in place (as in photo editors):
        // brightness bends the mid-tones (exponent 2^∓1 at the slider ends), contrast is an S-curve
        // around mid-grey. A plain offset clipped whole skies to paper white within a few steps.
        val tones = toneCurve(o.brightness, o.contrast)
        val photo = o.raster != Raster.TEXT
        // Dot gain only matters for patterns of single dots; coarse cells and thick lines print true
        val fine = o.raster == Raster.DOTS || o.raster == Raster.SCANLINES || o.raster == Raster.SCANLINES_WIDE
        val curve = PrintCurve.lut(o.density, o.toneGamma, o.monoCurve)
        val gamma = o.toneGamma.toFloat()

        // Unsharp mask (3×3); kept light so scaled high-contrast edges do not bloom at full heat
        val src = gray.copyOf()
        for (y in r.top until r.bottom) for (x in r.left until r.right) {
            val i = y * w + x
            var v = src[i]
            if (photo && x > r.left && y > r.top && x < r.right - 1 && y < r.bottom - 1) {
                val blur = (src[i - 1] + src[i + 1] + src[i - w] + src[i + w] + 4 * v) / 8f
                v += 0.4f * (v - blur)
            }
            v = tones[(((v - lo) / (hi - lo)).coerceIn(0f, 1f) * (tones.size - 1)).roundToInt()]
            if (subject != null && subject[i] < 1f) {
                val b = o.bgStrength * (1f - subject[i])
                if (o.background == Background.LIGHTER) v += (255f - v) * b
                else if (o.background == Background.DARKER) v *= 1f - b
            }
            if (tone != null) {
                val t = if (o.invert) 255f - v.coerceIn(0f, 255f) else v.coerceIn(0f, 255f)
                tone[i] = if (fade != null) t * fade[i] + 255f * (1f - fade[i]) else t
            }
            if (ink == Ink.GRAY) {
                // The printer makes the gray levels itself, no dot pattern to correct: only the tone
                // target, darkness 1 − (v/255)^gamma spread evenly over the 16 levels
                v = 255f * (v.coerceIn(0f, 255f) / 255f).pow(gamma)
            } else if (measured(o)) {
                // Measured print curve (PrintCurve): dither input that prints the target darkness
                val k = v.coerceIn(0f, 255f)
                val k0 = k.toInt().coerceAtMost(254)
                v = curve[k0] + (curve[k0 + 1] - curve[k0]) * (k - k0)
            } else if (fine) {
                // Dot-gain compensation in ink-coverage space (d = 1 - v, 1 = full black). It also
                // applies in the highlights: the dither now puts dots there, and uncorrected they
                // printed light skin and white fabric grey.
                val d = (1f - v / 255f).coerceIn(0f, 1f)
                val di = d - DOT_GAIN.toFloat() * d * (1f - d)
                v = (1f - di) * 255f
            }
            v = if (o.invert) 255f - v else v.coerceIn(0f, 255f)
            gray[i] = if (fade != null) v * fade[i] + 255f * (1f - fade[i]) else v
        }
    }

    // The raster look on the prepared tones
    private fun toBits(gray: FloatArray, w: Int, h: Int, o: Options): ByteArray {
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
            // Amiga at full resolution: 8 × 8 Bayer on single dots, same pattern size as AMIGA but
            // 65 tones instead of 17. Single dots merge like the photo dither's, so it gets the
            // measured tone correction (MEASURED).
            Raster.BAYER -> for (y in 0 until h) for (x in 0 until w) {
                val t = (BAYER8[(y and 7) * 8 + (x and 7)] + 0.5f) / 64f * 255f
                bits[y * w + x] = if (gray[y * w + x] < t) 1 else 0
            }
            // Amiga / early PC: 4×4 Bayer ordered dither on 2×2 blocks, the typical cross-hatch
            // Blue noise: a fixed threshold map like Bayer, without the grid (Dither.blue)
            Raster.BLUE -> for (y in 0 until h) for (x in 0 until w) {
                bits[y * w + x] = if (gray[y * w + x] < Dither.blueThreshold(x, y)) 1 else 0
            }
            Raster.RIEMERSMA -> Dither.riemersma(gray, w, h, bits)
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

    // Classic 8 × 8 Bayer matrix, built from the 2 × 2 one (the finest coordinate bit counts most)
    private val BAYER8 = IntArray(64) { i ->
        val x = i % 8; val y = i / 8
        var v = 0
        for (bit in 0 until 3) {
            val bx = (x shr bit) and 1; val by = (y shr bit) and 1
            v = v * 4 + intArrayOf(0, 2, 3, 1)[by * 2 + bx]
        }
        v
    }
    // Looks whose tones are corrected with the measured print curve (PrintCurve / paper profile)
    val MEASURED = setOf(Raster.PHOTO, Raster.BAYER, Raster.BLUE, Raster.RIEMERSMA)

    // Coarse Amiga is corrected only with a curve measured for it: its 2 × 2 blocks merge far less
    // than single dots, so the photo curve would lighten it much too far
    fun measured(o: Options) = o.raster in MEASURED || (o.raster == Raster.AMIGA && o.monoCurve != null)

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

    // A flat tone through the photo dither, for the calibration chart
    // A flat tone in the dither of a look (PHOTO, BAYER, BLUE, RIEMERSMA, AMIGA), for the calibration chart.
    // The ordered looks use the print position (x0, y0), as in a photo.
    fun ditherFlat(tone: Float, w: Int, h: Int, look: Raster = Raster.PHOTO, x0: Int = 0, y0: Int = 0): ByteArray {
        val bits = ByteArray(w * h)
        val g = FloatArray(w * h) { tone }
        when (look) {
            Raster.BAYER, Raster.BLUE -> for (y in 0 until h) for (x in 0 until w) {
                val t = if (look == Raster.BLUE) Dither.blueThreshold(x0 + x, y0 + y)
                else (BAYER8[((y0 + y) and 7) * 8 + ((x0 + x) and 7)] + 0.5f) / 64f * 255f
                bits[y * w + x] = if (tone < t) 1 else 0
            }
            Raster.RIEMERSMA -> Dither.riemersma(g, w, h, bits)
            // 4 × 4 Bayer on 2 × 2 cells, the cells counted from the print origin as in toBits
            Raster.AMIGA -> for (y in 0 until h) for (x in 0 until w) {
                val cx = (x0 + x) / 2; val cy = (y0 + y) / 2
                bits[y * w + x] = if (tone < (BAYER4[(cy and 3) * 4 + (cx and 3)] + 0.5f) / 16f * 255f) 1 else 0
            }
            else -> atkinson(g, w, h, bits)
        }
        return bits
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
