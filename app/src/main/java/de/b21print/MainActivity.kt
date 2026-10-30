package de.b21print

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.RectF
import android.graphics.ImageDecoder
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.speech.RecognizerIntent
import android.util.TypedValue
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.core.widget.NestedScrollView
import androidx.core.widget.doAfterTextChanged
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.card.MaterialCardView
import com.google.android.material.chip.Chip
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.google.android.material.slider.Slider
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@SuppressLint("MissingPermission")
class MainActivity : AppCompatActivity() {
    private val scope = MainScope()
    private var printer: B21Printer? = null
    private var source: Bitmap? = null
    // Depth map of source (portrait photos, see DepthMap), cropped like it
    private var depth: Bitmap? = null
    private var rendered: ImageProcessor.Result? = null
    private var renderJob: Job? = null
    private var photoUri: Uri? = null
    private var paperIndex = 0
    private var textMode = false
    private var selectedLabel: LabelPreset = Labels.NB835
    private var suppressEdits = false
    private val labelCards = mutableListOf<Pair<LabelPreset, MaterialCardView>>()

    private val prefs by lazy { getSharedPreferences("b21", MODE_PRIVATE) }
    private val history by lazy { History(prefs) }
    private val bluetooth by lazy { getSystemService(BluetoothManager::class.java).adapter }

    private lateinit var chipPrinter: Chip
    private lateinit var txtBattery: TextView
    private var battery: Niimbot.Battery? = null
    private var rollAutoRed = false
    private val sliderPano by lazy { findViewById<Slider>(R.id.sliderPano) }
    private val sliderPanoGap by lazy { findViewById<Slider>(R.id.sliderPanoGap) }
    private var batteryJob: Job? = null
    private var printing = false
    private lateinit var txtDims: TextView
    private lateinit var txtLog: TextView
    private lateinit var preview: ImageView
    private lateinit var labelCard: MaterialCardView
    private lateinit var btnPrint: MaterialButton
    private lateinit var btnSaveTemplate: MaterialButton
    private lateinit var progress: LinearProgressIndicator
    private lateinit var editWidth: TextInputEditText
    private lateinit var editHeight: TextInputEditText
    private lateinit var editCaption: TextInputEditText
    private lateinit var editCopies: TextInputEditText
    private lateinit var editText: TextInputEditText
    private lateinit var switchInstant: MaterialSwitch
    private lateinit var switchSoftEdge: MaterialSwitch
    private lateinit var switchOverscan: MaterialSwitch
    private lateinit var switchInvert: MaterialSwitch
    private lateinit var switchRed: MaterialSwitch
    private lateinit var sliderRed: com.google.android.material.slider.RangeSlider
    private lateinit var sliderDepthCut: Slider
    private lateinit var sliderBgStrength: Slider
    private var raster = ImageProcessor.Raster.PHOTO
    private var background = ImageProcessor.Background.SAME
    private var bgRaster = ImageProcessor.Raster.BLUE
    // The calibration chart is pre-dithered and must reach the printer unchanged
    private var calibration = false
    // Tone sweep: the photo in vertical stripes with different tone targets, to pick one on paper
    private var sweep = false
    // Paper profiles; null = the built-in defaults
    private val profiles by lazy { PaperProfile.load(prefs) }
    private var profile: PaperProfile? = null
    // The roll in the printer, from its RFID tag (null: no tag, or not connected)
    private var roll: B21Printer.Roll? = null
    // While the calibration dialog is open: receives the photo of the chart
    private var calPhoto: ((Uri) -> Unit)? = null
    private lateinit var toggleFont: MaterialButtonToggleGroup
    private lateinit var toggleStyle: MaterialButtonToggleGroup
    private lateinit var toggleFrame: MaterialButtonToggleGroup
    private lateinit var toggleTextColor: MaterialButtonToggleGroup
    private lateinit var toggleFrameColor: MaterialButtonToggleGroup
    private lateinit var sliderBrightness: Slider
    private lateinit var sliderContrast: Slider
    private lateinit var sliderDensity: Slider
    private lateinit var sliderOffsetX: Slider
    private lateinit var sliderOffsetY: Slider

    private val fontIds = listOf(R.id.font0, R.id.font1, R.id.font2)
    private val frameIds = listOf(R.id.frame0, R.id.frame1, R.id.frame2, R.id.frame3, R.id.frame4)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        chipPrinter = findViewById(R.id.chipPrinter)
        txtBattery = findViewById(R.id.txtBattery)
        txtDims = findViewById(R.id.txtDims)
        txtLog = findViewById(R.id.txtLog)
        preview = findViewById(R.id.preview)
        labelCard = findViewById(R.id.labelCard)
        btnPrint = findViewById(R.id.btnPrint)
        btnSaveTemplate = findViewById(R.id.btnSaveTemplate)
        progress = findViewById(R.id.progress)
        editWidth = findViewById(R.id.editWidth)
        editHeight = findViewById(R.id.editHeight)
        editCaption = findViewById(R.id.editCaption)
        editCopies = findViewById(R.id.editCopies)
        editText = findViewById(R.id.editText)
        switchInstant = findViewById(R.id.switchInstant)
        switchSoftEdge = findViewById(R.id.switchSoftEdge)
        switchOverscan = findViewById(R.id.switchOverscan)
        switchRed = findViewById(R.id.switchRed)
        sliderRed = findViewById(R.id.sliderRed)
        sliderDepthCut = findViewById(R.id.sliderDepthCut)
        sliderBgStrength = findViewById(R.id.sliderBgStrength)
        switchInvert = findViewById(R.id.switchInvert)
        toggleFont = findViewById(R.id.toggleFont)
        toggleStyle = findViewById(R.id.toggleStyle)
        toggleFrame = findViewById(R.id.toggleFrame)
        toggleTextColor = findViewById(R.id.toggleTextColor)
        toggleFrameColor = findViewById(R.id.toggleFrameColor)
        sliderBrightness = findViewById(R.id.sliderBrightness)
        sliderContrast = findViewById(R.id.sliderContrast)
        sliderDensity = findViewById(R.id.sliderDensity)
        sliderOffsetX = findViewById(R.id.sliderOffsetX)
        sliderOffsetY = findViewById(R.id.sliderOffsetY)

        chipPrinter.setOnClickListener {
            if (printer != null) printer?.disconnect() else ensurePermissions { scanAndConnect() }
        }
        findViewById<MaterialButton>(R.id.btnCamera).setOnClickListener { takePhoto() }
        findViewById<MaterialButton>(R.id.btnGallery).setOnClickListener {
            startActivityForResult(Intent(Intent.ACTION_GET_CONTENT).setType("image/*"), REQ_GALLERY)
        }
        findViewById<MaterialButton>(R.id.btnCrop).setOnClickListener { openCrop() }
        watchKeyboard()
        btnPrint.setOnClickListener { print() }
        findViewById<View>(R.id.btnPrints).setOnClickListener { showPrints() }

        // Photo / sign
        val toggleApp = findViewById<MaterialButtonToggleGroup>(R.id.toggleAppMode)
        toggleApp.addOnButtonCheckedListener { group, id, checked ->
            if (!checked) return@addOnButtonCheckedListener
            if (id == R.id.modeAppBooth) {
                // The booth is not a mode: keep the current one selected and open it
                group.check(if (textMode) R.id.modeAppText else R.id.modeAppPhoto)
                openBooth()
                return@addOnButtonCheckedListener
            }
            textMode = id == R.id.modeAppText
            prefs.edit().putBoolean("textMode", textMode).apply()
            applyModeVisibility()
            scheduleRender()
        }

        // Sign
        findViewById<TextInputLayout>(R.id.textLayout).setEndIconOnClickListener { startSpeech() }
        editText.doAfterTextChanged { scheduleRender(); updateTemplateButton() }
        findViewById<MaterialSwitch>(R.id.switchQr).setOnCheckedChangeListener { _, _ -> applyModeVisibility(); scheduleRender(); updateTemplateButton() }
        toggleFont.check(fontIds[prefs.getInt("font", 0).coerceIn(0, 2)])
        if (prefs.getBoolean("bold", true)) toggleStyle.check(R.id.styleBold)
        toggleFrame.check(frameIds[prefs.getInt("frame", 0).coerceIn(0, 4)])
        val styleChanged = MaterialButtonToggleGroup.OnButtonCheckedListener { _, _, _ ->
            applyModeVisibility()
            prefs.edit().putInt("font", fontIds.indexOf(toggleFont.checkedButtonId).coerceAtLeast(0))
                .putInt("frame", frameIds.indexOf(toggleFrame.checkedButtonId).coerceAtLeast(0))
                .putBoolean("bold", R.id.styleBold in toggleStyle.checkedButtonIds).apply()
            scheduleRender()
            updateTemplateButton()
        }
        toggleTextColor.check(if (prefs.getInt("textColor", 0) == 1) R.id.textRed else R.id.textBlack)
        toggleFrameColor.check(if (prefs.getInt("textFrameColor", 0) == 1) R.id.frameRed else R.id.frameBlack)
        val colourChanged = MaterialButtonToggleGroup.OnButtonCheckedListener { _, _, _ ->
            prefs.edit().putInt("textColor", if (toggleTextColor.checkedButtonId == R.id.textRed) 1 else 0)
                .putInt("textFrameColor", if (toggleFrameColor.checkedButtonId == R.id.frameRed) 1 else 0).apply()
            scheduleRender()
            updateTemplateButton()
        }
        toggleTextColor.addOnButtonCheckedListener(colourChanged)
        toggleFrameColor.addOnButtonCheckedListener(colourChanged)
        listOf(toggleFont, toggleStyle, toggleFrame).forEach { it.addOnButtonCheckedListener(styleChanged) }
        findViewById<MaterialButton>(R.id.btnHistory).setOnClickListener { showHistory() }
        btnSaveTemplate.setOnClickListener {
            val s = currentTextStyle()
            if (s.text.isBlank()) return@setOnClickListener toast(getString(R.string.print_need_text))
            toast(getString(if (history.toggleTemplate(s)) R.string.template_saved else R.string.template_removed))
            updateTemplateButton()
        }

        // Print
        // Paper type: a choice dialog instead of a dropdown, which had no room to open above the print bar
        val paperNames = resources.getStringArray(R.array.paper)
        val dropPaper = findViewById<TextInputEditText>(R.id.dropPaper)
        paperIndex = prefs.getInt("paper", 0).coerceIn(0, paperNames.size - 1)
        dropPaper.setText(paperNames[paperIndex])
        val pickPaper = View.OnClickListener {
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.paper_type)
                .setSingleChoiceItems(paperNames, paperIndex) { d, pos ->
                    paperIndex = pos
                    prefs.edit().putInt("paper", pos).apply()
                    dropPaper.setText(paperNames[pos])
                    d.dismiss()
                }
                .show()
        }
        dropPaper.setOnClickListener(pickPaper)
        findViewById<TextInputLayout>(R.id.paperLayout).setEndIconOnClickListener(pickPaper)
        profile = profiles.firstOrNull { it.id == prefs.getString("profile", null) }
        showProfile()
        findViewById<TextInputEditText>(R.id.dropProfile).setOnClickListener { pickProfile() }
        findViewById<TextInputLayout>(R.id.profileLayout).setEndIconOnClickListener { pickProfile() }
        val txtDensity = findViewById<TextView>(R.id.txtDensity)
        sliderDensity.value = prefs.getInt("density", 2).toFloat()
        txtDensity.text = sliderDensity.value.toInt().toString()
        sliderDensity.addOnChangeListener { _, v, _ ->
            txtDensity.text = v.toInt().toString()
            prefs.edit().putInt("density", v.toInt()).apply()
            // Keep the settings printed on the calibration chart current
            if (calibration) loadTestChart(calibrate = true) else scheduleRender()
        }
        val toggleSpeed = findViewById<MaterialButtonToggleGroup>(R.id.toggleSpeed)
        toggleSpeed.check(if (prefs.getInt("speed", 1) == 0) R.id.speedFast else R.id.speedFine)
        toggleSpeed.addOnButtonCheckedListener { _, id, checked ->
            if (checked) prefs.edit().putInt("speed", if (id == R.id.speedFast) 0 else 1).apply()
            if (checked) { if (calibration) loadTestChart(calibrate = true) else scheduleRender() }
        }
        // Two-colour roll: a band of image tones prints red, the rest black
        val redBand = findViewById<View>(R.id.redBand)
        val txtRedBand = findViewById<TextView>(R.id.txtRedBand)
        fun showRedBand() {
            val (lo, hi) = sliderRed.values.let { it[0].toInt() to it[1].toInt() }
            txtRedBand.text = getString(R.string.red_band, lo, hi)
        }
        switchRed.isChecked = prefs.getBoolean("redRoll", false)
        redBand.visibility = if (switchRed.isChecked) View.VISIBLE else View.GONE
        switchRed.setOnCheckedChangeListener { _, on ->
            prefs.edit().putBoolean("redRoll", on).apply()
            roll?.let { prefs.edit().putBoolean("twoColor_${it.barcode}", on).apply() }
            toggleSpeed.isEnabled = !on
            // The toggle keeps its saved state while red is on; show it again (and print with it) when red goes off
            if (!on) toggleSpeed.check(if (prefs.getInt("speed", 1) == 0) R.id.speedFast else R.id.speedFine)
            showDepthDetails()
            applyModeVisibility()
            scheduleRender()
        }
        toggleSpeed.isEnabled = !switchRed.isChecked
        sliderRed.values = listOf(prefs.getFloat("redLo", 50f), prefs.getFloat("redHi", 100f))
        showRedBand()
        sliderRed.addOnChangeListener { sl, _, _ ->
            prefs.edit().putFloat("redLo", sl.values[0]).putFloat("redHi", sl.values[1]).apply()
            showRedBand()
            scheduleRender()
        }

        // Label
        editWidth.doAfterTextChanged { onSizeEdited() }
        editHeight.doAfterTextChanged { onSizeEdited() }
        editCaption.doAfterTextChanged { scheduleRender() }
        switchInstant.setOnCheckedChangeListener { _, _ -> applyModeVisibility(); scheduleRender() }
        // Stamp: date or short text bottom right; black instant frame
        val stampIds = listOf(R.id.stamp0, R.id.stamp1, R.id.stamp2)
        val toggleStamp = findViewById<MaterialButtonToggleGroup>(R.id.toggleStamp)
        val toggleStampColor = findViewById<MaterialButtonToggleGroup>(R.id.toggleStampColor)
        val editStamp = findViewById<TextInputEditText>(R.id.editStamp)
        val switchInstantBlack = findViewById<MaterialSwitch>(R.id.switchInstantBlack)
        toggleStamp.check(stampIds[prefs.getInt("stampMode", 0).coerceIn(0, 2)])
        toggleStampColor.check(if (prefs.getBoolean("stampWhite", false)) R.id.stampWhite else R.id.stampBlack)
        editStamp.setText(prefs.getString("stampText", ""))
        switchInstantBlack.isChecked = prefs.getBoolean("instantBlack", false)
        toggleStamp.addOnButtonCheckedListener { _, id, on ->
            if (!on) return@addOnButtonCheckedListener
            prefs.edit().putInt("stampMode", stampIds.indexOf(id)).apply()
            applyModeVisibility(); scheduleRender()
        }
        toggleStampColor.addOnButtonCheckedListener { _, id, on ->
            if (!on) return@addOnButtonCheckedListener
            prefs.edit().putBoolean("stampWhite", id == R.id.stampWhite).apply(); scheduleRender()
        }
        editStamp.doAfterTextChanged { prefs.edit().putString("stampText", it?.toString().orEmpty()).apply(); scheduleRender() }
        switchInstantBlack.setOnCheckedChangeListener { _, on ->
            prefs.edit().putBoolean("instantBlack", on).apply(); scheduleRender()
        }
        val txtPano = findViewById<TextView>(R.id.txtPano)
        val txtPanoGap = findViewById<TextView>(R.id.txtPanoGap)
        sliderPano.addOnChangeListener { _, v, _ ->
            txtPano.text = if (v.toInt() == 1) getString(R.string.pano_off) else v.toInt().toString()
            applyModeVisibility(); scheduleRender()
        }
        sliderPanoGap.addOnChangeListener { _, v, _ ->
            txtPanoGap.text = getString(R.string.pano_gap_mm, v); scheduleRender()
        }
        val toggleOrient = findViewById<MaterialButtonToggleGroup>(R.id.toggleOrient)
        toggleOrient.check(intArrayOf(R.id.orient0, R.id.orient1, R.id.orient2)[prefs.getInt("orient", 0).coerceIn(0, 2)])
        toggleOrient.addOnButtonCheckedListener { _, id, checked ->
            if (!checked) return@addOnButtonCheckedListener
            prefs.edit().putInt("orient", intArrayOf(R.id.orient0, R.id.orient1, R.id.orient2).indexOf(id)).apply()
            // A crop made for the other orientation is refitted in scheduleRender
            scheduleRender()
        }
        switchOverscan.isChecked = prefs.getBoolean("overscan", false)
        switchOverscan.setOnCheckedChangeListener { _, on ->
            prefs.edit().putBoolean("overscan", on).apply()
            scheduleRender()
        }
        switchSoftEdge.isChecked = prefs.getBoolean("softEdge", false)
        switchSoftEdge.setOnCheckedChangeListener { _, on ->
            prefs.edit().putBoolean("softEdge", on).apply()
            scheduleRender()
        }
        // Fine-tuning is stored per label (calibrate once)
        val txtOffX = findViewById<TextView>(R.id.txtOffsetX)
        val txtOffY = findViewById<TextView>(R.id.txtOffsetY)
        // Folded away under one line that shows the current values
        val btnOffsets = findViewById<MaterialButton>(R.id.btnOffsets)
        val offsetBox = findViewById<View>(R.id.offsetBox)
        fun showOffsets() {
            val open = offsetBox.visibility == View.VISIBLE
            btnOffsets.text = getString(R.string.offsets, sliderOffsetX.value, sliderOffsetY.value) + if (open) "  ▴" else "  ▾"
        }
        btnOffsets.setOnClickListener {
            offsetBox.visibility = if (offsetBox.visibility == View.VISIBLE) View.GONE else View.VISIBLE
            showOffsets()
        }
        txtOffX.text = "%+.1f mm".format(0f)
        txtOffY.text = "%+.1f mm".format(0f)
        showOffsets()
        sliderOffsetX.addOnChangeListener { _, v, fromUser ->
            txtOffX.text = "%+.1f mm".format(v)
            showOffsets()
            if (fromUser) prefs.edit().putFloat("offX_${selectedLabel.code}", v).apply()
            scheduleRender()
        }
        sliderOffsetY.addOnChangeListener { _, v, fromUser ->
            txtOffY.text = "%+.1f mm".format(v)
            showOffsets()
            if (fromUser) prefs.edit().putFloat("offY_${selectedLabel.code}", v).apply()
            scheduleRender()
        }

        // Image
        switchInvert.setOnCheckedChangeListener { _, _ -> scheduleRender() }
        val rasterNames = resources.getStringArray(R.array.raster)
        val dropRaster = findViewById<TextInputEditText>(R.id.dropRaster)
        raster = ImageProcessor.Raster.entries.firstOrNull { it.name == prefs.getString("raster", null) }
            ?: ImageProcessor.Raster.PHOTO
        dropRaster.setText(rasterNames[raster.ordinal])
        val pickRaster = View.OnClickListener {
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.raster)
                .setSingleChoiceItems(rasterNames, raster.ordinal) { d, pos ->
                    raster = ImageProcessor.Raster.entries[pos]
                    prefs.edit().putString("raster", raster.name).apply()
                    dropRaster.setText(rasterNames[pos])
                    d.dismiss()
                    scheduleRender()
                }
                .show()
        }
        dropRaster.setOnClickListener(pickRaster)
        findViewById<TextInputLayout>(R.id.rasterLayout).setEndIconOnClickListener(pickRaster)
        val txtBrightness = findViewById<TextView>(R.id.txtBrightness)
        val txtContrast = findViewById<TextView>(R.id.txtContrast)
        txtBrightness.text = "%+d".format(sliderBrightness.value.toInt())
        txtContrast.text = "%+d".format(sliderContrast.value.toInt())
        sliderBrightness.addOnChangeListener { _, v, _ -> txtBrightness.text = "%+d".format(v.toInt()); scheduleRender() }
        sliderContrast.addOnChangeListener { _, v, _ -> txtContrast.text = "%+d".format(v.toInt()); scheduleRender() }
        buildFavorites()
        setupDepth()
        findViewById<MaterialButton>(R.id.btnResetTone).setOnClickListener {
            sliderBrightness.value = 0f
            sliderContrast.value = 0f
        }

        findViewById<TextView>(R.id.txtVersion).text =
            runCatching { packageManager.getPackageInfo(packageName, 0).versionName }.getOrNull().orEmpty()
        findViewById<TextView>(R.id.title).setOnLongClickListener { showDebugConsole(); true }
        buildLabelList()
        selectLabel(Labels.ALL.firstOrNull { it.name == prefs.getString("label", null) } ?: Labels.NB835)
        if (prefs.getBoolean("textMode", false)) toggleApp.check(R.id.modeAppText)
        // Printer and image survive rotation and theme changes; restored before the controls are
        // shown, or the depth section stayed hidden while its effect was still applied
        if (savedInstanceState != null) { source = ImageStore.cropped; depth = ImageStore.croppedDepth }
        applyModeVisibility()

        // Image shared from another app
        // Printer and image survive rotation
        Booth.log = ::log
        Booth.onDisconnected = {
            runOnUiThread {
                printer = null
                stopBatteryPolling()
                updatePrinterChip()
                log("Disconnected")
            }
        }
        printer = Booth.printer

        if (savedInstanceState == null && intent?.action == Intent.ACTION_SEND) {
            toggleApp.check(R.id.modeAppPhoto)
            @Suppress("DEPRECATION")
            (intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM))?.let { loadImage(it) }
        }
        updatePrinterChip()
        updateTemplateButton()
    }

    override fun onDestroy() {
        // Rotation recreates the activity; keep the connection in that case
        if (isFinishing) {
            stopBatteryPolling()
            printer?.disconnect()
            Booth.printer = null
        }
        Booth.log = null
        Booth.onDisconnected = null
        scope.cancel()
        super.onDestroy()
    }

    private fun dp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics)
    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    private fun fmt(v: Double) = if (v % 1.0 == 0.0) v.toInt().toString() else "%.1f".format(v)

    // Debug console log, while it is open
    private var consoleSink: ((String) -> Unit)? = null

    private fun log(msg: String) = runOnUiThread {
        android.util.Log.i("B21", msg)   // also readable with: adb logcat -s B21
        val t = SimpleDateFormat("HH:mm:ss", Locale.GERMANY).format(Date())
        txtLog.text = "$t  $msg\n${txtLog.text}"
        consoleSink?.invoke(msg)
    }

    private fun applyModeVisibility() {
        val photo = !textMode
        val win = selectedLabel.window != null
        fun show(id: Int, on: Boolean) { findViewById<View>(id).visibility = if (on) View.VISIBLE else View.GONE }
        show(R.id.photoActions, photo)
        show(R.id.btnCrop, photo && ImageStore.original != null)
        show(R.id.imageSection, photo)
        show(R.id.textCard, !photo)
        // Colours of a sign on a two-colour roll: the text (not for a QR code) and the frame (if there is one)
        val qr = findViewById<MaterialSwitch>(R.id.switchQr).isChecked
        show(R.id.textColorBox, !photo && switchRed.isChecked && (!qr || toggleFrame.checkedButtonId != R.id.frame0))
        show(R.id.toggleTextColor, !qr)
        show(R.id.toggleFrameColor, toggleFrame.checkedButtonId != R.id.frame0)
        show(R.id.windowTuning, win)
        show(R.id.switchInstant, photo && !win && !selectedLabel.round)
        show(R.id.toggleOrient, !win && !selectedLabel.round && physSizeMm().let { it.first != it.second })
        show(R.id.switchSoftEdge, photo)
        show(R.id.depthBox, photo && depth != null)
        showDepthDetails()
        show(R.id.switchOverscan, photo && selectedLabel.round)
        show(R.id.captionLayout, photo && !win && instantOn())
        show(R.id.panoBox, !win && !selectedLabel.round)
        show(R.id.switchInstantBlack, photo && !win && !selectedLabel.round && instantOn())
        val stampOk = photo && !selectedLabel.round
        show(R.id.toggleStamp, stampOk)
        show(R.id.stampLayout, stampOk && prefs.getInt("stampMode", 0) == 2)
        show(R.id.toggleStampColor, stampOk && prefs.getInt("stampMode", 0) != 0 && (win || !instantOn()))
        show(R.id.panoGapBox, panoN() > 1)
    }

    // ---------- Label list ----------

    private fun buildLabelList() {
        val list = findViewById<LinearLayout>(R.id.labelList)
        val inflater = LayoutInflater.from(this)
        for (preset in Labels.ALL) {
            val card = inflater.inflate(R.layout.item_label, list, false) as MaterialCardView
            card.findViewById<TextView>(R.id.name).text = preset.title(this)
            card.findViewById<TextView>(R.id.size).text = when {
                preset.freestyle -> getString(R.string.own_size)
                preset.round -> getString(R.string.size_round, fmt(preset.widthMm))
                else -> getString(R.string.size_rect, fmt(preset.widthMm), fmt(preset.heightMm))
            }
            val shape = card.findViewById<View>(R.id.shape)
            val s = dp(40f) / maxOf(preset.widthMm, preset.heightMm).toFloat()
            shape.layoutParams = FrameLayout.LayoutParams(
                (preset.widthMm * s).toInt(), (preset.heightMm * s).toInt(), Gravity.CENTER,
            )
            shape.background = labelIcon(preset)
            card.setOnClickListener { selectLabel(preset) }
            list.addView(card)
            labelCards.add(preset to card)
        }
    }

    private fun labelIcon(preset: LabelPreset): Drawable {
        val outline = MaterialColors.getColor(this, com.google.android.material.R.attr.colorOutline, Color.GRAY)
        val primary = MaterialColors.getColor(this, androidx.appcompat.R.attr.colorPrimary, Color.RED)
        val frame = GradientDrawable().apply {
            setColor(Color.WHITE)
            if (preset.round) shape = GradientDrawable.OVAL else cornerRadius = dp(3f)
            if (preset.freestyle) setStroke(dp(1.5f).toInt(), primary, dp(4f), dp(3f))
            else setStroke(dp(1f).toInt(), outline)
        }
        preset.window?.let { win ->
            val film = GradientDrawable().apply { setColor(preset.frameColor ?: Color.DKGRAY); cornerRadius = dp(2f) }
            val hole = GradientDrawable().apply { setColor(Color.WHITE) }
            val s = dp(40f) / maxOf(preset.widthMm, preset.heightMm).toFloat()
            return LayerDrawable(arrayOf(film, hole)).apply {
                setLayerInset(1, (win.x * s).toInt(), (win.y * s).toInt(),
                    ((preset.widthMm - win.x - win.w) * s).toInt(), ((preset.heightMm - win.y - win.h) * s).toInt())
            }
        }
        if (!preset.instant) return frame
        val photo = GradientDrawable().apply { setColor(primary); cornerRadius = dp(1f) }
        return LayerDrawable(arrayOf(frame, photo)).apply {
            val side = dp(4f).toInt()
            setLayerInset(1, side, side, side, dp(12f).toInt())
        }
    }

    private fun selectLabel(preset: LabelPreset) {
        selectedLabel = preset
        prefs.edit().putString("label", preset.name).apply()
        for ((p, card) in labelCards) {
            card.isChecked = p == preset
            card.strokeWidth = if (p == preset) dp(2f).toInt() else dp(1f).toInt()
            card.strokeColor = if (p == preset)
                MaterialColors.getColor(card, androidx.appcompat.R.attr.colorPrimary)
            else MaterialColors.getColor(card, com.google.android.material.R.attr.colorOutlineVariant)
        }
        if (!preset.freestyle) {
            suppressEdits = true
            editWidth.setText(fmt(preset.widthMm))
            editHeight.setText(fmt(preset.heightMm))
            suppressEdits = false
            switchInstant.isChecked = preset.instant
        }
        preset.window?.let { win ->
            findViewById<TextView>(R.id.txtWindowInfo).text =
                getString(R.string.window_info, preset.code, fmt(win.w), fmt(win.h))
            sliderOffsetX.value = prefs.getFloat("offX_${preset.code}", 0f)
            sliderOffsetY.value = prefs.getFloat("offY_${preset.code}", 0f)
        }
        applyModeVisibility()
        scheduleRender()
        // Scroll the selected label into view
        labelCards.firstOrNull { it.first == preset }?.second?.let { card ->
            val scroll = findViewById<android.widget.HorizontalScrollView>(R.id.labelScroll)
            scroll.post { scroll.smoothScrollTo(maxOf(0, card.left - dp(16f).toInt()), 0) }
        }
    }

    private fun onSizeEdited() {
        // Only count real input, not Android restoring state
        if (suppressEdits || !(editWidth.hasFocus() || editHeight.hasFocus())) return
        if (!selectedLabel.freestyle) {
            val pol = switchInstant.isChecked
            selectLabel(Labels.FREESTYLE)
            switchInstant.isChecked = pol
        }
        scheduleRender()
    }

    // ---------- Sign ----------

    private fun currentTextStyle() = TextStyle(
        text = editText.text?.toString()?.trim().orEmpty(),
        font = fontIds.indexOf(toggleFont.checkedButtonId).coerceAtLeast(0),
        bold = R.id.styleBold in toggleStyle.checkedButtonIds,
        italic = R.id.styleItalic in toggleStyle.checkedButtonIds,
        underline = R.id.styleUnderline in toggleStyle.checkedButtonIds,
        frame = frameIds.indexOf(toggleFrame.checkedButtonId).coerceAtLeast(0),
        label = selectedLabel.name,
        qr = findViewById<MaterialSwitch>(R.id.switchQr).isChecked,
        color = if (R.id.textRed == toggleTextColor.checkedButtonId) 1 else 0,
        frameColor = if (R.id.frameRed == toggleFrameColor.checkedButtonId) 1 else 0,
    )

    private fun applyTextStyle(s: TextStyle) {
        Labels.ALL.firstOrNull { it.name == s.label }?.let { if (it != selectedLabel) selectLabel(it) }
        toggleFont.check(fontIds[s.font.coerceIn(0, 2)])
        toggleFrame.check(frameIds[s.frame.coerceIn(0, 4)])
        listOf(R.id.styleBold to s.bold, R.id.styleItalic to s.italic, R.id.styleUnderline to s.underline)
            .forEach { (id, on) -> if (on) toggleStyle.check(id) else toggleStyle.uncheck(id) }
        findViewById<MaterialSwitch>(R.id.switchQr).isChecked = s.qr
        toggleTextColor.check(if (s.color == 1) R.id.textRed else R.id.textBlack)
        toggleFrameColor.check(if (s.frameColor == 1) R.id.frameRed else R.id.frameBlack)
        editText.setText(s.text)
        editText.setSelection(editText.length())
    }

    private fun updateTemplateButton() {
        if (!::btnSaveTemplate.isInitialized) return
        val saved = history.isTemplate(currentTextStyle())
        btnSaveTemplate.setIconResource(if (saved) R.drawable.ic_star else R.drawable.ic_star_outline)
        btnSaveTemplate.setText(if (saved) R.string.saved_template else R.string.save_template)
    }

    private fun startSpeech() {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toLanguageTag())
            .putExtra(RecognizerIntent.EXTRA_PROMPT, getString(R.string.speech_prompt))
        try {
            startActivityForResult(intent, REQ_SPEECH)
        } catch (e: Exception) {
            toast(getString(R.string.speech_unavailable))
        }
    }

    private fun showHistory() {
        val dialog = BottomSheetDialog(this)
        val list = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16f).toInt(), dp(8f).toInt(), dp(16f).toInt(), dp(24f).toInt())
        }
        fun header(text: String) = TextView(this).apply {
            this.text = text
            setTextAppearance(R.style.B21_SectionTitle)
            setPadding(dp(4f).toInt(), dp(16f).toInt(), 0, dp(8f).toInt())
        }
        fun row(s: TextStyle, removable: Boolean): View {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(12f).toInt(), dp(10f).toInt(), dp(4f).toInt(), dp(10f).toInt())
                val tv = TypedValue()
                theme.resolveAttribute(android.R.attr.selectableItemBackground, tv, true)
                setBackgroundResource(tv.resourceId)
            }
            val texts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            texts.addView(TextView(this).apply {
                text = s.text.replace('\n', ' ')
                textSize = 18f
                typeface = TextStyle.typeface(s.font, s.bold, s.italic)
                paint.isUnderlineText = s.underline
                maxLines = 1
            })
            texts.addView(TextView(this).apply {
                text = getString(R.string.history_entry,
                    Labels.ALL.firstOrNull { it.name == s.label }?.title(this@MainActivity) ?: s.label,
                    resources.getStringArray(R.array.font_names)[s.font.coerceIn(0, 2)],
                    resources.getStringArray(R.array.frame_names)[s.frame.coerceIn(0, 4)])
                setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodySmall)
            })
            row.addView(texts, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            val star = MaterialButton(this, null, com.google.android.material.R.attr.materialIconButtonStyle).apply {
                setIconResource(if (history.isTemplate(s)) R.drawable.ic_star else R.drawable.ic_star_outline)
                setOnClickListener {
                    history.toggleTemplate(s)
                    dialog.dismiss(); showHistory(); updateTemplateButton()
                }
            }
            row.addView(star)
            row.setOnClickListener { applyTextStyle(s); dialog.dismiss() }
            if (removable) row.setOnLongClickListener {
                history.removeRecent(s); dialog.dismiss(); showHistory(); true
            }
            return row
        }
        val templates = history.templates
        val recent = history.recent
        if (templates.isNotEmpty()) {
            list.addView(header(getString(R.string.templates)))
            templates.forEach { list.addView(row(it, false)) }
        }
        list.addView(header(getString(R.string.recently_printed)))
        if (recent.isEmpty()) {
            list.addView(TextView(this).apply {
                text = getString(R.string.history_empty)
                setPadding(dp(4f).toInt(), 0, 0, 0)
            })
        } else {
            recent.forEach { list.addView(row(it, true)) }
            list.addView(TextView(this).apply {
                text = getString(R.string.history_help)
                setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodySmall)
                setPadding(dp(4f).toInt(), dp(12f).toInt(), 0, 0)
            })
        }
        dialog.setContentView(NestedScrollView(this).apply { addView(list) })
        dialog.show()
    }

    // ---------- Image ----------

    private fun takePhoto(name: String = "photo.jpg", req: Int = REQ_CAMERA) {
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.CAMERA), REQ_CAMERA_PERM)
            return
        }
        val dir = File(cacheDir, "photos").apply { mkdirs() }
        val file = File(dir, name)
        val uri = FileProvider.getUriForFile(this, "de.b21print.files", file)
        photoUri = uri
        val intent = Intent(MediaStore.ACTION_IMAGE_CAPTURE)
            .putExtra(MediaStore.EXTRA_OUTPUT, uri)
            .addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        try {
            startActivityForResult(intent, req)
        } catch (e: Exception) {
            log("No camera app found")
        }
    }

    @Deprecated("Activity API")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_CROP) {
            // Cancelled without an earlier crop: use the whole image, centered
            if (resultCode != RESULT_OK && source == null && ImageStore.original != null) {
                ImageStore.free = true
                ImageStore.cropped = ImageStore.rotated()
                ImageStore.croppedDepth = ImageStore.rotatedDepth()
            }
            source = ImageStore.cropped
            depth = ImageStore.croppedDepth
            applyModeVisibility()
            scheduleRender()
            return
        }
        if (resultCode != RESULT_OK) return
        when (requestCode) {
            REQ_CAMERA -> photoUri?.let { loadImage(it) }
            REQ_GALLERY -> data?.data?.let { loadImage(it) }
            REQ_CAL_CAMERA -> photoUri?.let { calPhoto?.invoke(it) }
            REQ_BITMAP_FILE -> data?.data?.let { printBitmapFile(it) }
            REQ_CAL_GALLERY -> data?.data?.let { calPhoto?.invoke(it) }
            REQ_ENABLE_BT -> ensurePermissions { scanAndConnect() }
            REQ_FW_FILE -> data?.data?.let { uri ->
                val bytes = runCatching { contentResolver.openInputStream(uri)?.use { it.readBytes() } }.getOrNull()
                if (bytes == null) { toast(getString(R.string.file_unreadable)); return }
                var nm = uri.lastPathSegment ?: "firmware"
                runCatching {
                    contentResolver.query(uri, null, null, null, null)?.use { c ->
                        val idx = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                        if (idx >= 0 && c.moveToFirst()) nm = c.getString(idx)
                    }
                }
                confirmAndFlash(bytes, nm)
            }
            REQ_SPEECH -> data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()?.let {
                editText.setText(it.replaceFirstChar { c -> c.uppercase() })
                editText.setSelection(editText.length())
            }
        }
    }

    private fun loadImage(uri: Uri) {
        scope.launch {
            try {
                val (bmp, d) = withContext(Dispatchers.IO) {
                    val b = decode(uri)
                    b to DepthMap.read(contentResolver, uri, b.width, b.height)
                }
                ImageStore.reset(bmp, d)
                source = null
                depth = null
                // The boundary starts where the photo's depth splits best into near and far
                d?.let { sliderDepthCut.value = Math.round((1f - it.cut) * 100f).toFloat().coerceIn(0f, 100f) }
                if (d != null) log("Depth map ${d.bitmap.width}×${d.bitmap.height}")
                calibration = false
                sweep = false
                openCrop()
            } catch (e: Exception) {
                log("Could not load image: ${e.message}")
            }
        }
    }

    // ImageDecoder applies the EXIF rotation of camera photos
    private fun decode(uri: Uri): Bitmap =
        ImageDecoder.decodeBitmap(ImageDecoder.createSource(contentResolver, uri)) { dec, info, _ ->
            dec.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            val max = maxOf(info.size.width, info.size.height)
            if (max > 2400) {
                val s = 2400f / max
                dec.setTargetSize((info.size.width * s).toInt(), (info.size.height * s).toInt())
            }
        }

    private fun num(v: CharSequence?, def: Double) = v.toString().replace(',', '.').toDoubleOrNull() ?: def

    // The crop has the aspect of the photo area. When the frame, the label or the orientation changes
    // that aspect, refit the crop (same centre, about the same area) so the picture is not cut again.
    private fun refitCrop() {
        val orig = ImageStore.rotated() ?: return
        val n = ImageStore.norm ?: return
        if (ImageStore.free || calibration || textMode || source == null) return
        val a = photoAspect()
        val cur = source ?: return
        if (Math.abs(cur.width.toFloat() / cur.height / a - 1f) < 0.01f) return
        val bw = orig.width.toFloat(); val bh = orig.height.toFloat()
        val area = n.width() * bw * n.height() * bh
        var h = Math.sqrt((area / a).toDouble()).toFloat()
        var w = h * a
        val k = minOf(1f, bw / w, bh / h)
        w *= k; h *= k
        val cx = ((n.left + n.right) / 2f * bw).coerceIn(w / 2, bw - w / 2)
        val cy = ((n.top + n.bottom) / 2f * bh).coerceIn(h / 2, bh - h / 2)
        val nn = RectF((cx - w / 2) / bw, (cy - h / 2) / bh, (cx + w / 2) / bw, (cy + h / 2) / bh)
        ImageStore.norm = nn
        ImageStore.cropped = ImageStore.crop(orig, nn)
        ImageStore.croppedDepth = ImageStore.rotatedDepth()?.let { ImageStore.crop(it, nn) }
        source = ImageStore.cropped
        depth = ImageStore.croppedDepth
    }

    private fun scheduleRender() {
        if (!::sliderOffsetY.isInitialized) return
        refitCrop()
        showCalStatus()
        if (calibration && currentChartKey() != chartKey) { chartKey = currentChartKey(); loadTestChart(calibrate = true); return }
        renderJob?.cancel()
        renderJob = scope.launch {
            delay(120)
            val label = selectedLabel
            val (wmm, hmm) = labelSizeMm()
            txtDims.text = "${label.title(this@MainActivity)} · " + getString(R.string.size_rect, fmt(wmm), fmt(hmm))
            val r: ImageProcessor.Result
            if (textMode) {
                val style = currentTextStyle()
                val twoColour = switchRed.isChecked
                r = withContext(Dispatchers.Default) {
                    ImageProcessor.renderText(style, wmm, hmm, label.window, label.frameColor, label.round, capPx(), twoColour).withRot(orient())
                }
                rendered = if (style.text.isNotBlank()) r else null
            } else {
                // A fixed crop fills the area, a free crop is fitted and centered
                val opts = photoOptions(if (ImageStore.free) ImageProcessor.Fit.CONTAIN else ImageProcessor.Fit.COVER)
                val src = source
                val dep = depth
                r = withContext(Dispatchers.Default) {
                    if (sweep) ImageProcessor.renderSweep(src, wmm, hmm, label.window, label.frameColor, opts, label.round,
                        sweepGammas().map { "%.1f".format(Locale.ROOT, it) to opts.copy(toneGamma = it) })
                    else ImageProcessor.render(src, wmm, hmm, label.window, label.frameColor, opts, label.round, dep, capPx()).withRot(orient())
                }
                rendered = if (src != null) r else null
            }
            val stage = labelCard.parent as View
            val s = minOf((stage.width - stage.paddingLeft - stage.paddingRight).toFloat() / r.preview.width,
                (stage.height - stage.paddingTop - stage.paddingBottom).toFloat() / r.preview.height)
            preview.setImageBitmap(withContext(Dispatchers.Default) {
                ImageProcessor.forScreen(r.preview, (r.preview.width * s).toInt(), (r.preview.height * s).toInt())
            })
            sizePreviewCard(r.preview.width, r.preview.height, label.round)
            updatePrintButton()
        }
    }

    // Tone target of photos (PrintCurve gamma), set in the debug console
    private fun toneGamma() = prefs.getFloat("toneGamma", PrintCurve.DEFAULT_GAMMA.toFloat()).toDouble()

    // Tone sweep stripes, left to right: around the current gamma
    private fun sweepGammas() = toneGamma().let { g ->
        doubleArrayOf(g - 0.3, g, g + 0.3, g + 0.6).map { Math.round(maxOf(0.4, it) * 10) / 10.0 }.toDoubleArray()
    }

    private fun photoOptions(fit: ImageProcessor.Fit): ImageProcessor.Options {
        val label = selectedLabel
        if (calibration) return ImageProcessor.Options(
            fit = fit, rotation = 0, raster = ImageProcessor.Raster.TEXT,
            brightness = 0, contrast = 0, invert = false,
            instant = instantOn() && !label.round, caption = "",
        )
        return ImageProcessor.Options(
            fit = fit,
            rotation = 0,
            raster = raster,
            brightness = sliderBrightness.value.toInt(),
            contrast = sliderContrast.value.toInt(),
            invert = switchInvert.isChecked,
            instant = instantOn() && !label.round,
            softEdge = switchSoftEdge.isChecked,
            overscan = switchOverscan.isChecked && label.round,
            density = sliderDensity.value.toInt(),
            toneGamma = toneGamma(),
            ink = when {
                switchRed.isChecked -> ImageProcessor.Ink.RED_BLACK
                prefs.getInt("speed", 1) == 1 -> ImageProcessor.Ink.GRAY
                else -> ImageProcessor.Ink.MONO
            },
            redLo = sliderRed.values[0] / 100f,
            redHi = sliderRed.values[1] / 100f,
            grayCurve = profile?.curve(PaperProfile.GRAY, sliderDensity.value.toInt()),
            monoCurve = monoCurve(),
            depthCut = 1f - sliderDepthCut.value / 100f,
            background = background(),
            bgStrength = sliderBgStrength.value / 100f,
            bgRaster = bgRaster,
            bgMonoCurve = monoCurve(bgRaster),
            caption = editCaption.text?.toString().orEmpty(),
            stamp = stampText(),
            stampWhite = prefs.getBoolean("stampWhite", false),
            instantBlack = prefs.getBoolean("instantBlack", false),
            offsetXMm = if (label.window != null) sliderOffsetX.value.toDouble() else 0.0,
            offsetYMm = if (label.window != null) sliderOffsetY.value.toDouble() else 0.0,
        )
    }

    // The label as it sticks on the printer: width across the head, height in the feed direction
    private fun physSizeMm(): Pair<Double, Double> {
        val l = selectedLabel
        val wmm = if (l.freestyle) num(editWidth.text, 50.0).coerceIn(5.0, Niimbot.MAX_WIDTH_MM) else l.widthMm
        val hmm = if (l.freestyle) num(editHeight.text, 30.0).coerceIn(5.0, Niimbot.MAX_HEIGHT_MM) else l.heightMm
        return wmm to hmm
    }

    // 0 upright, 1/2 landscape (the picture is laid out wide and turned clockwise / counter-clockwise
    // for the print). Only for plain rectangular labels that are not square; charts stay upright.
    private fun orient(): Int {
        val l = selectedLabel
        val (w, h) = physSizeMm()
        if (l.round || l.window != null || w == h || calibration || sweep) return 0
        return prefs.getInt("orient", 0)
    }

    // Panorama: the picture spans N labels in a row, printed as N consecutive labels
    private fun panoN(): Int {
        val l = selectedLabel
        if (l.round || l.window != null || calibration || sweep) return 1
        return sliderPano.value.toInt()
    }
    private fun panoGapMm() = if (panoN() > 1) sliderPanoGap.value.toDouble() else 0.0
    // Date, the typed text or nothing, as set for the stamp
    private fun stampText() = when (prefs.getInt("stampMode", 0)) {
        1 -> PhotoStrip.dateText()
        2 -> prefs.getString("stampText", "").orEmpty().trim()
        else -> ""
    }

    private fun instantOn() = switchInstant.isChecked && panoN() == 1

    // One label as the picture sees it (turned for landscape)
    private fun singleSizeMm() = physSizeMm().let { (w, h) -> if (orient() == 0) w to h else h to w }

    // The whole picture: one label, or the strip of a panorama including the gaps between the labels
    private fun labelSizeMm() = singleSizeMm().let { (w, h) -> panoN().let { n -> (n * w + (n - 1) * panoGapMm()) to h } }
    private fun capMm() = if (orient() == 0 && panoN() == 1) 50.0 else 100000.0
    private fun capPx() = if (orient() == 0 && panoN() == 1) Niimbot.PRINTHEAD_PIXELS else Int.MAX_VALUE

    // The labels of a panorama, left to right = print order
    private fun panoSlices(r: ImageProcessor.Result): List<ImageProcessor.Result> {
        val n = panoN()
        if (n == 1) return listOf(r)
        val sw = ImageProcessor.mmToPx(singleSizeMm().first)
        val gp = ImageProcessor.mmToPx(panoGapMm())
        return List(n) { i ->
            val x0 = minOf(i * (sw + gp), r.width - 1)
            r.slice(x0, minOf(x0 + sw, r.width))
        }
    }

    // Aspect ratio of the area the photo goes into
    private fun photoAspect(): Float {
        val l = selectedLabel
        l.window?.let { return (it.w / it.h).toFloat() }
        if (l.round) return 1f
        val (wmm, hmm) = labelSizeMm()
        if (instantOn()) {
            val r = ImageProcessor.instantPhotoRect(ImageProcessor.mmToPx(minOf(wmm, capMm())), ImageProcessor.mmToPx(hmm))
            return r.width().toFloat() / r.height()
        }
        return (minOf(wmm, capMm()) / hmm).toFloat()
    }

    private fun openCrop() {
        if (ImageStore.original == null) return
        val (wmm, hmm) = labelSizeMm()
        val size = if (selectedLabel.round) getString(R.string.size_round, fmt(wmm))
            else getString(R.string.size_rect, fmt(wmm), fmt(hmm))
        val intent = Intent(this, CropActivity::class.java)
            .putExtra(CropActivity.EXTRA_ASPECT, photoAspect())
            .putExtra(CropActivity.EXTRA_ROUND, selectedLabel.round)
            .putExtra(CropActivity.EXTRA_LABEL, getString(R.string.crop_for, selectedLabel.title(this), size))
        startActivityForResult(intent, REQ_CROP)
    }

    // Shrink the preview while the keyboard is open so the text field stays visible
    private fun watchKeyboard() {
        val root = findViewById<View>(android.R.id.content)
        val stage = findViewById<View>(R.id.stage)
        val printBar = findViewById<View>(R.id.printBar)
        val normal = resources.getDimension(R.dimen.stage_height)
        root.viewTreeObserver.addOnGlobalLayoutListener {
            val r = android.graphics.Rect()
            root.getWindowVisibleDisplayFrame(r)
            val keyboard = root.rootView.height - r.bottom > root.rootView.height * 0.2
            // The print bar only takes room from the text field while typing
            val bar = if (keyboard) View.GONE else View.VISIBLE
            if (printBar.visibility != bar) {
                printBar.visibility = bar
                // The preview column changed size with the keyboard: fit the preview again
                if (stage.tag == "fill") scheduleRender()
            }
            // In tablet landscape the preview fills the left column
            if (stage.tag == "fill") return@addOnGlobalLayoutListener
            val target = if (keyboard) dp(130f).toInt() else normal.toInt()
            if (stage.layoutParams.height != target) {
                stage.layoutParams = stage.layoutParams.apply { height = target }
                scheduleRender()
            }
        }
    }

    // Fit the label into the preview with the right aspect; show circles round
    private fun sizePreviewCard(w: Int, h: Int, round: Boolean) {
        val stage = labelCard.parent as View
        stage.post {
            val aw = stage.width - stage.paddingLeft - stage.paddingRight
            val ah = stage.height - stage.paddingTop - stage.paddingBottom
            if (aw <= 0 || ah <= 0) return@post
            val s = minOf(aw.toFloat() / w, ah.toFloat() / h)
            val cw = (w * s).toInt()
            val ch = (h * s).toInt()
            labelCard.layoutParams = (labelCard.layoutParams as FrameLayout.LayoutParams).apply {
                width = cw; height = ch
            }
            labelCard.radius = if (round) minOf(cw, ch) / 2f else dp(6f)
        }
    }

    private fun updatePrintButton() {
        btnPrint.isEnabled = printer != null && rendered != null
        btnPrint.setText(when {
            printer == null -> R.string.print_need_printer
            rendered == null && textMode -> R.string.print_need_text
            rendered == null -> R.string.print_need_photo
            else -> R.string.print
        })
    }

    // Battery state from the heartbeat: read on connect and every 20 s while idle, never during a print
    private fun startBatteryPolling(p: B21Printer) {
        batteryJob?.cancel()
        batteryJob = scope.launch {
            var first = true
            while (printer === p) {
                if (!printing) {
                    p.readBattery()?.let {
                        battery = it
                        if (first) log("Battery: raw %d, level %d, temp %d °C [%s]".format(it.raw, it.level, it.tempC,
                            it.bytes.joinToString(" ") { b -> "%02X".format(b) }))
                        first = false
                        showBattery()
                    }
                }
                delay(20000)
            }
        }
    }

    private fun stopBatteryPolling() {
        batteryJob?.cancel()
        battery = null
        showBattery()
    }

    private fun batteryText(b: Niimbot.Battery) =
        getString(R.string.battery, "L${b.level}" + (b.volts?.let { " · %.2f V".format(Locale.ROOT, it) } ?: " · ${b.raw}"))

    private fun showBattery() {
        val b = battery
        txtBattery.visibility = if (b == null || printer == null) View.GONE else View.VISIBLE
        if (b == null) return
        txtBattery.text = batteryText(b)
        txtBattery.setCompoundDrawablesRelativeWithIntrinsicBounds(R.drawable.ic_battery, 0, 0, 0)
    }

    private fun updatePrinterChip(name: String? = null, connecting: Boolean = false) {
        chipPrinter.text = when {
            connecting -> getString(R.string.connecting)
            printer != null -> name ?: getString(R.string.connected)
            else -> getString(R.string.connect)
        }
        chipPrinter.chipIconTint = ColorStateList.valueOf(
            if (printer != null) getColor(R.color.ok)
            else MaterialColors.getColor(this, androidx.appcompat.R.attr.colorPrimary, Color.RED),
        )
        updatePrintButton()
    }

    // ---------- Bluetooth ----------

    private var permissionCallback: (() -> Unit)? = null

    private fun ensurePermissions(then: () -> Unit) {
        val perms = if (Build.VERSION.SDK_INT >= 31) {
            arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        val missing = perms.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isEmpty()) {
            if (bluetooth?.isEnabled != true) {
                startActivityForResult(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE), REQ_ENABLE_BT)
            } else {
                then()
            }
        } else {
            permissionCallback = then
            requestPermissions(missing.toTypedArray(), REQ_PERMS)
        }
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(code, perms, results)
        if (code == REQ_CAMERA_PERM && results.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
            if (calPhoto != null) takePhoto("chart.jpg", REQ_CAL_CAMERA) else takePhoto()
        }
        if (code == REQ_PERMS) {
            if (results.isNotEmpty() && results.all { it == PackageManager.PERMISSION_GRANTED }) {
                permissionCallback?.let { ensurePermissions(it) }
            } else {
                log("Bluetooth permission denied")
            }
        }
    }

    private fun scanAndConnect() {
        val scanner = bluetooth?.bluetoothLeScanner ?: return log("Bluetooth not available")
        val found = LinkedHashMap<String, BluetoothDevice>()
        val cb = object : ScanCallback() {
            override fun onScanResult(type: Int, result: ScanResult) {
                val name = result.device.name ?: result.scanRecord?.deviceName ?: return
                if (name.startsWith("B21")) found[result.device.address] = result.device
            }
        }
        log("Searching for printer …")
        chipPrinter.isEnabled = false
        chipPrinter.setText(R.string.searching)
        scanner.startScan(cb)
        scope.launch {
            // Stop once a printer was found (max. 6 s)
            for (i in 0 until 30) {
                delay(200)
                if (found.isNotEmpty() && i >= 5) break
            }
            scanner.stopScan(cb)
            chipPrinter.isEnabled = true
            updatePrinterChip()
            val devices = found.values.toList()
            when (devices.size) {
                0 -> log("No B21 found. Is the printer on?")
                1 -> connect(devices[0])
                else -> MaterialAlertDialogBuilder(this@MainActivity)
                    .setTitle(R.string.choose_printer)
                    .setItems(devices.map { "${it.name}  (${it.address})" }.toTypedArray()) { _, i -> connect(devices[i]) }
                    .show()
            }
        }
    }

    private fun connect(device: BluetoothDevice) {
        val p = B21Printer(applicationContext, { Booth.log?.invoke(it) }) {
            Booth.printer = null
            Booth.onDisconnected?.invoke()
        }
        // Remember the protocol variant that worked
        p.variant = prefs.getString("variant", null)?.let { n -> B21Printer.Variant.entries.firstOrNull { it.name == n } }
        p.onVariantFound = { v -> prefs.edit().putString("variant", v.name).apply() }
        scope.launch {
            updatePrinterChip(connecting = true)
            try {
                p.connect(device)
                printer = p
                Booth.printer = p
                onRoll(readRollRetry(p))
                startBatteryPolling(p)
            } catch (e: Exception) {
                p.disconnect()
                log("Error: ${e.message}")
            }
            updatePrinterChip(device.name)
        }
    }

    // Photo booth: take a selfie and print it with the current settings
    // Back from the booth: the last picture stays on the canvas, so its settings can still be changed
    override fun onResume() {
        super.onResume()
        val b = Booth.lastPhoto ?: return
        Booth.lastPhoto = null
        findViewById<MaterialButtonToggleGroup>(R.id.toggleAppMode).check(R.id.modeAppPhoto)
        ImageStore.reset(b)
        ImageStore.cropped = b
        ImageStore.free = true
        source = b
        depth = null
        calibration = false
        sweep = false
        applyModeVisibility()
        scheduleRender()
    }

    private fun openBooth() {
        // Without a printer you can still set up camera and framing
        if (printer == null) toast(getString(R.string.booth_no_printer))
        if (panoN() > 1) return toast(getString(R.string.pano_no_booth))
        val (wmm, hmm) = labelSizeMm()
        Booth.config = Booth.Config(
            label = selectedLabel,
            widthMm = wmm,
            heightMm = hmm,
            photoAspect = photoAspect(),
            options = photoOptions(ImageProcessor.Fit.COVER),
            density = sliderDensity.value.toInt(),
            twoColorRoll = switchRed.isChecked,
            labelType = intArrayOf(1, 2, 3, 5)[paperIndex],
            copies = num(editCopies.text, 1.0).toInt().coerceIn(1, 20),
            orient = orient(),
            stampMode = prefs.getInt("stampMode", 0),
            stampText = prefs.getString("stampText", "").orEmpty().trim(),
        )
        startActivity(Intent(this, BoothActivity::class.java))
    }

    private fun print() {
        val p = printer ?: return
        val r = rendered ?: return
        battery?.takeIf { it.low }?.let { b ->
            MaterialAlertDialogBuilder(this)
                .setMessage(getString(R.string.battery_low_ask, batteryText(b)))
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.print) { _, _ -> doPrint(p, r) }
                .show()
            return
        }
        doPrint(p, r)
    }

    private fun doPrint(p: B21Printer, r: ImageProcessor.Result) {
        val density = sliderDensity.value.toInt()
        val copies = num(editCopies.text, 1.0).toInt().coerceIn(1, 999)
        val labelType = intArrayOf(1, 2, 3, 5)[paperIndex]
        val textStyle = if (textMode) currentTextStyle() else null
        val twoColor = switchRed.isChecked
        printJob {
            val mode = when {
                twoColor -> "red/black"
                r.levels != null -> "16 grays"
                else -> "1-bit"
            }
            log("Printing ${r.width}×${r.height} px, $mode, density $density, speed pref ${prefs.getInt("speed", 1)}, roll ${roll?.barcode ?: "none"} …")
            val parts = panoSlices(r)
            printParts(p, parts, twoColor, density, labelType, copies, it)
            val title = if (textStyle != null) textStyle.text.replace('\n', ' ').take(30)
                else findViewById<TextView>(R.id.txtDims).text.toString()
            withContext(Dispatchers.IO) {
                runCatching { PrintArchive.save(this@MainActivity, parts, r.preview, title, twoColor, density, labelType, copies) }
            }
            textStyle?.let { history.addRecent(it) }
        }
    }

    // Parts go out one after another, all copies of the set in turn (panorama: label 1, 2, 3, then again)
    private suspend fun printParts(
        p: B21Printer, parts: List<ImageProcessor.Result>, twoColor: Boolean, density: Int, labelType: Int,
        copies: Int, onProgress: (Float) -> Unit,
    ) {
        if (parts.size == 1) return p.printResult(parts[0], twoColor, density, labelType, copies, onProgress)
        val total = parts.size * copies
        for (c in 0 until copies) parts.forEachIndexed { i, part ->
            val done = c * parts.size + i
            p.printResult(part, twoColor, density, labelType, 1) { f -> onProgress((done + f) / total) }
        }
    }

    // The list of recent prints; a tap offers to print the same dots again
    private fun showPrints() {
        val dialog = BottomSheetDialog(this)
        val list = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16f).toInt(), dp(8f).toInt(), dp(16f).toInt(), dp(24f).toInt())
        }
        list.addView(TextView(this).apply {
            text = getString(R.string.prints_title)
            setTextAppearance(R.style.B21_SectionTitle)
            setPadding(dp(4f).toInt(), dp(16f).toInt(), 0, dp(8f).toInt())
        })
        val entries = PrintArchive.list(this)
        if (entries.isEmpty()) list.addView(TextView(this).apply {
            text = getString(R.string.prints_empty); setPadding(dp(4f).toInt(), 0, 0, 0)
        })
        val fmt = java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.SHORT, java.text.DateFormat.SHORT)
        for (e in entries) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(8f).toInt(), dp(8f).toInt(), dp(8f).toInt(), dp(8f).toInt())
                val tv = TypedValue()
                theme.resolveAttribute(android.R.attr.selectableItemBackground, tv, true)
                setBackgroundResource(tv.resourceId)
            }
            row.addView(android.widget.ImageView(this).apply {
                setImageBitmap(BitmapFactory.decodeFile(e.thumb.path))
                adjustViewBounds = true
                scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
            }, LinearLayout.LayoutParams(dp(72f).toInt(), dp(72f).toInt()))
            val texts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            texts.addView(TextView(this).apply { text = e.title; textSize = 16f; maxLines = 1 })
            texts.addView(TextView(this).apply {
                text = jobInfo(e) + "\n" + fmt.format(java.util.Date(e.id))
                setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodySmall)
            })
            row.addView(texts, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(12f).toInt() })
            row.setOnClickListener { dialog.dismiss(); askReprint(e) }
            list.addView(row)
        }
        dialog.setContentView(NestedScrollView(this).apply { addView(list) })
        // On tablets the sheet would stay at its peek height, with the list below the screen edge
        dialog.behavior.skipCollapsed = true
        dialog.behavior.state = com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_EXPANDED
        dialog.show()
    }

    private fun jobInfo(e: PrintArchive.Entry): String {
        val mode = getString(when { e.twoColor -> R.string.prints_mode_red; e.gray -> R.string.prints_mode_gray; else -> R.string.prints_mode_mono })
        val parts = if (e.parts > 1) " · " + getString(R.string.prints_parts, e.parts) else ""
        return getString(R.string.prints_info, mode, e.density, e.copies) + parts
    }

    private fun askReprint(e: PrintArchive.Entry) {
        val img = android.widget.ImageView(this).apply {
            setImageBitmap(BitmapFactory.decodeFile(e.thumb.path))
            adjustViewBounds = true
            setPadding(dp(24f).toInt(), dp(16f).toInt(), dp(24f).toInt(), 0)
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(e.title)
            .setView(img)
            .setMessage(jobInfo(e))
            .setNegativeButton(android.R.string.cancel, null)
            .setNeutralButton(R.string.delete) { _, _ -> PrintArchive.delete(e); showPrints() }
            .setPositiveButton(R.string.print_again_job) { _, _ -> reprint(e) }
            .show()
    }

    private fun reprint(e: PrintArchive.Entry) {
        val p = printer ?: return toast(getString(R.string.printer_not_connected))
        if (printing) return
        printJob {
            val parts = withContext(Dispatchers.IO) { PrintArchive.load(e) }
            log("Printing again: ${e.title}, ${parts.size} part(s), density ${e.density}, roll ${roll?.barcode ?: "none"} …")
            printParts(p, parts, e.twoColor, e.density, e.labelType, e.copies, it)
        }
    }

    // Runs a print with the progress bar and a disabled print button
    private fun printJob(job: suspend (onProgress: (Float) -> Unit) -> Unit) {
        printing = true
        btnPrint.isEnabled = false
        btnPrint.setText(R.string.printing)
        progress.visibility = View.VISIBLE
        progress.setProgressCompat(0, false)
        scope.launch {
            try {
                job { f -> runOnUiThread { progress.setProgressCompat((f * 1000).toInt(), true) } }
            } catch (e: Exception) {
                log("Print error: ${e.message}")
                // After an error the roll tag may have been missed; read it again
                printer?.let { pr -> runCatching { onRoll(readRollRetry(pr)) } }
            }
            progress.visibility = View.INVISIBLE
            printing = false
            updatePrintButton()
        }
    }

    // Debug: prints an image file dot for dot in 1-bit (dark = dot), no scaling or processing, e.g. a
    // bitmap recovered from another app's Bluetooth traffic, to compare its dither through our sending
    private fun printBitmapFile(uri: Uri) {
        val p = printer ?: return
        val density = sliderDensity.value.toInt()
        val labelType = intArrayOf(1, 2, 3, 5)[paperIndex]
        printJob { onProgress ->
            val bmp = withContext(Dispatchers.IO) {
                contentResolver.openInputStream(uri)?.use { android.graphics.BitmapFactory.decodeStream(it) }
            } ?: throw Exception("Cannot read the file")
            if (bmp.width > Niimbot.PRINTHEAD_PIXELS) throw Exception("Wider than ${Niimbot.PRINTHEAD_PIXELS} dots")
            val w = bmp.width; val h = bmp.height
            val px = IntArray(w * h).also { bmp.getPixels(it, 0, w, 0, 0, w, h) }
            val bits = ByteArray(w * h) { if (Color.red(px[it]) < 128) 1 else 0 }
            log("Bitmap file ${w}×$h, density $density …")
            p.print(bits, w, h, density, labelType, 1, onProgress)
        }
    }

    // Test prints for the gray and red/black modes, sent straight to the printer (no preview),
    // full head width and the label height of the selected label
    private fun printColorTest(gray: Boolean) {
        val p = printer ?: return
        val h = ImageProcessor.mmToPx(physSizeMm().second)
        val density = sliderDensity.value.toInt()
        val labelType = intArrayOf(1, 2, 3, 5)[paperIndex]
        printJob { onProgress ->
            if (gray) {
                log("Gray wedge ${Niimbot.COLOR_HEAD}×$h, density $density …")
                val px = withContext(Dispatchers.Default) { TestChart.grayWedge(h, "D$density") }
                p.printGray(px, h, density, labelType, onProgress)
            } else {
                log("Red/black test ${Niimbot.COLOR_HEAD}×$h, density $density …")
                val (black, red) = withContext(Dispatchers.Default) { TestChart.twoColor(h, "D$density") }
                p.printTwoColor(black, red, h, density, labelType, onProgress)
            }
        }
    }

    private fun printGrayLoad() {
        val p = printer ?: return
        val h = ImageProcessor.mmToPx(physSizeMm().second)
        val density = sliderDensity.value.toInt()
        val labelType = intArrayOf(1, 2, 3, 5)[paperIndex]
        printJob { onProgress ->
            log("Gray load test ${Niimbot.COLOR_HEAD}×$h, density $density, levels ${TestChart.LOAD_LEVELS.joinToString(" / ")} …")
            val px = withContext(Dispatchers.Default) { TestChart.grayLoad(h, "LOAD D$density") }
            p.printGray(px, h, density, labelType, onProgress)
        }
    }

    // Hidden debug console: test chart, printer diagnostics, firmware flasher, and raw text commands
    // to the BLE characteristic with every reply. App log lines are mirrored into its log.
    private fun showDebugConsole() {
        val p = printer
        val pad = dp(16f).toInt()
        val gap = dp(8f).toInt()
        var dialog: androidx.appcompat.app.AlertDialog? = null
        fun button(label: String, enabled: Boolean = true, onClick: () -> Unit) =
            com.google.android.material.button.MaterialButton(
                this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                text = label; textSize = 12f; isSingleLine = true; isEnabled = enabled
                setOnClickListener { onClick() }
            }
        fun row(vararg views: View) = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            for ((i, v) in views.withIndex()) addView(v, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                .apply { if (i > 0) marginStart = gap })
        }

        val logView = TextView(this).apply {
            typeface = android.graphics.Typeface.MONOSPACE; textSize = 11f; setTextIsSelectable(true)
            setPadding(gap, gap, gap, gap)
        }
        val logScroll = NestedScrollView(this).apply {
            addView(logView)
            setBackgroundColor(com.google.android.material.color.MaterialColors.getColor(
                this, com.google.android.material.R.attr.colorSurfaceContainerHighest))
        }
        fun appendLog(line: String) = runOnUiThread {
            val t = SimpleDateFormat("HH:mm:ss", Locale.GERMANY).format(Date())
            logView.text = "$t  $line\n${logView.text}"
        }
        p?.rawListener = { bytes ->
            val hex = bytes.joinToString(" ") { "%02x".format(it) }
            val txt = bytes.map { if (it in 32..126) it.toInt().toChar() else '.' }.joinToString("")
            appendLog("RX [$hex]  \"$txt\"")
        }
        consoleSink = { appendLog(it) }

        val input = com.google.android.material.textfield.TextInputEditText(this).apply {
            setText(""); hint = "Raw text"; setSingleLine(true); textSize = 14f
        }
        val crlf = com.google.android.material.materialswitch.MaterialSwitch(this).apply {
            text = "Append \\r\\n"; isChecked = true
        }
        val send = button("Send", p != null) {
            var cmd = input.text?.toString().orEmpty()
            if (crlf.isChecked) cmd += "\r\n"
            appendLog("TX \"${cmd.replace("\r", "\\r").replace("\n", "\\n")}\"")
            scope.launch {
                try { p?.sendRaw(cmd.toByteArray(Charsets.US_ASCII)) }
                catch (e: Exception) { appendLog("Error: ${e.message}") }
            }
        }
        val presets = android.widget.HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(LinearLayout(this@MainActivity).apply {
                for (c in listOf("Heartbeat 2", "Heartbeat 3", "Heartbeat 4")) {
                    addView(Chip(this@MainActivity).apply {
                        text = c; isEnabled = p != null
                        setOnClickListener {
                            val bytes = Niimbot.packet(Niimbot.HEARTBEAT, intArrayOf(c.last() - '0'))
                            appendLog("TX 0x55 [" + bytes.joinToString(" ") { "%02x".format(it) } + "]")
                            scope.launch { runCatching { p?.sendRaw(bytes) } }
                        }
                    }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                        .apply { marginEnd = gap })
                }
                addView(Chip(this@MainActivity).apply {
                    text = "0x55 heartbeat"
                    isEnabled = p != null
                    setOnClickListener {
                        val bytes = Niimbot.packet(Niimbot.HEARTBEAT, intArrayOf(1))
                        appendLog("TX 0x55 [" + bytes.joinToString(" ") { "%02x".format(it) } + "]")
                        scope.launch { runCatching { p?.sendRaw(bytes) } }
                    }
                })
            })
        }
        fun header(text: String) = TextView(this).apply {
            this.text = text
            setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_TitleSmall)
            setPadding(0, pad, 0, gap / 2)
        }

        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, gap, pad, 0)
            if (p == null) addView(TextView(this@MainActivity).apply {
                text = getString(R.string.print_need_printer); textSize = 12f
                setPadding(0, 0, 0, gap)
            })
            addView(row(
                button("Test chart") { dialog?.dismiss(); loadTestChart() },
                button("Calibration") { dialog?.dismiss(); loadTestChart(calibrate = true) },
            ))
            val txtGamma = TextView(this@MainActivity).apply {
                textSize = 12f; text = "Tone gamma %.1f".format(Locale.ROOT, toneGamma())
            }
            addView(header("Photo tone target (gamma)"))
            addView(row(txtGamma, Slider(this@MainActivity).apply {
                valueFrom = 0.4f; valueTo = 2.4f; stepSize = 0.1f
                value = (Math.round(toneGamma() * 10) / 10f).coerceIn(0.4f, 2.4f)
                addOnChangeListener { _, v, fromUser ->
                    if (!fromUser) return@addOnChangeListener
                    prefs.edit().putFloat("toneGamma", v).apply()
                    txtGamma.text = "Tone gamma %.1f".format(Locale.ROOT, v)
                    scheduleRender()
                }
            }))
            addView(button(if (sweep) "Tone sweep: off" else "Tone sweep") {
                dialog?.dismiss()
                if (source == null || calibration) return@button toast(getString(R.string.print_need_photo))
                sweep = !sweep
                if (sweep) log("Tone sweep, stripes left to right: gamma " + sweepGammas().joinToString(" / "))
                scheduleRender()
            })
            addView(row(
                button("Gray wedge", p != null) { dialog?.dismiss(); printColorTest(gray = true) },
                button("Gray load test", p != null) { dialog?.dismiss(); printGrayLoad() },
            ))
            addView(row(
                button("Red/black test", p != null) { dialog?.dismiss(); printColorTest(gray = false) },
                button("Save print bitmap") {
                    // Exactly the dots that would be sent, 1:1, for checking patterns without a print
                    val r = rendered ?: return@button toast(getString(R.string.print_need_photo))
                    val px = IntArray(r.bits.size) { i ->
                        r.levels?.let { l -> val g = 255 - 17 * l[i]; android.graphics.Color.rgb(g, g, g) }
                            ?: if (r.bits[i].toInt() != 0) android.graphics.Color.BLACK else android.graphics.Color.WHITE
                    }
                    val f = File(getExternalFilesDir(null), "print.png")
                    f.outputStream().use { Bitmap.createBitmap(px, r.width, r.height, Bitmap.Config.ARGB_8888)
                        .compress(Bitmap.CompressFormat.PNG, 100, it) }
                    appendLog("Saved ${r.width}×${r.height} to ${f.absolutePath}")
                },
            ))
            addView(button("Print bitmap file …", p != null) {
                dialog?.dismiss()
                startActivityForResult(Intent(Intent.ACTION_GET_CONTENT).setType("image/*"), REQ_BITMAP_FILE)
            })
            addView(button("Printer diagnostics", p != null) { scope.launch { p?.diagnose() } })
            addView(button("Camera info") { runCatching { CameraInfo.describe(this@MainActivity) }.getOrElse { listOf("Camera info failed: ${it.message}") }.asReversed().forEach { appendLog(it) } })
            addView(button("⚠ Flash firmware …", p != null) { pickFirmware() }.apply {
                if (p != null) setTextColor(getColor(R.color.brand))
            })
            addView(header("Raw command (read-only commands only)"))
            addView(LinearLayout(this@MainActivity).apply {
                gravity = Gravity.CENTER_VERTICAL
                addView(com.google.android.material.textfield.TextInputLayout(this@MainActivity).apply { addView(input) },
                    LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                addView(send, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT).apply { marginStart = gap })
            })
            addView(presets)
            addView(crlf)
            addView(row(
                header("Log"),
                button("Copy") {
                    val cm = getSystemService(android.content.ClipboardManager::class.java)
                    cm.setPrimaryClip(android.content.ClipData.newPlainText("B21 log", logView.text))
                    toast("Log copied")
                },
                button("Clear") { logView.text = "" },
            ))
            addView(logScroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                (resources.displayMetrics.heightPixels * 0.3f).toInt()))
        }
        val titleBar = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(pad, gap, gap, 0)
            addView(TextView(this@MainActivity).apply {
                text = "Debug console"
                setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_TitleLarge)
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(android.widget.ImageButton(this@MainActivity).apply {
                setImageResource(R.drawable.ic_close)
                contentDescription = getString(R.string.close)
                background = null
                setOnClickListener { dialog?.dismiss() }
            })
        }
        val dlg = MaterialAlertDialogBuilder(this)
            .setCustomTitle(titleBar)
            .setView(NestedScrollView(this).apply { addView(body) })
            .setOnDismissListener { p?.rawListener = null; consoleSink = null }
            .create()
        dialog = dlg
        dlg.show()
    }

    // ---------- Favourites ----------

    // An image setting to come back to: look, print mode, density, brightness, contrast
    private data class Favorite(val raster: String, val fine: Boolean, val density: Int, val brightness: Int, val contrast: Int)

    private fun loadFavorites(): MutableList<Favorite> {
        val arr = runCatching { org.json.JSONArray(prefs.getString("favorites", "[]")) }.getOrElse { org.json.JSONArray() }
        return MutableList(arr.length()) { i ->
            val o = arr.getJSONObject(i)
            Favorite(o.getString("raster"), o.getBoolean("fine"), o.getInt("density"), o.getInt("brightness"), o.getInt("contrast"))
        }
    }

    private fun saveFavorites(list: List<Favorite>) {
        val arr = org.json.JSONArray()
        for (f in list) arr.put(org.json.JSONObject().put("raster", f.raster).put("fine", f.fine)
            .put("density", f.density).put("brightness", f.brightness).put("contrast", f.contrast))
        prefs.edit().putString("favorites", arr.toString()).apply()
    }

    private fun currentFavorite() = Favorite(raster.name, prefs.getInt("speed", 1) == 1, sliderDensity.value.toInt(),
        sliderBrightness.value.toInt(), sliderContrast.value.toInt())

    // e.g. "Amiga fine · Coarse D3 · +10/−5"
    private fun favoriteName(f: Favorite): String {
        val look = ImageProcessor.Raster.entries.firstOrNull { it.name == f.raster } ?: ImageProcessor.Raster.PHOTO
        val mode = getString(if (f.fine) R.string.speed_fine else R.string.speed_fast)
        val tone = if (f.brightness == 0 && f.contrast == 0) "" else " · %+d/%+d".format(f.brightness, f.contrast)
        return "${resources.getStringArray(R.array.raster)[look.ordinal]} · $mode D${f.density}$tone"
    }

    // Chips above the look: tap applies, long-press deletes, the last chip saves the current setting
    // Depth split (portrait photos): where the subject ends and what happens to the background.
    // The choice and its settings are kept; the boundary starts anew with each photo.
    private fun setupDepth() {
        val dropBg = findViewById<TextInputEditText>(R.id.dropBackground)
        val dropBgRaster = findViewById<TextInputEditText>(R.id.dropBgRaster)
        val txtCut = findViewById<TextView>(R.id.txtDepthCut)
        val txtStrength = findViewById<TextView>(R.id.txtBgStrength)
        val rasterNames = resources.getStringArray(R.array.raster)
        background = ImageProcessor.Background.entries.firstOrNull { it.name == prefs.getString("background", null) }
            ?: ImageProcessor.Background.SAME
        bgRaster = ImageProcessor.Raster.entries.firstOrNull { it.name == prefs.getString("bgRaster", null) }
            ?: ImageProcessor.Raster.BLUE
        sliderBgStrength.value = prefs.getInt("bgStrength", 50).toFloat()
        txtCut.text = "${sliderDepthCut.value.toInt()} %"
        txtStrength.text = "${sliderBgStrength.value.toInt()} %"
        dropBgRaster.setText(rasterNames[bgRaster.ordinal])
        sliderDepthCut.addOnChangeListener { _, v, _ -> txtCut.text = "${v.toInt()} %"; scheduleRender() }
        sliderBgStrength.addOnChangeListener { _, v, fromUser ->
            txtStrength.text = "${v.toInt()} %"
            if (fromUser) prefs.edit().putInt("bgStrength", v.toInt()).apply()
            scheduleRender()
        }
        val pickBg = View.OnClickListener {
            val choices = ImageProcessor.Background.entries.filter {
                switchRed.isChecked || (it != ImageProcessor.Background.RED && it != ImageProcessor.Background.SUBJECT_RED)
            }
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.background)
                .setSingleChoiceItems(choices.map { backgroundName(it) }.toTypedArray(), choices.indexOf(background())) { d, pos ->
                    background = choices[pos]
                    prefs.edit().putString("background", background.name).apply()
                    d.dismiss()
                    showDepthDetails()
                    scheduleRender()
                }
                .show()
        }
        dropBg.setOnClickListener(pickBg)
        findViewById<TextInputLayout>(R.id.backgroundLayout).setEndIconOnClickListener(pickBg)
        val pickBgRaster = View.OnClickListener {
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.bg_raster)
                .setSingleChoiceItems(rasterNames, bgRaster.ordinal) { d, pos ->
                    bgRaster = ImageProcessor.Raster.entries[pos]
                    prefs.edit().putString("bgRaster", bgRaster.name).apply()
                    dropBgRaster.setText(rasterNames[pos])
                    d.dismiss()
                    scheduleRender()
                }
                .show()
        }
        dropBgRaster.setOnClickListener(pickBgRaster)
        findViewById<TextInputLayout>(R.id.bgRasterLayout).setEndIconOnClickListener(pickBgRaster)
    }

    private fun backgroundName(b: ImageProcessor.Background) = getString(when (b) {
        ImageProcessor.Background.SAME -> R.string.bg_same
        ImageProcessor.Background.LIGHTER -> R.string.bg_lighter
        ImageProcessor.Background.DARKER -> R.string.bg_darker
        ImageProcessor.Background.LOOK -> R.string.bg_look
        ImageProcessor.Background.RED -> R.string.bg_red
        ImageProcessor.Background.SUBJECT_RED -> R.string.bg_subject_red
    })

    // Boundary only with a split; strength for lighter/darker, the raster for another look
    private fun showDepthDetails() {
        if (!::sliderBgStrength.isInitialized) return
        val b = background()
        findViewById<TextInputEditText>(R.id.dropBackground).setText(backgroundName(b))
        fun show(id: Int, on: Boolean) { findViewById<View>(id).visibility = if (on) View.VISIBLE else View.GONE }
        show(R.id.depthCutBox, b != ImageProcessor.Background.SAME)
        show(R.id.bgStrengthBox, b == ImageProcessor.Background.LIGHTER || b == ImageProcessor.Background.DARKER)
        show(R.id.bgRasterLayout, b == ImageProcessor.Background.LOOK)
        // A depth split decides the red itself; the tone band would have no effect
        show(R.id.redBand, switchRed.isChecked &&
            (depth == null || (b != ImageProcessor.Background.RED && b != ImageProcessor.Background.SUBJECT_RED)))
    }

    private fun buildFavorites() {
        val group = findViewById<com.google.android.material.chip.ChipGroup>(R.id.favGroup)
        group.removeAllViews()
        val list = loadFavorites()
        for (f in list) group.addView(Chip(this).apply {
            text = favoriteName(f)
            setOnClickListener { applyFavorite(f) }
            setOnLongClickListener {
                MaterialAlertDialogBuilder(this@MainActivity)
                    .setMessage(getString(R.string.fav_delete, favoriteName(f)))
                    .setPositiveButton(R.string.profile_delete) { _, _ -> saveFavorites(list - f); buildFavorites() }
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
                true
            }
        })
        group.addView(Chip(this).apply {
            text = getString(R.string.fav_add)
            chipIcon = getDrawable(R.drawable.ic_star_outline)
            setOnClickListener {
                val f = currentFavorite()
                if (f !in list) { saveFavorites(list + f); buildFavorites() }
                toast(getString(R.string.fav_saved, favoriteName(f)))
            }
        })
    }

    private fun applyFavorite(f: Favorite) {
        val look = ImageProcessor.Raster.entries.firstOrNull { it.name == f.raster } ?: return
        raster = look
        prefs.edit().putString("raster", look.name).apply()
        findViewById<TextInputEditText>(R.id.dropRaster).setText(resources.getStringArray(R.array.raster)[look.ordinal])
        if (!switchRed.isChecked) findViewById<MaterialButtonToggleGroup>(R.id.toggleSpeed).check(if (f.fine) R.id.speedFine else R.id.speedFast)
        sliderDensity.value = f.density.toFloat().coerceIn(sliderDensity.valueFrom, sliderDensity.valueTo)
        sliderBrightness.value = f.brightness.toFloat()
        sliderContrast.value = f.contrast.toFloat()
        scheduleRender()
    }

    // ---------- Paper profiles and calibration ----------

    private fun showProfile() {
        findViewById<TextInputEditText>(R.id.dropProfile).setText(profile?.name ?: getString(R.string.profile_standard))
        showCalStatus()
    }

    // The 1-bit looks with a measured correction: profile key, look, caption on the chart
    private val CAL_LOOKS = listOf(
        Triple(PaperProfile.MONO, ImageProcessor.Raster.PHOTO, "CAL"),
        Triple(PaperProfile.BAYER, ImageProcessor.Raster.BAYER, "CAL AMIGA F"),
        Triple(PaperProfile.BLUE, ImageProcessor.Raster.BLUE, "CAL NOISE"),
        Triple(PaperProfile.RIEMERSMA, ImageProcessor.Raster.RIEMERSMA, "CAL RIEMERSMA"),
        Triple(PaperProfile.AMIGA, ImageProcessor.Raster.AMIGA, "CAL AMIGA"),
    )

    // Which curve the current settings calibrate and use: gray (16 levels) or one of CAL_LOOKS;
    // null for looks without a measured correction
    private fun calMode(): String? = when {
        switchRed.isChecked -> null
        raster == ImageProcessor.Raster.PHOTO && prefs.getInt("speed", 1) == 1 -> PaperProfile.GRAY
        else -> CAL_LOOKS.firstOrNull { it.second == raster }?.first
    }

    private fun lookOf(mode: String) = CAL_LOOKS.firstOrNull { it.first == mode }?.second ?: ImageProcessor.Raster.PHOTO

    private fun modeName(mode: String) = when (mode) {
        PaperProfile.GRAY -> getString(R.string.cal_mode_gray)
        PaperProfile.MONO -> getString(R.string.cal_mode_mono)
        else -> resources.getStringArray(R.array.raster)[lookOf(mode).ordinal]
    }

    // A look without its own curve uses the photo look's (the profile's, else built-in); coarse
    // Amiga none (see ImageProcessor.measured)
    private fun monoCurve(): FloatArray? = monoCurve(raster)

    // Also for the background look of a depth split
    private fun monoCurve(look: ImageProcessor.Raster): FloatArray? {
        val d = sliderDensity.value.toInt()
        val p = profile ?: return null
        val own = CAL_LOOKS.firstOrNull { it.second == look }?.let { p.curve(it.first, d) }
        return own ?: if (look == ImageProcessor.Raster.AMIGA) null else p.curve(PaperProfile.MONO, d)
    }

    // The red choices only apply on a two-colour roll
    private fun background() = background.takeIf {
        switchRed.isChecked || (it != ImageProcessor.Background.RED && it != ImageProcessor.Background.SUBJECT_RED)
    } ?: ImageProcessor.Background.SAME

    // Under the profile field: whether the current mode and density have a measured curve
    private fun showCalStatus() {
        val layout = findViewById<TextInputLayout>(R.id.profileLayout)
        val mode = calMode()
        if (mode == null) { layout.helperText = null; return }
        val d = sliderDensity.value.toInt()
        val name = "${modeName(mode)} D$d"
        layout.helperText = when {
            profile?.curve(mode, d) != null -> getString(R.string.cal_status_own, name)
            mode == PaperProfile.AMIGA -> getString(R.string.cal_status_none, name)
            mode != PaperProfile.GRAY && mode != PaperProfile.MONO -> getString(R.string.cal_status_photo, name)
            else -> getString(R.string.cal_status_default, name)
        }
    }

    private fun selectProfile(p: PaperProfile?) {
        profile = p
        prefs.edit().putString("profile", p?.id).apply()
        showProfile()
        scheduleRender()
    }

    // Name, then what the profile holds, e.g. "16 grays D3, 1-bit D3 · 6978061953334"
    private fun profileSummary(p: PaperProfile): String {
        val curves = p.curves.keys.sorted().map { k ->
            modeName(k.filter { !it.isDigit() }) +
                " D" + k.filter { it.isDigit() }
        }
        val info = listOfNotNull(curves.joinToString(", ").ifEmpty { null }, p.barcode).joinToString(" · ")
        return if (info.isEmpty()) p.name else "${p.name}\n$info"
    }

    private fun pickProfile() {
        val list = listOf<PaperProfile?>(null) + profiles
        val names = list.map { p -> p?.let { profileSummary(it) } ?: getString(R.string.profile_standard) }.toTypedArray()
        val b = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.paper_profile)
            .setSingleChoiceItems(names, list.indexOf(profile).coerceAtLeast(0)) { d, pos -> selectProfile(list[pos]); d.dismiss() }
            .setPositiveButton(R.string.profile_calibrate) { _, _ -> showCalibration() }
        if (profiles.isNotEmpty()) b.setNeutralButton(R.string.profile_manage) { _, _ -> manageProfiles() }
        b.show()
    }

    // One checkbox per measured curve (profile, mode, density); the profile goes when its last curve does
    private fun manageProfiles() {
        val entries = profiles.flatMap { p -> p.curves.keys.sorted().map { k -> p to k } }
        val labels = entries.map { (p, k) ->
            "${p.name} · ${modeName(k.filter { !it.isDigit() })} D${k.filter { it.isDigit() }}"
        }.toTypedArray()
        val checked = BooleanArray(entries.size)
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.profile_manage)
            .setMultiChoiceItems(labels, checked) { _, i, on -> checked[i] = on }
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.profile_delete) { _, _ ->
                entries.filterIndexed { i, _ -> checked[i] }.forEach { (p, k) -> p.curves.remove(k) }
                profiles.removeAll { it.curves.isEmpty() }
                PaperProfile.save(prefs, profiles)
                if (profile != null && profile !in profiles) selectProfile(null) else { showProfile(); scheduleRender() }
            }
            .show()
    }

    // A roll with an RFID tag sets the two-colour switch (a two-colour roll printed in the black-only
    // modes comes out a faint red) and selects the profile saved for its barcode
    private suspend fun readRollRetry(p: B21Printer): B21Printer.Roll? {
        repeat(3) { i ->
            p.readRoll()?.let { return it }
            if (i < 2) delay(400)
        }
        return null
    }

    private fun onRoll(r: B21Printer.Roll?) {
        roll = r
        if (r == null) {
            log("No roll tag read")
            // A red/black state taken from an earlier roll must not stay on without one
            if (rollAutoRed) { rollAutoRed = false; switchRed.isChecked = false }
            return
        }
        log("Roll ${r.barcode}, ${r.used} of ${r.total} labels used")
        val two = prefs.getBoolean("twoColor_${r.barcode}", r.barcode in TWO_COLOR_ROLLS)
        if (two != switchRed.isChecked) {
            switchRed.isChecked = two
            rollAutoRed = two
            toast(getString(if (two) R.string.roll_two_color else R.string.roll_black, r.barcode))
        }
        val p = profiles.firstOrNull { it.barcode == r.barcode } ?: return
        if (p != profile) {
            selectProfile(p)
            toast(getString(R.string.profile_auto, r.barcode, p.name))
        }
    }

    // Prints a chart in the current mode, reads a photo of it, and keeps the measured curve in a
    // paper profile (for this mode and density)
    private fun showCalibration() {
        val l = selectedLabel
        if (switchRed.isChecked) return toast(getString(R.string.cal_no_red))
        val mode = calMode() ?: return toast(getString(R.string.cal_no_look))
        val gray = mode == PaperProfile.GRAY
        val density = sliderDensity.value.toInt()
        val (wmm, hmm) = physSizeMm()
        val w = minOf(ImageProcessor.mmToPx(wmm), if (gray) Niimbot.COLOR_HEAD else Niimbot.PRINTHEAD_PIXELS)
        val h = ImageProcessor.mmToPx(hmm)
        // Patches of at least 2 mm (gray bands 0.7 mm high), so the photo has enough of each to measure
        val chart = TestChart.calChart(w, h, gray)
        val small = chart.patches[0].let { minOf(it.w, it.h) < if (gray) 8 else 24 }
        if (l.round || l.window != null || small) return toast(getString(R.string.cal_plain_label))
        val key = mode + density
        val pad = dp(24f).toInt()
        val gap = dp(8f).toInt()
        var reading: FloatArray? = null

        fun button(label: Int, onClick: () -> Unit) =
            MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                setText(label); setOnClickListener { onClick() }
            }
        val status = TextView(this).apply { setPadding(0, gap, 0, gap) }
        val imgChart = ImageView(this).apply { adjustViewBounds = true; visibility = View.GONE }
        val imgPlot = ImageView(this).apply { adjustViewBounds = true; visibility = View.GONE; setPadding(0, gap, 0, gap) }
        val name = TextInputEditText(this).apply {
            setSingleLine(true)
            setText(profile?.name ?: roll?.let { getString(R.string.profile_roll, it.barcode) }
                ?: getString(R.string.profile_new, profiles.size + 1))
        }
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, gap, pad, 0)
            addView(TextView(this@MainActivity).apply {
                text = getString(R.string.cal_info,
                    modeName(mode), density, l.title(this@MainActivity))
                setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_TitleSmall)
            })
            addView(TextView(this@MainActivity).apply { setText(R.string.cal_steps); setPadding(0, gap, 0, gap) })
            addView(button(R.string.cal_print) { printCalibration(mode, w, h, density) }.apply { isEnabled = printer != null })
            addView(LinearLayout(this@MainActivity).apply {
                addView(button(R.string.cal_photo) { takePhoto("chart.jpg", REQ_CAL_CAMERA) }.apply { setIconResource(R.drawable.ic_camera) },
                    LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                addView(button(R.string.cal_pick) {
                    startActivityForResult(Intent(Intent.ACTION_GET_CONTENT).setType("image/*"), REQ_CAL_GALLERY)
                }.apply { setIconResource(R.drawable.ic_gallery) }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = gap })
            })
            addView(status)
            addView(imgChart)
            addView(imgPlot)
            addView(TextInputLayout(this@MainActivity).apply { hint = getString(R.string.cal_name); addView(name) })
        }
        val dlg = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.cal_title)
            .setView(NestedScrollView(this).apply { addView(body) })
            .setPositiveButton(R.string.cal_save, null)
            .setNegativeButton(R.string.close, null)
            .setOnDismissListener { calPhoto = null }
            .create()
        dlg.setCanceledOnTouchOutside(false)
        // A measured chart that was not saved must not vanish by accident
        fun tryClose() {
            if (reading == null) { dlg.dismiss(); return }
            MaterialAlertDialogBuilder(this)
                .setMessage(R.string.cal_discard)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.cal_discard_yes) { _, _ -> dlg.dismiss() }
                .show()
        }
        dlg.setOnKeyListener { _, key, ev ->
            if (key == android.view.KeyEvent.KEYCODE_BACK && ev.action == android.view.KeyEvent.ACTION_UP) { tryClose(); true } else false
        }
        dlg.show()
        dlg.getButton(android.content.DialogInterface.BUTTON_NEGATIVE).setOnClickListener { tryClose() }
        val save = dlg.getButton(android.content.DialogInterface.BUTTON_POSITIVE)
        save.isEnabled = false
        save.setOnClickListener {
            val dark = reading ?: return@setOnClickListener
            val nm = name.text?.toString()?.trim().orEmpty().ifEmpty { getString(R.string.profile_new, profiles.size + 1) }
            val p = profiles.firstOrNull { it.name == nm }
                ?: PaperProfile(java.util.UUID.randomUUID().toString(), nm).also { profiles += it }
            p.curves[key] = dark
            // One profile per roll type
            roll?.let { r -> profiles.filter { it.barcode == r.barcode }.forEach { it.barcode = null }; p.barcode = r.barcode }
            PaperProfile.save(prefs, profiles)
            selectProfile(p)
            toast(getString(R.string.cal_saved, nm))
            dlg.dismiss()
        }
        calPhoto = { uri ->
            status.setText(R.string.cal_reading)
            imgChart.visibility = View.GONE
            imgPlot.visibility = View.GONE
            save.isEnabled = false
            reading = null
            scope.launch {
                val r = withContext(Dispatchers.Default) {
                    runCatching { ChartReader.read(decode(uri), w, h, chart) }
                        .onFailure { log("Calibration: ${it.message}") }.getOrNull()
                }
                if (r == null) { status.setText(R.string.cal_not_found); return@launch }
                reading = r.dark
                status.text = getString(R.string.cal_result, r.dark.joinToString(" ") { Math.round(it * 100).toString() })
                imgChart.setImageBitmap(r.rectified)
                imgChart.visibility = View.VISIBLE
                imgPlot.setImageBitmap(ChartReader.plot(r.dark, 600, 300))
                imgPlot.visibility = View.VISIBLE
                save.isEnabled = true
            }
        }
    }

    private fun printCalibration(mode: String, w: Int, h: Int, density: Int) {
        val p = printer ?: return
        val labelType = intArrayOf(1, 2, 3, 5)[paperIndex]
        val look = lookOf(mode)
        val caption = (CAL_LOOKS.firstOrNull { it.first == mode }?.third ?: "CAL") + " D$density"
        printJob { onProgress ->
            log("Calibration chart ${w}×$h, $mode, density $density …")
            if (mode == PaperProfile.GRAY) {
                val levels = withContext(Dispatchers.Default) { TestChart.grayCalibration(w, h, "CAL 16B D$density") }
                val r = ImageProcessor.Result(ByteArray(w * h), w, h, Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888), levels)
                p.printResult(r, false, density, labelType, 1, onProgress)
            } else {
                val bits = withContext(Dispatchers.Default) {
                    val px = IntArray(w * h)
                    TestChart.calibration(w, h, caption, look)
                        .getPixels(px, 0, w, 0, 0, w, h)
                    ByteArray(w * h) { if (px[it] and 0xFFFFFF == 0) 1 else 0 }
                }
                p.print(bits, w, h, density, labelType, 1, onProgress)
            }
        }
    }

    // The area the photo goes into, in print dots
    private fun photoAreaPx(): Pair<Int, Int> {
        val l = selectedLabel
        val (wmm, hmm) = labelSizeMm()
        val win = l.window
        return when {
            win != null -> ImageProcessor.mmToPx(win.w) to ImageProcessor.mmToPx(win.h)
            l.round -> ImageProcessor.mmToPx(wmm).let { it to it }
            instantOn() -> ImageProcessor.instantPhotoRect(ImageProcessor.mmToPx(minOf(wmm, capMm())), ImageProcessor.mmToPx(hmm))
                .let { it.width() to it.height() }
            else -> ImageProcessor.mmToPx(minOf(wmm, capMm())) to ImageProcessor.mmToPx(hmm)
        }
    }

    // What the loaded calibration chart was made for. It is pre-dithered at print resolution, so it
    // must be rebuilt (not scaled) when the label, look or density changes.
    private var chartKey = ""
    private fun currentChartKey() = "${photoAreaPx()} ${calMode()} ${sliderDensity.value.toInt()}"

    // Test chart sized to the area the photo goes into, loaded like a cropped photo. The calibration
    // chart is dithered with the current look (photo, Amiga fine, blue noise, Riemersma).
    private fun loadTestChart(calibrate: Boolean = false) {
        val l = selectedLabel
        val (w, h) = photoAreaPx()
        val mode = calMode()?.takeIf { it != PaperProfile.GRAY } ?: PaperProfile.MONO
        val key = currentChartKey()
        scope.launch {
            val density = sliderDensity.value.toInt()
            val caption = (CAL_LOOKS.firstOrNull { it.first == mode }?.third ?: "CAL") + " D$density"
            // The calibration chart is made at print resolution, so it is drawn 1:1
            val chart = withContext(Dispatchers.Default) {
                if (calibrate) TestChart.calibration(w, h, caption, lookOf(mode)) else TestChart.create(w, h, l.round)
            }
            chartKey = key
            calibration = calibrate
            sweep = false
            findViewById<MaterialButtonToggleGroup>(R.id.toggleAppMode).check(R.id.modeAppPhoto)
            ImageStore.reset(chart)
            ImageStore.cropped = chart
            source = chart
            depth = null
            applyModeVisibility()
            scheduleRender()
            log((if (calibrate) "Calibration chart" else "Test chart") + " ${w}×${h} px (${l.title(this@MainActivity)})")
        }
    }

    // ---- Firmware flash (BLE) ----
    private fun pickFirmware() {
        if (printer == null) return toast(getString(R.string.print_need_printer))
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE).setType("*/*")
        try { startActivityForResult(i, REQ_FW_FILE) } catch (e: Exception) { toast(getString(R.string.no_file_picker)) }
    }

    private fun confirmAndFlash(bytes: ByteArray, name: String) {
        val m = Regex("(\\d+)\\.(\\d+)").find(name)
        val major = m?.groupValues?.get(1)?.toIntOrNull() ?: 0
        val minor = m?.groupValues?.get(2)?.toIntOrNull() ?: 0
        if (major == 0) return toast(getString(R.string.flash_no_version))
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.flash_title)
            .setMessage(getString(R.string.flash_message, name, bytes.size, "$major.$minor"))
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.flash) { _, _ -> runFlash(bytes, major, minor) }
            .show()
    }

    private fun runFlash(bytes: ByteArray, major: Int, minor: Int) {
        val p = printer ?: return
        val msg = TextView(this).apply { setPadding(dp(24f).toInt(), dp(16f).toInt(), dp(24f).toInt(), dp(8f).toInt()) }
        val bar = com.google.android.material.progressindicator.LinearProgressIndicator(this).apply {
            isIndeterminate = true
            val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            lp.setMargins(dp(24f).toInt(), 0, dp(24f).toInt(), dp(16f).toInt()); layoutParams = lp
        }
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; addView(msg); addView(bar) }
        msg.setText(R.string.flash_starting)
        val dlg = MaterialAlertDialogBuilder(this).setTitle(R.string.flash_progress_title)
            .setView(box).setCancelable(false).create()
        dlg.show()
        scope.launch {
            try {
                p.firmwareUpgrade(bytes, major, minor) { cur, tot ->
                    runOnUiThread {
                        bar.isIndeterminate = false; bar.max = tot; bar.setProgressCompat(cur, true)
                        msg.text = getString(R.string.flash_block, cur, tot)
                    }
                }
                runOnUiThread {
                    dlg.dismiss()
                    MaterialAlertDialogBuilder(this@MainActivity).setTitle(R.string.flash_done_title)
                        .setMessage(R.string.flash_done)
                        .setPositiveButton("OK", null).show()
                }
            } catch (e: Exception) {
                runOnUiThread {
                    dlg.dismiss()
                    MaterialAlertDialogBuilder(this@MainActivity).setTitle(R.string.flash_failed_title)
                        .setMessage(getString(R.string.flash_failed, e.message))
                        .setPositiveButton("OK", null).show()
                }
            }
        }
    }

    companion object {
        const val REQ_CAMERA = 1
        const val REQ_GALLERY = 2
        const val REQ_PERMS = 3
        const val REQ_ENABLE_BT = 4
        const val REQ_SPEECH = 5
        const val REQ_FW_FILE = 7
        const val REQ_CROP = 6
        const val REQ_CAMERA_PERM = 8
        const val REQ_CAL_CAMERA = 9
        const val REQ_CAL_GALLERY = 10
        const val REQ_BITMAP_FILE = 11
        // Barcodes of known black + red rolls; others are remembered from the switch
        val TWO_COLOR_ROLLS = setOf("030325120")
    }
}
