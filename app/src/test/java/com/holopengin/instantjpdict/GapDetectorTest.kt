package com.holopengin.instantjpdict

import com.holopengin.instantjpdict.util.GapDetector
import com.holopengin.instantjpdict.util.TIMESTEP_BLANK_CHAR
import com.holopengin.instantjpdict.util.medianOf
import com.holopengin.instantjpdict.util.timestepColumns
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Spacing-ratio gap detection (#44, plan Task 2.2).
 *
 * The trigger is the measured one: a dropped character leaves ~2.00× the median
 * spacing between neighbouring emitted characters, against 1.00× for ordinary
 * pairs. The threshold is **per orientation** (vertical 1.6, horizontal 1.8)
 * and configurable, because vertical is near-perfect at 1.6 while horizontal
 * needs the higher point and still misses a quarter of its deletions.
 *
 * Note on fixture size: the pitch denominator is the *line's own median spacing*,
 * so the tests use five characters (four spacings). With only two spacings — a
 * three-character line — the median is contaminated by the very gap being looked
 * for; `three_char_line_median_is_contaminated_by_the_gap` pins that limitation
 * explicitly so nobody "fixes" it by lowering the threshold.
 */
class GapDetectorTest {

    private val detector = GapDetector()

    // ── fixtures ────────────────────────────────────────────────────────────

    private val text5 = "あいうえお"

    private fun verticalBoxes(centres: List<Int>, height: Int = 20): List<JpDictRect> =
        centres.map { JpDictRect(left = 0, top = it - height / 2, right = 40, bottom = it - height / 2 + height) }

    private fun horizontalBoxes(centres: List<Int>, width: Int = 20, height: Int = 40): List<JpDictRect> =
        centres.map { JpDictRect(left = it - width / 2, top = 0, right = it - width / 2 + width, bottom = height) }

    private fun line(
        text: String = text5,
        boxes: List<JpDictRect> = emptyList(),
        isVertical: Boolean = true,
        raw: List<List<Pair<Char, Float>>> = emptyList(),
        cols: FloatArray = floatArrayOf(),
        cropW: Int = 0,
        cropH: Int = 0,
        seqLenTotal: Int = 0,
    ): LineResult = LineResult(
        text = text,
        charBoxes = boxes,
        alternatives = MutableList(text.length) { mutableListOf(text[it] to 1f) },
        isVertical = isVertical,
        rawAlternatives = raw,
        seqLenTotal = seqLenTotal,
        cropW = cropW,
        cropH = cropH,
        charCols = cols,
    )

    /** One timestep's top-N list; entry 0 is the head's emitted character. */
    private fun step(ch: Char, score: Float = 1f): List<Pair<Char, Float>> = listOf(ch to score)

    /** A blank timestep, stored as the `'\u3000'` entry (OcrEngine.kt:2159). */
    private val blankStep: List<Pair<Char, Float>> = listOf(TIMESTEP_BLANK_CHAR to 0.99f)

    /**
     * 11 timesteps that decode to [text5] with a deleted character between the
     * third and fourth emitted characters: emitted columns are 0, 2, 4, 8, 10.
     */
    private val rawWithDeletion: List<List<Pair<Char, Float>>> = listOf(
        step('あ'),          // t0
        blankStep,           // t1
        step('い'),          // t2
        blankStep,           // t3
        step('う'),          // t4
        blankStep,           // t5  ┐ three blanks where a character was dropped
        blankStep,           // t6  │
        blankStep,           // t7  ┘
        step('え'),          // t8
        blankStep,           // t9
        step('お'),          // t10
    )

    // ── the measured signal ─────────────────────────────────────────────────

    @Test
    fun double_spacing_in_the_middle_reports_exactly_one_gap_with_ratio_two() {
        // spacings 20, 20, 40, 20 → median 20 → the third pair is 2.00×
        val gaps = detector.detect(line(boxes = verticalBoxes(listOf(10, 30, 50, 90, 110))))

        assertEquals(1, gaps.size)
        val gap = gaps.single()
        assertEquals(3, gap.insertAt)                       // between chars 2 and 3
        assertEquals(2.0f, gap.ratio, 1e-4f)
        assertEquals(40f, gap.spanPx, 1e-4f)
        assertEquals(gap.ratio, gap.pitchRatio, 0f)         // plan-document spelling
    }

    @Test
    fun uniform_vertical_line_reports_none() {
        val gaps = detector.detect(line(boxes = verticalBoxes(listOf(10, 30, 50, 70, 90))))

        assertTrue(gaps.isEmpty())
    }

    @Test
    fun uniform_horizontal_line_reports_none() {
        val gaps = detector.detect(
            line(boxes = horizontalBoxes(listOf(10, 30, 50, 70, 90)), isVertical = false),
        )

        assertTrue(gaps.isEmpty())
    }

    // ── per-orientation thresholds ──────────────────────────────────────────

    @Test
    fun a_1_7_ratio_fires_vertically_and_not_horizontally() {
        val centres = listOf(10, 30, 50, 84, 104)           // spacings 20, 20, 34, 20 → 1.70×

        val vertical = detector.detect(line(boxes = verticalBoxes(centres)))
        assertEquals(1, vertical.size)
        assertEquals(1.7f, vertical.single().ratio, 1e-4f)
        assertEquals(3, vertical.single().insertAt)

        val horizontal = detector.detect(line(boxes = horizontalBoxes(centres), isVertical = false))
        assertTrue("1.70 < 1.8 horizontal threshold", horizontal.isEmpty())
    }

    @Test
    fun a_1_9_ratio_fires_horizontally_too() {
        val gaps = detector.detect(
            line(boxes = horizontalBoxes(listOf(10, 30, 50, 88, 108)), isVertical = false),
        )

        assertEquals(1, gaps.size)
        assertEquals(1.9f, gaps.single().ratio, 1e-4f)
    }

    @Test
    fun the_vertical_threshold_is_inclusive_at_1_6() {
        // measured curve is quoted as "ratio ≥ 1.6"
        val gaps = detector.detect(line(boxes = verticalBoxes(listOf(10, 30, 50, 82, 102))))

        assertEquals(1, gaps.size)
        assertEquals(1.6f, gaps.single().ratio, 1e-4f)
    }

    @Test
    fun thresholds_are_configurable_per_orientation() {
        assertEquals(1.6f, detector.thresholdFor(isVertical = true), 1e-4f)
        assertEquals(1.8f, detector.thresholdFor(isVertical = false), 1e-4f)

        val ratioTwoVertical = line(boxes = verticalBoxes(listOf(10, 30, 50, 90, 110)))
        assertTrue(GapDetector(verticalThreshold = 2.5f).detect(ratioTwoVertical).isEmpty())
        assertEquals(1, GapDetector(verticalThreshold = 1.2f).detect(ratioTwoVertical).size)

        // A caller may also sweep the curve without rebuilding the detector.
        assertTrue(detector.detect(ratioTwoVertical, threshold = 2.5f).isEmpty())
        assertEquals(1, detector.detect(ratioTwoVertical, threshold = 2.0f).size)
    }

    // ── geometry fallbacks ──────────────────────────────────────────────────

    @Test
    fun empty_char_boxes_falls_back_to_raw_alternatives() {
        val gaps = detector.detect(line(boxes = emptyList(), raw = rawWithDeletion))

        assertEquals(1, gaps.size)
        val gap = gaps.single()
        assertEquals(3, gap.insertAt)
        assertEquals(2.0f, gap.ratio, 1e-4f)
        // 4 timesteps × the model stride (8 px), since no crop length was cached
        assertEquals(32f, gap.spanPx, 1e-4f)
    }

    @Test
    fun fallback_span_uses_the_crop_length_when_it_is_known() {
        val vertical = detector.detect(
            line(boxes = emptyList(), raw = rawWithDeletion, cropH = 55, seqLenTotal = 11),
        )
        // cropH / seqLenTotal = 5 px per timestep, spacing = 4 timesteps
        assertEquals(20f, vertical.single().spanPx, 1e-4f)

        val horizontal = detector.detect(
            line(
                boxes = emptyList(), raw = rawWithDeletion, isVertical = false,
                cropW = 110, seqLenTotal = 11,
            ),
        )
        assertEquals(40f, horizontal.single().spanPx, 1e-4f)
    }

    @Test
    fun char_cols_geometry_is_used_when_boxes_are_missing() {
        val gaps = detector.detect(
            line(boxes = emptyList(), cols = floatArrayOf(0f, 2f, 4f, 8f, 10f)),
        )

        assertEquals(1, gaps.size)
        assertEquals(3, gaps.single().insertAt)
        assertEquals(2.0f, gaps.single().ratio, 1e-4f)
    }

    @Test
    fun text_that_does_not_match_the_timestep_walk_detects_nothing() {
        // 5 emitted columns derived, but the line claims 2 characters
        val gaps = detector.detect(line(text = "あい", boxes = emptyList(), raw = rawWithDeletion))

        assertTrue(gaps.isEmpty())
    }

    @Test
    fun degenerate_geometry_detects_nothing() {
        // every character on the same pixel → median spacing 0
        assertTrue(detector.detect(line(boxes = verticalBoxes(listOf(50, 50, 50, 50, 50)))).isEmpty())
        assertTrue(detector.detect(line(text = "あ", boxes = emptyList())).isEmpty())
    }

    @Test
    fun three_char_line_median_is_contaminated_by_the_gap() {
        // spacings 20, 40 → median 30 → the double gap reads as 1.33×, below both
        // thresholds. The pitch estimator needs a few ordinary pairs to be robust;
        // a two-spacing line cannot supply them. Documented, not a bug to "fix" by
        // lowering the threshold (that would break the measured false-positive rate).
        val gaps = detector.detect(line(boxes = verticalBoxes(listOf(10, 30, 70))))

        assertTrue(gaps.isEmpty())
    }

    // ── the rawAlternatives contract the fallback depends on ─────────────────

    @Test
    fun timestep_walk_matches_the_documented_assumptions() {
        // Entry 0 is the argmax; '\u3000' is the blank entry (OcrEngine.kt:2159);
        // blank resets the repeat state, spaces never collapse.
        val raw = listOf(
            step('あ', 0.90f),                   // t0 emitted
            step('あ', 0.80f),                   // t1 CTC repeat → collapsed
            blankStep,                            // t2 blank → resets the previous char
            step('あ', 0.70f),                   // t3 emitted again after the blank
            step(' ', 0.60f),                    // t4 space
            step(' ', 0.50f),                    // t5 space again — spaces never collapse
            step('い', 0.90f),                   // t6
        )

        assertArrayEquals(
            floatArrayOf(0f, 3f, 4f, 5f, 6f),
            timestepColumns(raw),
            0f,
        )
    }

    @Test
    fun blank_marker_and_placeholder_are_different_characters() {
        assertTrue(TIMESTEP_BLANK_CHAR == '\u3000')     // blank, as rawAlternatives stores it
        assertTrue(OcrEngine.GAP_CHAR == '\u25CC')      // the reversible placeholder
        assertFalse(TIMESTEP_BLANK_CHAR == OcrEngine.GAP_CHAR)
    }

    // ── median helper ───────────────────────────────────────────────────────

    @Test
    fun median_helper_handles_odd_even_and_empty_input() {
        assertEquals(0f, medianOf(floatArrayOf()), 0f)
        assertEquals(5f, medianOf(floatArrayOf(5f)), 0f)
        assertEquals(1.5f, medianOf(floatArrayOf(2f, 1f)), 0f)
        assertEquals(2f, medianOf(floatArrayOf(3f, 1f, 2f)), 0f)
    }
}
