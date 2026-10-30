package de.b21print

import android.graphics.Bitmap
import android.graphics.Color
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.segmentation.Segmentation
import com.google.mlkit.vision.segmentation.selfie.SelfieSegmenterOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.math.max
import kotlin.math.roundToInt

// Finds the person in a picture with ML Kit's selfie segmenter (the model ships inside the app and
// runs on the device) and returns it in the form of a depth map: gray, 255 = subject (near),
// 0 = background, soft at the edge. The background options of the photo pipeline take it from there.
object SubjectMask {
    // The model works at 256 px; more input only costs time
    private const val INPUT = 640
    // Less of the picture than this counts as no subject found
    private const val MIN_SUBJECT = 0.015f

    private val segmenter by lazy {
        Segmentation.getClient(
            SelfieSegmenterOptions.Builder().setDetectorMode(SelfieSegmenterOptions.SINGLE_IMAGE_MODE).build(),
        )
    }

    // Null if the model fails or finds no person. Same aspect as src, at most INPUT px on the long side.
    suspend fun find(src: Bitmap): Bitmap? {
        val s = minOf(1f, INPUT.toFloat() / max(src.width, src.height))
        val small = if (s < 1f) Bitmap.createScaledBitmap(src, max(1, (src.width * s).roundToInt()), max(1, (src.height * s).roundToInt()), true) else src
        val mask = suspendCancellableCoroutine<com.google.mlkit.vision.segmentation.SegmentationMask?> { c ->
            segmenter.process(InputImage.fromBitmap(small, 0))
                .addOnSuccessListener { c.resume(it) }
                .addOnFailureListener { c.resume(null) }
        } ?: return null
        val w = mask.width; val h = mask.height
        val buf = mask.buffer.asFloatBuffer()
        var subject = 0
        val px = IntArray(w * h) {
            val v = (buf.get(it) * 255f).roundToInt().coerceIn(0, 255)
            if (v > 127) subject++
            Color.rgb(v, v, v)
        }
        // Nobody in the picture: a mask of background only would blank the whole print
        if (subject < w * h * MIN_SUBJECT) return null
        return Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888)
    }
}
