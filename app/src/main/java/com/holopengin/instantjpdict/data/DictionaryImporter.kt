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
                } else if (entry2.name.startsWith("tag_bank_") && entry2.name.endsWith(".json")) {
                    val reader = JsonReader(InputStreamReader(zipInputStream2, "UTF-8"))
                    parseTagBank(reader, dao, dictionaryId)
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

    private suspend fun parseTagBank(reader: JsonReader, dao: DictionaryDao, dictionaryId: Int) {
        val batch = mutableListOf<DictionaryTag>()
        reader.beginArray()
        while (reader.hasNext()) {
            try {
                reader.beginArray()
                val name = reader.nextString()
                val category = reader.nextString()
                val order = reader.nextInt()
                val notes = reader.nextString()
                val popularity = reader.nextInt()
                reader.endArray()

                batch.add(DictionaryTag(
                    name = name,
                    category = category,
                    order = order,
                    notes = notes,
                    popularity = popularity,
                    dictionaryId = dictionaryId
                ))
            } catch (e: Exception) {
                Log.e("DictionaryImporter", "Failed to parse tag entry", e)
            }
        }
        reader.endArray()
        if (batch.isNotEmpty()) {
            dao.insertTags(batch)
        }
    }

    private fun nextStringOrArray(reader: JsonReader): String {
        return when (reader.peek()) {
            com.google.gson.stream.JsonToken.STRING -> reader.nextString()
            com.google.gson.stream.JsonToken.BEGIN_ARRAY -> {
                val list = mutableListOf<String>()
                reader.beginArray()
                while (reader.hasNext()) {
                    if (reader.peek() == com.google.gson.stream.JsonToken.STRING) {
                        list.add(reader.nextString())
                    } else {
                        reader.skipValue()
                    }
                }
                reader.endArray()
                list.joinToString(" ")
            }
            com.google.gson.stream.JsonToken.NULL -> {
                reader.nextNull()
                ""
            }
            else -> {
                reader.skipValue()
                ""
            }
        }
    }

    private fun nextIntSafe(reader: JsonReader): Int {
        return when (reader.peek()) {
            com.google.gson.stream.JsonToken.NUMBER -> {
                try {
                    reader.nextInt()
                } catch (e: Exception) {
                    try {
                        reader.nextDouble().toInt()
                    } catch (e2: Exception) {
                        Log.w("DictionaryImporter", "Failed to parse number", e2)
                        0
                    }
                }
            }
            com.google.gson.stream.JsonToken.STRING -> {
                reader.nextString().toIntOrNull() ?: 0
            }
            com.google.gson.stream.JsonToken.NULL -> {
                reader.nextNull()
                0
            }
            else -> {
                reader.skipValue()
                0
            }
        }
    }

    private fun parseKanjiEntry(reader: JsonReader, dictionaryId: Int): DictionaryEntry? {
        try {
            reader.beginArray()
            val kanji = nextStringOrArray(reader)
            val onyomi = nextStringOrArray(reader) // On
            val kunyomi = nextStringOrArray(reader) // Kun
            val gradeFreq = nextStringOrArray(reader) // Grade / Frequency info
            val definitions = if (reader.peek() != com.google.gson.stream.JsonToken.END_ARRAY) {
                gson.fromJson<Any>(reader, Any::class.java)
            } else null
            
            val meta = if (reader.peek() != com.google.gson.stream.JsonToken.END_ARRAY) {
                try {
                    gson.fromJson<Map<String, Any>>(reader, Map::class.java)
                } catch (e: Exception) {
                    null
                }
            } else null

            while (reader.hasNext()) {
                reader.skipValue()
            }
            reader.endArray()

            val jlpt = (meta?.get("jlpt") ?: "").toString()
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
            val tags1 = nextStringOrArray(reader)
            val rules = nextStringOrArray(reader)
            val popularity = nextIntSafe(reader)
            
            val definitions = if (reader.peek() != com.google.gson.stream.JsonToken.END_ARRAY) {
                gson.fromJson<Any>(reader, Any::class.java)
            } else null
            val definitionsJson = gson.toJson(definitions)
            
            val sequence = nextIntSafe(reader)
            val tags2 = nextStringOrArray(reader)

            while (reader.hasNext()) {
                reader.skipValue()
            }
            reader.endArray()

            // Combine all tags into the rules field for lookup, preserving original grouping
            val combinedRules = "$tags1 | $rules | $tags2"

            return DictionaryEntry(
                kanji = kanji,
                reading = reading,
                definitions = definitionsJson,
                rules = combinedRules,
                popularity = popularity,
                dictionaryId = dictionaryId
            )
        } catch (e: Exception) {
            Log.e("DictionaryImporter", "Failed to parse term entry", e)
            return null
        }
    }
}
