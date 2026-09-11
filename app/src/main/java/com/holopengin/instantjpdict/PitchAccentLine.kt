package com.holopengin.instantjpdict

import android.content.Context
import android.graphics.Color
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.util.TypedValue
import android.widget.TextView
import com.holopengin.instantjpdict.util.PitchAccent

/**
 * Pitch-accent line (#43): one reading+position pair per item, all of them on
 * a single comma-separated line so the popup gets one row instead of one row
 * per accent variant.
 *
 * Each item renders its morae in the Tokyo contour — high morae in
 * [ACCENT_COLOR], low morae in [PLAIN_COLOR]. When the downstep lands past
 * the final mora (odaka), a small down arrow follows the word: the pitch
 * keeps falling onto the unwritten particle slot. That arrow is what
 * separates odaka from heiban, whose in-word contour is identical.
 *
 * Why color instead of a step line or tick: the accent position determines the
 * whole Tokyo contour, so painting each mora high/low loses nothing — and the
 * arrow covers the one case the word alone gets wrong. Heiban (0) and odaka
 * (position == mora count) share the same in-word contour (L H…H); only the
 * particle slot tells them apart. Verified across the whole Kanjium dataset.
 *
 * A TextView rather than a custom View so overflow wraps like normal text.
 */
object PitchAccentLine {

    /** Item separator. JP comma, matching the headword lists (#64). */
    const val SEPARATOR = "、"

    /** Low morae and the falling particle slot — deliberately dimmer than high. */
    const val PLAIN_COLOR = 0xFFAAAAAA.toInt()

    /** High morae: pure white, matching the headword text. */
    const val ACCENT_COLOR = Color.WHITE

    /** Separators: dimmest of the three. */
    const val LABEL_COLOR = 0xFF888888.toInt()

    /** One reading paired with one accepted accent position. */
    data class Item(val reading: String, val position: Int)

    /**
     * Arrow marking the fall onto the unwritten particle slot: drawn when the
     * downstep lands past the final mora (odaka — position == mora count).
     * That particle is LOW (this is the textbook heiban/odaka split: heiban's
     * particle stays high), hence [PLAIN_COLOR]. Drawn small on the baseline
     * like any other character — no custom vertical positioning.
     */
    const val FALL_ARROW = "↓"

    /** Display size of [FALL_ARROW] relative to the morae. */
    const val FALL_ARROW_SIZE_RATIO = 0.7f

    /** One colored run of the line. [arrow] runs also get [FALL_ARROW_SIZE_RATIO]. */
    data class Segment(val text: String, val color: Int, val arrow: Boolean = false)

    /**
     * Pure layout of the line: morae colored by contour, separators, and the
     * fall arrow where the downstep leaves the word. Items whose reading has
     * no morae are dropped. Kept Context-free so it is unit-testable; [build]
     * only applies the spans.
     */
    fun segments(items: List<Item>): List<Segment> {
        val out = mutableListOf<Segment>()
        items.filter { it.reading.isNotEmpty() && PitchAccent.moraeOf(it.reading).isNotEmpty() }
            .forEachIndexed { index, item ->
                if (index > 0) out += Segment(SEPARATOR, LABEL_COLOR)
                val morae = PitchAccent.moraeOf(item.reading)
                val contour = PitchAccent.pattern(morae.size, item.position)
                morae.forEachIndexed { moraIndex, mora ->
                    out += Segment(mora, if (contour[moraIndex]) ACCENT_COLOR else PLAIN_COLOR)
                }
                if (PitchAccent.fallsBeyondWord(morae.size, item.position)) {
                    out += Segment(FALL_ARROW, PLAIN_COLOR, arrow = true)
                }
            }
        return out
    }

    /**
     * Build the pitch line for [items], or null when there is nothing to show.
     */
    fun build(context: Context, items: List<Item>, textSizePx: Float): TextView? {
        val segs = segments(items)
        if (segs.isEmpty()) return null

        val text = SpannableStringBuilder()
        segs.forEach { seg ->
            val start = text.length
            text.append(seg.text)
            text.setSpan(
                ForegroundColorSpan(seg.color),
                start,
                text.length,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
            )
            if (seg.arrow) {
                text.setSpan(
                    RelativeSizeSpan(FALL_ARROW_SIZE_RATIO),
                    start,
                    text.length,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
                )
            }
        }

        return TextView(context).apply {
            setText(text, TextView.BufferType.SPANNABLE)
            setTextSize(TypedValue.COMPLEX_UNIT_PX, textSizePx)
            setTextColor(LABEL_COLOR)
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
            )
        }
    }
}
