package com.holopengin.instantjpdict

import com.holopengin.instantjpdict.util.CharLm
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * The blank's list, at the level it actually broke (#44). The bug was not in the pool or the
 * model: it was that the placeholder was only handled where the alternatives table had *no*
 * entry, and the table does carry one for the placeholder — so the ranked path was
 * unreachable and the list came back as the dotted circle alone.
 *
 * This asserts on the controller's public state, which is what the panel renders.
 */
class BlankAlternativesTest {

    private val gap = '\u25CC'

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

    /** The reported case: the table *does* hold the placeholder, as withGapCharAt writes it. */
    private fun lineWithGapEntry(): LineResult = LineResult(
        text = "私${gap}う",
        charBoxes = emptyList(),
        alternatives = listOf(
            mutableListOf('私' to 1f),
            mutableListOf(gap to 0f),
            mutableListOf('う' to 1f),
        ),
        rawAlternatives = listOf(listOf('、' to 0.7f, '。' to 0.5f)),
    )

    /** The older case: the table never grew, so there is no entry to read at all. */
    private fun lineWithShortTable(): LineResult = LineResult(
        text = "私${gap}う",
        charBoxes = emptyList(),
        alternatives = listOf(mutableListOf('私' to 1f)),
        rawAlternatives = emptyList(),
    )

    private fun controllerFor(line: LineResult, lm: CharLm? = null): OcrOverlayStateController {
        val c = OcrOverlayStateController()
        c.activeLineResults = mutableListOf(line)
        c.installCharLm(lm)
        return c
    }

    @Test
    fun a_blank_offers_more_than_the_placeholder_even_when_the_table_holds_it() {
        val state = controllerFor(lineWithGapEntry()).getAlternativesUiState(0, 1)!!
        assertEquals("the placeholder should lead and be selected", gap, state.candidates[0].char)
        assertTrue(state.candidates[0].isSelected)
        assertTrue(
            "a blank must never offer the placeholder alone: ${state.candidates.map { it.char }}",
            state.candidates.size > 1,
        )
    }

    @Test
    fun a_blank_offers_more_than_the_placeholder_when_the_table_never_grew() {
        val state = controllerFor(lineWithShortTable()).getAlternativesUiState(0, 1)!!
        assertTrue("blank list was ${state.candidates.map { it.char }}", state.candidates.size > 1)
    }

    @Test
    fun the_model_orders_the_blank_list_by_the_line_context() {
        // の is the most frequent of the fallback set in this toy corpus, and the model
        // should therefore place it ahead of 。 after 私.
        val lm = CharLm.fromBytes(packed(listOf("私" to 100, "の" to 50, "、" to 20)))!!
        val state = controllerFor(lineWithShortTable(), lm).getAlternativesUiState(0, 1)!!
        val chars = state.candidates.map { it.char }
        assertEquals("blank list was $chars", 'の', chars[1])
        assertTrue("、 should beat 。 (unseen): $chars", chars.indexOf('、') < chars.indexOf('。'))
        assertFalse(chars.contains(gap).not())
    }

    @Test
    fun an_ordinary_character_still_gets_its_own_head_list() {
        val state = controllerFor(lineWithGapEntry()).getAlternativesUiState(0, 0)!!
        assertEquals(listOf('私'), state.candidates.map { it.char })
    }
}
