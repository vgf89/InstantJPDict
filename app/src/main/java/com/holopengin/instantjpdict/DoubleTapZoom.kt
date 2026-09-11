package com.holopengin.instantjpdict

import android.content.Context

/**
 * Pure double-tap zoom toggle math for the screenshot overlay (#59).
 *
 * The math takes no Android types and stays JVM-unit-testable; only
 * [isEnabled]/[setEnabled] touch SharedPreferences.
 *
 * #72: the feature is opt-in and OFF by default. While it is on, a single tap
 * on empty space cannot close until the double-tap window has expired, because
 * the overlay has to wait to see whether a second tap is coming. That cost is
 * paid on every close, so the default favours an instant close over zoom.
 */
object DoubleTapZoom {
    /** Zoomed level toggled to from rest (ticket open question: fixed 2.5x for v1). */
    const val ZOOMED_SCALE = 2.5f
    const val MIN_SCALE = 1f
    const val MAX_SCALE = 5f
    /** Double-tap zoom transition length (both directions). */
    const val ANIM_DURATION_MS = 200L

    /**
     * Scales at or below this count as "at rest" → double-tap zooms in.
     * Anything above → double-tap resets to 1x. Rests are exactly 1f
     * (pinch clamps to 1f..5f), so the epsilon only absorbs float drift.
     */
    const val REST_THRESHOLD = 1.05f

    data class ZoomState(val scale: Float, val transX: Float, val transY: Float)

    /** #72: double-tap zoom is opt-in; see the class doc for why it is off. */
    const val PREF_ENABLED = "double_tap_zoom_enabled"
    const val DEF_ENABLED = false

    fun isEnabled(ctx: Context): Boolean =
        ctx.getSharedPreferences(OcrEngine.PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(PREF_ENABLED, DEF_ENABLED)

    fun setEnabled(ctx: Context, enabled: Boolean) {
        ctx.getSharedPreferences(OcrEngine.PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(PREF_ENABLED, enabled)
            .apply()
    }

    /**
     * Toggle zoom around ([focusX], [focusY]) — same focus math as the
     * pinch handler (`onScale`): the tapped point stays stationary on
     * screen, i.e. `focus * newScale + newTrans == focus * oldScale + oldTrans`.
     */
    fun toggle(
        currentScale: Float,
        currentTransX: Float,
        currentTransY: Float,
        focusX: Float,
        focusY: Float,
        zoomedScale: Float = ZOOMED_SCALE,
    ): ZoomState {
        if (currentScale > REST_THRESHOLD) {
            return ZoomState(MIN_SCALE, 0f, 0f)
        }
        val target = zoomedScale.coerceIn(MIN_SCALE, MAX_SCALE)
        val safeScale = currentScale.coerceAtLeast(0.01f)
        val factor = target / safeScale
        return ZoomState(
            target,
            focusX - (focusX - currentTransX) * factor,
            focusY - (focusY - currentTransY) * factor,
        )
    }
}
