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
import com.google.android.material.textfield.MaterialAutoCompleteTextView
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
    private lateinit var switchPolaroid: MaterialSwitch
    private lateinit var switchInvert: MaterialSwitch
    private lateinit var toggleMode: MaterialButtonToggleGroup
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
        switchPolaroid = findViewById(R.id.switchPolaroid)
        switchInvert = findViewById(R.id.switchInvert)
        toggleMode = findViewById(R.id.toggleMode)
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
        findViewById<MaterialButton>(R.id.btnBooth).setOnClickListener { openBooth() }
        watchKeyboard()
        btnPrint.setOnClickListener { print() }

        // Photo / sign
        val toggleApp = findViewById<MaterialButtonToggleGroup>(R.id.toggleAppMode)
        toggleApp.addOnButtonCheckedListener { _, id, checked ->
            if (!checked) return@addOnButtonCheckedListener
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
        val dropPaper = findViewById<MaterialAutoCompleteTextView>(R.id.dropPaper)
        dropPaper.setText(resources.getStringArray(R.array.paper)[0], false)
        dropPaper.setOnItemClickListener { _, _, pos, _ -> paperIndex = pos }
        val txtDensity = findViewById<TextView>(R.id.txtDensity)
        sliderDensity.value = prefs.getInt("density", 3).toFloat()
        txtDensity.text = sliderDensity.value.toInt().toString()
        sliderDensity.addOnChangeListener { _, v, _ ->
            txtDensity.text = v.toInt().toString()
            prefs.edit().putInt("density", v.toInt()).apply()
        }

        // Label
        editWidth.doAfterTextChanged { onSizeEdited() }
        editHeight.doAfterTextChanged { onSizeEdited() }
        editCaption.doAfterTextChanged { scheduleRender() }
        switchPolaroid.setOnCheckedChangeListener { _, _ -> applyModeVisibility(); scheduleRender() }
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
        toggleMode.addOnButtonCheckedListener { _, _, checked -> if (checked) scheduleRender() }
        sliderBrightness.addOnChangeListener { _, _, _ -> scheduleRender() }
        sliderContrast.addOnChangeListener { _, _, _ -> scheduleRender() }
        findViewById<MaterialButton>(R.id.btnResetTone).setOnClickListener {
            sliderBrightness.value = 0f
            sliderContrast.value = 0f
        }

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

    private fun log(msg: String) = runOnUiThread {
        val t = SimpleDateFormat("HH:mm:ss", Locale.GERMANY).format(Date())
        txtLog.text = "$t  $msg\n${txtLog.text}"
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
        show(R.id.switchPolaroid, photo && !win && !selectedLabel.round)
        show(R.id.captionLayout, photo && !win && switchPolaroid.isChecked)
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
        if (!preset.polaroid) return frame
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
            switchPolaroid.isChecked = preset.polaroid
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
            val pol = switchPolaroid.isChecked
            selectLabel(Labels.FREESTYLE)
            switchPolaroid.isChecked = pol
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

    private fun takePhoto() {
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.CAMERA), REQ_CAMERA_PERM)
            return
        }
        val dir = File(cacheDir, "photos").apply { mkdirs() }
        val file = File(dir, "photo.jpg")
        val uri = FileProvider.getUriForFile(this, "de.b21print.files", file)
        photoUri = uri
        val intent = Intent(MediaStore.ACTION_IMAGE_CAPTURE)
            .putExtra(MediaStore.EXTRA_OUTPUT, uri)
            .addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        try {
            startActivityForResult(intent, REQ_CAMERA)
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
                source = withContext(Dispatchers.IO) {
                    // ImageDecoder applies the EXIF rotation of camera photos
                    ImageDecoder.decodeBitmap(ImageDecoder.createSource(contentResolver, uri)) { dec, info, _ ->
                        dec.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                        val max = maxOf(info.size.width, info.size.height)
                        if (max > 2400) {
                            val s = 2400f / max
                            dec.setTargetSize((info.size.width * s).toInt(), (info.size.height * s).toInt())
                        }
                    }
                }
                ImageStore.reset(source!!)
                source = null
                openCrop()
            } catch (e: Exception) {
                log("Could not load image: ${e.message}")
            }
        }
    }

    private fun num(v: CharSequence?, def: Double) = v.toString().replace(',', '.').toDoubleOrNull() ?: def

    private fun scheduleRender() {
        if (!::sliderOffsetY.isInitialized) return
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
                    ImageProcessor.render(src, wmm, hmm, label.window, label.frameColor, opts, label.round)
                }
                rendered = if (src != null) r else null
            }
            preview.setImageBitmap(r.preview)
            sizePreviewCard(r.preview.width, r.preview.height, label.round)
            updatePrintButton()
        }
    }

    private fun photoOptions(fit: ImageProcessor.Fit): ImageProcessor.Options {
        val label = selectedLabel
        return ImageProcessor.Options(
            fit = fit,
            rotation = 0,
            raster = when (toggleMode.checkedButtonId) {
                R.id.modeDots -> ImageProcessor.Raster.DOTS
                R.id.modeText -> ImageProcessor.Raster.TEXT
                else -> ImageProcessor.Raster.PHOTO
            },
            brightness = sliderBrightness.value.toInt(),
            contrast = sliderContrast.value.toInt(),
            invert = switchInvert.isChecked,
            polaroid = switchPolaroid.isChecked && !label.round,
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
        if (switchPolaroid.isChecked) {
            val r = ImageProcessor.polaroidPhotoRect(ImageProcessor.mmToPx(minOf(wmm, 50.0)), ImageProcessor.mmToPx(hmm))
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
        // In tablet landscape the preview fills the left column
        if (stage.tag == "fill") return
        val normal = resources.getDimension(R.dimen.stage_height)
        root.viewTreeObserver.addOnGlobalLayoutListener {
            val r = android.graphics.Rect()
            root.getWindowVisibleDisplayFrame(r)
            val keyboard = root.rootView.height - r.bottom > root.rootView.height * 0.2
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
        if (code == REQ_CAMERA_PERM && results.firstOrNull() == PackageManager.PERMISSION_GRANTED) takePhoto()
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
        btnPrint.isEnabled = false
        btnPrint.setText(R.string.printing)
        progress.visibility = View.VISIBLE
        progress.setProgressCompat(0, false)
        scope.launch {
            try {
                log("Printing ${r.width}×${r.height} px, density $density …")
                p.print(r.bits, r.width, r.height, density, labelType, copies) { f ->
                    runOnUiThread { progress.setProgressCompat((f * 1000).toInt(), true) }
                }
                textStyle?.let { history.addRecent(it) }
            } catch (e: Exception) {
                log("Print error: ${e.message}")
            }
            progress.visibility = View.INVISIBLE
            updatePrintButton()
        }
    }

    // Hidden debug console: sends a raw text command to the BLE characteristic and shows every
    // reply. Also hosts printer diagnostics and the firmware flasher.
    private fun showDebugConsole() {
        val p = printer ?: return toast(getString(R.string.print_need_printer))
        val pad = dp(16f).toInt()
        val input = com.google.android.material.textfield.TextInputEditText(this).apply {
            setText("test get sensor"); setSingleLine(true)
        }
        val crlf = com.google.android.material.materialswitch.MaterialSwitch(this).apply {
            text = "Append line ending (\r\n)"; isChecked = true
        }
        val logView = TextView(this).apply {
            typeface = android.graphics.Typeface.MONOSPACE; textSize = 11f; setTextIsSelectable(true)
        }
        val scroll = NestedScrollView(this).apply {
            addView(logView); minimumHeight = dp(220f).toInt()
        }
        fun appendLog(line: String) = runOnUiThread {
            val t = SimpleDateFormat("HH:mm:ss", Locale.GERMANY).format(Date())
            logView.text = "$t  $line\n${logView.text}"
        }
        p.rawListener = { bytes ->
            val hex = bytes.joinToString(" ") { "%02x".format(it) }
            val txt = bytes.map { if (it in 32..126) it.toInt().toChar() else '.' }.joinToString("")
            appendLog("RX [$hex]  \"$txt\"")
        }
        val presets = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            for (c in listOf("test get sensor", "test rfidMode 3", "#10001")) {
                addView(com.google.android.material.button.MaterialButton(
                    this@MainActivity, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                    text = c; textSize = 11f
                    setOnClickListener { input.setText(c) }
                    val lp = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                    lp.marginEnd = dp(4f).toInt(); layoutParams = lp
                })
            }
        }
        val control = com.google.android.material.button.MaterialButton(
            this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            text = "Check: send 0x55 heartbeat"; textSize = 12f
            setOnClickListener {
                val bytes = Niimbot.packet(Niimbot.HEARTBEAT, intArrayOf(1))
                appendLog("TX 0x55 [" + bytes.joinToString(" ") { "%02x".format(it) } + "]")
                scope.launch { runCatching { p.sendRaw(bytes) } }
            }
        }
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, dp(8f).toInt(), pad, 0)
            addView(TextView(this@MainActivity).apply {
                text = "Send a raw command to the firmware debug console. Read-only commands only."
                textSize = 12f; setPadding(0, 0, 0, dp(8f).toInt())
            })
            addView(com.google.android.material.textfield.TextInputLayout(this@MainActivity).apply { addView(input) })
            addView(presets)
            addView(control)
            addView(com.google.android.material.button.MaterialButton(
                this@MainActivity, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                text = "Printer diagnostics (RFID / authenticity)"; textSize = 12f
                setOnClickListener { scope.launch { p.diagnose() } }
            })
            addView(com.google.android.material.button.MaterialButton(
                this@MainActivity, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                text = "⚠ Flash firmware …"; textSize = 12f
                setTextColor(getColor(R.color.brand))
                setOnClickListener { pickFirmware() }
            })
            addView(crlf)
            addView(scroll)
        }
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle("Debug console")
            .setView(body)
            .setPositiveButton("Send", null)
            .setNegativeButton(R.string.close) { d, _ -> d.dismiss() }
            .setOnDismissListener { p.rawListener = null }
            .create()
        dialog.show()
        // Keep the dialog open after Send
        dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            var cmd = input.text?.toString().orEmpty()
            if (crlf.isChecked) cmd += "\r\n"
            appendLog("TX \"${cmd.replace("\r","\\r").replace("\n","\\n")}\"")
            scope.launch {
                try { p.sendRaw(cmd.toByteArray(Charsets.US_ASCII)) }
                catch (e: Exception) { appendLog("Error: ${e.message}") }
            }
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
    }
}
