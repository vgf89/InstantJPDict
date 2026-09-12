package com.holopengin.instantjpdict

import com.holopengin.instantjpdict.util.BlankGaps
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * Feature 2 (#44): the clickable blank policy.
 *
 * The detector (`GapDetectorTest`) says *where* a character was dropped and the insertion
 * (`LineResultGapTest`) says what the line looks like afterwards; this pins the policy in
 * between — vertical only, idempotent, and a placeholder that stays fillable.
 *
 * Fixture geometry is the one GapDetectorTest measured: five characters at y-centres
 * 10/30/50/90/110 have spacings 20/20/40/20, median 20, so the third interval is 2.00× and
 * reports exactly one gap at insertion index 3.
 */
class BlankGapsTest {

    private val text5 = "あいうえお"

    private fun verticalBoxes(centres: List<Int>, height: Int = 20): List<JpDictRect> =
        centres.map { JpDictRect(left = 0, top = it - height / 2, right = 40, bottom = it - height / 2 + height) }

    private fun line(
        text: String = text5,
        boxes: List<JpDictRect> = emptyList(),
        isVertical: Boolean = true,
        cols: FloatArray = floatArrayOf(),
        overrides: MutableMap<Int, Pair<Char, Float>> = mutableMapOf(),
    ): LineResult = LineResult(
        text = text,
        charBoxes = boxes,
        alternatives = MutableList(text.length) { mutableListOf(text[it] to 1f) },
        isVertical = isVertical,
        charCols = cols,
        overrides = overrides,
    )

    private val gapped = listOf(10, 30, 50, 90, 110)

    @Test
    fun a_measured_vertical_gap_becomes_a_placeholder_between_its_neighbours() {
        val out = BlankGaps.apply(line(boxes = verticalBoxes(gapped)))

        // the placeholder lands between う and え, and every character keeps its identity
        // at its shifted index
        assertEquals("あいう${OcrEngine.GAP_CHAR}えお", out.text)
        assertEquals(text5.length + 1, out.charBoxes.size)
        // the synthetic alternatives entry is the placeholder itself: nothing is claimed
        // about a character nobody has evidence for, and the panel still has a selection
        assertEquals(listOf(OcrEngine.GAP_CHAR to 0f), out.alternatives[3])
        assertEquals(listOf('え' to 1f), out.alternatives[4])
    }

    @Test
    fun horizontal_lines_are_never_touched() {
        // The horizontal trigger measured 13% false, so a horizontal line is not eligible at
        // all — not even with the same geometry that fires on vertical.
        val boxes = gapped.map { JpDictRect(left = it - 10, top = 0, right = it + 10, bottom = 40) }
        val horizontal = line(boxes = boxes, isVertical = false)

        assertSame(horizontal, BlankGaps.apply(horizontal))
    }

    @Test
    fun a_line_that_already_has_a_placeholder_is_unchanged() {
        // Applied twice must not insert twice: the line is replaced on every decode, and a
        // second placeholder would shift the user's filled override.
        val once = BlankGaps.apply(line(boxes = verticalBoxes(gapped)))
        val twice = BlankGaps.apply(once)

        assertSame(once, twice)
    }

    @Test
    fun an_evenly_spaced_line_is_unchanged() {
        val even = line(boxes = verticalBoxes(listOf(10, 30, 50, 70, 90)))

        assertSame(even, BlankGaps.apply(even))
    }

    @Test
    fun the_placeholder_column_sits_between_its_neighbours() {
        // charCols drives box recomputation (#49); the new column is the midpoint, matching
        // the geometry the placeholder box itself is interpolated from.
        val cols = floatArrayOf(0f, 2f, 4f, 8f, 10f)
        val plain = line(boxes = verticalBoxes(gapped), cols = cols)

        val out = BlankGaps.apply(plain)

        assertNotSame(plain, out)
        assertEquals(6, out.charCols.size)
        assertEquals(6f, out.charCols[3], 0.001f)
        assertEquals(10f, out.charCols[5], 0.001f)
    }

    @Test
    fun a_filled_blank_is_still_an_override_and_survives_the_shift() {
        // Filling the blank writes overrides[3]; an override that was already on a later
        // character must move with it, or the fill would land on the wrong character.
        val withOverride = line(boxes = verticalBoxes(gapped),
                                overrides = mutableMapOf(4 to ('ぇ' to 1f)))

        val out = BlankGaps.apply(withOverride)

        assertEquals('ぇ', out.overrides[5]!!.first)
        assertEquals(true, out.overrides[3] == null)
    }
}
