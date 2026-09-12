package com.holopengin.instantjpdict.util

import com.holopengin.instantjpdict.JpDictRect
import com.holopengin.instantjpdict.LineResult
import com.holopengin.instantjpdict.OcrEngine

/**
 * Insert the reversible blank placeholder [OcrEngine.GAP_CHAR] (U+25CC) at
 * character index [index] of this line (#44, plan Task 2.3).
 *
 * This is the *synthetic position* half of the gap pipeline: [GapDetector] says
 * **where** a character was dropped, this says what the line looks like once the
 * placeholder is materialised there. It is pure data — no UI, no model, no
 * dictionary; the placeholder is deliberately un-lookupable downstream
 * (`OcrOverlayStateController.kt:366` returns `null` for a character index whose
 * text is `GAP_CHAR`, which is exactly the "clickable blank, no definition"
 * behaviour), so nothing here needs to change that file.
 *
 * ## What stays index-aligned
 *
 * `LineResult` carries parallel per-character lists; after insertion they must all
 * still describe the *same* characters at the *same* indices:
 *
 *  - `text` grows by one character, with the placeholder at [index]. The character
 *    that was at `index` is now at `index + 1` (and so on for the rest).
 *  - `charBoxes` grows by one, interpolated between the two neighbours — the box
 *    that was at `index` still belongs to its character, now at `index + 1`.
 *  - `alternatives` grows by one synthetic entry ([gapAlternatives]); the entry
 *    that was at `index` stays with its character at `index + 1`.
 *  - `charCols` (the CTC timestep column per emitted character, #49) grows by one
 *    at [index], so box recomputation from the very same columns still lines up.
 *  - `overrides` keys move: every key `>= index` shifts by +1, so an applied
 *    correction or a filled blank keeps pointing at the character it was applied
 *    to. Keys before [index] are untouched.
 *
 * Lists that carry no data (empty `charBoxes` before crop geometry is known, an
 * empty `alternatives`) are **left empty** rather than given a single mismatched
 * entry — "empty" means "unknown", and one entry cannot describe `n + 1`
 * characters.
 *
 * @param index character index to insert at, `0..text.length`. Out of range is a
 *   no-op: the receiver is returned unchanged.
 * @param column the CTC timestep column for the new position (the value the
 *   detector's fallback geometry worked in), inserted into `charCols`.
 * @param gapAlternatives the synthetic alternatives entry for the placeholder.
 *   Defaults to the convention the decode paths already use for a reversible
 *   blank: `GAP_CHAR` at score 0 (`OcrEngine.kt:1596`, `:2173`).
 */
fun LineResult.withGapCharAt(
    index: Int,
    column: Float,
    gapAlternatives: MutableList<Pair<Char, Float>> = mutableListOf(OcrEngine.GAP_CHAR to 0f),
): LineResult {
    if (index < 0 || index > text.length) return this

    val insert = OcrEngine.GAP_CHAR
    val newText = buildString(text.length + 1) {
        append(text, 0, index)
        append(insert)
        append(text, index, text.length)
    }

    // Boxes: only meaningful when they already describe this text one-for-one.
    val newBoxes = if (charBoxes.size == text.length && charBoxes.isNotEmpty()) {
        val out = ArrayList<JpDictRect>(charBoxes.size + 1)
        out.addAll(charBoxes.subList(0, index))
        out.add(interpolateGapBox(charBoxes, index, isVertical))
        out.addAll(charBoxes.subList(index, charBoxes.size))
        out
    } else {
        charBoxes
    }

    val newAlternatives = if (alternatives.size == text.length && alternatives.isNotEmpty()) {
        val out = ArrayList<MutableList<Pair<Char, Float>>>(alternatives.size + 1)
        out.addAll(alternatives.subList(0, index))
        out.add(gapAlternatives)
        out.addAll(alternatives.subList(index, alternatives.size))
        out
    } else {
        alternatives
    }

    val newCols = if (charCols.isNotEmpty() && charCols.size == text.length) {
        FloatArray(charCols.size + 1).also { out ->
            System.arraycopy(charCols, 0, out, 0, index)
            out[index] = column
            System.arraycopy(charCols, index, out, index + 1, charCols.size - index)
        }
    } else {
        charCols
    }

    val newOverrides = LinkedHashMap<Int, Pair<Char, Float>>(overrides.size)
    for ((key, value) in overrides) {
        newOverrides[if (key >= index) key + 1 else key] = value
    }

    return copy(
        text = newText,
        charBoxes = newBoxes,
        alternatives = newAlternatives,
        overrides = newOverrides,
        charCols = newCols,
    )
}

/**
 * Alias for [withGapCharAt] under the name the subtask used. Same behaviour,
 * same arguments.
 */
fun LineResult.withGapAt(
    index: Int,
    column: Float,
): LineResult = withGapCharAt(index, column)

/**
 * A placeholder box that sits between the neighbours it was dropped from.
 *
 * The insertion index is a *position*, so the two characters that bracket it are
 * the boxes at `index - 1` (before) and `index` (after) in the pre-insertion list.
 * The box is centred on the midpoint of their centres and sized as the mean of
 * their extents along the reading axis (y for vertical, x for horizontal), with
 * the cross-axis extent spanning both neighbours. At either end of the line there
 * is only one neighbour, and its box is reused.
 */
private fun interpolateGapBox(
    boxes: List<JpDictRect>,
    index: Int,
    isVertical: Boolean,
): JpDictRect {
    val before = boxes.getOrNull(index - 1)
    val after = boxes.getOrNull(index)
    if (before == null) return after ?: JpDictRect(0, 0, 0, 0)
    if (after == null) return before

    return if (isVertical) {
        val centreY = (before.centerY() + after.centerY()) / 2
        val height = maxOf(1, (before.height() + after.height()) / 2)
        JpDictRect(
            left = minOf(before.left, after.left),
            top = centreY - height / 2,
            right = maxOf(before.right, after.right),
            bottom = centreY - height / 2 + height,
        )
    } else {
        val centreX = (before.centerX() + after.centerX()) / 2
        val width = maxOf(1, (before.width() + after.width()) / 2)
        JpDictRect(
            left = centreX - width / 2,
            top = minOf(before.top, after.top),
            right = centreX - width / 2 + width,
            bottom = maxOf(before.bottom, after.bottom),
        )
    }
}
