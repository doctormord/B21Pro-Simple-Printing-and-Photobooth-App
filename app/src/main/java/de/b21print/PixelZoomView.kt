package de.b21print

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View

/** Shows a bitmap with hard pixel edges; pinch zooms, one finger moves it. */
class PixelZoomView(context: Context) : View(context) {
    private var bmp: Bitmap? = null
    private val m = Matrix()
    private val paint = Paint().apply { isFilterBitmap = false }
    private var lastX = 0f
    private var lastY = 0f
    private val scaler = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(d: ScaleGestureDetector): Boolean {
            m.postScale(d.scaleFactor, d.scaleFactor, d.focusX, d.focusY)
            invalidate(); return true
        }
    })

    fun setBitmap(b: Bitmap) { bmp = b; fit() }

    private fun fit() {
        val b = bmp ?: return
        if (width == 0) return
        val s = minOf(width.toFloat() / b.width, height.toFloat() / b.height)
        m.setScale(s, s); m.postTranslate((width - b.width * s) / 2, (height - b.height * s) / 2)
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) { fit() }

    override fun onDraw(c: Canvas) {
        c.drawColor(0xFF202020.toInt())
        bmp?.let { c.drawBitmap(it, m, paint) }
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        scaler.onTouchEvent(e)
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { lastX = e.x; lastY = e.y; parent.requestDisallowInterceptTouchEvent(true) }
            MotionEvent.ACTION_MOVE -> if (!scaler.isInProgress && e.pointerCount == 1) {
                m.postTranslate(e.x - lastX, e.y - lastY); invalidate(); lastX = e.x; lastY = e.y
            }
            MotionEvent.ACTION_POINTER_UP, MotionEvent.ACTION_UP -> { lastX = e.x; lastY = e.y }
        }
        return true
    }
}
