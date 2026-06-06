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

    override fun onDraw(canvas: Canvas) {
        val textStr = text.toString()
        if (textStr.isEmpty()) return

        // We use a reference character to determine a stable baseline.
        val refChar = "あ" 
        paint.getTextBounds(refChar, 0, 1, refBounds)
        val fixedBaselineOffsetY = (height / 2f) - (refBounds.top + refBounds.bottom) / 2f
        
        // Center horizontally based on the exact visual bounds of the rendered glyphs.
        paint.getTextBounds(textStr, 0, textStr.length, bounds)
        val x = (width / 2f) - (bounds.left + bounds.right) / 2f

        paint.color = currentTextColor
        canvas.drawText(textStr, x, fixedBaselineOffsetY, paint)
    }
}
