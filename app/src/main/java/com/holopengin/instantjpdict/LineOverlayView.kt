package com.holopengin.instantjpdict

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.view.View
import kotlin.math.roundToInt

/**
 * Single View per Line that draws all glyphs directly on Canvas — replaces 3× Views per char
 * (FrameLayout + CenteredTextView + View, 5850 Views for 65×30) to reduce UI jank.
 * Handles yoko (horizontal) and tate (vertical) with true ink center over true bbox center.
 */
class LineOverlayView(
    context: Context,
    private var line: LineResult,
    private var fixedSize: Int,
    private var lineLeft: Int,
    private var lineTop: Int,
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
    // Ink margin (#49): halfwidth ink legally overflows its 0.5em advance box
    // (by design — sizing comes from line height), and edge chars would clip
    // against the view bounds. The view is padded by [margin] on all sides;
    // addLineToResults sizes/positions the LayoutParams with the same margin
    // via [marginFor], so draw coords and hit rects just shift by [margin].
    private var margin = marginFor(fixedSize)

    companion object {
        /** View padding per side, in units of fixedSize (#49). 0.30 covers
         * the worst proportional-latin overflow (~0.17em/side for W/% at
         * 0.90 textSize, plus bold-highlight headroom). */
        const val INK_MARGIN_RATIO = 0.30f
        /** Halfwidth glyph trim (#49): shared line-height textSize renders
         * ASCII ~10% too large next to kanji on-device (eyeball-calibrated;
         * nudge if the device font changes). Applied as a center-scale so
         * centering is untouched — and it also shrinks edge overflow. */
        const val ASCII_GLYPH_SCALE = 0.9f
        fun marginFor(fixedSize: Int): Int =
            (fixedSize * INK_MARGIN_RATIO).roundToInt().coerceAtLeast(1)
    }

    init {
        // Vertical substitution lives ONLY here, never in backend text (#47):
        // per-char Minikin vert subs (ja locale). vrt2 probed on-device as a
        // no-op over vert (identical bounds on all probe chars) — vert only.
        if (line.isVertical) {
            paint.textLocale = java.util.Locale.JAPANESE
            paint.fontFeatureSettings = "'vert' 1"
        }
        updateHitRects()
    }

    fun updateLine(newLine: LineResult, newFixedSize: Int, newLineLeft: Int = lineLeft, newLineTop: Int = lineTop) {
        line = newLine
        fixedSize = newFixedSize
        lineLeft = newLineLeft
        lineTop = newLineTop
        margin = marginFor(fixedSize)
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
            hitRects.add(android.graphics.Rect(box.left - lineLeft + margin, box.top - lineTop + margin, box.right - lineLeft + margin, box.bottom - lineTop + margin))
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        // View is sized to line bounds plus ink margin on all sides (parent
        // positions at lineLeft/top minus margin — see addLineToResults).
        val lineW = (line.charBoxes.maxOfOrNull { it.right } ?: 0) - (line.charBoxes.minOfOrNull { it.left } ?: 0)
        val lineH = (line.charBoxes.maxOfOrNull { it.bottom } ?: 0) - (line.charBoxes.minOfOrNull { it.top } ?: 0)
        val w = if (lineW > 0) lineW + 2 * margin else MeasureSpec.getSize(widthMeasureSpec)
        val h = if (lineH > 0) lineH + 2 * margin else MeasureSpec.getSize(heightMeasureSpec)
        setMeasuredDimension(w.coerceAtLeast(1), h.coerceAtLeast(1))
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
            val viewCenterX = (box.centerX() - lineLeft + margin).toFloat()
            val viewCenterY = (box.centerY() - lineTop + margin).toFloat()

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
            // Halfwidth ASCII sizing is driven
            // by line height (#49): the 0.5em box is an advance for
            // positioning/hit-testing, so fit against the full-em width and
            // let ink overflow symmetric bearings instead of shrinking to the
            // advance box (which halved cap height vs neighboring CJK).
            val isHalf = OcrEngine.isHalfWidth(charStr[0])
            if (line.isVertical) {
                val hLimit = if (isHalf) maxH * 2f else maxH
                if (glyphH > hLimit) scale = hLimit / glyphH.coerceAtLeast(1f)
            } else {
                val wLimit = if (isHalf) maxW * 2f else maxW
                if (glyphW > wLimit) scale = wLimit / glyphW.coerceAtLeast(1f)
            }

            val isHighlighted = highlightedIndices.contains(i)
            paint.color = if (isHighlighted) Color.YELLOW else Color.parseColor("#FF7777")
            // Keep typeface bold for highlighted
            paint.typeface = if (isHighlighted) android.graphics.Typeface.DEFAULT_BOLD else android.graphics.Typeface.DEFAULT

            // Halfwidth trim (#49): shared line-height textSize overshoots
            // ASCII ~10% next to kanji — scale about the box center (centering
            // untouched) on top of any box-fit shrink.
            val drawScale = scale * (if (isHalf) ASCII_GLYPH_SCALE else 1f)
            if (drawScale < 0.99f) {
                canvas.save()
                canvas.translate(viewCenterX, viewCenterY)
                canvas.scale(drawScale, drawScale)
                canvas.translate(-viewCenterX, -viewCenterY)
                canvas.drawText(charStr, x, y, paint)
                canvas.restore()
            } else {
                canvas.drawText(charStr, x, y, paint)
            }
        }
    }

    private var downHitIdx: Int = -1
    private val tap = TapDisambiguator(0f)

    private fun slopPx(): Float {
        var slop = tap.slopPx
        if (slop <= 0f) {
            slop = android.view.ViewConfiguration.get(context).scaledTouchSlop.toFloat()
            tap.slopPx = slop
        }
        return slop
    }

    override fun onTouchEvent(event: android.view.MotionEvent): Boolean {
        when (event.actionMasked) {
            android.view.MotionEvent.ACTION_DOWN -> {
                slopPx()
                val x = event.x.toInt()
                val y = event.y.toInt()
                for (i in hitRects.indices) {
                    if (hitRects[i].contains(x, y)) {
                        downHitIdx = i
                        tap.onDown(event.x, event.y)
                        // Deliberately NOT calling
                        // requestDisallowInterceptTouchEvent(true) here (#61):
                        // claiming the stream on DOWN starves the root
                        // layout's pan/pinch handling for touches that start
                        // on a character. The tap is only claimed on UP if
                        // the finger stayed within touch slop; otherwise the
                        // parent intercepts (child gets CANCEL) and gestures
                        // proceed. Tap fires synchronously on UP — no added
                        // latency vs before.
                        return true
                    }
                }
                downHitIdx = -1
                tap.cancel()
                return false
            }
            android.view.MotionEvent.ACTION_POINTER_DOWN -> {
                // Second finger = pinch: abandon the tap candidate and let
                // the parent's ScaleGestureDetector own the stream.
                downHitIdx = -1
                tap.cancel()
                parent?.requestDisallowInterceptTouchEvent(false)
                return false
            }
            android.view.MotionEvent.ACTION_MOVE -> {
                if (downHitIdx != -1) {
                    if (tap.shouldCancelOnMove(event.x, event.y)) {
                        // Drag: abandon the tap, explicitly allow the parent
                        // to intercept so pan takes over.
                        downHitIdx = -1
                        tap.cancel()
                        parent?.requestDisallowInterceptTouchEvent(false)
                        return false
                    }
                    // Within slop: keep consuming so the stream stays alive,
                    // while still allowing the parent to intercept.
                    return true
                }
            }
            android.view.MotionEvent.ACTION_UP -> {
                if (downHitIdx != -1) {
                    val idx = downHitIdx
                    downHitIdx = -1
                    val stillTap = idx in hitRects.indices &&
                        tap.isTapAtUp(event.x, event.y) &&
                        hitRects[idx].contains(event.x.toInt(), event.y.toInt())
                    tap.cancel()
                    if (stillTap) {
                        onCharClick(idx)
                    }
                    return true
                }
            }
            android.view.MotionEvent.ACTION_CANCEL -> {
                downHitIdx = -1
                tap.cancel()
            }
        }
        return super.onTouchEvent(event)
    }
}
