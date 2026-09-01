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

        val viewCenterX = width / 2f
        val viewCenterY = height / 2f

        // True glyph ink center (for primary axis) and view bbox true center
        val glyphCenterXPrimary = (bounds.left + bounds.right) / 2f
        val glyphCenterYPrimary = (bounds.top + bounds.bottom) / 2f
        val refCenterX = (refBounds.left + refBounds.right) / 2f
        val refCenterY = (refBounds.top + refBounds.bottom) / 2f

        val glyphCenterX: Float
        val glyphCenterY: Float
        if (isVertical) {
            glyphCenterX = refCenterX
            glyphCenterY = glyphCenterYPrimary
        } else {
            glyphCenterX = glyphCenterXPrimary
            glyphCenterY = refCenterY
        }

        // Center first: glyph true center over bbox true center
        val x = viewCenterX - glyphCenterX
        val y = viewCenterY - glyphCenterY

        // Scale about center if still too large (primary-axis only to avoid over-shrink)
        val glyphW = bounds.width().toFloat()
        val glyphH = bounds.height().toFloat()
        val maxW = width * 0.92f
        val maxH = height * 0.92f
        var scale = 1f
        if (isVertical) {
            if (glyphH > maxH) scale = maxH / glyphH.coerceAtLeast(1f)
        } else {
            if (glyphW > maxW) scale = maxW / glyphW.coerceAtLeast(1f)
        }

        if (scale < 0.99f) {
            canvas.save()
            // Scale about view center (which is glyph center after translation)
            canvas.translate(viewCenterX, viewCenterY)
            canvas.scale(scale, scale)
            canvas.translate(-viewCenterX, -viewCenterY)
            paint.color = currentTextColor
            canvas.drawText(textStr, x, y, paint)
            canvas.restore()
        } else {
            paint.color = currentTextColor
            canvas.drawText(textStr, x, y, paint)
        }
    }
}
