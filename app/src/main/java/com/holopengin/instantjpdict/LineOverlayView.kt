package com.holopengin.instantjpdict

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.view.View

/**
 * Single View per Line that draws all glyphs directly on Canvas — replaces 3× Views per char
 * (FrameLayout + CenteredTextView + View, 5850 Views for 65×30) to reduce UI jank.
 * Handles yoko (horizontal) and tate (vertical) with true ink center over true bbox center.
 */
class LineOverlayView(
    context: Context,
    private var line: LineResult,
    private var fixedSize: Int,
    private val onCharClick: (charIdx: Int) -> Unit
) : View(context) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FF7777")
        typeface = android.graphics.Typeface.DEFAULT
        textSize = fixedSize * 0.90f
        isAntiAlias = true
    }
    private val bounds = Rect()
    private val refBounds = Rect()
    private val hitRects = mutableListOf<android.graphics.Rect>()
    var highlightedIndices: Set<Int> = emptySet()
        private set

    init {
        if (line.isVertical) {
            paint.textLocale = java.util.Locale.JAPANESE
            paint.fontFeatureSettings = "'vert' 1"
        }
        updateHitRects()
    }

    fun updateLine(newLine: LineResult, newFixedSize: Int) {
        line = newLine
        fixedSize = newFixedSize
        paint.textSize = fixedSize * 0.90f
        if (line.isVertical) {
            paint.textLocale = java.util.Locale.JAPANESE
            paint.fontFeatureSettings = "'vert' 1"
        } else {
            paint.textLocale = java.util.Locale.ROOT
            paint.fontFeatureSettings = null
        }
        updateHitRects()
        invalidate()
    }

    fun setHighlighted(indices: Set<Int>) {
        highlightedIndices = indices
        invalidate()
    }

    private fun updateHitRects() {
        hitRects.clear()
        for (box in line.charBoxes) {
            hitRects.add(android.graphics.Rect(box.left, box.top, box.right, box.bottom))
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        // View covers the entire screenshot; parent FrameLayout is screen-sized
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val h = MeasureSpec.getSize(heightMeasureSpec)
        setMeasuredDimension(w, h)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (line.charBoxes.isEmpty() || line.text.isEmpty()) return

        val refChar = "あ"
        paint.getTextBounds(refChar, 0, 1, refBounds)

        for (i in line.charBoxes.indices) {
            val box = line.charBoxes[i]
            val charStr = line.text.getOrNull(i)?.toString() ?: continue
            if (charStr.isEmpty()) continue

            val boxW = box.width().coerceAtLeast(1)
            val boxH = box.height().coerceAtLeast(1)
            val viewCenterX = box.centerX().toFloat()
            val viewCenterY = box.centerY().toFloat()

            // Measure glyph at current paint size
            paint.getTextBounds(charStr, 0, charStr.length, bounds)
            val glyphW = bounds.width().toFloat()
            val glyphH = bounds.height().toFloat()
            if (glyphW <= 0 || glyphH <= 0) continue

            // True centers
            val glyphCenterX: Float
            val glyphCenterY: Float
            if (line.isVertical) {
                glyphCenterX = (refBounds.left + refBounds.right) / 2f
                glyphCenterY = (bounds.top + bounds.bottom) / 2f
            } else {
                glyphCenterX = (bounds.left + bounds.right) / 2f
                glyphCenterY = (refBounds.top + refBounds.bottom) / 2f
            }

            var x = viewCenterX - glyphCenterX
            var y = viewCenterY - glyphCenterY

            // Scale about center if needed (thin boxes)
            val maxW = boxW * 0.92f
            val maxH = boxH * 0.92f
            var scale = 1f
            if (line.isVertical) {
                if (glyphH > maxH) scale = maxH / glyphH.coerceAtLeast(1f)
            } else {
                if (glyphW > maxW) scale = maxW / glyphW.coerceAtLeast(1f)
            }

            val isHighlighted = highlightedIndices.contains(i)
            paint.color = if (isHighlighted) Color.YELLOW else Color.parseColor("#FF7777")
            // Keep typeface bold for highlighted
            paint.typeface = if (isHighlighted) android.graphics.Typeface.DEFAULT_BOLD else android.graphics.Typeface.DEFAULT

            if (scale < 0.99f) {
                canvas.save()
                canvas.translate(viewCenterX, viewCenterY)
                canvas.scale(scale, scale)
                canvas.translate(-viewCenterX, -viewCenterY)
                canvas.drawText(charStr, x, y, paint)
                canvas.restore()
            } else {
                canvas.drawText(charStr, x, y, paint)
            }
        }
    }

    override fun onTouchEvent(event: android.view.MotionEvent): Boolean {
        if (event.action == android.view.MotionEvent.ACTION_UP) {
            val x = event.x.toInt()
            val y = event.y.toInt()
            for (i in hitRects.indices) {
                if (hitRects[i].contains(x, y)) {
                    onCharClick(i)
                    return true
                }
            }
        }
        return super.onTouchEvent(event)
    }
}
