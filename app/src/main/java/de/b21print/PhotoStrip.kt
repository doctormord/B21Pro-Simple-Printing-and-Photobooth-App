package de.b21print

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect

// Photo booth layouts: several shots on one label, like a classic photo booth strip
object PhotoStrip {
    const val SINGLE = 0
    const val STRIP3 = 1   // three shots one below the other
    const val GRID4 = 2    // 2 × 2

    fun dateText(): String = java.text.SimpleDateFormat("dd.MM.yyyy HH:mm", java.util.Locale.GERMANY).format(java.util.Date())

    fun count(mode: Int) = when (mode) { STRIP3 -> 3; GRID4 -> 4; else -> 1 }

    // Which modes the label shape allows: the strip needs a label taller than wide, 2 × 2 a
    // label that is not wider than tall. Round labels only take single shots; a window label counts with the size of its window.
    fun allowed(mode: Int, wMm: Double, hMm: Double, round: Boolean): Boolean {
        if (mode == SINGLE) return true
        if (round) return false
        val a = wMm / hMm
        return when (mode) {
            STRIP3 -> a < 0.95
            GRID4 -> a < 1.05
            else -> false
        }
    }

    class Plan(val tiles: List<Rect>, val footer: Rect?, val textScale: Int, val lines: List<String>)

    // Pixel-font text of one or two lines: one line if it fits at 2 dots per pixel or more, else
    // split at the space nearest the middle
    fun wrap(text: String, avail: Int): List<String> {
        val t = text.trim().uppercase()
        if (t.length * 6 * 2 <= avail || t.length < 2) return listOf(t)
        val mid = t.length / 2
        val cut = (0 until t.length).filter { t[it] == ' ' }.minByOrNull { Math.abs(it - mid) } ?: mid
        return listOf(t.substring(0, cut).trim(), t.substring(cut).trim()).filter { it.isNotEmpty() }
    }

    fun plan(w: Int, h: Int, mode: Int, footer: String?): Plan {
        if (mode == SINGLE) return Plan(listOf(Rect(0, 0, w, h)), null, 0, emptyList())
        val b = maxOf(4, (minOf(w, h) * 0.05).toInt())
        var scale = 0; var lines = emptyList<String>(); var fh = 0
        if (!footer.isNullOrBlank()) {
            lines = wrap(footer, w - 2 * b)
            scale = maxOf(1, minOf((w - 2 * b) / (6 * lines.maxOf { it.length }), maxOf(3, w / 150)))
            fh = (lines.size * 8 - 1) * scale + b
        }
        val innerW = w - 2 * b
        val innerH = h - 2 * b - fh
        val tiles = ArrayList<Rect>()
        when (mode) {
            STRIP3 -> {
                val th = (innerH - 2 * b) / 3
                for (i in 0 until 3) tiles += Rect(b, b + i * (th + b), b + innerW, b + i * (th + b) + th)
            }
            else -> {
                val tw = (innerW - b) / 2; val th = (innerH - b) / 2
                for (i in 0 until 4) {
                    val x = b + (i % 2) * (tw + b); val y = b + (i / 2) * (th + b)
                    tiles += Rect(x, y, x + tw, y + th)
                }
            }
        }
        val f = if (lines.isNotEmpty()) Rect(b, h - fh, w - b, h - b) else null
        return Plan(tiles, f, scale, lines)
    }

    // Width in dots of a line set in the pixel font
    fun textWidth(text: String, s: Int) = maxOf(0, text.length * 6 - 1) * s

    // Draws text in the pixel font into a canvas
    fun drawText(c: Canvas, text: String, x0: Int, y0: Int, s: Int, ink: Paint) {
        var x = x0
        for (ch in text.uppercase()) {
            TestChart.FONT[ch]?.let { g ->
                for ((ry, row) in g.withIndex()) for ((rx, v) in row.withIndex()) if (v == '#')
                    c.drawRect((x + rx * s).toFloat(), (y0 + ry * s).toFloat(),
                        (x + rx * s + s).toFloat(), (y0 + ry * s + s).toFloat(), ink)
            }
            x += 6 * s
        }
    }

    // Aspect (width / height) of one shot, so the viewfinder can frame it
    fun tileAspect(w: Int, h: Int, mode: Int, footer: String?): Float {
        val t = plan(w, h, mode, footer).tiles[0]
        return t.width().toFloat() / t.height()
    }

    // Cover: the centre part of each picture with the tile's aspect
    private fun drawTiles(c: Canvas, p: Plan, photos: List<Bitmap>) {
        val paint = Paint(Paint.FILTER_BITMAP_FLAG)
        for ((i, t) in p.tiles.withIndex()) {
            val src = photos[i]
            val ta = t.width().toFloat() / t.height()
            val sa = src.width.toFloat() / src.height
            val sw = if (sa > ta) (src.height * ta).toInt() else src.width
            val sh = if (sa > ta) src.height else (src.width / ta).toInt()
            val sx = (src.width - sw) / 2; val sy = (src.height - sh) / 2
            c.drawBitmap(src, Rect(sx, sy, sx + sw, sy + sh), t, paint)
        }
    }

    // The subject masks laid out like the pictures. Frame and footer count as subject (white), so a
    // background effect leaves them alone: a black frame must not turn red with the background.
    fun composeMask(masks: List<Bitmap>, w: Int, h: Int, mode: Int, footer: String?): Bitmap {
        val p = plan(w, h, mode, footer)
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        c.drawColor(Color.WHITE)
        drawTiles(c, p, masks)
        return out
    }

    fun compose(photos: List<Bitmap>, w: Int, h: Int, mode: Int, black: Boolean, footer: String?): Bitmap {
        val p = plan(w, h, mode, footer)
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        val bg = if (black) Color.BLACK else Color.WHITE
        c.drawColor(bg)
        drawTiles(c, p, photos)
        p.footer?.let { f ->
            val ink = Paint().apply { color = if (black) Color.WHITE else Color.BLACK }
            val s = p.textScale
            var y = f.top
            for (line in p.lines) {
                drawText(c, line, f.left + (f.width() - textWidth(line, s)) / 2, y, s, ink)
                y += 8 * s
            }
        }
        return out
    }
}
