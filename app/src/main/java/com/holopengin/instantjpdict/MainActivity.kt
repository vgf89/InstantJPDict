package com.holopengin.instantjpdict

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.provider.Settings
import android.widget.Button
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.holopengin.instantjpdict.data.AppDatabase
import com.holopengin.instantjpdict.data.DictionaryImporter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {

    private lateinit var tvStatus: TextView

    private val importLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { importDictionary(it) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        val layout = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(48, 48, 48, 48)
            fitsSystemWindows = true
        }

        // Apply a FrameLayout wrapper to create a margin around the main layout
        val root = android.widget.FrameLayout(this).apply {
            layoutParams = android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT
            ).apply {
                setMargins(32, 32, 32, 32)
            }
        }
        root.addView(layout)

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

        setContentView(root)
        refreshStatus()
    }

    private fun addButton(parent: android.view.ViewGroup, text: String, onClick: () -> Unit) {
        val button = Button(this).apply {
            this.text = text
            setOnClickListener { onClick() }
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
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
