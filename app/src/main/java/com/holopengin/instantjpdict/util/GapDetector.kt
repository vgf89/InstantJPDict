package com.holopengin.instantjpdict.util

import com.holopengin.instantjpdict.LineResult
import kotlin.math.abs

/**
 * One detected deletion site on a recognised line (#44, plan Task 2.2).
 *
 * The spacing trigger is the measured one: a character dropped by the recogniser
 * leaves roughly **twice** the median spacing between the neighbouring emitted
 * characters (measured 2.00× at deletion sites versus 1.00× for ordinary adjacent
 * pairs over 231 paired lines).
 *
 * @property insertAt the **character index in [LineResult.text]** the placeholder
 *   belongs at: the gap sits between `text[insertAt - 1]` and `text[insertAt]`.
 *   Feed it straight to `LineResult.withGapCharAt(insertAt, column)`.
 * @property ratio this pair's spacing divided by the line's own median spacing
 *   (scale-invariant: pixels and timesteps give the same number).
 * @property spanPx the pair's spacing in pixels; estimated from timesteps (and the
 *   crop length) when no char boxes are available.
 */
data class Gap(
    val insertAt: Int,
    val ratio: Float,
    val spanPx: Float,
) {
    /** The plan document's name for [ratio]; same value, kept so downstream
     *  call sites can use either spelling. */
    val pitchRatio: Float get() = ratio
}

/**
 * Spacing-ratio detector for characters the recogniser dropped (#44, plan Task 2.2).
 *
 * ## Why per-orientation
 *
 * The trigger was measured separately for the two domains, and they are not the
 * same problem (M5 in `docs/ocr-oov-correction-plan.md`):
 *
 * | bench | deletion ratio | rule | recall | false rate |
 * |---|---|---|---|---|
 * | vertical (`vert_large`) | median 2.00 (p25 1.92) | ≥1.6 | **1.00** | **0.00%** |
 * | horizontal (trails ×2) | median 1.67 (p25 **1.00**) | ≥1.6 | 0.60 | 1.10% |
 * | horizontal | | ≥1.8 | 0.20 | 0.66% |
 *
 * A quarter of horizontal deletions leave *no* wider gap at all, so horizontal
 * can never be as good as vertical here, and the two thresholds differ. They are
 * **configurable constructor parameters** — never a hardcoded constant — because
 * the right point on the curve depends on whether a missed gap or a spurious
 * affordance is worse, and on the second signal (component agreement) that the
 * horizontal path needs before showing anything.
 *
 * ## Geometry
 *
 * Spacing is measured between adjacent **emitted** characters, in reading order:
 *
 *  1. **[LineResult.charBoxes] centres** (preferred) — `centerY` for vertical,
 *     `centerX` for horizontal. Spacings are already pixels.
 *  2. **[LineResult.charCols]** — the cached CTC timestep column per emitted
 *     character (#49). Used when `charBoxes` is empty/short; spacings are in
 *     timesteps and are converted with the crop length.
 *  3. **[LineResult.rawAlternatives]**, walked exactly as
 *     `OcrEngine.reDecodeLineResult` walks it — the last resort when neither
 *     geometry cache is populated (see [timestepColumns] for the assumptions).
 *
 * The ratio is scale-invariant, so the threshold carries across all three.
 */
class GapDetector(
    /** Trigger for vertical (tategumi) lines. Measured recall 1.00, false 0.00% at 1.6. */
    val verticalThreshold: Float = DEFAULT_VERTICAL_RATIO,
    /** Trigger for horizontal lines. 1.8 alone; horizontal also needs the component signal. */
    val horizontalThreshold: Float = DEFAULT_HORIZONTAL_RATIO,
) {

    /** The threshold that applies to a line of this orientation. */
    fun thresholdFor(isVertical: Boolean): Float =
        if (isVertical) verticalThreshold else horizontalThreshold

    /** Detect gaps using the line's own orientation threshold. */
    fun detect(line: LineResult): List<Gap> = detect(line, thresholdFor(line.isVertical))

    /**
     * Detect gaps using an explicit [threshold] (lets a caller sweep the curve
     * without rebuilding the detector).
     *
     * A pair triggers when `spacing / medianSpacing >= threshold` — the
     * `>=` form is the one the measured sweep is quoted in ("ratio ≥ 1.6").
     */
    fun detect(line: LineResult, threshold: Float): List<Gap> {
        val n = line.text.length
        if (n < MIN_EMITTED_CHARS) return emptyList()

        val geometry = geometryOf(line) ?: return emptyList()
        val centres = geometry.centres
        if (centres.size != n) return emptyList()

        val spacings = FloatArray(n - 1) { abs(centres[it + 1] - centres[it]) }
        val pitch = medianOf(spacings)
        if (pitch <= 0f) return emptyList()   // degenerate geometry: everything on one pixel

        val gaps = ArrayList<Gap>()
        for (k in spacings.indices) {
            val ratio = spacings[k] / pitch
            if (ratio >= threshold) {
                gaps.add(
                    Gap(
                        insertAt = k + 1,                       // between chars k and k+1
                        ratio = ratio,
                        spanPx = spacings[k] * geometry.pxPerUnit,
                    )
                )
            }
        }
        return gaps
    }

    // ── geometry extraction ──────────────────────────────────────────────────

    private class Geometry(val centres: FloatArray, val pxPerUnit: Float)

    private fun geometryOf(line: LineResult): Geometry? {
        val n = line.text.length
        if (n == 0) return null

        if (line.charBoxes.size >= n) {
            val centres = FloatArray(n)
            for (i in 0 until n) {
                val box = line.charBoxes[i]
                centres[i] = (if (line.isVertical) box.centerY() else box.centerX()).toFloat()
            }
            return Geometry(centres, pxPerUnit = 1f)   // boxes are already pixels
        }

        val pxPerTimestep = pixelPerTimestep(line)
        if (line.charCols.size == n) return Geometry(line.charCols, pxPerUnit = pxPerTimestep)

        val derived = timestepColumns(line.rawAlternatives)
        if (derived.size != n) return null
        return Geometry(derived, pxPerUnit = pxPerTimestep)
    }

    /**
     * Pixels per CTC timestep along the reading axis — mirrors `computeCharBoxes`'
     * `avgColW` (`cropH / seqLenTotal` vertical, `cropW / seqLenTotal` horizontal).
     * Falls back to the model's own stride ([DEFAULT_TIMESTEP_STRIDE_PX]) when the
     * crop geometry was never cached.
     */
    private fun pixelPerTimestep(line: LineResult): Float {
        val len = if (line.isVertical) line.cropH else line.cropW
        if (len > 0 && line.seqLenTotal > 0) return len.toFloat() / line.seqLenTotal.toFloat()
        return DEFAULT_TIMESTEP_STRIDE_PX
    }

    companion object {
        /** Vertical trigger: measured recall 1.00, false positives 0.00% (M5). */
        const val DEFAULT_VERTICAL_RATIO = 1.6f

        /** Horizontal trigger: the best available point, and still weak on its own. */
        const val DEFAULT_HORIZONTAL_RATIO = 1.8f

        /** Model downsampling stride, for when the crop length is unknown. */
        const val DEFAULT_TIMESTEP_STRIDE_PX = 8f

        /** Fewer emitted characters than this cannot produce a spacing pair. */
        const val MIN_EMITTED_CHARS = 2
    }
}

/**
 * Emitted character → CTC timestep column, recovered from
 * [LineResult.rawAlternatives] (`OcrEngine.kt`).
 *
 * ## Assumptions — verified against the code, and pinned by unit test
 *
 *  - `rawAlternatives[t]` is the top-N list for timestep `t`.
 *  - **The head's emitted character for a timestep is its FIRST entry** —
 *    `reDecodeLineResult` takes `alts.firstOrNull()` as the argmax
 *    (`OcrEngine.kt:2158`), the same way `ctcDecodeTopK` takes `indexed[0]`.
 *  - **Blank is stored as the `'\u3000'` entry** — `decodeChar(0)` (blank) and
 *    `decodeChar(18708)` both return `'\u3000'` (`OcrEngine.kt:1705-1713`), and
 *    `reDecodeLineResult` reads `blankScore` by matching that character
 *    (`OcrEngine.kt:2159`). If that ever changes, this walk and the
 *    confident/contest split break together — hence the test.
 *  - Collapse rules mirror `reDecodeLineResult` exactly: a blank timestep *resets*
 *    the previous character (so a repeat after a blank is emitted), a space never
 *    collapses, and any other character collapses only against the immediately
 *    preceding emitted character.
 *
 * Returns an empty array for input that derives nothing; the caller compares the
 * size against `text.length` and gives up when they disagree (which happens when
 * `blankThreshold > 0` surfaced a character at a blank timestep — use
 * [LineResult.charCols] there, it is populated for every emitted character).
 */
internal fun timestepColumns(raw: List<List<Pair<Char, Float>>>): FloatArray {
    val cols = ArrayList<Float>(raw.size)
    var prevChar: Char? = null
    for ((t, alts) in raw.withIndex()) {
        val top = alts.firstOrNull() ?: continue
        val ch = top.first
        when {
            ch == TIMESTEP_BLANK_CHAR -> prevChar = null
            ch == ' ' -> {
                cols.add(t.toFloat())
                prevChar = ' '
            }
            ch == prevChar -> Unit    // CTC repeat collapse
            else -> {
                cols.add(t.toFloat())
                prevChar = ch
            }
        }
    }
    return cols.toFloatArray()
}

/** Blank is stored in `rawAlternatives` as the ideographic space (`OcrEngine.kt:2159`). */
internal const val TIMESTEP_BLANK_CHAR = '\u3000'

/** Median of a float array; 0 for an empty array. Even counts average the middles. */
internal fun medianOf(values: FloatArray): Float {
    if (values.isEmpty()) return 0f
    val sorted = values.clone()
    sorted.sort()
    val mid = sorted.size / 2
    return if (sorted.size % 2 == 1) sorted[mid] else (sorted[mid - 1] + sorted[mid]) / 2f
}
