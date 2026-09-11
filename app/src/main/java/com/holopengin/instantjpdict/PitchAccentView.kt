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
 * Yomitan-style pitch-accent step line (#43): the reading's morae drawn with a
 * contour above them — high morae on the upper level, low on the lower, with
 * vertical connectors where the pitch changes — plus the numeric downstep
 * position as a fallback label.
 *
 * Pure drawing: the mora split and contour come from [PitchAccent]; this view
 * only measures and paints, so it holds no Android-state assumptions beyond a
 * Paint.
 */
class PitchAccentView(
    context: Context,
    private val morae: List<String>,
    private val highs: List<Boolean>,
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
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.LTGRAY
        style = Paint.Style.STROKE
        strokeWidth = max(1f, textSizePx * 0.09f)
        strokeCap = Paint.Cap.ROUND
    }
    private val fm = Paint.FontMetrics()

    private var moraWidths = FloatArray(0)
    private var labelWidth = 0f
    private var gap = 0f
    private var lineArea = 0f
    private var textHeight = 0f

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        moraWidths = FloatArray(morae.size) { textPaint.measureText(morae[it]) }
        labelWidth = labelPaint.measureText(positionLabel)
        textPaint.getFontMetrics(fm)
        textHeight = fm.descent - fm.ascent
        lineArea = textPaint.textSize * 0.6f
        gap = textPaint.textSize * 0.6f

        val w = (moraWidths.sum() + gap + labelWidth).roundToInt()
        val h = (lineArea + textHeight).roundToInt()
        setMeasuredDimension(w.coerceAtLeast(1), h.coerceAtLeast(1))
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (morae.isEmpty()) return

        val insets = linePaint.strokeWidth / 2f
        val highY = insets
        val lowY = (lineArea - insets).coerceAtLeast(highY)
        val baseline = lineArea - fm.ascent

        var x = 0f
        for (i in morae.indices) {
            val w = moraWidths[i]
            val isHigh = highs.getOrElse(i) { false }
            val y = if (isHigh) highY else lowY
            // Level segment spanning this mora.
            canvas.drawLine(x, y, x + w, y, linePaint)
            // Connector where the contour steps between morae.
            if (i > 0) {
                val prevY = if (highs.getOrElse(i - 1) { false }) highY else lowY
                if (prevY != y) canvas.drawLine(x, prevY, x, y, linePaint)
            }
            canvas.drawText(morae[i], x, baseline, textPaint)
            x += w
        }
        canvas.drawText(positionLabel, x + gap, baseline, labelPaint)
    }

    companion object {
        /**
         * Row for one reading, or null when there is no pitch data to show.
         * Multiple positions (homograph readings with more than one accepted
         * contour) render one view each, first position first.
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
                    PitchAccent.pattern(morae.size, position),
                    PitchAccent.formatPosition(position),
                    textSizePx,
                )
            }
        }
    }
}
