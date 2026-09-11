package com.holopengin.instantjpdict

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.View
import com.holopengin.instantjpdict.util.PitchAccent
import kotlin.math.roundToInt

/**
 * Pitch-accent row (#43): the reading's morae with the accented mora in pure
 * white and the rest light gray, plus the numeric position as a fallback
 * label. Heiban renders all-gray — with no downstep there is no accented
 * mora, and the uniform gray is exactly that information.
 *
 * Why color instead of a step line or tick: the accent position determines
 * the whole Tokyo contour, so highlighting the mora before the fall loses
 * nothing — and it wins on the one case the contour gets wrong. Heiban (0)
 * and odaka (position == mora count) share the same in-word contour
 * (L H…H); only the mark tells them apart. Verified across the whole
 * Kanjium dataset.
 */
class PitchAccentView(
    context: Context,
    private val morae: List<String>,
    /** 0-based accented mora (white), or null for heiban (all gray). */
    private val markIndex: Int?,
    private val positionLabel: String,
    textSizePx: Float = 13f * context.resources.displayMetrics.scaledDensity,
) : View(context) {

    private val plainPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.LTGRAY
        textSize = textSizePx
    }
    private val accentPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = textSizePx
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.GRAY
        textSize = textSizePx * 0.9f
    }
    private val fm = Paint.FontMetrics()

    private var moraWidths = FloatArray(0)
    private var labelWidth = 0f
    private var gap = 0f
    private var textHeight = 0f

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        moraWidths = FloatArray(morae.size) { plainPaint.measureText(morae[it]) }
        labelWidth = labelPaint.measureText(positionLabel)
        plainPaint.getFontMetrics(fm)
        textHeight = fm.descent - fm.ascent
        gap = plainPaint.textSize * 0.6f

        val w = (moraWidths.sum() + gap + labelWidth).roundToInt()
        val h = textHeight.roundToInt()
        setMeasuredDimension(w.coerceAtLeast(1), h.coerceAtLeast(1))
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (morae.isEmpty()) return

        val baseline = -fm.ascent
        var x = 0f
        for (i in morae.indices) {
            canvas.drawText(morae[i], x, baseline, if (i == markIndex) accentPaint else plainPaint)
            x += moraWidths[i]
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
