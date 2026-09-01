package com.holopengin.instantjpdict

import android.content.Context
import android.graphics.Canvas
import android.graphics.Rect
import android.util.AttributeSet
import android.widget.TextView

/**
 * A TextView that centers a single character precisely within its bounds using the actual
 * rendered text's bounding box.
 */
class CenteredTextView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : TextView(context, attrs, defStyleAttr) {

    private val bounds = Rect()
    private val refBounds = Rect()

    /** Set by overlay for vertical tategaki — controls axis prioritization. */
    var isVertical: Boolean = false

    override fun onDraw(canvas: Canvas) {
        val textStr = text.toString()
        if (textStr.isEmpty()) return

        // Use reference glyph for font-metrics-driven axis to keep baseline stable.
        val refChar = "あ"
        paint.getTextBounds(refChar, 0, 1, refBounds)

        paint.getTextBounds(textStr, 0, textStr.length, bounds)
        val x: Float
        val y: Float
        if (isVertical) {
            // Tategaki: perfectly centered vertically, horizontally via font metrics
            x = width / 2f - (refBounds.left + refBounds.right) / 2f
            y = height / 2f - (bounds.top + bounds.bottom) / 2f
        } else {
            // Yoko: perfectly centered horizontally, vertically via font metrics
            x = width / 2f - (bounds.left + bounds.right) / 2f
            y = height / 2f - (refBounds.top + refBounds.bottom) / 2f
        }

        paint.color = currentTextColor
        canvas.drawText(textStr, x, y, paint)
    }
}
