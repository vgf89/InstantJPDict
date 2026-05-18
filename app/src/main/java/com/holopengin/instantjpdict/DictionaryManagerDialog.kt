package com.holopengin.instantjpdict

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.holopengin.instantjpdict.data.AppDatabase
import com.holopengin.instantjpdict.data.DictionaryMeta
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun DictionaryManagerDialog(
    onDismiss: () -> Unit,
    context: android.content.Context
) {
    val scope = rememberCoroutineScope()
    val dictionaries = remember { mutableStateListOf<DictionaryMeta>() }
    var dictionaryToDelete by remember { mutableStateOf<DictionaryMeta?>(null) }
    var isDeleting by remember { mutableStateOf(false) }

    // Refresh function
    suspend fun refresh() {
        val db = AppDatabase.getDatabase(context)
        val loaded = withContext(Dispatchers.IO) { db.dictionaryDao().getAllDictionaries() }
        dictionaries.clear()
        dictionaries.addAll(loaded)
    }

    fun saveOrder() {
        scope.launch(Dispatchers.IO) {
            val dao = AppDatabase.getDatabase(context).dictionaryDao()
            dictionaries.forEachIndexed { index, dict ->
                dao.updatePriority(dict.id, index)
            }
        }
    }

    fun moveUp(index: Int) {
        if (index > 0) {
            val item = dictionaries.removeAt(index)
            dictionaries.add(index - 1, item)
            saveOrder()
        }
    }

    fun moveDown(index: Int) {
        if (index < dictionaries.size - 1) {
            val item = dictionaries.removeAt(index)
            dictionaries.add(index + 1, item)
            saveOrder()
        }
    }

    LaunchedEffect(Unit) {
        refresh()
    }

    if (dictionaryToDelete != null) {
        AlertDialog(
            onDismissRequest = { dictionaryToDelete = null },
            title = { Text("Delete Dictionary") },
            text = { Text("Are you sure you want to delete '${dictionaryToDelete?.name}'? All entries will be removed.") },
            confirmButton = {
                TextButton(onClick = {
                    val dict = dictionaryToDelete
                    dictionaryToDelete = null
                    if (dict != null) {
                        isDeleting = true
                        scope.launch {
                            val db = AppDatabase.getDatabase(context)
                            withContext(Dispatchers.IO) {
                                db.dictionaryDao().deleteEntriesForDictionary(dict.id)
                                db.dictionaryDao().deleteTagsForDictionary(dict.id)
                                db.dictionaryDao().deleteDictionary(dict.id)
                            }
                            refresh()
                            isDeleting = false
                        }
                    }
                }) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { dictionaryToDelete = null }) {
                    Text("Cancel")
                }
            }
        )
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Manage Dictionaries") },
        text = {
            if (isDeleting) {
                Box(modifier = Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            } else {
                LazyColumn {
                    itemsIndexed(dictionaries, key = { _, dict -> dict.id }) { index, dict ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(text = dict.name, modifier = Modifier.weight(1f))
                            
                            IconButton(onClick = { moveUp(index) }, enabled = index > 0) {
                                Icon(Icons.Default.ArrowUpward, contentDescription = "Move Up")
                            }
                            IconButton(onClick = { moveDown(index) }, enabled = index < dictionaries.size - 1) {
                                Icon(Icons.Default.ArrowDownward, contentDescription = "Move Down")
                            }
                            IconButton(onClick = { dictionaryToDelete = dict }) {
                                Icon(Icons.Default.Delete, contentDescription = "Delete")
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Close")
            }
        }
    )
}
