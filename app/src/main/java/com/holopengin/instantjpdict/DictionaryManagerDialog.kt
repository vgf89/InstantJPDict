package com.holopengin.instantjpdict

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DragHandle
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
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
    
    val listState = rememberLazyListState()
    var draggedItemIndex by remember { mutableStateOf<Int?>(null) }
    var dragOffset by remember { mutableStateOf(0f) }

    // Refresh function
    suspend fun refresh() {
        val db = AppDatabase.getDatabase(context)
        val loaded = withContext(Dispatchers.IO) { db.dictionaryDao().getAllDictionaries() }
        dictionaries.clear()
        dictionaries.addAll(loaded)
    }

    LaunchedEffect(Unit) {
        refresh()
    }

    fun saveOrder() {
        scope.launch(Dispatchers.IO) {
            val dao = AppDatabase.getDatabase(context).dictionaryDao()
            dictionaries.forEachIndexed { index, dict ->
                dao.updatePriority(dict.id, index)
            }
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
                LazyColumn(state = listState) {
                    itemsIndexed(dictionaries, key = { _, dict -> dict.id }) { index, dict ->
                        val isDragging = draggedItemIndex == index
                        val scale by animateFloatAsState(if (isDragging) 1.05f else 1f)
                        val elevation by animateFloatAsState(if (isDragging) 8f else 0f)

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .graphicsLayer {
                                    translationY = if (isDragging) dragOffset else 0f
                                    scaleX = scale
                                    scaleY = scale
                                    shadowElevation = elevation.dp.toPx()
                                }
                                .zIndex(if (isDragging) 1f else 0f)
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Default.DragHandle,
                                contentDescription = "Drag Handle",
                                modifier = Modifier
                                    .padding(end = 8.dp)
                                    .pointerInput(Unit) {
                                        detectDragGestures(
                                            onDragStart = { offset ->
                                                val index = listState.layoutInfo.visibleItemsInfo
                                                    .firstOrNull { item ->
                                                        offset.y.toInt() in item.offset..(item.offset + item.size)
                                                    }?.index
                                                if (index != null) {
                                                    draggedItemIndex = index
                                                }
                                            },
                                            onDragEnd = {
                                                draggedItemIndex = null
                                                dragOffset = 0f
                                                saveOrder()
                                            },
                                            onDragCancel = {
                                                draggedItemIndex = null
                                                dragOffset = 0f
                                            },
                                            onDrag = { change, dragAmount ->
                                                change.consume()
                                                dragOffset += dragAmount.y

                                                val currentIdx = draggedItemIndex ?: return@detectDragGestures
                                                val layoutInfo = listState.layoutInfo
                                                val draggingItem = layoutInfo.visibleItemsInfo
                                                    .firstOrNull { it.index == currentIdx } ?: return@detectDragGestures

                                                val center = draggingItem.offset + draggingItem.size / 2 + dragOffset
                                                val targetItem = layoutInfo.visibleItemsInfo.firstOrNull { item ->
                                                    center.toInt() in item.offset..(item.offset + item.size) && item.index != currentIdx
                                                }

                                                if (targetItem != null) {
                                                    dictionaries.add(targetItem.index, dictionaries.removeAt(currentIdx))
                                                    draggedItemIndex = targetItem.index
                                                    // This is the key: adjust the offset so the item stays under the finger
                                                    dragOffset = center - (targetItem.offset + targetItem.size / 2)
                                                }
                                            }
                                        )
                                    }
                            )
                            
                            Text(text = dict.name, modifier = Modifier.weight(1f))
                            
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
