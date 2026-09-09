package com.holopengin.instantjpdict

/**
 * Pure tap-vs-drag/pinch disambiguation logic for overlay taps (#61).
 *
 * [LineOverlayView] must not claim the touch stream on ACTION_DOWN: doing so
 * (plus `requestDisallowInterceptTouchEvent(true)`) starves the root layout's
 * pan/pinch handling whenever a touch starts on a character. Instead the view
 * records a tap *candidate* on DOWN and only claims the tap on UP if the
 * finger never moved past touch slop and no second finger appeared.
 *
 * This class holds no Android types so it is JVM-unit-testable; the view
 * feeds it MotionEvent coordinates.
 */
class TapDisambiguator(var slopPx: Float) {
    var hasCandidate: Boolean = false
        private set
    var downX: Float = 0f
        private set
    var downY: Float = 0f
        private set

    fun onDown(x: Float, y: Float) {
        downX = x
        downY = y
        hasCandidate = true
    }

    /** True once the finger has moved past slop — caller must abandon the tap. */
    fun shouldCancelOnMove(x: Float, y: Float): Boolean {
        if (!hasCandidate) return true
        val dx = x - downX
        val dy = y - downY
        return dx * dx + dy * dy > slopPx * slopPx
    }

    /** True if the UP position still counts as the same tap. */
    fun isTapAtUp(x: Float, y: Float): Boolean =
        hasCandidate && !shouldCancelOnMove(x, y)

    fun cancel() {
        hasCandidate = false
    }

    companion object {
        /** Squared-distance slop check without object allocation. */
        fun movedPastSlop(downX: Float, downY: Float, x: Float, y: Float, slopPx: Float): Boolean {
            val dx = x - downX
            val dy = y - downY
            return dx * dx + dy * dy > slopPx * slopPx
        }
    }
}
