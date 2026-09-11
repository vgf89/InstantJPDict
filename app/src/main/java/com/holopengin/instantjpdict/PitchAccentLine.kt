package com.holopengin.instantjpdict

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextPaint
import android.text.style.ForegroundColorSpan
import android.text.style.ReplacementSpan
import android.util.TypedValue
import android.widget.TextView
import com.holopengin.instantjpdict.util.PitchAccent
import kotlin.math.roundToInt

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
     * particle stays high), hence [PLAIN_COLOR]. Sized down and vertically
     * centered on the text line by [CenteredArrowSpan].
     */
    const val FALL_ARROW = "↓"

    /** One colored run of the line. [arrow] runs draw via [CenteredArrowSpan]. */
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
            val span = if (seg.arrow) CenteredArrowSpan(seg.color) else ForegroundColorSpan(seg.color)
            text.setSpan(span, start, text.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
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

    /**
     * Small arrow pinned to the vertical middle of the text line. A reduced
     * glyph on the baseline would sit low; this span shrinks the arrow and
     * centers its own cap box on the line instead. The surrounding line
     * height is left untouched.
     */
    private class CenteredArrowSpan(
        private val color: Int,
        private val sizeRatio: Float = 0.7f,
    ) : ReplacementSpan() {
        private var arrowWidth = 0f

        override fun getSize(
            paint: Paint,
            text: CharSequence,
            start: Int,
            end: Int,
            fm: Paint.FontMetricsInt?,
        ): Int {
            val p = TextPaint(paint).apply { textSize = paint.textSize * sizeRatio }
            arrowWidth = p.measureText(text, start, end)
            return arrowWidth.roundToInt()
        }

        override fun draw(
            canvas: Canvas,
            text: CharSequence,
            start: Int,
            end: Int,
            x: Float,
            top: Int,
            y: Int,
            bottom: Int,
            paint: Paint,
        ) {
            val p = TextPaint(paint).apply {
                textSize = paint.textSize * sizeRatio
                color = this@CenteredArrowSpan.color
            }
            val metrics = p.fontMetrics
            val baseline = (top + bottom) / 2f - (metrics.ascent + metrics.descent) / 2f
            canvas.drawText(text, start, end, x, baseline, p)
        }
    }
}
