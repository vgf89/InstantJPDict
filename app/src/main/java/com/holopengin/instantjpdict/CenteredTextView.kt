package com.holopengin.instantjpdict

import android.content.Context
import android.graphics.Canvas
import android.util.AttributeSet
import android.widget.TextView

class CenteredTextView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : TextView(context, attrs, defStyleAttr) {

    override fun onDraw(canvas: Canvas) {
        val textStr = text.toString()
        if (textStr.isEmpty()) return

        val metrics = paint.fontMetrics
        // Visual center of the glyph relative to its baseline
        val glyphCenterOffset = (metrics.ascent + metrics.descent) / 2f
        
        // Target vertical center of the view
        val targetY = (height / 2f) - glyphCenterOffset
        
        // Target horizontal center
        val targetX = (width / 2f) - (paint.measureText(textStr) / 2f)

        // Ensure the paint has the correct color from the TextView
        paint.color = currentTextColor
        canvas.drawText(textStr, targetX, targetY, paint)
    }
}
