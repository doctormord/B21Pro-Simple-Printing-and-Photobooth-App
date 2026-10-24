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
) {
    fun toJson(): JSONObject = JSONObject()
        .put("text", text).put("font", font).put("bold", bold).put("italic", italic)
        .put("underline", underline).put("frame", frame).put("label", label)

    companion object {

        fun fromJson(o: JSONObject) = TextStyle(
            o.optString("text"), o.optInt("font"), o.optBoolean("bold", true), o.optBoolean("italic"),
            o.optBoolean("underline"), o.optInt("frame"), o.optString("label"),
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
