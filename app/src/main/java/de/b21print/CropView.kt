package de.b21print

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.util.TypedValue
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs

// Image with a movable, resizable crop rectangle. aspect = width/height, null = free.
class CropView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {
    var bitmap: Bitmap? = null
        set(v) { field = v; pendingNorm = null; layoutImage(); invalidate() }

    var aspect: Float? = null
        set(v) { field = v; resetRect(); invalidate() }

    var round = false
        set(v) { field = v; invalidate() }

    private val imgRect = RectF()
    private val crop = RectF()
    private var pendingNorm: RectF? = null

    private fun dp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics)

    private val bmpPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val dimPaint = Paint().apply { color = 0xA0000000.toInt() }
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; style = Paint.Style.STROKE; strokeWidth = dp(1.5f)
    }
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x80FFFFFF.toInt(); style = Paint.Style.STROKE; strokeWidth = dp(0.8f)
    }
    private val handlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; style = Paint.Style.STROKE; strokeWidth = dp(4f); strokeCap = Paint.Cap.ROUND
    }

    // Crop relative to the image (0..1), e.g. to restore an earlier crop
    fun setNormRect(r: RectF?) {
        pendingNorm = r
        layoutImage()
        invalidate()
    }

    fun normRect(): RectF = RectF(
        (crop.left - imgRect.left) / imgRect.width(), (crop.top - imgRect.top) / imgRect.height(),
        (crop.right - imgRect.left) / imgRect.width(), (crop.bottom - imgRect.top) / imgRect.height(),
    )

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        if (pendingNorm == null && imgRect.width() > 0) pendingNorm = normRect()
        layoutImage()
    }

    private fun layoutImage() {
        val b = bitmap ?: return
        if (width == 0 || height == 0) return
        val pad = dp(24f)
        val aw = width - 2 * pad
        val ah = height - 2 * pad
        val s = minOf(aw / b.width, ah / b.height)
        val w = b.width * s
        val h = b.height * s
        imgRect.set((width - w) / 2, (height - h) / 2, (width + w) / 2, (height + h) / 2)
        val n = pendingNorm
        if (n != null) {
            crop.set(
                imgRect.left + n.left * imgRect.width(), imgRect.top + n.top * imgRect.height(),
                imgRect.left + n.right * imgRect.width(), imgRect.top + n.bottom * imgRect.height(),
            )
            pendingNorm = null
        } else {
            resetRect()
        }
    }

    // Largest centered crop with the requested aspect ratio
    fun resetRect() {
        if (imgRect.width() <= 0) return
        val a = aspect
        if (a == null) {
            crop.set(imgRect)
            crop.inset(imgRect.width() * 0.08f, imgRect.height() * 0.08f)
        } else {
            var w = imgRect.width()
            var h = w / a
            if (h > imgRect.height()) { h = imgRect.height(); w = h * a }
            crop.set(imgRect.centerX() - w / 2, imgRect.centerY() - h / 2, imgRect.centerX() + w / 2, imgRect.centerY() + h / 2)
        }
    }

    override fun onDraw(canvas: Canvas) {
        val b = bitmap ?: return
        canvas.drawBitmap(b, null, imgRect, bmpPaint)

        // Dim the outside
        val path = Path().apply {
            fillType = Path.FillType.EVEN_ODD
            addRect(imgRect, Path.Direction.CW)
            if (round) addOval(crop, Path.Direction.CW) else addRect(crop, Path.Direction.CW)
        }
        canvas.drawPath(path, dimPaint)

        // Rule-of-thirds lines and border
        for (i in 1..2) {
            val x = crop.left + crop.width() * i / 3
            val y = crop.top + crop.height() * i / 3
            canvas.drawLine(x, crop.top, x, crop.bottom, gridPaint)
            canvas.drawLine(crop.left, y, crop.right, y, gridPaint)
        }
        canvas.drawRect(crop, linePaint)
        if (round) canvas.drawOval(crop, linePaint)

        // Corner handles
        val l = dp(18f)
        for ((cx, cy) in corners()) {
            val sx = if (cx == crop.left) 1 else -1
            val sy = if (cy == crop.top) 1 else -1
            canvas.drawLine(cx, cy, cx + sx * l, cy, handlePaint)
            canvas.drawLine(cx, cy, cx, cy + sy * l, handlePaint)
        }
    }

    private fun corners() = listOf(
        crop.left to crop.top, crop.right to crop.top, crop.left to crop.bottom, crop.right to crop.bottom,
    )

    private var dragCorner = -1
    private var moving = false
    private var lastX = 0f
    private var lastY = 0f

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val hit = dp(36f)
                dragCorner = corners().indexOfFirst { (cx, cy) -> abs(e.x - cx) < hit && abs(e.y - cy) < hit }
                moving = dragCorner < 0 && crop.contains(e.x, e.y)
                lastX = e.x; lastY = e.y
                parent?.requestDisallowInterceptTouchEvent(true)
                return dragCorner >= 0 || moving
            }
            MotionEvent.ACTION_MOVE -> {
                if (dragCorner >= 0) resizeTo(e.x, e.y)
                else if (moving) moveBy(e.x - lastX, e.y - lastY)
                lastX = e.x; lastY = e.y
                invalidate()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { dragCorner = -1; moving = false }
        }
        return true
    }

    private fun moveBy(dx: Float, dy: Float) {
        val ox = dx.coerceIn(imgRect.left - crop.left, imgRect.right - crop.right)
        val oy = dy.coerceIn(imgRect.top - crop.top, imgRect.bottom - crop.bottom)
        crop.offset(ox, oy)
    }

    private fun resizeTo(x: Float, y: Float) {
        // The opposite corner stays fixed
        val ax = if (dragCorner == 0 || dragCorner == 2) crop.right else crop.left
        val ay = if (dragCorner == 0 || dragCorner == 1) crop.bottom else crop.top
        val px = x.coerceIn(imgRect.left, imgRect.right)
        val py = y.coerceIn(imgRect.top, imgRect.bottom)
        val min = dp(48f)
        var w = maxOf(min, abs(px - ax))
        var h = maxOf(min, abs(py - ay))
        aspect?.let { a -> if (w / h > a) w = h * a else h = w / a }
        val sx = if (dragCorner == 0 || dragCorner == 2) -1 else 1
        val sy = if (dragCorner == 0 || dragCorner == 1) -1 else 1
        // Not beyond the image edge
        val maxW = if (sx > 0) imgRect.right - ax else ax - imgRect.left
        val maxH = if (sy > 0) imgRect.bottom - ay else ay - imgRect.top
        val k = minOf(1f, maxW / w, maxH / h)
        w *= k; h *= k
        crop.set(minOf(ax, ax + sx * w), minOf(ay, ay + sy * h), maxOf(ax, ax + sx * w), maxOf(ay, ay + sy * h))
    }
}
