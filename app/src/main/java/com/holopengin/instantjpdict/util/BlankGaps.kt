package com.holopengin.instantjpdict.util

import android.content.Context
import com.holopengin.instantjpdict.LineResult
import com.holopengin.instantjpdict.OcrEngine

/**
 * Feature 2 (#44): materialise a character the recogniser dropped as a **tappable blank**.
 *
 * The placeholder is [OcrEngine.GAP_CHAR] (U+25CC, a dotted circle). Tapping it opens the
 * alternatives panel — the dictionary lookup deliberately returns null for a placeholder —
 * and the panel always offers the manual IME entry, which is how the ground-truth character
 * gets typed in (decision 5: show nothing and stay clickable, because there is a manual
 * character entry mode). Filling a blank writes an ordinary `overrides[i]` entry, so it is
 * reversible exactly like any other correction.
 *
 * ## Why vertical only
 *
 * M5 measured the trigger per orientation, and they are not the same problem:
 *
 * | bench | rule | recall | false rate |
 * |---|---|---|---|
 * | vertical (`vert_large`) | spacing ratio >= 1.6 | 1.00 | 0.00% |
 * | horizontal (trails x2) | spacing ratio >= 1.6 | 0.60 | 13% |
 *
 * So a horizontal line is not eligible at all: the detector's own per-orientation threshold
 * would still admit gaps there, and 13% of ordinary horizontal intervals look like gaps.
 *
 * ## Idempotence
 *
 * Applying this twice must not insert twice. A line that already carries a placeholder is
 * returned unchanged, and insertions run right-to-left so the detector's `insertAt` indices
 * (computed against the original text) stay valid as the text grows.
 */
object BlankGaps {
    const val PREF_ENABLED = "blank_gaps_enabled"
    const val DEF_ENABLED = true

    fun isEnabled(ctx: Context): Boolean =
        ctx.getSharedPreferences(OcrEngine.PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(PREF_ENABLED, DEF_ENABLED)

    fun setEnabled(ctx: Context, enabled: Boolean) {
        ctx.getSharedPreferences(OcrEngine.PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putBoolean(PREF_ENABLED, enabled).apply()
    }

    /** [apply] when the setting is on, else the line unchanged. */
    fun applyIfEnabled(ctx: Context, line: LineResult): LineResult =
        if (isEnabled(ctx)) apply(line) else line

    /**
     * Insert a placeholder for every measured gap in [line], or return it unchanged when
     * the line is horizontal, already carries a placeholder, or has no gaps.
     */
    fun apply(line: LineResult): LineResult {
        if (!line.isVertical) return line
        if (line.text.indexOf(OcrEngine.GAP_CHAR) >= 0) return line
        val gaps = GapDetector().detect(line)
        if (gaps.isEmpty()) return line

        var out = line
        for (gap in gaps.sortedByDescending { it.insertAt }) {
            out = out.withGapCharAt(gap.insertAt, column = columnFor(out, gap.insertAt))
        }
        return out
    }

    /**
     * The CTC timestep column for the new position: midway between the neighbours' columns
     * when they are known (the same "between the characters it was dropped from" geometry
     * the placeholder box uses), else the preceding column, else 0.
     */
    private fun columnFor(line: LineResult, insertAt: Int): Float {
        val cols = line.charCols
        if (cols.isEmpty() || cols.size != line.text.length) return 0f
        val before = cols.getOrNull(insertAt - 1)
        val after = cols.getOrNull(insertAt)
        return when {
            before != null && after != null -> (before + after) / 2f
            before != null -> before
            after != null -> after
            else -> 0f
        }
    }
}
