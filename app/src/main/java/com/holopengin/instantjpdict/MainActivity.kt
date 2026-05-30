package com.holopengin.instantjpdict

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.provider.Settings
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
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
        }

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

        setContentView(layout)
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
            tvStatus.text = "DB contains $entryCount entries in $dictCount dictionaries"
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
                    tvStatus.text = "Importing: $progress entries..."
                }
                tvStatus.text = result.fold(
                    onSuccess = { count -> "Imported $count entries" },
                    onFailure = { e -> "Error: ${e.message}" }
                )
            } catch (e: Exception) {
                tvStatus.text = "Error initializing importer: ${e.message}"
            }
        }
    }
}
