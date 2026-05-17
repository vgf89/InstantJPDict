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
    private val db = AppDatabase.getDatabase(context)
    private val dao = db.dictionaryDao()
    private val gson = Gson()

    suspend fun importZip(uri: android.net.Uri): Result<Int> = withContext(Dispatchers.IO) {
        try {
            val inputStream = context.contentResolver.openInputStream(uri) ?: return@withContext Result.failure(Exception("Failed to open input stream"))
            val zipInputStream = ZipInputStream(inputStream)
            var totalEntries = 0

            var entry = zipInputStream.nextEntry
            while (entry != null) {
                if (entry.name.startsWith("term_bank_") && entry.name.endsWith(".json")) {
                    Log.d("DictionaryImporter", "Processing ${entry.name}")
                    val reader = JsonReader(InputStreamReader(zipInputStream, "UTF-8"))
                    totalEntries += parseTermBank(reader)
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

    private suspend fun parseTermBank(reader: JsonReader): Int {
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
            }
        }
        reader.endArray()

        if (batch.isNotEmpty()) {
            dao.insertAll(batch)
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
            
            val definitionsList = mutableListOf<String>()
            reader.beginArray()
            while (reader.hasNext()) {
                val def = gson.fromJson<Any>(reader, Any::class.java)
                definitionsList.add(gson.toJson(def))
            }
            reader.endArray()
            
            reader.nextInt() // sequence
            reader.skipValue() // more tags
            reader.endArray()

            return DictionaryEntry(
                kanji = kanji,
                reading = reading,
                definitions = gson.toJson(definitionsList),
                rules = rules,
                popularity = popularity
            )
        } catch (e: Exception) {
            Log.e("DictionaryImporter", "Failed to parse term entry", e)
            return null
        }
    }
}
