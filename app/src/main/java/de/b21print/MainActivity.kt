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
import android.graphics.Color
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
    private var raster = ImageProcessor.Raster.PHOTO
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
        switchInvert = findViewById(R.id.switchInvert)
        toggleFont = findViewById(R.id.toggleFont)
        toggleStyle = findViewById(R.id.toggleStyle)
        toggleFrame = findViewById(R.id.toggleFrame)
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
        toggleFont.check(fontIds[prefs.getInt("font", 0).coerceIn(0, 2)])
        if (prefs.getBoolean("bold", true)) toggleStyle.check(R.id.styleBold)
        toggleFrame.check(frameIds[prefs.getInt("frame", 0).coerceIn(0, 4)])
        val styleChanged = MaterialButtonToggleGroup.OnButtonCheckedListener { _, _, _ ->
            prefs.edit().putInt("font", fontIds.indexOf(toggleFont.checkedButtonId).coerceAtLeast(0))
                .putInt("frame", frameIds.indexOf(toggleFrame.checkedButtonId).coerceAtLeast(0))
                .putBoolean("bold", R.id.styleBold in toggleStyle.checkedButtonIds).apply()
            scheduleRender()
            updateTemplateButton()
        }
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
            redBand.visibility = if (on) View.VISIBLE else View.GONE
            toggleSpeed.isEnabled = !on
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
        txtOffX.text = "%+.1f mm".format(0f)
        txtOffY.text = "%+.1f mm".format(0f)
        sliderOffsetX.addOnChangeListener { _, v, fromUser ->
            txtOffX.text = "%+.1f mm".format(v)
            if (fromUser) prefs.edit().putFloat("offX_${selectedLabel.code}", v).apply()
            scheduleRender()
        }
        sliderOffsetY.addOnChangeListener { _, v, fromUser ->
            txtOffY.text = "%+.1f mm".format(v)
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
        sliderBrightness.addOnChangeListener { _, _, _ -> scheduleRender() }
        sliderContrast.addOnChangeListener { _, _, _ -> scheduleRender() }
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
        applyModeVisibility()

        // Image shared from another app
        // Printer and image survive rotation
        Booth.log = ::log
        Booth.onDisconnected = {
            runOnUiThread {
                printer = null
                updatePrinterChip()
                log("Disconnected")
            }
        }
        printer = Booth.printer
        if (savedInstanceState != null) source = ImageStore.cropped

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
        show(R.id.windowTuning, win)
        show(R.id.switchInstant, photo && !win && !selectedLabel.round)
        show(R.id.switchSoftEdge, photo)
        show(R.id.switchOverscan, photo && selectedLabel.round)
        show(R.id.captionLayout, photo && !win && switchInstant.isChecked)
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
    )

    private fun applyTextStyle(s: TextStyle) {
        Labels.ALL.firstOrNull { it.name == s.label }?.let { if (it != selectedLabel) selectLabel(it) }
        toggleFont.check(fontIds[s.font.coerceIn(0, 2)])
        toggleFrame.check(frameIds[s.frame.coerceIn(0, 4)])
        listOf(R.id.styleBold to s.bold, R.id.styleItalic to s.italic, R.id.styleUnderline to s.underline)
            .forEach { (id, on) -> if (on) toggleStyle.check(id) else toggleStyle.uncheck(id) }
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
            }
            source = ImageStore.cropped
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
                source = withContext(Dispatchers.IO) { decode(uri) }
                ImageStore.reset(source!!)
                source = null
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

    private fun scheduleRender() {
        if (!::sliderOffsetY.isInitialized) return
        showCalStatus()
        renderJob?.cancel()
        renderJob = scope.launch {
            delay(120)
            val label = selectedLabel
            val (wmm, hmm) = labelSizeMm()
            txtDims.text = "${label.title(this@MainActivity)} · " + getString(R.string.size_rect, fmt(wmm), fmt(hmm))
            val r: ImageProcessor.Result
            if (textMode) {
                val style = currentTextStyle()
                r = withContext(Dispatchers.Default) {
                    ImageProcessor.renderText(style, wmm, hmm, label.window, label.frameColor, label.round)
                }
                rendered = if (style.text.isNotBlank()) r else null
            } else {
                // A fixed crop fills the area, a free crop is fitted and centered
                val opts = photoOptions(if (ImageStore.free) ImageProcessor.Fit.CONTAIN else ImageProcessor.Fit.COVER)
                val src = source
                r = withContext(Dispatchers.Default) {
                    if (sweep) ImageProcessor.renderSweep(src, wmm, hmm, label.window, label.frameColor, opts, label.round,
                        sweepGammas().map { "%.1f".format(Locale.ROOT, it) to opts.copy(toneGamma = it) })
                    else ImageProcessor.render(src, wmm, hmm, label.window, label.frameColor, opts, label.round)
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
            instant = switchInstant.isChecked && !label.round, caption = "",
        )
        return ImageProcessor.Options(
            fit = fit,
            rotation = 0,
            raster = raster,
            brightness = sliderBrightness.value.toInt(),
            contrast = sliderContrast.value.toInt(),
            invert = switchInvert.isChecked,
            instant = switchInstant.isChecked && !label.round,
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
            caption = editCaption.text?.toString().orEmpty(),
            offsetXMm = if (label.window != null) sliderOffsetX.value.toDouble() else 0.0,
            offsetYMm = if (label.window != null) sliderOffsetY.value.toDouble() else 0.0,
        )
    }

    private fun labelSizeMm(): Pair<Double, Double> {
        val l = selectedLabel
        val wmm = if (l.freestyle) num(editWidth.text, 50.0).coerceIn(5.0, Niimbot.MAX_WIDTH_MM) else l.widthMm
        val hmm = if (l.freestyle) num(editHeight.text, 30.0).coerceIn(5.0, Niimbot.MAX_HEIGHT_MM) else l.heightMm
        return wmm to hmm
    }

    // Aspect ratio of the area the photo goes into
    private fun photoAspect(): Float {
        val l = selectedLabel
        l.window?.let { return (it.w / it.h).toFloat() }
        if (l.round) return 1f
        val (wmm, hmm) = labelSizeMm()
        if (switchInstant.isChecked) {
            val r = ImageProcessor.instantPhotoRect(ImageProcessor.mmToPx(minOf(wmm, 50.0)), ImageProcessor.mmToPx(hmm))
            return r.width().toFloat() / r.height()
        }
        return (minOf(wmm, 50.0) / hmm).toFloat()
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
                onRoll(p.readRoll())
            } catch (e: Exception) {
                p.disconnect()
                log("Error: ${e.message}")
            }
            updatePrinterChip(device.name)
        }
    }

    // Photo booth: take a selfie and print it with the current settings
    private fun openBooth() {
        // Without a printer you can still set up camera and framing
        if (printer == null) toast(getString(R.string.booth_no_printer))
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
        )
        startActivity(Intent(this, BoothActivity::class.java))
    }

    private fun print() {
        val p = printer ?: return
        val r = rendered ?: return
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
            log("Printing ${r.width}×${r.height} px, $mode, density $density …")
            p.printResult(r, twoColor, density, labelType, copies, it)
            textStyle?.let { history.addRecent(it) }
        }
    }

    // Runs a print with the progress bar and a disabled print button
    private fun printJob(job: suspend (onProgress: (Float) -> Unit) -> Unit) {
        btnPrint.isEnabled = false
        btnPrint.setText(R.string.printing)
        progress.visibility = View.VISIBLE
        progress.setProgressCompat(0, false)
        scope.launch {
            try {
                job { f -> runOnUiThread { progress.setProgressCompat((f * 1000).toInt(), true) } }
            } catch (e: Exception) {
                log("Print error: ${e.message}")
            }
            progress.visibility = View.INVISIBLE
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
        val h = ImageProcessor.mmToPx(labelSizeMm().second)
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
            setText("test get sensor"); setSingleLine(true); textSize = 14f
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
                for (c in listOf("test get sensor", "test rfidMode 3", "#10001")) {
                    addView(Chip(this@MainActivity).apply {
                        text = c; setOnClickListener { input.setText(c) }
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
                button("Red/black test", p != null) { dialog?.dismiss(); printColorTest(gray = false) },
            ))
            addView(button("Save print bitmap") {
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
            })
            addView(button("Print bitmap file …", p != null) {
                dialog?.dismiss()
                startActivityForResult(Intent(Intent.ACTION_GET_CONTENT).setType("image/*"), REQ_BITMAP_FILE)
            })
            addView(button("Printer diagnostics", p != null) { scope.launch { p?.diagnose() } })
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
        val dlg = MaterialAlertDialogBuilder(this)
            .setTitle("Debug console")
            .setView(NestedScrollView(this).apply { addView(body) })
            .setNegativeButton(R.string.close, null)
            .setOnDismissListener { p?.rawListener = null; consoleSink = null }
            .create()
        dialog = dlg
        dlg.show()
    }

    // ---------- Paper profiles and calibration ----------

    private fun showProfile() {
        findViewById<TextInputEditText>(R.id.dropProfile).setText(profile?.name ?: getString(R.string.profile_standard))
        showCalStatus()
    }

    // Which curve the current settings calibrate and use: gray (16 levels), mono (1-bit photo look),
    // bayer (Amiga fine); null for looks without a measured correction
    private fun calMode(): String? = when {
        switchRed.isChecked -> null
        raster == ImageProcessor.Raster.PHOTO && prefs.getInt("speed", 1) == 1 -> PaperProfile.GRAY
        raster == ImageProcessor.Raster.PHOTO -> PaperProfile.MONO
        raster == ImageProcessor.Raster.BAYER -> PaperProfile.BAYER
        else -> null
    }

    private fun modeName(mode: String) = getString(when (mode) {
        PaperProfile.GRAY -> R.string.cal_mode_gray
        PaperProfile.BAYER -> R.string.raster_bayer
        else -> R.string.cal_mode_mono
    })

    // Amiga fine without its own curve uses the photo look's (the profile's, else built-in)
    private fun monoCurve(): FloatArray? {
        val d = sliderDensity.value.toInt()
        val p = profile ?: return null
        return (if (raster == ImageProcessor.Raster.BAYER) p.curve(PaperProfile.BAYER, d) else null)
            ?: p.curve(PaperProfile.MONO, d)
    }

    // Under the profile field: whether the current mode and density have a measured curve
    private fun showCalStatus() {
        val layout = findViewById<TextInputLayout>(R.id.profileLayout)
        val mode = calMode()
        if (mode == null) { layout.helperText = null; return }
        val d = sliderDensity.value.toInt()
        val name = "${modeName(mode)} D$d"
        layout.helperText = when {
            profile?.curve(mode, d) != null -> getString(R.string.cal_status_own, name)
            mode == PaperProfile.BAYER -> getString(R.string.cal_status_photo, name)
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
        profile?.let { cur ->
            b.setNeutralButton(R.string.profile_delete) { _, _ ->
                MaterialAlertDialogBuilder(this)
                    .setMessage(getString(R.string.profile_delete_confirm, cur.name))
                    .setPositiveButton(R.string.profile_delete) { _, _ ->
                        profiles.remove(cur)
                        PaperProfile.save(prefs, profiles)
                        selectProfile(null)
                    }
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
            }
        }
        b.show()
    }

    // A roll with an RFID tag sets the two-colour switch (a two-colour roll printed in the black-only
    // modes comes out a faint red) and selects the profile saved for its barcode
    private fun onRoll(r: B21Printer.Roll?) {
        roll = r
        if (r == null) return
        log("Roll ${r.barcode}, ${r.used} of ${r.total} labels used")
        val two = prefs.getBoolean("twoColor_${r.barcode}", r.barcode in TWO_COLOR_ROLLS)
        if (two != switchRed.isChecked) {
            switchRed.isChecked = two
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
        val (wmm, hmm) = labelSizeMm()
        val w = minOf(ImageProcessor.mmToPx(wmm), if (gray) Niimbot.COLOR_HEAD else Niimbot.PRINTHEAD_PIXELS)
        val h = ImageProcessor.mmToPx(hmm)
        // Patches of at least 2 mm, so the photo has enough of each to measure
        val small = TestChart.calLayout(w, h, if (gray) 4 else 5).patches[0].let { minOf(it.w, it.h) < 24 }
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
                addView(button(R.string.cal_photo) { takePhoto("chart.jpg", REQ_CAL_CAMERA) },
                    LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                addView(button(R.string.cal_pick) {
                    startActivityForResult(Intent(Intent.ACTION_GET_CONTENT).setType("image/*"), REQ_CAL_GALLERY)
                }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = gap })
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
        dlg.show()
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
                    runCatching { ChartReader.read(decode(uri), w, h, if (gray) 4 else 5) }
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
        val bayer = mode == PaperProfile.BAYER
        printJob { onProgress ->
            log("Calibration chart ${w}×$h, $mode, density $density …")
            if (mode == PaperProfile.GRAY) {
                val levels = withContext(Dispatchers.Default) { TestChart.grayCalibration(w, h, "CAL 16 D$density") }
                val r = ImageProcessor.Result(ByteArray(w * h), w, h, Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888), levels)
                p.printResult(r, false, density, labelType, 1, onProgress)
            } else {
                val bits = withContext(Dispatchers.Default) {
                    val px = IntArray(w * h)
                    TestChart.calibration(w, h, if (bayer) "CAL AMIGA F D$density" else "CAL D$density", bayer)
                        .getPixels(px, 0, w, 0, 0, w, h)
                    ByteArray(w * h) { if (px[it] and 0xFFFFFF == 0) 1 else 0 }
                }
                p.print(bits, w, h, density, labelType, 1, onProgress)
            }
        }
    }

    // Test chart sized to the area the photo goes into, loaded like a cropped photo
    private fun loadTestChart(calibrate: Boolean = false) {
        val l = selectedLabel
        val (wmm, hmm) = labelSizeMm()
        val w: Int
        val h: Int
        val win = l.window
        when {
            win != null -> { w = ImageProcessor.mmToPx(win.w); h = ImageProcessor.mmToPx(win.h) }
            l.round -> { w = ImageProcessor.mmToPx(wmm); h = w }
            switchInstant.isChecked -> {
                val r = ImageProcessor.instantPhotoRect(ImageProcessor.mmToPx(minOf(wmm, 50.0)), ImageProcessor.mmToPx(hmm))
                w = r.width(); h = r.height()
            }
            else -> { w = ImageProcessor.mmToPx(minOf(wmm, 50.0)); h = ImageProcessor.mmToPx(hmm) }
        }
        scope.launch {
            val density = sliderDensity.value.toInt()
            // The calibration chart is made at print resolution, so it is drawn 1:1
            val chart = withContext(Dispatchers.Default) {
                if (calibrate) TestChart.calibration(w, h, "CAL D$density") else TestChart.create(w, h, l.round)
            }
            calibration = calibrate
            sweep = false
            findViewById<MaterialButtonToggleGroup>(R.id.toggleAppMode).check(R.id.modeAppPhoto)
            ImageStore.reset(chart)
            ImageStore.cropped = chart
            source = chart
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
