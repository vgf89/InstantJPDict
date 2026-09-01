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

    /** Set by overlay for vertical tategaki — controls axis prioritization. */
    var isVertical: Boolean = false

    override fun onDraw(canvas: Canvas) {
        val textStr = text.toString()
        if (textStr.isEmpty()) return

        // Perfect centering: glyph ink center aligns with view (bbox) center.
        // For horizontal, X is critical; for vertical, Y is critical — we center both
        // using the actual rendered glyph bounds (including 'vert' feature).
        paint.getTextBounds(textStr, 0, textStr.length, bounds)
        val x = width / 2f - (bounds.left + bounds.right) / 2f
        val y = height / 2f - (bounds.top + bounds.bottom) / 2f

        paint.color = currentTextColor
        canvas.drawText(textStr, x, y, paint)
    }
}
