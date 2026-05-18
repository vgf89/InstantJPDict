package com.holopengin.instantjpdict

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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

    LaunchedEffect(Unit) {
        val db = AppDatabase.getDatabase(context)
        val loaded = withContext(Dispatchers.IO) { db.dictionaryDao().getAllDictionaries() }
        dictionaries.addAll(loaded)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Manage Dictionaries") },
        text = {
            LazyColumn {
                itemsIndexed(dictionaries) { index, dict ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(8.dp),
                        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
                    ) {
                        Text(text = dict.name, modifier = Modifier.weight(1f))
                        IconButton(onClick = {
                            scope.launch {
                                val db = AppDatabase.getDatabase(context)
                                db.dictionaryDao().deleteEntriesForDictionary(dict.id)
                                db.dictionaryDao().deleteDictionary(dict.id)
                                dictionaries.removeAt(index)
                            }
                        }) {
                            Icon(Icons.Default.Delete, contentDescription = "Delete")
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
