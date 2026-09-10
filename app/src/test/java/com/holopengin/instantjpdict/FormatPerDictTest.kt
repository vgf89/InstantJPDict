package com.holopengin.instantjpdict

import com.google.gson.Gson
import com.holopengin.instantjpdict.data.DictionaryEntry
import org.junit.Test

import org.junit.Assert.*

// Entries from different dictionaries must never merge into one block,
// and each block carries its source name.
class FormatPerDictTest {
    private fun entry(
        kanji: String,
        reading: String,
        dictId: Int,
        onyomi: String? = null,
        kunyomi: String? = null
    ) = DictionaryEntry(
        kanji = kanji,
        reading = reading,
        definitions = "\"x\"",
        rules = "",
        popularity = 0,
        dictionaryId = dictId,
        onyomi = onyomi,
        kunyomi = kunyomi
    )

    @Test
    fun splits_dictionaries_and_labels_source() {
        val c = OcrOverlayStateController()
        val out = c.formatDictionaryResults(
            listOf(
                TermMatch(
                    "君",
                    listOf(
                        entry("君", "きみ", 1),
                        entry("君", "クン", 2, onyomi = "クン", kunyomi = "きみ")
                    )
                )
            ),
            Gson(),
            mapOf(1 to "JMdict", 2 to "KANJIDIC")
        )
        assertEquals(2, out.size)
        val jm = out.first { it.dictionaryName == "JMdict" }
        val kd = out.first { it.dictionaryName == "KANJIDIC" }
        assertTrue(jm.readingGroups.none { it.isKanjiEntry })
        assertTrue(kd.readingGroups.all { it.isKanjiEntry })
        // JMdict-only rows stay one block.
        assertEquals(listOf("きみ"), jm.readingGroups.map { it.reading })
    }

    @Test
    fun single_dictionary_unchanged_except_label() {
        val c = OcrOverlayStateController()
        val out = c.formatDictionaryResults(
            listOf(TermMatch("た", listOf(entry("他", "た", 1), entry("多", "た", 1)))),
            Gson(),
            mapOf(1 to "JMdict")
        )
        assertEquals(1, out.size)
        assertEquals("JMdict", out[0].dictionaryName)
        assertEquals(1, out[0].readingGroups.size)
        assertEquals(2, out[0].readingGroups[0].headwords.size)
    }

    @Test
    fun missing_name_omits_label_but_still_splits() {
        val c = OcrOverlayStateController()
        val out = c.formatDictionaryResults(
            listOf(
                TermMatch(
                    "君",
                    listOf(entry("君", "きみ", 1), entry("君", "クン", 9, onyomi = "クン"))
                )
            ),
            Gson(),
            mapOf(1 to "JMdict")
        )
        assertEquals(2, out.size)
        assertNull(out.first { it.dictionaryName == null }.dictionaryName)
    }
}
