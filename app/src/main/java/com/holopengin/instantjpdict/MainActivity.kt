package com.holopengin.instantjpdict

import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.provider.Settings
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.util.Log
import android.view.Gravity
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.holopengin.instantjpdict.data.AppDatabase
import com.holopengin.instantjpdict.data.DictionaryImporter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

class MainActivity : AppCompatActivity() {

    private lateinit var tvStatus: TextView

    private val importLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { importDictionary(it) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val prefs = getSharedPreferences(OcrEngine.PREFS_NAME, MODE_PRIVATE)

        // ScrollView wrapper so tuning controls don't overflow
        val scrollView = ScrollView(this).apply {
            layoutParams = android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT
            )
            isFillViewport = true
        }
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 48, 48, 48)
            fitsSystemWindows = true
        }
        scrollView.addView(layout)
        val root = android.widget.FrameLayout(this).apply {
            layoutParams = android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT
            ).apply {
                setMargins(32, 32, 32, 32)
            }
        }
        root.addView(scrollView)

        val title = TextView(this).apply {
            text = "Instant JP Dict"
            textSize = 24f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setPadding(0, 0, 0, 16)
        }
        layout.addView(title)

        tvStatus = TextView(this).apply {
            text = "Status: Loading..."
            setPadding(0, 0, 0, 32)
        }
        layout.addView(tvStatus)

        addButton(layout, "Enable Accessibility Service") {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }

        val accessibilityHelp = TextView(this).apply {
            text = "Note: If android displays an \"App access was denied\" popup when attempting to enable it the Accessibility Service, you may need to go to your system settings, find 'InstantJPDict' in the app list, and tap the three-dot menu to select 'Allow restricted settings'."
            textSize = 12f
            setPadding(0, 0, 0, 16)
        }
        layout.addView(accessibilityHelp)

        addButton(layout, "Download Dictionaries") {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/yomidevs/jmdict-yomitan")))
        }

        addButton(layout, "Import Yomitan Dictionary (.zip)") {
            importLauncher.launch(arrayOf("application/zip"))
        }

        addButton(layout, "Manage Dictionaries") {
            DictionaryManagerDialog.show(this)
        }

        addButton(layout, "Gamepad Controls") {
            GamepadSettingsDialog.show(this)
        }

        addButton(layout, "Refresh Status") {
            refreshStatus()
        }

        // ————— PP-OCR parameter tuning — debug controls — #14 —————
        // Hidden behind Debug settings checkbox — keeps main screen clean
        val debugPrefsKey = "debug_settings_enabled"
        val tuningContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = if (prefs.getBoolean(debugPrefsKey, false)) LinearLayout.VISIBLE else LinearLayout.GONE
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        val debugToggle = CheckBox(this).apply {
            text = "Debug settings (PP-OCR Tuning)"
            isChecked = prefs.getBoolean(debugPrefsKey, false)
            textSize = 14f
            setPadding(0, 24, 0, 8)
            setOnCheckedChangeListener { _, checked ->
                prefs.edit().putBoolean(debugPrefsKey, checked).apply()
                tuningContainer.visibility = if (checked) LinearLayout.VISIBLE else LinearLayout.GONE
                Log.i("MainActivity", "debug_settings_enabled=$checked")
            }
        }
        layout.addView(debugToggle)
        layout.addView(tuningContainer)

        val tuningHeader = TextView(this).apply {
            text = "PP-OCR Tuning (Debug)"
            textSize = 18f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setPadding(0, 8, 0, 8)
        }
        tuningContainer.addView(tuningHeader)
        val tuningHelp = TextView(this).apply {
            text = "Tune for tight (but not too tight) crops and no missing っ / punctuation. Values are live from SharedPreferences (${OcrEngine.PREFS_NAME}); restart overlay or re-run OCR to apply. LONG_SIDE fixed at 960 (model input)."
            textSize = 11f
            setPadding(0, 0, 0, 12)
        }
        tuningContainer.addView(tuningHelp)

        // live summary line that shows current values
        val liveSummary = TextView(this).apply {
            textSize = 11f
            setPadding(0, 0, 0, 12)
            setBackgroundColor(android.graphics.Color.argb(20, 0, 0, 0))
        }
        tuningContainer.addView(liveSummary)
        fun refreshLiveSummary() {
            val thresh = prefs.getFloat(OcrEngine.PREF_DET_THRESH, OcrEngine.DEF_DET_THRESH)
            val unclip = prefs.getFloat(OcrEngine.PREF_DET_UNCLIP, OcrEngine.DEF_DET_UNCLIP)
            val longSide = OcrEngine.DEF_DET_LONG_SIDE
            val xOver = prefs.getFloat(OcrEngine.PREF_X_OVERLAP, OcrEngine.DEF_X_OVERLAP)
            val recConf = prefs.getFloat(OcrEngine.PREF_REC_CONF, OcrEngine.DEF_REC_CONF)
            val backend = prefs.getString(OcrEngine.PREF_BACKEND, "onnx") ?: "onnx"
            val line = "live: backend=$backend detThresh=${String.format("%.2f", thresh)} unclip=${String.format("%.2f", unclip)} longSide=$longSide xOver=${String.format("%.2f", xOver)} recConf=${String.format("%.2f", recConf)}"
            liveSummary.text = line
        }
        refreshLiveSummary()

        // helper to add one tunable row: label + live value + SeekBar + EditText + Apply
        fun addTunable(
            label: String,
            prefKey: String,
            default: Float,
            min: Float,
            max: Float,
            step: Float,
            isInt: Boolean
        ) {
            val steps = ((max - min) / step).roundToInt().coerceAtLeast(1)
            fun valueForProgress(p: Int): Float = min + p * step
            fun progressForValue(v: Float): Int = ((v - min) / step).roundToInt().coerceIn(0, steps)
            fun formatValue(v: Float): String = if (isInt) v.roundToInt().toString() else String.format("%.2f", v)

            val curRaw: Float = if (isInt) {
                prefs.getInt(prefKey, default.roundToInt()).toFloat()
            } else {
                prefs.getFloat(prefKey, default)
            }
            val cur = curRaw.coerceIn(min, max)

            val container = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, 12, 0, 12)
                setBackgroundColor(android.graphics.Color.argb(8, 0, 0, 0))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { setMargins(0, 0, 0, 12) }
            }

            val tvLabel = TextView(this).apply {
                text = "$label  (default ${formatValue(default)})"
                textSize = 13f
                setTypeface(null, android.graphics.Typeface.BOLD)
            }
            container.addView(tvLabel)

            val tvLive = TextView(this).apply {
                text = "current: ${formatValue(cur)}"
                textSize = 12f
                setTextColor(android.graphics.Color.rgb(0, 100, 0))
            }
            container.addView(tvLive)

            val seek = SeekBar(this).apply {
                this.max = steps
                progress = progressForValue(cur)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            }
            container.addView(seek)

            val editRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, 6, 0, 0)
            }
            val edit = EditText(this).apply {
                setText(formatValue(cur))
                textSize = 12f
                inputType = if (isInt) InputType.TYPE_CLASS_NUMBER else InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL or InputType.TYPE_NUMBER_FLAG_SIGNED
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(0, 0, 8, 0) }
                setPadding(12, 8, 12, 8)
                setBackgroundColor(android.graphics.Color.argb(30, 0, 0, 0))
            }
            editRow.addView(edit)
            val btnApply = Button(this).apply {
                text = "Apply"
                textSize = 11f
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
                minWidth = 0
                minimumWidth = 0
                setPadding(16, 0, 16, 0)
            }
            editRow.addView(btnApply)
            val btnReset = Button(this).apply {
                text = "Reset"
                textSize = 11f
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { leftMargin = 8 }
                minWidth = 0
                minimumWidth = 0
                setPadding(16, 0, 16, 0)
            }
            editRow.addView(btnReset)
            container.addView(editRow)

            // SeekBar listener: live update tvLive + edit, commit on stop
            var fromSeek = false
            seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                    if (!fromUser) return
                    fromSeek = true
                    val v = valueForProgress(p)
                    tvLive.text = "current: ${formatValue(v)} (dragging)"
                    edit.setText(formatValue(v))
                    // don't commit yet; live TextView shows dragging value, summary updates on stop
                }
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {
                    val v = valueForProgress(seek.progress)
                    if (isInt) prefs.edit().putInt(prefKey, v.roundToInt()).apply()
                    else prefs.edit().putFloat(prefKey, v).apply()
                    tvLive.text = "current: ${formatValue(v)}"
                    refreshLiveSummary()
                    Toast.makeText(this@MainActivity, "$label = ${formatValue(v)}", Toast.LENGTH_SHORT).show()
                    Log.i("MainActivity", "tuning $prefKey = $v")
                    fromSeek = false
                }
            })

            // Edit Apply
            btnApply.setOnClickListener {
                val raw = edit.text.toString().trim()
                val parsed = raw.toFloatOrNull()
                if (parsed == null) {
                    Toast.makeText(this, "Invalid number", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                if (parsed < min - 1e-6 || parsed > max + 1e-6) {
                    Toast.makeText(this, "Out of range [$min, $max]", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                // snap to step for non-int? keep as-is but store
                val v = if (isInt) parsed.roundToInt().toFloat() else parsed
                if (isInt) prefs.edit().putInt(prefKey, v.roundToInt()).apply()
                else prefs.edit().putFloat(prefKey, v).apply()
                seek.progress = progressForValue(v)
                tvLive.text = "current: ${formatValue(v)}"
                refreshLiveSummary()
                Toast.makeText(this, "$label = ${formatValue(v)}", Toast.LENGTH_SHORT).show()
                Log.i("MainActivity", "tuning $prefKey = $v (via EditText)")
            }
            btnReset.setOnClickListener {
                if (isInt) prefs.edit().putInt(prefKey, default.roundToInt()).apply()
                else prefs.edit().putFloat(prefKey, default).apply()
                seek.progress = progressForValue(default)
                edit.setText(formatValue(default))
                tvLive.text = "current: ${formatValue(default)}"
                refreshLiveSummary()
                Toast.makeText(this, "$label reset to ${formatValue(default)}", Toast.LENGTH_SHORT).show()
                Log.i("MainActivity", "tuning $prefKey reset to $default")
            }

            // live: if user types, update tvLive preview (don't commit)
            edit.addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                override fun afterTextChanged(s: Editable?) {
                    if (fromSeek) return
                    val t = s?.toString()?.trim() ?: return
                    val pv = t.toFloatOrNull() ?: return
                    if (pv in min..max) {
                        tvLive.text = "current: ${formatValue(pv)} (typed, press Apply)"
                    }
                }
            })

            tuningContainer.addView(container)
        }

        addTunable("PPOCR_DET_THRESH", OcrEngine.PREF_DET_THRESH, OcrEngine.DEF_DET_THRESH, 0.05f, 0.60f, 0.01f, false)
        addTunable("PPOCR_DET_UNCLIP_RATIO", OcrEngine.PREF_DET_UNCLIP, OcrEngine.DEF_DET_UNCLIP, 0.5f, 3.0f, 0.01f, false)
        addTunable("X_OVERLAP_THRESHOLD", OcrEngine.PREF_X_OVERLAP, OcrEngine.DEF_X_OVERLAP, 0.0f, 1.0f, 0.01f, false)
        addTunable("REC_CONFIDENCE_THRESHOLD", OcrEngine.PREF_REC_CONF, OcrEngine.DEF_REC_CONF, 0.0f, 0.50f, 0.01f, false)

        addButton(tuningContainer, "Reset all tuning to defaults") {
            prefs.edit()
                .putFloat(OcrEngine.PREF_DET_THRESH, OcrEngine.DEF_DET_THRESH)
                .putFloat(OcrEngine.PREF_DET_UNCLIP, OcrEngine.DEF_DET_UNCLIP)
                .putInt(OcrEngine.PREF_DET_LONG_SIDE, OcrEngine.DEF_DET_LONG_SIDE)
                .putFloat(OcrEngine.PREF_X_OVERLAP, OcrEngine.DEF_X_OVERLAP)
                .putFloat(OcrEngine.PREF_REC_CONF, OcrEngine.DEF_REC_CONF)
                .apply()
            Toast.makeText(this, "All tuning reset to defaults — reopen screen to refresh", Toast.LENGTH_LONG).show()
            Log.i("MainActivity", "all tuning reset to defaults")
            // Recreate to refresh SeekBars
            recreate()
        }

        val tuningFooter = TextView(this).apply {
            text = "ncnn only — onnxruntime removed (12s→7s proven). DET/REC both ncnn 960×960 / 48×W."
            textSize = 10f
            setPadding(0, 16, 0, 0)
            setTextColor(android.graphics.Color.DKGRAY)
        }
        tuningContainer.addView(tuningFooter)

        setContentView(root)
        refreshStatus()
    }

    private fun addButton(parent: android.view.ViewGroup, text: String, onClick: () -> Unit) {
        val button = Button(this).apply {
            this.text = text
            setOnClickListener { onClick() }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(0, 0, 0, 16)
            }
        }
        parent.addView(button)
    }

    private fun refreshStatus() {
        lifecycleScope.launch {
            val db = AppDatabase.getDatabase(applicationContext)
            val entryCount = withContext(Dispatchers.IO) { db.dictionaryDao().getCount() }
            val dictCount = withContext(Dispatchers.IO) { db.dictionaryDao().getAllDictionaries().size }
            withContext(Dispatchers.Main) {
                tvStatus.text = "DB contains $entryCount entries in $dictCount dictionaries"
            }
        }
    }

    private fun importDictionary(uri: Uri) {
        val name = contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (cursor.moveToFirst()) cursor.getString(nameIndex) else "Imported Dictionary"
        } ?: "Imported Dictionary"

        tvStatus.text = "Importing..."
        lifecycleScope.launch {
            try {
                val importer = DictionaryImporter(applicationContext)
                val result = importer.importZip(uri, name) { progress ->
                    lifecycleScope.launch(Dispatchers.Main) {
                        tvStatus.text = "Importing: $progress entries..."
                    }
                }
                withContext(Dispatchers.Main) {
                    tvStatus.text = result.fold(
                        onSuccess = { count -> "Imported $count entries" },
                        onFailure = { e -> "Error: ${e.message}" }
                    )
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    tvStatus.text = "Error initializing importer: ${e.message}"
                }
            }
        }
    }
}
