package com.holopengin.instantjpdict.data

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.InputStreamReader
import java.util.zip.ZipInputStream

class DictionaryImporter(private val context: Context) {
    private val gson = Gson()

    suspend fun importZip(uri: android.net.Uri, fileName: String, onProgress: (Int) -> Unit): Result<Int> = withContext(Dispatchers.IO) {
        val startTime = System.currentTimeMillis()
        try {
            val db = AppDatabase.getDatabase(context)
            val dao = db.dictionaryDao()
            
            val inputStream = context.contentResolver.openInputStream(uri) ?: return@withContext Result.failure(Exception("Failed to open input stream"))
            val bufferedStream = BufferedInputStream(inputStream)
            val zipInputStream = ZipInputStream(bufferedStream)
            
            var dictTitle = fileName.removeSuffix(".zip")
            var dictionaryId: Int? = null
            var totalProcessed = 0
            
            val batchChannel = Channel<List<DictionaryEntry>>(capacity = 10)
            
            val dbJob = launch {
                for (batch in batchChannel) {
                    dao.insertAll(batch)
                    totalProcessed += batch.size
                    onProgress(totalProcessed)
                }
            }

            var entry = zipInputStream.nextEntry
            while (entry != null) {
                when {
                    entry.name == "index.json" -> {
                        val reader = JsonReader(InputStreamReader(zipInputStream, "UTF-8"))
                        val map = gson.fromJson<Map<String, Any>>(reader, Map::class.java)
                        val newTitle = map["title"] as? String
                        if (newTitle != null) {
                            dictTitle = newTitle
                            val id = dictionaryId
                            if (id != null) {
                                dao.updateName(id, dictTitle)
                            }
                        }
                    }
                    entry.name.startsWith("term_bank_") && entry.name.endsWith(".json") -> {
                        if (dictionaryId == null) {
                            val maxPriority = dao.getMaxPriority() ?: -1
                            dictionaryId = dao.insertDictionary(DictionaryMeta(name = dictTitle, priority = maxPriority + 1)).toInt()
                        }
                        val reader = JsonReader(InputStreamReader(zipInputStream, "UTF-8"))
                        processTermBank(reader, dictionaryId!!, batchChannel)
                    }
                    entry.name.startsWith("kanji_bank_") && entry.name.endsWith(".json") -> {
                        if (dictionaryId == null) {
                            val maxPriority = dao.getMaxPriority() ?: -1
                            dictionaryId = dao.insertDictionary(DictionaryMeta(name = dictTitle, priority = maxPriority + 1)).toInt()
                        }
                        val reader = JsonReader(InputStreamReader(zipInputStream, "UTF-8"))
                        processKanjiBank(reader, dictionaryId!!, batchChannel)
                    }
                    entry.name.startsWith("tag_bank_") && entry.name.endsWith(".json") -> {
                        if (dictionaryId == null) {
                            val maxPriority = dao.getMaxPriority() ?: -1
                            dictionaryId = dao.insertDictionary(DictionaryMeta(name = dictTitle, priority = maxPriority + 1)).toInt()
                        }
                        val reader = JsonReader(InputStreamReader(zipInputStream, "UTF-8"))
                        parseTagBank(reader, dao, dictionaryId!!)
                    }
                }
                zipInputStream.closeEntry()
                entry = zipInputStream.nextEntry
            }
            
            batchChannel.close()
            dbJob.join()
            zipInputStream.close()
            
            val duration = System.currentTimeMillis() - startTime
            Log.i("DictionaryImporter", "Imported $totalProcessed entries in ${duration}ms")
            
            Result.success(totalProcessed)
        } catch (e: Exception) {
            Log.e("DictionaryImporter", "Import failed", e)
            Result.failure(e)
        }
    }

    private suspend fun processTermBank(reader: JsonReader, dictionaryId: Int, channel: Channel<List<DictionaryEntry>>) {
        val batchSize = 5000
        var batch = mutableListOf<DictionaryEntry>()
        
        reader.beginArray()
        while (reader.hasNext()) {
            val entry = parseTermEntry(reader, dictionaryId)
            if (entry != null) {
                batch.add(entry)
            }
            if (batch.size >= batchSize) {
                channel.send(batch)
                batch = mutableListOf()
            }
        }
        reader.endArray()
        if (batch.isNotEmpty()) channel.send(batch)
    }

    private suspend fun processKanjiBank(reader: JsonReader, dictionaryId: Int, channel: Channel<List<DictionaryEntry>>) {
        val batchSize = 5000
        var batch = mutableListOf<DictionaryEntry>()
        
        reader.beginArray()
        while (reader.hasNext()) {
            val entry = parseKanjiEntry(reader, dictionaryId)
            if (entry != null) {
                batch.add(entry)
            }
            if (batch.size >= batchSize) {
                channel.send(batch)
                batch = mutableListOf()
            }
        }
        reader.endArray()
        if (batch.isNotEmpty()) channel.send(batch)
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
                while (reader.peek() != JsonToken.END_ARRAY) reader.skipValue()
                reader.endArray()
            }
        }
        reader.endArray()
        if (batch.isNotEmpty()) {
            dao.insertTags(batch)
        }
    }

    private fun nextStringOrArray(reader: JsonReader): String {
        return when (reader.peek()) {
            JsonToken.STRING -> reader.nextString()
            JsonToken.BEGIN_ARRAY -> {
                val list = mutableListOf<String>()
                reader.beginArray()
                while (reader.hasNext()) {
                    if (reader.peek() == JsonToken.STRING) {
                        list.add(reader.nextString())
                    } else {
                        reader.skipValue()
                    }
                }
                reader.endArray()
                list.joinToString(" ")
            }
            JsonToken.NULL -> {
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
            JsonToken.NUMBER -> {
                try {
                    reader.nextInt()
                } catch (e: Exception) {
                    try {
                        reader.nextDouble().toInt()
                    } catch (e2: Exception) {
                        0
                    }
                }
            }
            JsonToken.STRING -> {
                reader.nextString().toIntOrNull() ?: 0
            }
            JsonToken.NULL -> {
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
            val onyomi = nextStringOrArray(reader)
            val kunyomi = nextStringOrArray(reader)
            val gradeFreq = nextStringOrArray(reader)
            
            val definitions = if (reader.peek() != JsonToken.END_ARRAY) {
                gson.fromJson<Any>(reader, Any::class.java)
            } else null
            
            val meta = if (reader.peek() != JsonToken.END_ARRAY) {
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
                reading = onyomi,
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

    private fun parseTermEntry(reader: JsonReader, dictionaryId: Int): DictionaryEntry? {
        try {
            reader.beginArray()
            val kanji = reader.nextString()
            val reading = reader.nextString()
            val tags1 = nextStringOrArray(reader)
            val rules = nextStringOrArray(reader)
            val popularity = nextIntSafe(reader)
            
            val definitions = if (reader.peek() != JsonToken.END_ARRAY) {
                gson.fromJson<Any>(reader, Any::class.java)
            } else null
            val definitionsJson = gson.toJson(definitions)
            
            val sequence = nextIntSafe(reader)
            val tags2 = nextStringOrArray(reader)

            while (reader.hasNext()) {
                reader.skipValue()
            }
            reader.endArray()

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
