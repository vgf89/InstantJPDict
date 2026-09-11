package com.holopengin.instantjpdict.data

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonElement
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.InputStream
import java.io.InputStreamReader
import java.util.zip.ZipInputStream

class DictionaryImporter(private val context: Context) {
    private val gson = Gson()

    private companion object {
        const val TAG = "DictionaryImporter"
    }

    suspend fun importZip(uri: android.net.Uri, fileName: String, onProgress: (Int) -> Unit): Result<Int> = withContext(Dispatchers.IO) {
        try {
            val inputStream = context.contentResolver.openInputStream(uri)
                ?: return@withContext Result.failure(Exception("Failed to open input stream"))
            Result.success(
                importZipStream(
                    BufferedInputStream(inputStream),
                    fileName.removeSuffix(".zip"),
                    onProgress,
                    builtIn = false,
                )
            )
        } catch (e: Exception) {
            Log.e(TAG, "Import failed", e)
            Result.failure(e)
        }
    }

    /**
     * Import a dictionary bundled in the APK's assets (#43) — the vendored
     * pitch dictionary, which needs no network and no file picker.
     *
     * Re-importing replaces any existing copy with the same title, so the
     * action is idempotent: tapping it twice leaves one dictionary, not two
     * stacked copies of the same 124k rows.
     */
    suspend fun importBundledAsset(assetPath: String, onProgress: (Int) -> Unit): Result<Int> = withContext(Dispatchers.IO) {
        try {
            val title = context.assets.open(assetPath).use { readZipTitle(it) }
            if (title != null) {
                val dao = AppDatabase.getDatabase(context).dictionaryDao()
                dao.findDictionaryByName(title)?.let { existing ->
                    Log.i(TAG, "Replacing existing '${existing.name}' (id=${existing.id})")
                    dao.deleteEntriesForDictionary(existing.id)
                    dao.deleteDictionary(existing.id)
                }
            }
            val inputStream = context.assets.open(assetPath)
            Result.success(
                importZipStream(
                    BufferedInputStream(inputStream),
                    assetPath.substringAfterLast('/').removeSuffix(".zip"),
                    onProgress,
                    builtIn = true,
                )
            )
        } catch (e: Exception) {
            Log.e(TAG, "Bundled import failed", e)
            Result.failure(e)
        }
    }

    /** Title declared by a zip's index.json, or null when unreadable. */
    private fun readZipTitle(input: InputStream): String? {
        return try {
            ZipInputStream(input).use { zip ->
                var entry = zip.nextEntry
                while (entry != null) {
                    if (entry.name == "index.json") {
                        val reader = JsonReader(InputStreamReader(zip, "UTF-8"))
                        val map = gson.fromJson<Map<String, Any>>(reader, Map::class.java)
                        return map["title"] as? String
                    }
                    zip.closeEntry()
                    entry = zip.nextEntry
                }
                null
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not read dictionary title", e)
            null
        }
    }

    /** Shared import core: reads a dictionary zip and writes its rows. */
    private suspend fun importZipStream(
        bufferedStream: BufferedInputStream,
        fallbackTitle: String,
        onProgress: (Int) -> Unit,
        builtIn: Boolean = false,
    ): Int = coroutineScope {
            val startTime = System.currentTimeMillis()
            val db = AppDatabase.getDatabase(context)
            val dao = db.dictionaryDao()

            val zipInputStream = ZipInputStream(bufferedStream)

            var dictTitle = fallbackTitle
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
                            dictionaryId = dao.insertDictionary(DictionaryMeta(name = dictTitle, priority = maxPriority + 1, builtIn = builtIn)).toInt()
                        }
                        val reader = JsonReader(InputStreamReader(zipInputStream, "UTF-8"))
                        processTermBank(reader, dictionaryId!!, batchChannel)
                    }
                    entry.name.startsWith("kanji_bank_") && entry.name.endsWith(".json") -> {
                        if (dictionaryId == null) {
                            val maxPriority = dao.getMaxPriority() ?: -1
                            dictionaryId = dao.insertDictionary(DictionaryMeta(name = dictTitle, priority = maxPriority + 1, builtIn = builtIn)).toInt()
                        }
                        val reader = JsonReader(InputStreamReader(zipInputStream, "UTF-8"))
                        processKanjiBank(reader, dictionaryId!!, batchChannel)
                    }
                    entry.name.startsWith("tag_bank_") && entry.name.endsWith(".json") -> {
                        if (dictionaryId == null) {
                            val maxPriority = dao.getMaxPriority() ?: -1
                            dictionaryId = dao.insertDictionary(DictionaryMeta(name = dictTitle, priority = maxPriority + 1, builtIn = builtIn)).toInt()
                        }
                        val reader = JsonReader(InputStreamReader(zipInputStream, "UTF-8"))
                        parseTagBank(reader, dao, dictionaryId!!)
                    }
                    // #43: term-meta banks carry pitch-accent data (and freq,
                    // which we skip). Stored like a term entry so lookup finds
                    // it, then filtered out of the entry list at render time.
                    entry.name.startsWith("term_meta_bank_") && entry.name.endsWith(".json") -> {
                        if (dictionaryId == null) {
                            val maxPriority = dao.getMaxPriority() ?: -1
                            dictionaryId = dao.insertDictionary(DictionaryMeta(name = dictTitle, priority = maxPriority + 1, builtIn = builtIn)).toInt()
                        }
                        val reader = JsonReader(InputStreamReader(zipInputStream, "UTF-8"))
                        processTermMetaBank(reader, dictionaryId!!, batchChannel)
                    }
                }
                zipInputStream.closeEntry()
                entry = zipInputStream.nextEntry
            }
            
            batchChannel.close()
            dbJob.join()
            zipInputStream.close()
            
            val duration = System.currentTimeMillis() - startTime
            Log.i(TAG, "Imported $totalProcessed entries in ${duration}ms")

            totalProcessed
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

    /**
     * #43: Yomitan term-meta bank rows are `[term, type, data]`. Only `pitch`
     * rows are kept (freq/ipa skipped); each becomes a DictionaryEntry keyed
     * on the term with its reading, and its data stored verbatim as the
     * definition payload so render-time parsing sees exact integers.
     */
    private suspend fun processTermMetaBank(reader: JsonReader, dictionaryId: Int, channel: Channel<List<DictionaryEntry>>) {
        val batchSize = 5000
        var batch = mutableListOf<DictionaryEntry>()

        reader.beginArray()
        while (reader.hasNext()) {
            try {
                reader.beginArray()
                val term = reader.nextString()
                val type = nextStringOrArray(reader)
                val data: JsonElement? = if (reader.peek() != JsonToken.END_ARRAY) {
                    gson.fromJson(reader, JsonElement::class.java)
                } else null
                while (reader.hasNext()) reader.skipValue()
                reader.endArray()

                if (type == "pitch" && data != null && term.isNotEmpty()) {
                    val reading = data.takeIf { it.isJsonObject }
                        ?.asJsonObject?.get("reading")
                        ?.takeIf { it.isJsonPrimitive }?.asJsonPrimitive?.asString
                    if (!reading.isNullOrEmpty()) {
                        batch.add(DictionaryEntry(
                            kanji = term,
                            reading = reading,
                            definitions = data.toString(),
                            rules = "",
                            popularity = 0,
                            dictionaryId = dictionaryId
                        ))
                    }
                }
            } catch (e: Exception) {
                Log.e("DictionaryImporter", "Failed to parse term meta entry", e)
                while (reader.peek() != JsonToken.END_ARRAY && reader.peek() != JsonToken.END_DOCUMENT) reader.skipValue()
                if (reader.peek() == JsonToken.END_ARRAY) reader.endArray()
            }
            if (batch.size >= batchSize) {
                channel.send(batch)
                batch = mutableListOf()
            }
        }
        reader.endArray()
        if (batch.isNotEmpty()) channel.send(batch)
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
