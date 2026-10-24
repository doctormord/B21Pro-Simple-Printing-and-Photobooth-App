package de.b21print

import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.RectF
import android.os.Bundle
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup

// Shared image holder; too large for an Intent
object ImageStore {
    var original: Bitmap? = null
    var rotation = 0
    var norm: RectF? = null
    var free = false
    var cropped: Bitmap? = null

    fun reset(b: Bitmap) {
        original = b; rotation = 0; norm = null; free = false; cropped = null
    }

    fun rotated(): Bitmap? {
        val b = original ?: return null
        if (rotation == 0) return b
        val m = Matrix().apply { postRotate(rotation.toFloat()) }
        return Bitmap.createBitmap(b, 0, 0, b.width, b.height, m, true)
    }
}

class CropActivity : AppCompatActivity() {
    private lateinit var crop: CropView
    private var stickerAspect = 1f

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_crop)
        crop = findViewById(R.id.cropView)
        stickerAspect = intent.getFloatExtra(EXTRA_ASPECT, 1f)
        crop.round = intent.getBooleanExtra(EXTRA_ROUND, false)
        findViewById<TextView>(R.id.cropSubtitle).text = intent.getStringExtra(EXTRA_LABEL).orEmpty()

        val bmp = ImageStore.rotated() ?: return finish()
        crop.bitmap = bmp
        crop.aspect = if (ImageStore.free) null else stickerAspect
        crop.setNormRect(ImageStore.norm)

        val toggle = findViewById<MaterialButtonToggleGroup>(R.id.toggleCropMode)
        toggle.check(if (ImageStore.free) R.id.cropFree else R.id.cropSticker)
        toggle.addOnButtonCheckedListener { _, id, checked ->
            if (!checked) return@addOnButtonCheckedListener
            ImageStore.free = id == R.id.cropFree
            crop.aspect = if (ImageStore.free) null else stickerAspect
        }
        findViewById<MaterialButton>(R.id.btnCropRotate).setOnClickListener {
            ImageStore.rotation = (ImageStore.rotation + 90) % 360
            crop.bitmap = ImageStore.rotated()
            crop.aspect = if (ImageStore.free) null else stickerAspect
        }
        findViewById<MaterialButton>(R.id.btnCropCancel).setOnClickListener { finish() }
        findViewById<MaterialButton>(R.id.btnCropDone).setOnClickListener {
            val b = crop.bitmap ?: return@setOnClickListener
            val n = crop.normRect()
            ImageStore.norm = n
            val x = (n.left * b.width).toInt().coerceIn(0, b.width - 1)
            val y = (n.top * b.height).toInt().coerceIn(0, b.height - 1)
            val w = (n.width() * b.width).toInt().coerceIn(1, b.width - x)
            val h = (n.height() * b.height).toInt().coerceIn(1, b.height - y)
            ImageStore.cropped = Bitmap.createBitmap(b, x, y, w, h)
            setResult(RESULT_OK)
            finish()
        }
    }

    companion object {
        const val EXTRA_ASPECT = "aspect"
        const val EXTRA_ROUND = "round"
        const val EXTRA_LABEL = "label"
    }
}
