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

        val refChar = "あ"
        paint.getTextBounds(refChar, 0, 1, refBounds)
        paint.getTextBounds(textStr, 0, textStr.length, bounds)

        // True centers
        val viewCenterX = width / 2f
        val viewCenterY = height / 2f
        val glyphCenterX: Float
        val glyphCenterY: Float
        if (isVertical) {
            // Tategaki: primary vertical via glyph, horizontal via metrics
            glyphCenterX = (refBounds.left + refBounds.right) / 2f
            glyphCenterY = (bounds.top + bounds.bottom) / 2f
        } else {
            glyphCenterX = (bounds.left + bounds.right) / 2f
            glyphCenterY = (refBounds.top + refBounds.bottom) / 2f
        }

        // Center first: translate glyph center over view center
        var x = viewCenterX - glyphCenterX
        var y = viewCenterY - glyphCenterY

        // If still too large, scale uniformly about view center
        val glyphW = bounds.width().toFloat()
        val glyphH = bounds.height().toFloat()
        // Use view bounds with small padding
        val maxW = width * 0.92f
        val maxH = height * 0.92f
        var scale = 1f
        if (glyphW > maxW || glyphH > maxH) {
            scale = minOf(maxW / glyphW.coerceAtLeast(1f), maxH / glyphH.coerceAtLeast(1f))
        }

        if (scale < 0.99f) {
            canvas.save()
            canvas.translate(viewCenterX, viewCenterY)
            canvas.scale(scale, scale)
            canvas.translate(-viewCenterX, -viewCenterY)
            // After scale, need to recompute draw position relative to scaled coord?
            // We already have x,y for unscaled; with canvas scaled about center, draw at same x,y
            paint.color = currentTextColor
            canvas.drawText(textStr, x, y, paint)
            canvas.restore()
        } else {
            paint.color = currentTextColor
            canvas.drawText(textStr, x, y, paint)
        }
    }
}
