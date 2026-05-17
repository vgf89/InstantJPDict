package com.holopengin.instantjpdict

import android.content.Intent
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
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
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
                    val importer = remember { DictionaryImporter(this) }
                    var status by remember { mutableStateOf("Ready") }
                    
                    val launcher = rememberLauncherForActivityResult(
                        contract = ActivityResultContracts.GetContent()
                    ) { uri ->
                        uri?.let {
                            status = "Importing..."
                            scope.launch {
                                val result = importer.importZip(it)
                                status = result.fold(
                                    onSuccess = { count -> "Imported $count entries" },
                                    onFailure = { e -> "Error: ${e.message}" }
                                )
                            }
                        }
                    }

                    HomeScreen(
                        status = status,
                        onOpenSettings = {
                            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                        },
                        onImportDict = {
                            launcher.launch("application/zip")
                        },
                        onCheckDb = {
                            scope.launch {
                                val count = AppDatabase.getDatabase(this@MainActivity).dictionaryDao().getCount()
                                status = "DB contains $count entries"
                            }
                        },
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
    Column(modifier = modifier.padding(16.dp)) {
        Text(text = "Instant JP Dict", style = MaterialTheme.typography.headlineMedium)
        Spacer(modifier = Modifier.height(8.dp))
        Text(text = "Status: $status")
        Spacer(modifier = Modifier.height(16.dp))
        Button(onClick = onOpenSettings, modifier = Modifier.fillMaxWidth()) {
            Text("Enable Accessibility Service")
        }
        Spacer(modifier = Modifier.height(8.dp))
        Button(onClick = onImportDict, modifier = Modifier.fillMaxWidth()) {
            Text("Import Yomitan Dictionary (.zip)")
        }
        Spacer(modifier = Modifier.height(8.dp))
        Button(onClick = onCheckDb, modifier = Modifier.fillMaxWidth()) {
            Text("Check DB Count")
        }
    }
}
