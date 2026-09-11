package com.holopengin.instantjpdict

import android.content.Context
import android.graphics.Color
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
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
 * the final mora (odaka), the unwritten particle slot is drawn as
 * [BEYOND_WORD] in [PLAIN_COLOR] — it is low. That placeholder is what
 * separates odaka from heiban, whose in-word contour is identical.
 *
 * Why color instead of a step line or tick: the accent position determines the
 * whole Tokyo contour, so painting each mora high/low loses nothing — and the
 * 〇 placeholder covers the one case the word alone gets wrong. Heiban (0)
 * and odaka (position == mora count) share the same in-word contour
 * (L H…H); only the particle slot tells them apart. Verified across the
 * whole Kanjium dataset.
 *
 * A TextView rather than a custom View so overflow wraps like normal text.
 */
object PitchAccentLine {

    /** Item separator. JP comma, matching the headword lists (#64). */
    const val SEPARATOR = "、"

    /** Low morae and the low particle slot — deliberately dimmer than high. */
    const val PLAIN_COLOR = 0xFFAAAAAA.toInt()

    /** High morae: pure white, matching the headword text. */
    const val ACCENT_COLOR = Color.WHITE

    /** Numeric position label and separators: dimmest of the three. */
    const val LABEL_COLOR = 0xFF888888.toInt()

    /** One reading paired with one accepted accent position. */
    data class Item(val reading: String, val position: Int)

    /**
     * Placeholder for the unwritten mora the pitch falls onto: when the
     * downstep lands past the final mora (odaka — position == mora count),
     * the following particle carries the fall, so it is drawn as part of the
     * item. That particle is LOW (this is the textbook heiban/odaka split:
     * heiban's particle stays high), hence [PLAIN_COLOR].
     */
    const val BEYOND_WORD = "〇"

    /**
     * Build the pitch line for [items], or null when there is nothing to show.
     * Items whose reading has no morae are dropped, and so is a lone trailing
     * separator — the line never ends in a comma.
     *
     * The accent position needs no numeric label: the white mora's index + 1
     * IS the position (no white = heiban 0; white on the last mora plus [BEYOND_WORD]
     * = odaka), so the encoding round-trips without digits.
     */
    fun build(context: Context, items: List<Item>, textSizePx: Float): TextView? {
        val usable = items.filter { it.reading.isNotEmpty() && PitchAccent.moraeOf(it.reading).isNotEmpty() }
        if (usable.isEmpty()) return null

        val text = SpannableStringBuilder()
        usable.forEachIndexed { index, item ->
            if (index > 0) text.append(SEPARATOR)
            val morae = PitchAccent.moraeOf(item.reading)
            val contour = PitchAccent.pattern(morae.size, item.position)
            morae.forEachIndexed { moraIndex, mora ->
                val start = text.length
                text.append(mora)
                text.setSpan(
                    ForegroundColorSpan(if (contour[moraIndex]) ACCENT_COLOR else PLAIN_COLOR),
                    start,
                    text.length,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
                )
            }
            if (PitchAccent.fallsBeyondWord(morae.size, item.position)) {
                val start = text.length
                text.append(BEYOND_WORD)
                text.setSpan(
                    ForegroundColorSpan(PLAIN_COLOR),
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
