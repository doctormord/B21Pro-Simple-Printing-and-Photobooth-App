package de.b21print

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.media.MediaActionSound
import android.os.Bundle
import android.util.AttributeSet
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import androidx.core.view.children
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.progressindicator.LinearProgressIndicator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// Handed over by MainActivity: the connected printer and the print settings chosen there
object Booth {
    class Config(
        val label: LabelPreset,
        val widthMm: Double,
        val heightMm: Double,
        val photoAspect: Float,
        val options: ImageProcessor.Options,
        val density: Int,
        val twoColorRoll: Boolean,
        val labelType: Int,
        val copies: Int,
        val orient: Int = 0,
        val stampMode: Int = 0,
        val stampText: String = "",
    ) {
        // Strips always carry a line at the bottom: the typed text, else the date
        fun footer() = if (stampMode == 2 && stampText.isNotBlank()) stampText else PhotoStrip.dateText()
    }

    var printer: B21Printer? = null
    var config: Config? = null
    // Point to the MainActivity currently shown (a new instance after rotation)
    var log: ((String) -> Unit)? = null
    var onDisconnected: (() -> Unit)? = null
    // Last picture taken (cut to the frame), picked up by MainActivity when the booth closes
    var lastPhoto: Bitmap? = null
}

// Dims everything outside the label area so you see what will be printed
class BoothOverlay(context: Context, attrs: AttributeSet?) : View(context, attrs) {
    var aspect = 1f
        set(v) { field = v; requestLayout(); invalidate() }
    var round = false
        set(v) { field = v; invalidate() }
    val frame = RectF()

    private val dp = resources.displayMetrics.density
    private val scrim = Paint().apply { color = Color.argb(150, 0, 0, 0) }
    private val clear = Paint(Paint.ANTI_ALIAS_FLAG).apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR) }
    private val border = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; color = Color.WHITE; strokeWidth = 3 * dp
    }

    init { setLayerType(LAYER_TYPE_HARDWARE, null) }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        super.onLayout(changed, l, t, r, b)
        // Space between top bar and shutter
        val left = 24 * dp
        val top = 84 * dp
        val right = width - 24 * dp
        val bottom = height - 270 * dp
        val aw = right - left
        val ah = bottom - top
        val a = if (round) 1f else aspect
        val w = if (aw / ah > a) ah * a else aw
        val h = w / a
        val cx = (left + right) / 2
        val cy = (top + bottom) / 2
        frame.set(cx - w / 2, cy - h / 2, cx + w / 2, cy + h / 2)
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), scrim)
        val r = 8 * dp
        if (round) {
            canvas.drawOval(frame, clear)
            canvas.drawOval(frame, border)
        } else {
            canvas.drawRoundRect(frame, r, r, clear)
            canvas.drawRoundRect(frame, r, r, border)
        }
    }
}

class BoothActivity : AppCompatActivity() {
    private val scope = MainScope()
    private val prefs by lazy { getSharedPreferences("b21", MODE_PRIVATE) }
    private val sound = MediaActionSound()

    private lateinit var previewView: PreviewView
    private lateinit var overlay: BoothOverlay
    private lateinit var shutter: View
    private lateinit var txtCountdown: TextView
    private lateinit var flash: View
    private lateinit var result: View
    private lateinit var resultCard: MaterialCardView
    private lateinit var resultImage: ImageView
    private lateinit var txtStatus: TextView
    private lateinit var progress: LinearProgressIndicator
    private lateinit var resultActions: View
    private lateinit var btnTimer: MaterialButton
    private lateinit var btnFlash: MaterialButton
    private lateinit var btnLeft: MaterialButton
    private lateinit var btnRight: MaterialButton

    // Result view: review first, then print
    private enum class Stage { REVIEW, PRINTING, DONE }
    private var stage = Stage.REVIEW

    private var cameraProvider: ProcessCameraProvider? = null
    private var front = true
    private var busy = false
    private var shot: ImageProcessor.Result? = null
    private var dismissJob: Job? = null

    private val timerSteps = intArrayOf(0, 3, 5, 10)
    private var mode: Int
        get() = prefs.getInt("boothMode", 0)
        set(v) = prefs.edit().putInt("boothMode", v).apply()
    private var blackFrame: Boolean
        get() = prefs.getBoolean("boothBlack", false)
        set(v) = prefs.edit().putBoolean("boothBlack", v).apply()
    private lateinit var txtHint: TextView
    private lateinit var toggleMode: com.google.android.material.button.MaterialButtonToggleGroup
    private lateinit var toggleFrame: com.google.android.material.button.MaterialButtonToggleGroup
    private val modeIds = intArrayOf(R.id.boothMode0, R.id.boothMode1, R.id.boothMode2)
    // What the strip layout fills: the label, or the window of a window label
    private val areaWmm get() = Booth.config!!.let { it.label.window?.w ?: it.widthMm }
    private val areaHmm get() = Booth.config!!.let { it.label.window?.h ?: it.heightMm }
    private val labelW get() = ImageProcessor.mmToPx(areaWmm).let {
        if (Booth.config!!.orient == 0 && Booth.config!!.label.window == null) minOf(Niimbot.PRINTHEAD_PIXELS, it) else it }
    private val labelH get() = ImageProcessor.mmToPx(areaHmm)

    private var timer: Int
        get() = prefs.getInt("boothTimer", 3)
        set(v) = prefs.edit().putInt("boothTimer", v).apply()
    private var screenFlash: Boolean
        get() = prefs.getBoolean("boothFlash", true)
        set(v) = prefs.edit().putBoolean("boothFlash", v).apply()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val cfg = Booth.config ?: return finish()
        setContentView(R.layout.activity_booth)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }

        // If the system bars are shown, keep the controls clear of them
        val top = findViewById<View>(R.id.boothTop)
        val bottom = findViewById<View>(R.id.boothBottom)
        val pad = top.paddingTop
        val padBottom = bottom.paddingBottom
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.boothRoot)) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            top.setPadding(pad + bars.left, pad + bars.top, pad + bars.right, top.paddingBottom)
            bottom.setPadding(bottom.paddingLeft, bottom.paddingTop, bottom.paddingRight, padBottom + bars.bottom)
            insets
        }

        previewView = findViewById(R.id.cameraPreview)
        overlay = findViewById(R.id.boothOverlay)
        shutter = findViewById(R.id.btnShutter)
        txtCountdown = findViewById(R.id.txtCountdown)
        flash = findViewById(R.id.boothFlash)
        result = findViewById(R.id.boothResult)
        resultCard = findViewById(R.id.boothResultCard)
        resultImage = findViewById(R.id.boothResultImage)
        txtStatus = findViewById(R.id.txtBoothStatus)
        progress = findViewById(R.id.boothProgress)
        resultActions = findViewById(R.id.boothResultActions)
        btnTimer = findViewById(R.id.btnBoothTimer)
        btnFlash = findViewById(R.id.btnBoothFlash)
        btnLeft = findViewById(R.id.btnBoothAgain)
        btnRight = findViewById(R.id.btnBoothNext)

        overlay.round = cfg.label.round
        txtHint = findViewById(R.id.txtBoothHint)
        toggleMode = findViewById(R.id.toggleBoothMode)
        toggleFrame = findViewById(R.id.toggleBoothFrame)
        for ((i, id) in modeIds.withIndex()) {
            val ok = PhotoStrip.allowed(i, areaWmm, areaHmm, cfg.label.round)
            findViewById<View>(id).isEnabled = ok
            if (!ok && mode == i) mode = 0
        }
        // Modes that do not fit the label are greyed out; hide the row when only single is left
        toggleMode.visibility = if (modeIds.drop(1).any { findViewById<View>(it).isEnabled }) View.VISIBLE else View.GONE
        toggleMode.check(modeIds[mode])
        toggleFrame.check(if (blackFrame) R.id.boothFrameBlack else R.id.boothFrameWhite)
        toggleMode.addOnButtonCheckedListener { _, id, checked ->
            if (checked && !busy) { mode = modeIds.indexOf(id); updateMode() }
        }
        toggleFrame.addOnButtonCheckedListener { _, id, checked ->
            if (checked) { blackFrame = id == R.id.boothFrameBlack; updateMode() }
        }
        updateMode()
        val size = if (cfg.label.round) getString(R.string.size_round, fmt(cfg.widthMm))
            else getString(R.string.size_rect, fmt(cfg.widthMm), fmt(cfg.heightMm))
        val extras = buildList {
            if (cfg.options.instant) add("Instant")
            if (cfg.copies > 1) add("${cfg.copies}×")
        }.joinToString(" · ")
        findViewById<TextView>(R.id.txtBoothInfo).text =
            "${cfg.label.title(this)} · $size" + if (extras.isNotEmpty()) "\n$extras" else ""

        sound.load(MediaActionSound.SHUTTER_CLICK)
        updateTimerButton()
        btnTimer.setOnClickListener {
            timer = timerSteps[(timerSteps.indexOf(timer) + 1) % timerSteps.size]
            updateTimerButton()
        }
        findViewById<View>(R.id.btnBoothClose).setOnClickListener { onBackPressedDispatcher.onBackPressed() }
        // Do not leave while printing, the job would stop halfway
        onBackPressedDispatcher.addCallback(this, object : androidx.activity.OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when {
                    result.visibility == View.VISIBLE && stage == Stage.PRINTING -> toast(getString(R.string.still_printing))
                    result.visibility == View.VISIBLE -> hideResult()
                    else -> finish()
                }
            }
        })
        findViewById<View>(R.id.btnBoothFlip).setOnClickListener { front = !front; bindCamera() }
        shutter.setOnClickListener { trigger() }
        btnFlash.setOnClickListener { screenFlash = !screenFlash; updateFlashButton() }
        updateFlashButton()
        // Left: delete / print again · right: print / next photo
        btnLeft.setOnClickListener {
            if (stage == Stage.DONE) shot?.let { printResult(it) } else hideResult()
        }
        btnRight.setOnClickListener {
            if (stage == Stage.DONE) hideResult() else shot?.let { printResult(it) }
        }

        if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) startCamera()
        else requestPermissions(arrayOf(Manifest.permission.CAMERA), REQ_CAMERA_PERM)
    }

    override fun onDestroy() {
        scope.cancel()
        sound.release()
        super.onDestroy()
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(code, perms, results)
        if (code != REQ_CAMERA_PERM) return
        if (results.firstOrNull() == PackageManager.PERMISSION_GRANTED) startCamera()
        else { toast(getString(R.string.camera_permission)); finish() }
    }

    // Volume keys and Bluetooth selfie remotes trigger as well
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_VOLUME_DOWN, KeyEvent.KEYCODE_CAMERA,
            KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_SPACE -> {
                if (event.repeatCount == 0) {
                    if (result.visibility != View.VISIBLE) trigger()
                    else if (stage == Stage.DONE) hideResult()
                }
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    // Viewfinder frames one shot; the frame choice only matters for strip and grid
    private fun updateMode() {
        val cfg = Booth.config ?: return
        overlay.aspect = if (mode == PhotoStrip.SINGLE) cfg.photoAspect
            else PhotoStrip.tileAspect(labelW, labelH, mode, cfg.footer())
        toggleFrame.visibility = if (mode == PhotoStrip.SINGLE) View.GONE else View.VISIBLE
        txtHint.setText(R.string.booth_hint)
    }

    private fun fmt(v: Double) = if (v % 1.0 == 0.0) v.toInt().toString() else "%.1f".format(v)
    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    private fun updateTimerButton() {
        btnTimer.text = if (timer == 0) getString(R.string.timer_off) else getString(R.string.timer_seconds, timer)
    }

    private fun updateFlashButton() {
        // Icon-only toggle: full opacity = flash on, dimmed = off
        btnFlash.contentDescription = getString(if (screenFlash) R.string.flash_on else R.string.flash_off)
        btnFlash.alpha = if (screenFlash) 1f else 0.45f
    }

    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            cameraProvider = future.get()
            val p = cameraProvider ?: return@addListener
            front = p.hasCamera(CameraSelector.DEFAULT_FRONT_CAMERA)
            findViewById<View>(R.id.btnBoothFlip).visibility =
                if (front && p.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA)) View.VISIBLE else View.GONE
            bindCamera()
        }, ContextCompat.getMainExecutor(this))
    }

    private fun bindCamera() {
        val p = cameraProvider ?: return
        val selector = if (front) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA
        val preview = Preview.Builder().build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
        try {
            p.unbindAll()
            p.bindToLifecycle(this, selector, preview)
        } catch (e: Exception) {
            toast(getString(R.string.camera_unavailable, e.message))
        }
    }

    private fun trigger() {
        if (busy || result.visibility == View.VISIBLE) return
        busy = true
        shutter.isEnabled = false
        toggleMode.children.forEach { it.isEnabled = false }
        scope.launch {
            val cfg = Booth.config ?: return@launch
            val n = PhotoStrip.count(mode)
            val shots = ArrayList<Bitmap>()
            for (i in 0 until n) {
                if (n > 1) txtHint.text = getString(R.string.booth_shot_n, i + 1, n)
                // Later shots get at least a short countdown, even with the timer off
                val secs = if (i == 0) timer else maxOf(timer, 2)
                val bmp = capture(secs)
                if (bmp == null) {
                    toast(getString(R.string.no_camera_image))
                    break
                }
                shots += withContext(Dispatchers.Default) { cropToFrame(bmp) }
            }
            if (shots.size < n) { finishSeries(); return@launch }
            val r = withContext(Dispatchers.Default) {
                val label = if (n == 1) shots[0] else PhotoStrip.compose(shots, labelW, labelH, mode, blackFrame, cfg.footer())
                Booth.lastPhoto = label
                // The composed label already has its frame, no instant frame on top
                val opts = if (n == 1) cfg.options.copy(stamp = if (cfg.stampMode == 1) PhotoStrip.dateText() else cfg.options.stamp)
                    else cfg.options.copy(instant = false, caption = "", softEdge = false, stamp = "")
                ImageProcessor.render(label, cfg.widthMm, cfg.heightMm, cfg.label.window, cfg.label.frameColor,
                    opts, cfg.label.round, null,
                    if (cfg.orient == 0) Niimbot.PRINTHEAD_PIXELS else Int.MAX_VALUE).withRot(cfg.orient)
            }
            shot = r
            txtHint.setText(R.string.booth_hint)
            showResult(r)
        }
    }

    private fun finishSeries() {
        busy = false; shutter.isEnabled = true
        toggleMode.children.forEachIndexed { i, v ->
            v.isEnabled = PhotoStrip.allowed(i, areaWmm, areaHmm, Booth.config!!.label.round)
        }
        txtHint.setText(R.string.booth_hint)
    }

    // Countdown, flash and grab of the preview as it looks on screen (mirrored for the front camera)
    private suspend fun capture(secs: Int): Bitmap? {
        for (n in secs downTo 1) {
            txtCountdown.text = n.toString()
            txtCountdown.visibility = View.VISIBLE
            txtCountdown.scaleX = 1.4f; txtCountdown.scaleY = 1.4f; txtCountdown.alpha = 1f
            txtCountdown.animate().scaleX(1f).scaleY(1f).alpha(0.6f).setDuration(900).start()
            delay(1000)
        }
        txtCountdown.visibility = View.GONE
        val useFlash = screenFlash
        if (useFlash) {
            // Screen flash: white at full brightness, wait for auto exposure to settle
            window.attributes = window.attributes.apply { screenBrightness = 1f }
            flash.animate().cancel()
            flash.alpha = 1f
            delay(550)
        }
        val bmp = previewView.bitmap
        sound.play(MediaActionSound.SHUTTER_CLICK)
        if (useFlash) {
            window.attributes = window.attributes.apply {
                screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
            }
        } else flash.alpha = 0.9f
        flash.animate().alpha(0f).setDuration(450).start()
        return bmp
    }

    private fun cropToFrame(b: Bitmap): Bitmap {
        // previewView and overlay have the same size, so frame coordinates map directly
        val sx = b.width.toFloat() / previewView.width
        val sy = b.height.toFloat() / previewView.height
        val f = overlay.frame
        val x = (f.left * sx).toInt().coerceIn(0, b.width - 1)
        val y = (f.top * sy).toInt().coerceIn(0, b.height - 1)
        val w = (f.width() * sx).toInt().coerceIn(1, b.width - x)
        val h = (f.height() * sy).toInt().coerceIn(1, b.height - y)
        return Bitmap.createBitmap(b, x, y, w, h)
    }

    private fun showResult(r: ImageProcessor.Result) {
        dismissJob?.cancel()
        resultImage.setImageBitmap(r.preview)
        // Fit the label large but fully visible
        val root = findViewById<View>(R.id.boothRoot)
        val maxW = root.width * 0.7f
        val maxH = root.height * 0.55f
        val s = minOf(maxW / r.preview.width, maxH / r.preview.height)
        resultCard.layoutParams = resultCard.layoutParams.apply {
            width = (r.preview.width * s).toInt(); height = (r.preview.height * s).toInt()
        }
        resultCard.radius = if (Booth.config?.label?.round == true) minOf(resultCard.layoutParams.width, resultCard.layoutParams.height) / 2f
            else 6 * resources.displayMetrics.density
        setStage(Stage.REVIEW)
        txtStatus.setText(R.string.booth_review)
        result.alpha = 0f
        result.visibility = View.VISIBLE
        result.animate().alpha(1f).setDuration(250).start()
    }

    private fun setStage(st: Stage) {
        stage = st
        progress.visibility = if (st == Stage.PRINTING) View.VISIBLE else View.INVISIBLE
        resultActions.visibility = if (st == Stage.PRINTING) View.INVISIBLE else View.VISIBLE
        if (st == Stage.DONE) {
            btnLeft.setText(R.string.print_again); btnLeft.setIconResource(R.drawable.ic_print)
            btnRight.setText(R.string.next_photo); btnRight.setIconResource(R.drawable.ic_camera)
        } else {
            btnLeft.setText(R.string.delete); btnLeft.setIconResource(R.drawable.ic_close)
            btnRight.setText(R.string.print); btnRight.setIconResource(R.drawable.ic_print)
        }
    }

    private fun hideResult() {
        dismissJob?.cancel()
        result.animate().alpha(0f).setDuration(200).withEndAction { result.visibility = View.GONE }.start()
        finishSeries()
    }

    private fun printResult(r: ImageProcessor.Result) {
        val p = Booth.printer ?: return toast(getString(R.string.printer_not_connected))
        val cfg = Booth.config ?: return
        dismissJob?.cancel()
        setStage(Stage.PRINTING)
        progress.setProgressCompat(0, false)
        txtStatus.setText(R.string.booth_printing)
        scope.launch {
            val ok = try {
                p.printResult(r, cfg.twoColorRoll, cfg.density, cfg.labelType, cfg.copies) { f ->
                    runOnUiThread { progress.setProgressCompat((f * 1000).toInt(), true) }
                }
                true
            } catch (e: Exception) {
                txtStatus.text = getString(R.string.print_error, e.message)
                false
            }
            // On error go back to review so it can be retried
            setStage(if (ok) Stage.DONE else Stage.REVIEW)
            if (ok) {
                val title = "${cfg.label.title(this@BoothActivity)} · Booth"
                withContext(Dispatchers.IO) {
                    runCatching { PrintArchive.save(this@BoothActivity, listOf(r), r.preview, title, cfg.twoColorRoll,
                        cfg.density, cfg.labelType, cfg.copies) }
                }
                txtStatus.setText(R.string.booth_done)
                // Return to the camera after a short while
                dismissJob = scope.launch { delay(8000); hideResult() }
            }
        }
    }

    companion object {
        const val REQ_CAMERA_PERM = 11
    }
}
