package com.holopengin.instantjpdict

import android.content.Context
import android.graphics.Canvas
import android.util.AttributeSet
import android.widget.TextView

/**
 * A TextView that centers a single character precisely within its bounds using FontMetrics.
 * This avoids the standard TextView's baseline-based positioning which can be inconsistent
 * for character overlays.
 */
class CenteredTextView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : TextView(context, attrs, defStyleAttr) {

    override fun onDraw(canvas: Canvas) {
        val textStr = text.toString()
        if (textStr.isEmpty()) return

        // Use FontMetrics to calculate the precise vertical center of the font.
        // 'ascent' (distance from baseline to top, negative) and 'descent' (distance from baseline to bottom, positive)
        // define the standard vertical extent of the glyphs.
        val metrics = paint.fontMetrics
        
        // The baseline should be positioned such that (ascent + descent) / 2 is at view center.
        // This centers the character's visual body rather than its full line height.
        val baselineOffsetY = (height / 2f) - (metrics.ascent + metrics.descent) / 2f
        
        // Center horizontally based on the measured width of the specific string
        val x = (width / 2f) - (paint.measureText(textStr) / 2f)

        paint.color = currentTextColor
        canvas.drawText(textStr, x, baselineOffsetY, paint)
    }
}
