package com.holopengin.instantjpdict

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.View
import com.holopengin.instantjpdict.util.PitchAccent
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Pitch-accent row (#43): the reading's morae with a single downstep mark over
 * the mora carrying the accent, plus the numeric position as a fallback label.
 *
 * Why one mark instead of a full step line: the accent position determines the
 * whole Tokyo contour, so the mark loses nothing — and it wins on the one case
 * the contour gets wrong. Heiban (0) and odaka (position == mora count) share
 * the same in-word contour (L H…H); only the mark (none vs. on the final mora)
 * tells them apart. Verified across the whole Kanjium dataset.
 *
 * Heiban draws no mark at all — that absence is the encoding.
 */
class PitchAccentView(
    context: Context,
    private val morae: List<String>,
    /** 0-based mora carrying the downstep, or null for heiban. */
    private val markIndex: Int?,
    private val positionLabel: String,
    textSizePx: Float = 13f * context.resources.displayMetrics.scaledDensity,
) : View(context) {

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.LTGRAY
        textSize = textSizePx
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.GRAY
        textSize = textSizePx * 0.9f
    }
    private val markPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.LTGRAY
        style = Paint.Style.STROKE
        strokeWidth = max(1f, textSizePx * 0.1f)
        strokeCap = Paint.Cap.ROUND
    }
    private val fm = Paint.FontMetrics()

    private var moraWidths = FloatArray(0)
    private var labelWidth = 0f
    private var gap = 0f
    private var markArea = 0f
    private var textHeight = 0f

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        moraWidths = FloatArray(morae.size) { textPaint.measureText(morae[it]) }
        labelWidth = labelPaint.measureText(positionLabel)
        textPaint.getFontMetrics(fm)
        textHeight = fm.descent - fm.ascent
        markArea = if (markIndex == null) 0f else textPaint.textSize * 0.5f
        gap = textPaint.textSize * 0.6f

        val w = (moraWidths.sum() + gap + labelWidth).roundToInt()
        val h = (markArea + textHeight).roundToInt()
        setMeasuredDimension(w.coerceAtLeast(1), h.coerceAtLeast(1))
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (morae.isEmpty()) return

        val baseline = markArea - fm.ascent
        var x = 0f
        for (i in morae.indices) {
            val w = moraWidths[i]
            canvas.drawText(morae[i], x, baseline, textPaint)
            if (i == markIndex) {
                // Downstep tick above the accented mora, centred on it.
                val cx = x + w / 2f
                canvas.drawLine(cx, markArea * 0.15f, cx, markArea - textPaint.textSize * 0.08f, markPaint)
            }
            x += w
        }
        canvas.drawText(positionLabel, x + gap, baseline, labelPaint)
    }

    companion object {
        /**
         * Row for one reading, or null when there is no pitch data to show.
         * A term with several accepted accents renders one row each.
         */
        fun rowsFor(
            context: Context,
            reading: String,
            positions: List<Int>,
            textSizePx: Float,
        ): List<PitchAccentView> {
            if (reading.isEmpty() || positions.isEmpty()) return emptyList()
            val morae = PitchAccent.moraeOf(reading)
            if (morae.isEmpty()) return emptyList()
            return positions.map { position ->
                PitchAccentView(
                    context,
                    morae,
                    PitchAccent.markIndex(morae.size, position),
                    PitchAccent.formatPosition(position),
                    textSizePx,
                )
            }
        }
    }
}
