package com.holopengin.instantjpdict

import com.holopengin.instantjpdict.util.GapDetector
import com.holopengin.instantjpdict.util.withGapAt
import com.holopengin.instantjpdict.util.withGapCharAt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Synthetic position insertion (#44, plan Task 2.3). Materialising a detected gap
 * must leave the line's parallel per-character lists describing the same
 * characters at the same indices — that is the whole contract.
 */
class LineResultGapTest {

    // ── fixtures ────────────────────────────────────────────────────────────

    private fun boxes(vertical: Boolean, centres: List<Int> = listOf(10, 30, 50, 70, 90)): List<JpDictRect> =
        if (vertical) {
            centres.map { JpDictRect(left = 0, top = it - 10, right = 40, bottom = it + 10) }
        } else {
            centres.map { JpDictRect(left = it - 10, top = 0, right = it + 10, bottom = 40) }
        }

    private fun fixture(
        text: String = "あいうえお",
        vertical: Boolean = true,
        charBoxes: List<JpDictRect>? = null,
        overrides: MutableMap<Int, Pair<Char, Float>> = mutableMapOf(0 to ('Z' to 1f), 4 to ('X' to 1f)),
        charCols: FloatArray? = null,
    ): LineResult = LineResult(
        text = text,
        charBoxes = charBoxes ?: boxes(vertical),
        alternatives = MutableList(text.length) { mutableListOf(text[it] to 1f) },
        isVertical = vertical,
        overrides = overrides,
        rawAlternatives = emptyList(),
        seqLenTotal = 0,
        cropW = if (vertical) 40 else 100,
        cropH = if (vertical) 100 else 40,
        charCols = charCols ?: FloatArray(text.length) { it.toFloat() },
    )

    private val gap = OcrEngine.GAP_CHAR

    // ── the insertion itself ────────────────────────────────────────────────

    @Test
    fun inserts_the_placeholder_at_the_requested_index() {
        val line = fixture()

        val out = line.withGapCharAt(index = 3, column = 3.5f)

        assertEquals(6, out.text.length)
        assertTrue(out.text[3] == gap)
        assertEquals(line.text.substring(0, 3) + gap + line.text.substring(3), out.text)
        // everything else about the line is carried over
        assertEquals(line.isVertical, out.isVertical)
        assertEquals(line.cropW, out.cropW)
        assertEquals(line.cropH, out.cropH)
    }

    @Test
    fun all_per_character_lists_stay_the_same_length() {
        val out = fixture().withGapCharAt(index = 3, column = 3f)

        assertEquals(6, out.text.length)
        assertEquals(out.text.length, out.charBoxes.size)
        assertEquals(out.text.length, out.alternatives.size)
        assertEquals(out.text.length, out.charCols.size)

        // and the receiver is not mutated
        val line = fixture()
        line.withGapCharAt(index = 3, column = 3f)
        assertEquals(5, line.text.length)
        assertEquals(5, line.charBoxes.size)
        assertEquals(5, line.alternatives.size)
        assertEquals(setOf(0, 4), line.overrides.keys)   // not re-keyed in place
    }

    @Test
    fun a_character_at_index_n_moves_to_n_plus_one() {
        val line = fixture()

        val out = line.withGapCharAt(index = 3, column = 3f)

        for (i in 0 until 3) {
            assertEquals(line.text[i], out.text[i])
            assertEquals(line.charBoxes[i], out.charBoxes[i])
            assertEquals(line.alternatives[i], out.alternatives[i])
            assertEquals(line.charCols[i], out.charCols[i], 0f)
        }
        for (i in 3 until 5) {
            assertEquals(line.text[i], out.text[i + 1])
            assertEquals(line.charBoxes[i], out.charBoxes[i + 1])
            assertEquals(line.alternatives[i], out.alternatives[i + 1])
            assertEquals(line.charCols[i], out.charCols[i + 1], 0f)
        }
    }

    @Test
    fun overrides_follow_the_characters_they_were_applied_to() {
        val out = fixture().withGapCharAt(index = 3, column = 3f)

        assertEquals(2, out.overrides.size)
        assertEquals('Z' to 1f, out.overrides[0])          // before the insertion: unchanged
        assertEquals('X' to 1f, out.overrides[5])          // was index 4, now shifted
        assertFalse(out.overrides.containsKey(4))
        assertFalse(out.overrides.containsKey(3))          // the placeholder carries no override
    }

    @Test
    fun every_override_shifts_when_inserting_at_the_start() {
        val out = fixture().withGapCharAt(index = 0, column = 0f)

        assertEquals('Z' to 1f, out.overrides[1])
        assertEquals('X' to 1f, out.overrides[5])
        assertEquals(2, out.overrides.size)
    }

    // ── the interpolated box ────────────────────────────────────────────────

    @Test
    fun the_gap_box_sits_between_its_neighbours() {
        val line = fixture(vertical = true)               // centres 10, 30, 50, 70, 90

        val out = line.withGapCharAt(index = 3, column = 3f)

        val before = out.charBoxes[2]
        val inserted = out.charBoxes[3]
        val after = out.charBoxes[4]
        assertEquals(50, before.centerY())
        assertEquals(60, inserted.centerY())              // midpoint of 50 and 70
        assertEquals(70, after.centerY())
        assertEquals(20, inserted.height())               // mean of the neighbours' heights
        assertEquals(0, inserted.left)
        assertEquals(40, inserted.right)
        // the neighbours keep their own boxes
        assertEquals(line.charBoxes[2], before)
        assertEquals(line.charBoxes[3], after)
    }

    @Test
    fun the_gap_box_interpolates_on_the_x_axis_for_horizontal_lines() {
        val out = fixture(vertical = false).withGapCharAt(index = 1, column = 1f)

        val inserted = out.charBoxes[1]
        assertEquals(20, inserted.centerX())              // midpoint of 10 and 30
        assertEquals(20, inserted.width())
        assertEquals(0, inserted.top)
        assertEquals(40, inserted.bottom)
    }

    @Test
    fun inserting_at_the_ends_reuses_the_single_neighbour() {
        val line = fixture()

        val atStart = line.withGapCharAt(index = 0, column = 0f)
        assertTrue(atStart.text[0] == gap)
        assertEquals(line.charBoxes[0], atStart.charBoxes[0])
        assertEquals(atStart.text.length, atStart.charBoxes.size)

        val atEnd = line.withGapCharAt(index = line.text.length, column = 5f)
        assertTrue(atEnd.text[5] == gap)
        assertEquals(line.charBoxes[4], atEnd.charBoxes[5])   // single neighbour reused
        assertEquals(atEnd.text.length, atEnd.charBoxes.size)
    }

    // ── alternatives + the lookup guard ─────────────────────────────────────

    @Test
    fun the_synthetic_alternatives_entry_defaults_to_the_reversible_blank() {
        val out = fixture().withGapCharAt(index = 3, column = 3f)

        assertEquals(1, out.alternatives[3].size)
        assertEquals(gap, out.alternatives[3][0].first)
        assertEquals(0f, out.alternatives[3][0].second, 0f)
    }

    @Test
    fun a_caller_can_supply_its_own_gap_alternatives() {
        val custom = mutableListOf(gap to 0.5f, 'あ' to 0.4f)

        val out = fixture().withGapCharAt(index = 2, column = 2f, gapAlternatives = custom)

        assertEquals(custom, out.alternatives[2])
        assertEquals(6, out.alternatives.size)
    }

    @Test
    fun the_inserted_placeholder_is_unlookupable() {
        // OcrOverlayStateController.kt:366 returns null (no dictionary lookup) when
        // the tapped character index holds GAP_CHAR — the boxed blank's whole point.
        val out = fixture().withGapCharAt(index = 3, column = 3f)

        assertTrue(out.text.getOrNull(3) == gap)
        assertTrue(out.text.getOrNull(2) == 'う')          // neighbours unaffected
        assertTrue(out.text.getOrNull(4) == 'え')
    }

    // ── degenerate inputs ───────────────────────────────────────────────────

    @Test
    fun an_index_out_of_range_is_a_no_op() {
        val line = fixture()

        assertSame(line, line.withGapCharAt(index = -1, column = 0f))
        assertSame(line, line.withGapCharAt(index = 6, column = 0f))
    }

    @Test
    fun empty_box_and_column_lists_stay_empty() {
        val line = fixture(charBoxes = emptyList(), charCols = floatArrayOf())

        val out = line.withGapCharAt(index = 2, column = 2f)

        assertEquals(6, out.text.length)
        assertEquals(6, out.alternatives.size)
        assertTrue(out.charBoxes.isEmpty())                // no geometry to interpolate
        assertTrue(out.charCols.isEmpty())
    }

    @Test
    fun withGapAt_is_the_same_operation_as_withGapCharAt() {
        val line = fixture()

        val a = line.withGapCharAt(index = 2, column = 2f)
        val b = line.withGapAt(index = 2, column = 2f)

        assertEquals(a.text, b.text)
        assertEquals(a.charBoxes, b.charBoxes)
        assertEquals(a.alternatives, b.alternatives)
        assertEquals(a.overrides, b.overrides)
    }

    // ── integration: detect → materialise ───────────────────────────────────

    @Test
    fun a_detected_gap_can_be_materialised_and_consumes_the_spacing() {
        val line = LineResult(
            text = "あいうえお",
            charBoxes = boxes(vertical = true, centres = listOf(10, 30, 50, 90, 110)),
            alternatives = MutableList(5) { mutableListOf("あいうえお"[it] to 1f) },
            isVertical = true,
            overrides = mutableMapOf(4 to ('X' to 1f)),
            charCols = FloatArray(5) { it.toFloat() },
        )

        val detected = GapDetector().detect(line)
        assertEquals(1, detected.size)
        val detectedGap = detected.single()
        assertEquals(3, detectedGap.insertAt)

        val out = line.withGapCharAt(detectedGap.insertAt, column = 3f)

        assertEquals(6, out.text.length)
        assertEquals(6, out.charBoxes.size)
        assertEquals(6, out.alternatives.size)
        assertEquals(6, out.charCols.size)
        assertTrue(out.text[3] == gap)
        assertEquals('X' to 1f, out.overrides[5])
        // the placeholder's box takes up the space, so the line reads as uniform again
        assertTrue(GapDetector().detect(out).isEmpty())
    }
}
