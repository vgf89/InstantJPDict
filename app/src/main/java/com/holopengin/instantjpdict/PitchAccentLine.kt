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
 * Each item renders its morae with the accented mora in [ACCENT_COLOR] and the
 * rest in [PLAIN_COLOR], followed by the numeric position label. Heiban shows
 * all morae in [PLAIN_COLOR] — with no downstep there is no accented mora, and
 * the uniform gray is exactly that information.
 *
 * Why color instead of a step line or tick: the accent position determines the
 * whole Tokyo contour, so highlighting the mora before the fall loses nothing —
 * and it wins on the one case the contour gets wrong. Heiban (0) and odaka
 * (position == mora count) share the same in-word contour (L H…H); only the
 * mark tells them apart. Verified across the whole Kanjium dataset.
 *
 * A TextView rather than a custom View so overflow wraps like normal text.
 */
object PitchAccentLine {

    /** Item separator. JP comma, matching the headword lists (#64). */
    const val SEPARATOR = "、"

    /** Unaccented morae — deliberately dimmer than the accented one. */
    const val PLAIN_COLOR = 0xFFAAAAAA.toInt()

    /** The accented mora: pure white, matching the headword text. */
    const val ACCENT_COLOR = Color.WHITE

    /** Numeric position label and separators: dimmest of the three. */
    const val LABEL_COLOR = 0xFF888888.toInt()

    /** One reading paired with one accepted accent position. */
    data class Item(val reading: String, val position: Int)

    /**
     * Build the pitch line for [items], or null when there is nothing to show.
     * Items whose reading has no morae are dropped, and so is a lone trailing
     * separator — the line never ends in a comma.
     */
    fun build(context: Context, items: List<Item>, textSizePx: Float): TextView? {
        val usable = items.filter { it.reading.isNotEmpty() && PitchAccent.moraeOf(it.reading).isNotEmpty() }
        if (usable.isEmpty()) return null

        val text = SpannableStringBuilder()
        usable.forEachIndexed { index, item ->
            if (index > 0) text.append(SEPARATOR)
            val morae = PitchAccent.moraeOf(item.reading)
            val accented = PitchAccent.markIndex(morae.size, item.position)
            morae.forEachIndexed { moraIndex, mora ->
                val start = text.length
                text.append(mora)
                text.setSpan(
                    ForegroundColorSpan(if (moraIndex == accented) ACCENT_COLOR else PLAIN_COLOR),
                    start,
                    text.length,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
                )
            }
            val labelStart = text.length
            text.append(PitchAccent.formatPosition(item.position))
            text.setSpan(
                ForegroundColorSpan(LABEL_COLOR),
                labelStart,
                text.length,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
            )
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
