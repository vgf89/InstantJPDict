package com.holopengin.instantjpdict

import com.holopengin.instantjpdict.util.CharLm
import com.holopengin.instantjpdict.util.GapCandidates
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * The blank's candidate list (#44, Feature 2). The model is built here in the loader's own
 * packed format, which exercises [CharLm.fromBytes] and the binary search as well as the
 * ranking that depends on them.
 */
class GapCandidatesTest {

    /** A packed table: "<4sIII" header (magic, entries, order, unigram mass), 10-byte records. */
    private fun packed(entries: List<Pair<String, Int>>): ByteArray {
        val records = entries.map { (ngram, count) ->
            val units = IntArray(CharLm.MAX_ORDER)
            ngram.forEachIndexed { i, ch -> units[i] = ch.code }
            units to count
        }.sortedWith(compareBy({ it.first[0] }, { it.first[1] }, { it.first[2] }, { it.first[3] }))
        val head = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN)
        head.put("CLM1".toByteArray()).putInt(records.size).putInt(CharLm.MAX_ORDER)
        head.putInt(records.filter { r -> r.first.drop(1).all { it == 0 } }.sumOf { it.second })
        val out = ByteArrayOutputStream()
        out.write(head.array())
        for ((units, count) in records) {
            val rec = ByteBuffer.allocate(10).order(ByteOrder.LITTLE_ENDIAN)
            units.forEach { rec.putShort(it.toShort()) }
            rec.putShort(count.toShort())
            out.write(rec.array())
        }
        return out.toByteArray()
    }

    @Test
    fun the_model_binary_searches_the_packed_table() {
        val lm = CharLm.fromBytes(packed(listOf("私" to 100, "思" to 50, "阿" to 1, "私思" to 40)))!!
        assertEquals(100, lm.count("私"))
        assertEquals(40, lm.count("私思"))
        assertEquals(0, lm.count("阿呆"))
        assertEquals(0, lm.count("ののののの"))  // longer than the model's order
    }

    @Test
    fun the_pool_keeps_kana_and_punctuation_and_deduplicates() {
        val alts = listOf(
            listOf('\u25CC' to 0.9f, '、' to 0.5f, '思' to 0.3f),
            listOf('思' to 0.8f, '阿' to 0.2f),
        )
        // A kanji-only pool would come back empty here, which is the reported bug: the
        // evidence for the gap in vertical text is very often punctuation.
        assertEquals(listOf('、', '思', '阿'), GapCandidates.generate("私\u25CCう", alts, 1, null))
    }

    @Test
    fun the_model_reorders_the_pool_by_the_line_context() {
        val lm = CharLm.fromBytes(packed(listOf("私" to 100, "思" to 50, "阿" to 1)))!!
        val alts = listOf(listOf('阿' to 0.9f, '思' to 0.2f))
        // Discovery order puts 阿 first; the text prior over 私's line puts 思 first.
        assertEquals(listOf('阿', '思'), GapCandidates.generate("私\u25CCう", alts, 1, null))
        assertEquals(listOf('思', '阿'), GapCandidates.generate("私\u25CCう", alts, 1, lm))
    }

    @Test
    fun the_context_stops_at_the_placeholder_and_the_model_order() {
        assertEquals("私", GapCandidates.contextBefore("私\u25CCう", 1))
        assertEquals("", GapCandidates.contextBefore("\u25CCう", 0))
        assertEquals("アイウ", GapCandidates.contextBefore("エアイウ\u25CC", 4))
    }

    @Test
    fun a_line_with_no_evidence_offers_nothing() {
        assertTrue(GapCandidates.generate("の\u25CC。", emptyList(), 1, null).isEmpty())
        assertTrue(GapCandidates.generate("の\u25CC。", listOf(listOf(' ' to 1f)), 1, null).isEmpty())
    }
}
