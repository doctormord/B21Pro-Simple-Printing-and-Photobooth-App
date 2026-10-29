package de.b21print

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import org.json.JSONObject

// Sign: short text, always centered and as large as possible
data class TextStyle(
    val text: String,
    val font: Int = 0,      // 0 clean, 1 classic, 2 hand
    val bold: Boolean = true,
    val italic: Boolean = false,
    val underline: Boolean = false,
    val frame: Int = 0,     // 0 none, 1 line, 2 bold, 3 double, 4 dashed
    val label: String = "",
    val qr: Boolean = false,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("text", text).put("font", font).put("bold", bold).put("italic", italic)
        .put("underline", underline).put("frame", frame).put("label", label).put("qr", qr)

    companion object {

        fun fromJson(o: JSONObject) = TextStyle(
            o.optString("text"), o.optInt("font"), o.optBoolean("bold", true), o.optBoolean("italic"),
            o.optBoolean("underline"), o.optInt("frame"), o.optString("label"), o.optBoolean("qr"),
        )

        fun typeface(font: Int, bold: Boolean, italic: Boolean): Typeface {
            val family = when (font) {
                1 -> Typeface.SERIF
                2 -> Typeface.create("casual", Typeface.NORMAL)
                else -> Typeface.SANS_SERIF
            }
            val style = when {
                bold && italic -> Typeface.BOLD_ITALIC
                bold -> Typeface.BOLD
                italic -> Typeface.ITALIC
                else -> Typeface.NORMAL
            }
            return Typeface.create(family, style)
        }
    }
}

object TextLabel {
    private fun mm(v: Double) = ImageProcessor.mmToPx(v).toFloat()

    // Draws frame and text into a w×h image (print head coordinates).
    // area: the label in those coordinates (partly outside for wide labels)
    fun draw(canvas: Canvas, area: RectF, round: Boolean, s: TextStyle) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; style = Paint.Style.STROKE }

        // Frame slightly inset so it stays within the printable area
        val inset = mm(1.5)
        val frameBox = RectF(area).apply { inset(inset, inset) }
        val thin = mm(0.35)
        fun shape(r: RectF, stroke: Float, dashed: Boolean = false) {
            paint.strokeWidth = stroke
            paint.pathEffect = if (dashed) DashPathEffect(floatArrayOf(mm(1.2), mm(0.8)), 0f) else null
            val half = stroke / 2
            val rr = RectF(r).apply { inset(half, half) }
            if (round) canvas.drawOval(rr, paint)
            else canvas.drawRoundRect(rr, mm(2.0), mm(2.0), paint)
        }
        var textBox = RectF(frameBox)
        when (s.frame) {
            1 -> shape(frameBox, thin)
            2 -> shape(frameBox, mm(1.0))
            3 -> {
                shape(frameBox, thin)
                shape(RectF(frameBox).apply { inset(mm(0.9), mm(0.9)) }, thin)
            }
            4 -> shape(frameBox, mm(0.5), dashed = true)
        }
        if (s.frame != 0) textBox.inset(mm(2.5), mm(2.0))
        if (round) {
            // Largest rectangle in the circle, a bit wider than tall
            val r = minOf(textBox.width(), textBox.height()) / 2
            val hw = r * 0.82f
            val hh = r * 0.57f
            textBox = RectF(textBox.centerX() - hw, textBox.centerY() - hh, textBox.centerX() + hw, textBox.centerY() + hh)
        }

        val text = s.text.trim()
        if (text.isEmpty()) return
        if (s.qr) { drawQr(canvas, textBox, text); return }
        val tp = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            typeface = TextStyle.typeface(s.font, s.bold, s.italic)
            isUnderlineText = s.underline
            textAlign = Paint.Align.CENTER
            // The handwriting font has no real italic
            if (s.italic && s.font == 2) textSkewX = -0.2f
        }

        // Pick the best layout: 1–3 lines, largest possible font
        var best: Pair<List<String>, Float>? = null
        for (lines in candidates(text)) {
            tp.textSize = 100f
            val widest = lines.maxOf { tp.measureText(it) }
            val fm = tp.fontMetrics
            val lineH = fm.descent - fm.ascent
            // Slanted text extends past the measured width
            val slant = if (s.italic) 0.9f else 1f
            val size = 100f * minOf(textBox.width() * slant / widest, textBox.height() / (lineH * lines.size))
            if (best == null || size > best.second) best = lines to size
        }
        val (lines, size) = best ?: return
        tp.textSize = size
        val fm = tp.fontMetrics
        val lineH = fm.descent - fm.ascent
        var y = textBox.centerY() - lineH * lines.size / 2 - fm.ascent
        for (line in lines) {
            canvas.drawText(line, textBox.centerX(), y, tp)
            y += lineH
        }
    }

    // Smallest module (dots) that is still reliable on thermal paper
    private const val QR_MIN_MODULE = 3
    private const val QR_QUIET = 2

    // Standard QR code, as large as fits with whole dots per module. The error correction is the
    // highest of Q, M, L whose modules still print at QR_MIN_MODULE dots or more; a short text
    // therefore gets plenty of redundancy and a long one only as much as the size allows.
    private fun drawQr(canvas: Canvas, box: RectF, text: String) {
        val levels = listOf(com.google.zxing.qrcode.decoder.ErrorCorrectionLevel.Q,
            com.google.zxing.qrcode.decoder.ErrorCorrectionLevel.M, com.google.zxing.qrcode.decoder.ErrorCorrectionLevel.L)
        val avail = minOf(box.width(), box.height())
        var chosen: com.google.zxing.qrcode.encoder.QRCode? = null
        var module = 0
        for (level in levels) {
            val hints = mapOf(
                com.google.zxing.EncodeHintType.ERROR_CORRECTION to level,
                com.google.zxing.EncodeHintType.CHARACTER_SET to "UTF-8",
            )
            val code = runCatching { com.google.zxing.qrcode.encoder.Encoder.encode(text, level, hints) }.getOrNull() ?: continue
            val m = (avail / (code.matrix.width + 2 * QR_QUIET)).toInt()
            if (chosen == null || m >= QR_MIN_MODULE) { chosen = code; module = m }
            if (m >= QR_MIN_MODULE) break
        }
        val code = chosen ?: return
        if (module < 1) return
        val n = code.matrix.width
        val side = n * module
        val x0 = Math.round(box.centerX() - side / 2f).toFloat()
        val y0 = Math.round(box.centerY() - side / 2f).toFloat()
        val p = Paint().apply { color = Color.BLACK; style = Paint.Style.FILL }
        for (y in 0 until n) for (x in 0 until n) if (code.matrix.get(x, y).toInt() == 1) {
            canvas.drawRect(x0 + x * module, y0 + y * module, x0 + (x + 1) * module, y0 + (y + 1) * module, p)
        }
    }

    private fun candidates(text: String): List<List<String>> {
        val explicit = text.split('\n').map { it.trim() }.filter { it.isNotEmpty() }
        if (explicit.size > 1) return listOf(explicit)
        val words = text.split(Regex("\\s+"))
        val out = mutableListOf(listOf(text))
        if (words.size >= 2) out.add(balanced(words, 2))
        if (words.size >= 3) out.add(balanced(words, 3))
        return out
    }

    // Distribute words over n lines so the longest line is as short as possible
    private fun balanced(words: List<String>, n: Int): List<String> {
        var bestLines = listOf(words.joinToString(" "))
        var bestLen = Int.MAX_VALUE
        fun rec(start: Int, left: Int, acc: List<String>) {
            if (left == 1) {
                val lines = acc + words.subList(start, words.size).joinToString(" ")
                val len = lines.maxOf { it.length }
                if (len < bestLen) { bestLen = len; bestLines = lines }
                return
            }
            for (end in start + 1..words.size - left + 1) {
                rec(end, left - 1, acc + words.subList(start, end).joinToString(" "))
            }
        }
        rec(0, n, emptyList())
        return bestLines
    }

    fun render(s: TextStyle, w: Int, h: Int, area: RectF, round: Boolean): ByteArray {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(Color.WHITE)
        draw(c, area, round, s)
        val px = IntArray(w * h)
        bmp.getPixels(px, 0, w, 0, 0, w, h)
        bmp.recycle()
        return ByteArray(w * h) { if (Color.red(px[it]) < 140) 1 else 0 }
    }
}
