package com.holopengin.instantjpdict.data

import android.content.Context
import com.google.gson.stream.JsonReader
import android.util.Log
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.InputStreamReader
import java.util.zip.ZipInputStream
import java.util.zip.ZipEntry

class DictionaryImporter(private val context: Context) {
    private val gson = Gson()

    suspend fun importZip(uri: android.net.Uri, fileName: String, onProgress: (Int) -> Unit): Result<Int> = withContext(Dispatchers.IO) {
        try {
            val db = AppDatabase.getDatabase(context)
            val dao = db.dictionaryDao()
            
            // First pass: extract metadata
            var dictTitle = fileName.removeSuffix(".zip")
            
            val inputStream1 = context.contentResolver.openInputStream(uri) ?: return@withContext Result.failure(Exception("Failed to open input stream"))
            val zipInputStream1 = ZipInputStream(inputStream1)
            var entry1: ZipEntry? = zipInputStream1.nextEntry
            while (entry1 != null) {
                if (entry1.name == "index.json") {
                    val reader = JsonReader(InputStreamReader(zipInputStream1, "UTF-8"))
                    val map = gson.fromJson<Map<String, Any>>(reader, Map::class.java)
                    dictTitle = map["title"] as? String ?: dictTitle
                    break
                }
                zipInputStream1.closeEntry()
                entry1 = zipInputStream1.nextEntry
            }
            zipInputStream1.close()

            val dictionaryId = dao.insertDictionary(DictionaryMeta(name = dictTitle, priority = 0)).toInt()

            // Second pass: process entries
            val inputStream2 = context.contentResolver.openInputStream(uri) ?: return@withContext Result.failure(Exception("Failed to open input stream"))
            val zipInputStream2 = ZipInputStream(inputStream2)
            var totalEntries = 0
            
            var entry2: ZipEntry? = zipInputStream2.nextEntry
            while (entry2 != null) {
                if (entry2.name.startsWith("term_bank_") && entry2.name.endsWith(".json")) {
                    val reader = JsonReader(InputStreamReader(zipInputStream2, "UTF-8"))
                    totalEntries += parseTermBank(reader, dao, dictionaryId) { batchCount ->
                        onProgress(totalEntries + batchCount)
                    }
                } else if (entry2.name.startsWith("kanji_bank_") && entry2.name.endsWith(".json")) {
                    val reader = JsonReader(InputStreamReader(zipInputStream2, "UTF-8"))
                    totalEntries += parseKanjiBank(reader, dao, dictionaryId) { batchCount ->
                        onProgress(totalEntries + batchCount)
                    }
                }
                zipInputStream2.closeEntry()
                entry2 = zipInputStream2.nextEntry
            }
            zipInputStream2.close()
            Result.success(totalEntries)
        } catch (e: Exception) {
            Log.e("DictionaryImporter", "Import failed", e)
            Result.failure(e)
        }
    }

    private suspend fun parseKanjiBank(reader: JsonReader, dao: DictionaryDao, dictionaryId: Int, onBatchImported: (Int) -> Unit): Int {
        var count = 0
        val batchSize = 1000
        val batch = mutableListOf<DictionaryEntry>()

        reader.beginArray()
        while (reader.hasNext()) {
            val kanjiData = parseKanjiEntry(reader, dictionaryId)
            if (kanjiData != null) {
                batch.add(kanjiData)
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

    private fun parseKanjiEntry(reader: JsonReader, dictionaryId: Int): DictionaryEntry? {
        try {
            reader.beginArray()
            val kanji = reader.nextString()
            val onyomi = reader.nextString() // On
            val kunyomi = reader.nextString() // Kun
            val gradeFreq = reader.nextString() // Grade / Frequency info
            val definitions = gson.fromJson<Any>(reader, Any::class.java) // Glossary
            val meta = gson.fromJson<Map<String, Any>>(reader, Map::class.java) // Metadata object
            reader.endArray()

            val jlpt = meta["jlpt"]?.toString() ?: ""
            val rules = "grade:$gradeFreq"

            return DictionaryEntry(
                kanji = kanji,
                reading = onyomi, // Keep 'reading' for compatibility
                definitions = gson.toJson(definitions),
                rules = rules,
                popularity = 0,
                dictionaryId = dictionaryId,
                onyomi = onyomi,
                kunyomi = kunyomi,
                jlpt = jlpt
            )
        } catch (e: Exception) {
            Log.e("DictionaryImporter", "Failed to parse kanji entry", e)
            return null
        }
    }

    private suspend fun parseTermBank(reader: JsonReader, dao: DictionaryDao, dictionaryId: Int, onBatchImported: (Int) -> Unit): Int {
        var count = 0
        val batchSize = 1000
        val batch = mutableListOf<DictionaryEntry>()

        reader.beginArray()
        while (reader.hasNext()) {
            val termData = parseTermEntry(reader, dictionaryId)
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

    private fun parseTermEntry(reader: JsonReader, dictionaryId: Int): DictionaryEntry? {
        try {
            reader.beginArray()
            val kanji = reader.nextString()
            val reading = reader.nextString()
            reader.skipValue() // tags
            val rules = reader.nextString()
            val popularity = reader.nextInt()
            
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
                popularity = popularity,
                dictionaryId = dictionaryId
            )
        } catch (e: Exception) {
            Log.e("DictionaryImporter", "Failed to parse term entry", e)
            return null
        }
    }
}
