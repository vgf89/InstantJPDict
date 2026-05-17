package com.holopengin.instantjpdict.data

import android.content.Context
import com.google.gson.stream.JsonReader
import android.util.Log
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.InputStreamReader
import java.util.zip.ZipInputStream

class DictionaryImporter(private val context: Context) {
    private val gson = Gson()

    suspend fun importZip(uri: android.net.Uri, onProgress: (Int) -> Unit): Result<Int> = withContext(Dispatchers.IO) {
        try {
            val db = AppDatabase.getDatabase(context)
            val dao = db.dictionaryDao()
            
            val inputStream = context.contentResolver.openInputStream(uri) ?: return@withContext Result.failure(Exception("Failed to open input stream"))
            val zipInputStream = ZipInputStream(inputStream)
            var totalEntries = 0

            var entry = zipInputStream.nextEntry
            while (entry != null) {
                if (entry.name.startsWith("term_bank_") && entry.name.endsWith(".json")) {
                    Log.d("DictionaryImporter", "Processing ${entry.name}")
                    val reader = JsonReader(InputStreamReader(zipInputStream, "UTF-8"))
                    totalEntries += parseTermBank(reader, dao) { batchCount ->
                        onProgress(totalEntries + batchCount)
                    }
                }
                zipInputStream.closeEntry()
                entry = zipInputStream.nextEntry
            }
            zipInputStream.close()
            Result.success(totalEntries)
        } catch (e: Exception) {
            Log.e("DictionaryImporter", "Import failed", e)
            Result.failure(e)
        }
    }

    private suspend fun parseTermBank(reader: JsonReader, dao: DictionaryDao, onBatchImported: (Int) -> Unit): Int {
        var count = 0
        val batchSize = 1000
        val batch = mutableListOf<DictionaryEntry>()

        reader.beginArray()
        while (reader.hasNext()) {
            val termData = parseTermEntry(reader)
            if (termData != null) {
                batch.add(termData)
                count++
            }

            if (batch.size >= batchSize) {
                dao.insertAll(batch)
                batch.clear()
                onBatchImported(count)
            }
        }
        reader.endArray()

        if (batch.isNotEmpty()) {
            dao.insertAll(batch)
            onBatchImported(count)
        }
        return count
    }

    private fun parseTermEntry(reader: JsonReader): DictionaryEntry? {
        try {
            reader.beginArray()
            val kanji = reader.nextString()
            val reading = reader.nextString()
            reader.skipValue() // tags
            val rules = reader.nextString()
            val popularity = reader.nextInt()
            
            // Definitions is an array at index 5
            val definitions = gson.fromJson<Any>(reader, Any::class.java)
            val definitionsJson = gson.toJson(definitions)
            
            reader.nextInt() // sequence
            reader.skipValue() // more tags
            reader.endArray()

            return DictionaryEntry(
                kanji = kanji,
                reading = reading,
                definitions = definitionsJson,
                rules = rules,
                popularity = popularity
            )
        } catch (e: Exception) {
            Log.e("DictionaryImporter", "Failed to parse term entry", e)
            return null
        }
    }
}
