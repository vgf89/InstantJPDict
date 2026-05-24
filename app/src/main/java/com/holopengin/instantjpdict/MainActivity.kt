package com.holopengin.instantjpdict

import android.content.Intent
import androidx.core.net.toUri
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.holopengin.instantjpdict.data.AppDatabase
import com.holopengin.instantjpdict.data.DictionaryImporter
import com.holopengin.instantjpdict.ui.theme.InstantJPDictTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            InstantJPDictTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    val scope = rememberCoroutineScope()
                    var status by remember { mutableStateOf("Ready") }
                    
                    val launcher = rememberLauncherForActivityResult(
                        contract = ActivityResultContracts.OpenDocument()
                    ) { uri ->
                        uri?.let {
                            val cursor = contentResolver.query(it, null, null, null, null)
                            val name = cursor?.use { c ->
                                val nameIndex = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                                if (c.moveToFirst()) c.getString(nameIndex) else "Imported Dictionary"
                            } ?: "Imported Dictionary"
                            
                            status = "Importing..."
                            scope.launch {
                                try {
                                    val importer = DictionaryImporter(applicationContext)
                                    val result = importer.importZip(it, name) { progress ->
                                        status = "Importing: $progress entries..."
                                    }
                                    status = result.fold(
                                        onSuccess = { count -> "Imported $count entries" },
                                        onFailure = { e -> "Error: ${e.message}" }
                                    )
                                } catch (e: Exception) {
                                    status = "Error initializing importer: ${e.message}"
                                }
                            }
                        }
                    }

                    val refreshStatus = remember {
                        {
                            scope.launch {
                                val db = AppDatabase.getDatabase(applicationContext)
                                val entryCount = db.dictionaryDao().getCount()
                                val dictCount = db.dictionaryDao().getAllDictionaries().size
                                status = "DB contains $entryCount entries in $dictCount dictionaries"
                            }
                        }
                    }

                    LaunchedEffect(Unit) {
                        refreshStatus()
                    }

                    HomeScreen(
                        status = status,
                        onOpenSettings = {
                            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                        },
                        onImportDict = {
                            launcher.launch(arrayOf("application/zip"))
                        },
                        onCheckDb = { refreshStatus() },
                        modifier = Modifier.padding(innerPadding)
                    )
                }
            }
        }
    }
}

@Composable
fun HomeScreen(
    status: String,
    onOpenSettings: () -> Unit,
    onImportDict: () -> Unit,
    onCheckDb: () -> Unit,
    modifier: Modifier = Modifier
) {
    var showDeleteConfirm by remember { mutableStateOf(false) }

    Column(modifier = modifier.padding(16.dp)) {
        Text(text = "Instant JP Dict", style = MaterialTheme.typography.headlineMedium)
        Spacer(modifier = Modifier.height(8.dp))
        Text(text = "Status: $status")
        Spacer(modifier = Modifier.height(16.dp))
        Button(onClick = onOpenSettings, modifier = Modifier.fillMaxWidth()) {
            Text("Enable Accessibility Service")
        }
        Spacer(modifier = Modifier.height(8.dp))
        val context = androidx.compose.ui.platform.LocalContext.current
        Button(
            onClick = {
                val intent = Intent(Intent.ACTION_VIEW, "https://github.com/yomidevs/jmdict-yomitan".toUri())
                context.startActivity(intent)
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Download Dictionaries")
        }
        Spacer(modifier = Modifier.height(8.dp))
        Button(onClick = onImportDict, modifier = Modifier.fillMaxWidth()) {
            Text("Import Yomitan Dictionary (.zip)")
        }
        var showManageDicts by remember { mutableStateOf(false) }

        if (showManageDicts) {
            DictionaryManagerDialog(
                onDismiss = { showManageDicts = false },
                context = androidx.compose.ui.platform.LocalContext.current
            )
        }

        Spacer(modifier = Modifier.height(8.dp))
        Button(onClick = { showManageDicts = true }, modifier = Modifier.fillMaxWidth()) {
            Text("Manage Dictionaries")
        }
        Spacer(modifier = Modifier.height(8.dp))
        Button(onClick = onCheckDb, modifier = Modifier.fillMaxWidth()) {
            Text("Refresh Status")
        }
    }
}
